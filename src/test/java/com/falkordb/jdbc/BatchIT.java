package com.falkordb.jdbc;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

/** Batch execution on {@link Statement} and {@link PreparedStatement} against a real server. */
class BatchIT {

    private Connection connection;
    private Statement statement;

    @BeforeEach
    void connect() throws SQLException {
        connection = TestServer.connect("jdbc-batch");
        statement = connection.createStatement();
        statement.execute("MATCH (n) DETACH DELETE n");
    }

    @AfterEach
    void disconnect() throws SQLException {
        connection.close();
    }

    private List<String> names() throws SQLException {
        List<String> names = new ArrayList<>();
        try (Statement reader = connection.createStatement();
                ResultSet results = reader.executeQuery("MATCH (p:Person) RETURN p.name AS name ORDER BY name")) {
            while (results.next()) {
                names.add(results.getString("name"));
            }
        }
        return names;
    }

    @Nested
    @DisplayName("Statement")
    class Statements {

        @Test
        void runsQueuedStatementsInOrderAndReportsACountForEach() throws SQLException {
            statement.addBatch("CREATE (:Person {name: 'Alice'})");
            statement.addBatch("CREATE (:Person {name: 'Bob'})");
            statement.addBatch("MATCH (p:Person {name: 'Alice'}) SET p.age = 30");

            int[] counts = statement.executeBatch();

            // A node plus the property set on it, then the same again, then one property.
            assertThat(counts).containsExactly(2, 2, 1);
            assertThat(names()).containsExactly("Alice", "Bob");
        }

        @Test
        void reportsTheSameCountsAsALargeBatch() throws SQLException {
            statement.addBatch("CREATE (:Person {name: 'Alice'})");
            statement.addBatch("CREATE (:Person {name: 'Bob'})");

            assertThat(statement.executeLargeBatch()).containsExactly(2L, 2L);
        }

        @Test
        void returnsNoCountsForAnEmptyBatch() throws SQLException {
            assertThat(statement.executeBatch()).isEmpty();
            assertThat(statement.executeLargeBatch()).isEmpty();
        }

        @Test
        void emptiesTheQueueOnceItHasRun() throws SQLException {
            statement.addBatch("CREATE (:Person {name: 'Alice'})");
            statement.executeBatch();

            assertThat(statement.executeBatch()).isEmpty();
            assertThat(names()).containsExactly("Alice");
        }

        @Test
        void discardsQueuedStatementsOnClearBatch() throws SQLException {
            statement.addBatch("CREATE (:Person {name: 'Alice'})");
            statement.clearBatch();

            assertThat(statement.executeBatch()).isEmpty();
            assertThat(names()).isEmpty();
        }

        @Test
        void leavesNoCurrentResultSetOrUpdateCount() throws SQLException {
            statement.executeQuery("RETURN 1 AS n");
            statement.addBatch("CREATE (:Person {name: 'Alice'})");

            statement.executeBatch();

            assertThat(statement.getResultSet()).isNull();
            assertThat(statement.getUpdateCount()).isEqualTo(-1);
        }

        @Test
        void rejectsANullStatement() {
            assertThatThrownBy(() -> statement.addBatch(null)).isInstanceOf(SQLException.class);
        }

        @Test
        void refusesToQueueOnAClosedStatement() throws SQLException {
            statement.close();

            assertThatThrownBy(() -> statement.addBatch("CREATE (:Person)")).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.clearBatch()).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> statement.executeBatch()).isInstanceOf(SQLException.class);
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        void stopsAtTheFirstFailureAndReportsTheCountsSoFar() throws SQLException {
            statement.addBatch("CREATE (:Person {name: 'Alice'})");
            statement.addBatch("THIS IS NOT CYPHER");
            statement.addBatch("CREATE (:Person {name: 'Carol'})");

            assertThatThrownBy(() -> statement.executeBatch())
                    .isInstanceOf(BatchUpdateException.class)
                    .hasMessageContaining("Batch entry 2 of 3")
                    .satisfies(failure -> {
                        BatchUpdateException batch = (BatchUpdateException) failure;
                        assertThat(batch.getUpdateCounts()).containsExactly(2);
                        assertThat(batch.getLargeUpdateCounts()).containsExactly(2L);
                        assertThat(batch.getCause()).isInstanceOf(SQLException.class);
                    });

            // No transaction, so the statement that ran before the failure stays committed and the
            // one after it never ran.
            assertThat(names()).containsExactly("Alice");
        }

        @Test
        void rejectsAQueuedStatementThatReturnsRows() throws SQLException {
            statement.addBatch("CREATE (:Person {name: 'Alice'})");
            statement.addBatch("MATCH (p:Person) RETURN p.name AS name");

            assertThatThrownBy(() -> statement.executeBatch())
                    .isInstanceOf(BatchUpdateException.class)
                    .hasMessageContaining("Batch entry 2 of 2")
                    .hasMessageContaining("result set")
                    .satisfies(failure -> assertThat(((BatchUpdateException) failure).getUpdateCounts())
                            .containsExactly(2));
        }

        @Test
        void emptiesTheQueueEvenWhenAStatementFails() throws SQLException {
            statement.addBatch("CREATE (:Person {name: 'Alice'})");
            statement.addBatch("THIS IS NOT CYPHER");

            assertThatThrownBy(() -> statement.executeBatch()).isInstanceOf(BatchUpdateException.class);

            // A retry must not re-send the entry that already took effect.
            assertThat(statement.executeBatch()).isEmpty();
            assertThat(names()).containsExactly("Alice");
        }
    }

    @Nested
    @DisplayName("PreparedStatement")
    class Prepared {

        @Test
        void sendsOneEntryPerSetOfBindings() throws SQLException {
            try (PreparedStatement prepared = connection.prepareStatement("CREATE (:Person {name: ?, age: ?})")) {
                prepared.setString(1, "Alice");
                prepared.setInt(2, 30);
                prepared.addBatch();
                prepared.setString(1, "Bob");
                prepared.setInt(2, 41);
                prepared.addBatch();

                assertThat(prepared.executeBatch()).containsExactly(3, 3);
            }

            assertThat(names()).containsExactly("Alice", "Bob");
        }

        @Test
        void snapshotsTheBindingsWhenTheyAreQueued() throws SQLException {
            try (PreparedStatement prepared = connection.prepareStatement("CREATE (:Person {name: ?})")) {
                prepared.setString(1, "Alice");
                prepared.addBatch();
                // Rebinding after queueing must not change the entry already queued, and clearing
                // the parameters must not empty the queue.
                prepared.setString(1, "Bob");
                prepared.clearParameters();

                assertThat(prepared.executeBatch()).containsExactly(2);
            }

            assertThat(names()).containsExactly("Alice");
        }

        @Test
        void bindsNullAsANullProperty() throws SQLException {
            try (PreparedStatement prepared =
                    connection.prepareStatement("CREATE (:Person {name: 'Alice', nickname: ?})")) {
                prepared.setNull(1, java.sql.Types.VARCHAR);
                prepared.addBatch();

                // Setting a property to null does not create it, so only the node and `name` count.
                assertThat(prepared.executeBatch()).containsExactly(2);
            }

            try (Statement reader = connection.createStatement();
                    ResultSet results = reader.executeQuery("MATCH (p:Person) RETURN p.nickname AS nickname")) {
                assertThat(results.next()).isTrue();
                assertThat(results.getString("nickname")).isNull();
                assertThat(results.wasNull()).isTrue();
            }
        }

        @Test
        void refusesToQueueWithAnUnboundPlaceholder() throws SQLException {
            try (PreparedStatement prepared = connection.prepareStatement("CREATE (:Person {name: ?})")) {
                assertThatThrownBy(prepared::addBatch)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("parameter");
            }
        }

        @Test
        void keepsTheParameterisedEntrySafeFromInjection() throws SQLException {
            String attack = "Alice'}) DETACH DELETE (n) CREATE (:Owned {x: '1";

            try (PreparedStatement prepared = connection.prepareStatement("CREATE (:Person {name: ?})")) {
                prepared.setString(1, attack);
                prepared.addBatch();
                prepared.executeBatch();
            }

            assertThat(names()).containsExactly(attack);
            try (Statement reader = connection.createStatement();
                    ResultSet results = reader.executeQuery("MATCH (o:Owned) RETURN count(o) AS owned")) {
                assertThat(results.next()).isTrue();
                assertThat(results.getLong("owned")).isZero();
            }
        }

        @Test
        void discardsQueuedEntriesOnClearBatch() throws SQLException {
            try (PreparedStatement prepared = connection.prepareStatement("CREATE (:Person {name: ?})")) {
                prepared.setString(1, "Alice");
                prepared.addBatch();
                prepared.clearBatch();

                assertThat(prepared.executeBatch()).isEmpty();
            }

            assertThat(names()).isEmpty();
        }
    }

    @Nested
    @DisplayName("DatabaseMetaData")
    class Metadata {

        @Test
        void advertisesBatchUpdates() throws SQLException {
            assertThat(connection.getMetaData().supportsBatchUpdates()).isTrue();
        }
    }
}
