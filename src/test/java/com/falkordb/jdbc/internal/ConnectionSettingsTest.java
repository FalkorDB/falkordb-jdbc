package com.falkordb.jdbc.internal;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConnectionSettingsTest {

    private static ConnectionSettings parse(String url) throws SQLException {
        return ConnectionSettings.parse(url, new Properties());
    }

    private static Properties props(String... keysAndValues) {
        Properties properties = new Properties();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            properties.setProperty(keysAndValues[i], keysAndValues[i + 1]);
        }
        return properties;
    }

    @Nested
    @DisplayName("acceptsUrl")
    class AcceptsUrl {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "jdbc:falkordb://localhost/social",
                    "jdbc:falkordb+ssl://localhost/social",
                    "jdbc:falkordb+s://localhost/social",
                    "jdbc:falkordbs://localhost/social",
                    "JDBC:FALKORDB://localhost/social"
                })
        void accepts(String url) {
            assertThat(ConnectionSettings.acceptsUrl(url)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"jdbc:neo4j://localhost/neo4j", "jdbc:postgresql://localhost/db", "falkordb://x", ""})
        void rejects(String url) {
            assertThat(ConnectionSettings.acceptsUrl(url)).isFalse();
        }

        @Test
        void rejectsNull() {
            assertThat(ConnectionSettings.acceptsUrl(null)).isFalse();
        }
    }

    @Nested
    @DisplayName("URL parsing")
    class Parsing {

        @Test
        void readsHostPortAndGraph() throws SQLException {
            ConnectionSettings settings = parse("jdbc:falkordb://graph.example.com:7000/social");

            assertThat(settings.host()).isEqualTo("graph.example.com");
            assertThat(settings.port()).isEqualTo(7000);
            assertThat(settings.graphName()).isEqualTo("social");
            assertThat(settings.ssl()).isFalse();
            assertThat(settings.readOnly()).isFalse();
        }

        @Test
        void defaultsHostAndPort() throws SQLException {
            ConnectionSettings settings = parse("jdbc:falkordb:///social");

            assertThat(settings.host()).isEqualTo(ConnectionSettings.DEFAULT_HOST);
            assertThat(settings.port()).isEqualTo(ConnectionSettings.DEFAULT_PORT);
        }

        @Test
        void defaultsPortWhenOnlyHostGiven() throws SQLException {
            assertThat(parse("jdbc:falkordb://db.internal/social").port()).isEqualTo(6379);
        }

        @ParameterizedTest
        @ValueSource(strings = {"jdbc:falkordb+ssl", "jdbc:falkordb+s", "jdbc:falkordbs"})
        void tlsSchemesEnableSsl(String scheme) throws SQLException {
            assertThat(parse(scheme + "://localhost/social").ssl()).isTrue();
        }

        @Test
        void sslParameterEnablesSslOnThePlainScheme() throws SQLException {
            assertThat(parse("jdbc:falkordb://localhost/social?ssl=true").ssl()).isTrue();
        }

        @Test
        void readsCredentialsFromUserInfo() throws SQLException {
            ConnectionSettings settings = parse("jdbc:falkordb://alice:s3cret@localhost/social");

            assertThat(settings.user()).contains("alice");
            assertThat(settings.password()).contains("s3cret");
        }

        @Test
        void decodesPercentEncodedUserInfo() throws SQLException {
            ConnectionSettings settings = parse("jdbc:falkordb://a%40b:p%3Aw%2F@localhost/social");

            assertThat(settings.user()).contains("a@b");
            assertThat(settings.password()).contains("p:w/");
        }

        @Test
        void acceptsPasswordOnlyUserInfo() throws SQLException {
            ConnectionSettings settings = parse("jdbc:falkordb://:justapassword@localhost/social");

            assertThat(settings.user()).isEmpty();
            assertThat(settings.password()).contains("justapassword");
        }

        @Test
        void decodesPercentEncodedGraphName() throws SQLException {
            assertThat(parse("jdbc:falkordb://localhost/my%20graph").graphName())
                    .isEqualTo("my graph");
        }

        @Test
        void readsDurationsAndPoolSizes() throws SQLException {
            ConnectionSettings settings = parse("jdbc:falkordb://localhost/social"
                    + "?connectionTimeout=1500&socketTimeout=2500&queryTimeout=3000"
                    + "&poolMaxTotal=16&poolMaxIdle=4&poolMaxWait=750");

            assertThat(settings.connectionTimeout()).contains(Duration.ofMillis(1500));
            assertThat(settings.socketTimeout()).contains(Duration.ofMillis(2500));
            assertThat(settings.queryTimeoutMillis()).hasValue(3000L);
            assertThat(settings.poolMaxTotal()).hasValue(16);
            assertThat(settings.poolMaxIdle()).hasValue(4);
            assertThat(settings.poolMaxWait()).contains(Duration.ofMillis(750));
        }

        @Test
        void unsetOptionalsStayEmpty() throws SQLException {
            ConnectionSettings settings = parse("jdbc:falkordb://localhost/social");

            assertThat(settings.connectionTimeout()).isEmpty();
            assertThat(settings.socketTimeout()).isEmpty();
            assertThat(settings.queryTimeoutMillis()).isEmpty();
            assertThat(settings.poolMaxTotal()).isEmpty();
            assertThat(settings.poolMaxIdle()).isEmpty();
            assertThat(settings.poolMaxWait()).isEmpty();
        }

        @Test
        void readOnlyParameterIsHonoured() throws SQLException {
            assertThat(parse("jdbc:falkordb://localhost/social?readOnly=true").readOnly())
                    .isTrue();
        }

        @Test
        void trailingSlashOnTheGraphIsIgnored() throws SQLException {
            assertThat(parse("jdbc:falkordb://localhost/social/").graphName()).isEqualTo("social");
        }

        @Test
        void hostsRejectedByUriStillParse() throws SQLException {
            ConnectionSettings settings = parse("jdbc:falkordb://my_host:6380/social");

            assertThat(settings.host()).isEqualTo("my_host");
            assertThat(settings.port()).isEqualTo(6380);
        }
    }

    @Nested
    @DisplayName("property precedence")
    class Precedence {

        @Test
        void propertiesBeatUrlParameters() throws SQLException {
            ConnectionSettings settings = ConnectionSettings.parse(
                    "jdbc:falkordb://localhost/social?ssl=false&readOnly=false",
                    props("ssl", "true", "readOnly", "true"));

            assertThat(settings.ssl()).isTrue();
            assertThat(settings.readOnly()).isTrue();
        }

        @Test
        void propertiesBeatUserInfo() throws SQLException {
            ConnectionSettings settings = ConnectionSettings.parse(
                    "jdbc:falkordb://alice:fromurl@localhost/social", props("user", "bob", "password", "fromprops"));

            assertThat(settings.user()).contains("bob");
            assertThat(settings.password()).contains("fromprops");
        }

        @Test
        void graphPropertyOverridesUrlPath() throws SQLException {
            ConnectionSettings settings =
                    ConnectionSettings.parse("jdbc:falkordb://localhost/fromurl", props("graph", "fromprops"));

            assertThat(settings.graphName()).isEqualTo("fromprops");
        }

        @Test
        void urlParametersApplyWhenNoPropertyIsGiven() throws SQLException {
            ConnectionSettings settings =
                    ConnectionSettings.parse("jdbc:falkordb://localhost/social?poolMaxTotal=9", new Properties());

            assertThat(settings.poolMaxTotal()).hasValue(9);
        }
    }

    @Nested
    @DisplayName("rejects malformed URLs")
    class Malformed {

        @Test
        void rejectsForeignSubProtocol() {
            assertThatThrownBy(() -> parse("jdbc:neo4j://localhost/neo4j"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("jdbc:falkordb:");
        }

        @Test
        void rejectsNullUrl() {
            assertThatThrownBy(() -> parse(null)).isInstanceOf(SQLException.class);
        }

        @Test
        void rejectsMissingGraph() {
            assertThatThrownBy(() -> parse("jdbc:falkordb://localhost"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("graph");
        }

        @Test
        void rejectsEmptyGraph() {
            assertThatThrownBy(() -> parse("jdbc:falkordb://localhost/"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("graph");
        }

        @Test
        void rejectsUnknownScheme() {
            assertThatThrownBy(() -> parse("jdbc:falkordb+quic://localhost/social"))
                    .isInstanceOf(SQLException.class);
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "jdbc:falkordb://localhost:0/social",
                    "jdbc:falkordb://localhost:70000/social",
                    "jdbc:falkordb://localhost:-1/social"
                })
        void rejectsPortsOutsideRange(String url) {
            assertThatThrownBy(() -> parse(url)).isInstanceOf(SQLException.class);
        }

        @Test
        void rejectsNonNumericTimeout() {
            assertThatThrownBy(() -> parse("jdbc:falkordb://localhost/social?socketTimeout=soon"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("socketTimeout");
        }

        @Test
        void rejectsNegativeTimeout() {
            assertThatThrownBy(() -> parse("jdbc:falkordb://localhost/social?connectionTimeout=-5"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("connectionTimeout");
        }

        @Test
        void rejectsNonBooleanFlag() {
            assertThatThrownBy(() -> parse("jdbc:falkordb://localhost/social?ssl=perhaps"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("ssl");
        }

        @Test
        void rejectsUnknownParameter() {
            assertThatThrownBy(() -> parse("jdbc:falkordb://localhost/social?frobnicate=1"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("frobnicate");
        }
    }

    @Nested
    @DisplayName("toRedactedUrl")
    class Redaction {

        @Test
        void hidesThePassword() throws SQLException {
            String url =
                    parse("jdbc:falkordb://alice:s3cret@localhost:6380/social").toRedactedUrl();

            assertThat(url)
                    .doesNotContain("s3cret")
                    .contains("alice")
                    .contains("localhost:6380")
                    .contains("/social");
        }

        @Test
        void omitsUserInfoWhenThereIsNone() throws SQLException {
            assertThat(parse("jdbc:falkordb://localhost/social").toRedactedUrl())
                    .isEqualTo("jdbc:falkordb://localhost:6379/social");
        }

        @Test
        void reflectsTls() throws SQLException {
            assertThat(parse("jdbc:falkordb+ssl://localhost/social").toRedactedUrl())
                    .startsWith("jdbc:falkordb+ssl://");
        }
    }

    @Nested
    @DisplayName("credentials in error messages")
    class ErrorRedaction {

        private static final String AT = "@";

        @Test
        void hidesTheUserinfoPassword() {
            assertThatThrownBy(() -> ConnectionSettings.parse("jdbc:falkordb://alice:hunter2@localhost:6379", null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("***")
                    .hasMessageNotContaining("hunter2");
        }

        @Test
        void hidesThePasswordQueryParameter() {
            assertThatThrownBy(() ->
                            ConnectionSettings.parse("jdbc:falkordb://localhost/g?password=hunter2&bogus=1", null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("***")
                    .hasMessageNotContaining("hunter2");
        }

        @Test
        void hidesAPasswordContainingAnAtSign() {
            String url = "jdbc:falkordb://alice:hun" + AT + "ter2" + AT + "localhost:6379";

            assertThatThrownBy(() -> ConnectionSettings.parse(url, null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("***")
                    .hasMessageContaining("localhost:6379")
                    .hasMessageNotContaining("hun")
                    .hasMessageNotContaining("ter2");
        }

        @Test
        void leavesAUrlWithoutCredentialsAlone() {
            assertThat(SQLErrors.redact("jdbc:falkordb://localhost:6379/social"))
                    .isEqualTo("jdbc:falkordb://localhost:6379/social");
        }
    }

    @Nested
    @DisplayName("percent-decoding")
    class PercentDecoding {

        @Test
        void keepsALiteralPlusInAGraphName() throws SQLException {
            ConnectionSettings settings = ConnectionSettings.parse("jdbc:falkordb://localhost/a+b", null);

            assertThat(settings.graphName()).isEqualTo("a+b");
        }

        @Test
        void keepsALiteralPlusInAPassword() throws SQLException {
            ConnectionSettings settings = ConnectionSettings.parse("jdbc:falkordb://user:pa+ss@localhost/graph", null);

            assertThat(settings.password()).contains("pa+ss");
        }

        @Test
        void decodesEscapedCharacters() throws SQLException {
            ConnectionSettings settings =
                    ConnectionSettings.parse("jdbc:falkordb://user:p%40%3Aw@localhost/my%20graph", null);

            assertThat(settings.password()).contains("p@:w");
            assertThat(settings.graphName()).isEqualTo("my graph");
        }

        @Test
        void decodesMultiByteCharacters() throws SQLException {
            ConnectionSettings settings = ConnectionSettings.parse("jdbc:falkordb://localhost/%C3%A9%C3%A8", null);

            assertThat(settings.graphName()).isEqualTo("éè");
        }

        @Test
        void rejectsAMalformedEscape() {
            // A malformed escape must surface as a SQLException, never as an unchecked
            // IllegalArgumentException escaping from the decoder.
            assertThatThrownBy(() -> ConnectionSettings.parse("jdbc:falkordb://localhost/%zz", null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Invalid FalkorDB JDBC URL");
        }

        @Test
        void rejectsATruncatedEscape() {
            assertThatThrownBy(() -> ConnectionSettings.parse("jdbc:falkordb://localhost/graph%4", null))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("Invalid FalkorDB JDBC URL");
        }
    }
}
