package com.falkordb.jdbc;

import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FalkorDBDriverTest {

    private final FalkorDBDriver driver = new FalkorDBDriver();

    @Nested
    @DisplayName("registration")
    class Registration {

        @Test
        void isDiscoveredThroughTheServiceLoader() throws SQLException {
            Driver registered = DriverManager.getDriver("jdbc:falkordb://localhost:6379/social");

            assertThat(registered).isInstanceOf(FalkorDBDriver.class);
        }

        @Test
        void isVisibleInTheDriverList() {
            assertThat(java.util.Collections.list(DriverManager.getDrivers()))
                    .anyMatch(FalkorDBDriver.class::isInstance);
        }

        @Test
        void doesNotClaimToBeJdbcCompliant() {
            assertThat(driver.jdbcCompliant()).isFalse();
        }

        @Test
        void reportsAVersion() throws SQLException {
            assertThat(driver.getMajorVersion()).isNotNegative();
            assertThat(driver.getMinorVersion()).isNotNegative();
            assertThat(FalkorDBDriver.version()).isNotBlank();
        }

        @Test
        void exposesAParentLogger() throws SQLException {
            assertThat(driver.getParentLogger().getName()).isEqualTo("com.falkordb.jdbc");
        }
    }

    @Nested
    @DisplayName("acceptsURL")
    class AcceptsUrl {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "jdbc:falkordb://localhost/social",
                    "jdbc:falkordb+ssl://localhost:6380/social",
                    "jdbc:falkordbs://localhost/social"
                })
        void acceptsFalkorDbUrls(String url) throws SQLException {
            assertThat(driver.acceptsURL(url)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"jdbc:neo4j://localhost/neo4j", "jdbc:mysql://localhost/db"})
        void rejectsOtherDrivers(String url) throws SQLException {
            assertThat(driver.acceptsURL(url)).isFalse();
        }

        @Test
        void returnsNullForAnotherDriversUrl() throws SQLException {
            assertThat(driver.connect("jdbc:neo4j://localhost/neo4j", new Properties()))
                    .isNull();
        }

        @Test
        void reportsMalformedFalkorDbUrls() {
            assertThatThrownBy(() -> driver.connect("jdbc:falkordb://localhost", new Properties()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("graph");
        }
    }

    @Nested
    @DisplayName("getPropertyInfo")
    class PropertyInfo {

        @Test
        void describesEveryConnectionProperty() throws SQLException {
            DriverPropertyInfo[] info = driver.getPropertyInfo("jdbc:falkordb://localhost/social", new Properties());

            assertThat(Arrays.stream(info).map(p -> p.name))
                    .contains(
                            "user",
                            "password",
                            "graph",
                            "ssl",
                            "connectionTimeout",
                            "socketTimeout",
                            "queryTimeout",
                            "poolMaxTotal",
                            "poolMaxIdle",
                            "poolMaxWait",
                            "readOnly");
            assertThat(info)
                    .allSatisfy(property -> assertThat(property.description).isNotBlank());
        }

        @Test
        void reflectsValuesAlreadySupplied() throws SQLException {
            Properties supplied = new Properties();
            supplied.setProperty("user", "alice");

            DriverPropertyInfo[] info = driver.getPropertyInfo("jdbc:falkordb://localhost/social", supplied);

            assertThat(Arrays.stream(info).filter(p -> p.name.equals("user")).findFirst())
                    .hasValueSatisfying(property -> assertThat(property.value).isEqualTo("alice"));
        }

        @Test
        void offersBooleanChoicesForFlags() throws SQLException {
            DriverPropertyInfo[] info = driver.getPropertyInfo("jdbc:falkordb://localhost/social", new Properties());

            assertThat(Arrays.stream(info).filter(p -> p.name.equals("ssl")).findFirst())
                    .hasValueSatisfying(property -> assertThat(property.choices).containsExactly("true", "false"));
        }
    }

    @Nested
    @DisplayName("translate")
    class Translate {

        @Test
        void rewritesPlaceholdersWithoutAConnection() throws SQLException {
            assertThat(FalkorDBDriver.translate("MATCH (n) WHERE n.id = ? RETURN n"))
                    .isEqualTo("MATCH (n) WHERE n.id = $p1 RETURN n");
        }

        @Test
        void leavesCypherAlone() throws SQLException {
            assertThat(FalkorDBDriver.translate("MATCH (n) RETURN n")).isEqualTo("MATCH (n) RETURN n");
        }
    }
}
