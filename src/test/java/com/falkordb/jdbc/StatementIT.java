package com.falkordb.jdbc;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link Statement} execution, update counts and result navigation against a real server. */
class StatementIT {

    private Connection connection;
    private Statement statement;

    @BeforeEach
    void connect() throws SQLException {
        connection = TestServer.connect("jdbc-statement");
        statement = connection.createStatement();
        statement.execute("MATCH (n) DETACH DELETE n");
    }

    @AfterEach
    void disconnect() throws SQLException {
        connection.close();
    }

    @Nested
    @DisplayName("executeQuery")
    class Queries {

        @Test
        void readsASingleRow() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 'hello' AS greeting")) {
                assertThat(results.next()).isTrue();
                assertThat(results.getString("greeting")).isEqualTo("hello");
                assertThat(results.next()).isFalse();
            }
        }

        @Test
        void readsManyRowsInOrder() throws SQLException {
            List<Long> values = new ArrayList<>();

            try (ResultSet results = statement.executeQuery("UNWIND [1, 2, 3] AS value RETURN value")) {
                while (results.next()) {
                    values.add(results.getLong(1));
                }
            }

            assertThat(values).containsExactly(1L, 2L, 3L);
        }

        @Test
        void readsMatchedNodes() throws SQLException {
            statement.executeUpdate("CREATE (:Person {name: 'Alice'}), (:Person {name: 'Bob'})");

            List<String> names = new ArrayList<>();
            try (ResultSet results = statement.executeQuery("MATCH (p:Person) RETURN p.name AS name ORDER BY name")) {
                while (results.next()) {
                    names.add(results.getString("name"));
                }
            }

            assertThat(names).containsExactly("Alice", "Bob");
        }

        @Test
        void returnsAnEmptyResultSetForNoMatches() throws SQLException {
            try (ResultSet results = statement.executeQuery("MATCH (n:Missing) RETURN n")) {
                assertThat(results.next()).isFalse();
                assertThat(results.getMetaData().getColumnCount()).isEqualTo(1);
            }
        }

        @Test
        void rejectsAWriteThatReturnsNothing() {
            assertThatThrownBy(() -> statement.executeQuery("CREATE (:NoReturn)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("executeUpdate");
        }

        @Test
        void tracksTheColumnLabels() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 1 AS a, 2 AS b")) {
                assertThat(results.getMetaData().getColumnCount()).isEqualTo(2);
                assertThat(results.getMetaData().getColumnLabel(1)).isEqualTo("a");
                assertThat(results.getMetaData().getColumnLabel(2)).isEqualTo("b");
            }
        }

        @Test
        void findsColumnsByLabelCaseInsensitively() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 7 AS Answer")) {
                assertThat(results.findColumn("answer")).isEqualTo(1);
                assertThat(results.next()).isTrue();
                assertThat(results.getInt("ANSWER")).isEqualTo(7);
            }
        }
    }

    @Nested
    @DisplayName("executeUpdate")
    class Updates {

        @Test
        void countsCreatedNodesAndProperties() throws SQLException {
            int updated = statement.executeUpdate("CREATE (:Counted {a: 1, b: 2})");

            assertThat(updated).isEqualTo(3); // one node + two properties
        }

        @Test
        void countsCreatedRelationships() throws SQLException {
            statement.executeUpdate("CREATE (:A {id: 1}), (:B {id: 2})");

            int updated = statement.executeUpdate(
                    "MATCH (a:A {id: 1}), (b:B {id: 2}) CREATE (a)-[:KNOWS {since: 2020}]->(b)");

            assertThat(updated).isEqualTo(2); // one relationship + one property
        }

        @Test
        void countsDeletions() throws SQLException {
            statement.executeUpdate("CREATE (:Doomed), (:Doomed)");

            assertThat(statement.executeUpdate("MATCH (n:Doomed) DELETE n")).isEqualTo(2);
        }

        @Test
        void returnsZeroWhenNothingChanges() throws SQLException {
            assertThat(statement.executeUpdate("MATCH (n:Absent) DELETE n")).isZero();
        }

        @Test
        void rejectsAQueryThatReturnsRows() {
            assertThatThrownBy(() -> statement.executeUpdate("RETURN 1"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("executeQuery");
        }
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        void reportsAResultSetForAReadQuery() throws SQLException {
            assertThat(statement.execute("RETURN 1 AS one")).isTrue();
            assertThat(statement.getUpdateCount()).isEqualTo(-1);

            try (ResultSet results = statement.getResultSet()) {
                assertThat(results.next()).isTrue();
            }
        }

        @Test
        void reportsAnUpdateCountForAWrite() throws SQLException {
            assertThat(statement.execute("CREATE (:Executed)")).isFalse();
            assertThat(statement.getResultSet()).isNull();
            assertThat(statement.getUpdateCount()).isEqualTo(1);
        }

        @Test
        void hasNoMoreResults() throws SQLException {
            statement.execute("RETURN 1");

            assertThat(statement.getMoreResults()).isFalse();
            assertThat(statement.getUpdateCount()).isEqualTo(-1);
        }

        @Test
        void closesThePreviousResultSetOnReexecution() throws SQLException {
            statement.execute("RETURN 1");
            ResultSet first = statement.getResultSet();

            statement.execute("RETURN 2");

            assertThat(first.isClosed()).isTrue();
        }
    }

    @Nested
    @DisplayName("statement configuration")
    class Configuration {

        @Test
        void appliesAQueryTimeout() throws SQLException {
            statement.setQueryTimeout(30);

            assertThat(statement.getQueryTimeout()).isEqualTo(30);
            try (ResultSet results = statement.executeQuery("RETURN 1")) {
                assertThat(results.next()).isTrue();
            }
        }

        @Test
        void abortsAQueryThatOverrunsItsTimeout() throws SQLException {
            statement.setQueryTimeout(1);

            assertThatThrownBy(() -> statement.executeQuery(
                            "UNWIND range(1, 100000000) AS x WITH x WHERE x % 2 = 0 RETURN " + "count(x) AS total"))
                    .isInstanceOf(SQLException.class);
        }

        @Test
        void rejectsANegativeTimeout() {
            assertThatThrownBy(() -> statement.setQueryTimeout(-1)).isInstanceOf(SQLException.class);
        }

        @Test
        void warnsThatMaxRowsCannotBeEnforced() throws SQLException {
            statement.setMaxRows(10);

            assertThat(statement.getMaxRows()).isEqualTo(10);
            assertThat((Object) statement.getWarnings()).isNotNull();
        }

        @Test
        void refusesBatchExecution() {
            assertThatThrownBy(() -> statement.addBatch("CREATE (:Batched)"))
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
            assertThatThrownBy(() -> statement.executeBatch()).isInstanceOf(SQLFeatureNotSupportedException.class);
        }

        @Test
        void refusesToCancel() {
            assertThatThrownBy(() -> statement.cancel()).isInstanceOf(SQLFeatureNotSupportedException.class);
        }

        @Test
        void refusesGeneratedKeys() {
            assertThatThrownBy(() -> statement.executeUpdate("CREATE (:Keyed)", Statement.RETURN_GENERATED_KEYS))
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
        }

        @Test
        void exposesItsConnection() throws SQLException {
            assertThat(statement.getConnection()).isSameAs(connection);
        }
    }

    @Nested
    @DisplayName("result set navigation")
    class Navigation {

        @Test
        void isForwardOnly() throws SQLException {
            try (ResultSet results = statement.executeQuery("UNWIND [1, 2] AS value RETURN value")) {
                assertThat(results.getType()).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
                assertThat(results.getConcurrency()).isEqualTo(ResultSet.CONCUR_READ_ONLY);
                assertThatThrownBy(() -> results.previous()).isInstanceOf(SQLException.class);
            }
        }

        @Test
        void tracksItsRowNumber() throws SQLException {
            try (ResultSet results = statement.executeQuery("UNWIND [1, 2] AS value RETURN value")) {
                assertThat(results.isBeforeFirst()).isTrue();
                results.next();
                assertThat(results.getRow()).isEqualTo(1);
                assertThat(results.isFirst()).isTrue();
                results.next();
                assertThat(results.getRow()).isEqualTo(2);
                assertThat(results.next()).isFalse();
                assertThat(results.isAfterLast()).isTrue();
            }
        }

        @Test
        void refusesUpdates() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 1 AS one")) {
                results.next();

                assertThatThrownBy(() -> results.updateInt(1, 2)).isInstanceOf(SQLFeatureNotSupportedException.class);
            }
        }

        @Test
        void rejectsAccessBeforeTheFirstRow() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 1 AS one")) {
                assertThatThrownBy(() -> results.getInt(1))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("row");
            }
        }

        @Test
        void rejectsAnOutOfRangeColumn() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 1 AS one")) {
                results.next();

                assertThatThrownBy(() -> results.getInt(2)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> results.getInt(0)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> results.getInt("missing")).isInstanceOf(SQLException.class);
            }
        }
    }
}
