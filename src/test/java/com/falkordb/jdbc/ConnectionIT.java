package com.falkordb.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Connection lifecycle, read-only routing, catalogs and error mapping against a real server. */
class ConnectionIT {

    private Connection connection;

    @BeforeEach
    void connect() throws SQLException {
        connection = TestServer.connect("jdbc-connection");
    }

    @AfterEach
    void disconnect() throws SQLException {
        if (connection != null) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("MATCH (n) DETACH DELETE n");
            } catch (SQLException ignored) {
                // the graph may not exist if a test never wrote to it
            }
            connection.close();
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        void opensThroughDriverManager() throws SQLException {
            assertThat(connection.isClosed()).isFalse();
            assertThat(connection.isValid(5)).isTrue();
        }

        @Test
        void reportsTheGraphAsItsCatalog() throws SQLException {
            assertThat(connection.getCatalog()).isEqualTo("jdbc-connection");
        }

        @Test
        void closesIdempotently() throws SQLException {
            connection.close();
            connection.close();

            assertThat(connection.isClosed()).isTrue();
            assertThat(connection.isValid(5)).isFalse();
        }

        @Test
        void closesItsStatements() throws SQLException {
            Statement statement = connection.createStatement();

            connection.close();

            assertThat(statement.isClosed()).isTrue();
        }

        @Test
        void refusesToWorkOnceClosed() throws SQLException {
            connection.close();

            assertThatThrownBy(() -> connection.createStatement())
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        void unwrapsToItsOwnType() throws SQLException {
            assertThat(connection.isWrapperFor(FalkorDBConnection.class)).isTrue();
            assertThat(connection.unwrap(FalkorDBConnection.class)).isSameAs(connection);
        }
    }

    @Nested
    @DisplayName("read-only mode")
    class ReadOnly {

        @Test
        void isWritableByDefault() throws SQLException {
            assertThat(connection.isReadOnly()).isFalse();

            try (Statement statement = connection.createStatement()) {
                assertThat(statement.executeUpdate("CREATE (:Writable {n: 1})")).isEqualTo(2);
            }
        }

        @Test
        void routesReadsThroughReadOnlyQuery() throws SQLException {
            connection.setReadOnly(true);
            assertThat(connection.isReadOnly()).isTrue();

            try (Statement statement = connection.createStatement();
                    ResultSet results = statement.executeQuery("RETURN 1 AS one")) {
                assertThat(results.next()).isTrue();
                assertThat(results.getInt("one")).isEqualTo(1);
            }
        }

        @Test
        void rejectsWritesWhileReadOnly() throws SQLException {
            connection.setReadOnly(true);

            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeUpdate("CREATE (:Nope)"))
                        .isInstanceOf(SQLException.class);
            }
        }

        @Test
        void canBeSetFromTheUrl() throws SQLException {
            try (Connection readOnly = TestServer.connect("jdbc-connection", properties("readOnly", "true"))) {
                assertThat(readOnly.isReadOnly()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("transactions")
    class Transactions {

        @Test
        void areAlwaysAutoCommitted() throws SQLException {
            assertThat(connection.getAutoCommit()).isTrue();
            assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_NONE);
        }

        @Test
        void refuseToBeDisabled() {
            assertThatThrownBy(() -> connection.setAutoCommit(false))
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
        }

        @Test
        void acceptARedundantAutoCommitTrue() throws SQLException {
            connection.setAutoCommit(true);

            assertThat(connection.getAutoCommit()).isTrue();
        }

        @Test
        void rejectCommitAndRollback() {
            assertThatThrownBy(() -> connection.commit()).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.rollback()).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> connection.setSavepoint()).isInstanceOf(SQLFeatureNotSupportedException.class);
        }

        @Test
        void rejectUnsupportedIsolationLevels() {
            assertThatThrownBy(() -> connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Nested
    @DisplayName("error mapping")
    class Errors {

        @Test
        void turnsSyntaxErrorsIntoSqlExceptions() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeQuery("THIS IS NOT CYPHER"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(thrown -> assertThat(((SQLException) thrown).getSQLState())
                                .startsWith("42"));
            }
        }

        @Test
        void reportsUnknownFunctionsAsSyntaxErrors() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeQuery("RETURN noSuchFunction(1)"))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("noSuchFunction");
            }
        }

        @Test
        void keepsTheConnectionUsableAfterAFailedQuery() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.executeQuery("RETURN")).isInstanceOf(SQLException.class);
            }

            try (Statement statement = connection.createStatement();
                    ResultSet results = statement.executeQuery("RETURN 1 AS one")) {
                assertThat(results.next()).isTrue();
            }
        }

        @Test
        void failsFastOnAnUnreachableServer() {
            assertThatThrownBy(() -> java.sql.DriverManager.getConnection(
                                    "jdbc:falkordb://localhost:1/nowhere", properties("connectionTimeout", "250"))
                            .close())
                    .isInstanceOf(SQLException.class)
                    .satisfies(thrown ->
                            assertThat(((SQLException) thrown).getSQLState()).startsWith("08"));
        }
    }

    private static Properties properties(String key, String value) {
        Properties properties = new Properties();
        properties.setProperty(key, value);
        return properties;
    }
}
