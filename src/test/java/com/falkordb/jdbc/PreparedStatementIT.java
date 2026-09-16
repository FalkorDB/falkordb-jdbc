package com.falkordb.jdbc;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parameter binding, injection safety and named-parameter pass-through against a real server. */
class PreparedStatementIT {

    private Connection connection;

    @BeforeEach
    void connect() throws SQLException {
        connection = TestServer.connect("jdbc-prepared");
        try (Statement statement = connection.createStatement()) {
            statement.execute("MATCH (n) DETACH DELETE n");
        }
    }

    @AfterEach
    void disconnect() throws SQLException {
        connection.close();
    }

    @Nested
    @DisplayName("positional parameters")
    class Positional {

        @Test
        void bindOneValue() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                statement.setString(1, "hello");

                try (ResultSet results = statement.executeQuery()) {
                    assertThat(results.next()).isTrue();
                    assertThat(results.getString("value")).isEqualTo("hello");
                }
            }
        }

        @Test
        void bindManyValuesInOrder() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS first, ? AS second")) {
                statement.setLong(1, 1L);
                statement.setLong(2, 2L);

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getLong("first")).isEqualTo(1L);
                    assertThat(results.getLong("second")).isEqualTo(2L);
                }
            }
        }

        @Test
        void driveAWriteAndItsReadBack() throws SQLException {
            try (PreparedStatement insert = connection.prepareStatement("CREATE (:Person {name: ?, age: ?})")) {
                insert.setString(1, "Alice");
                insert.setInt(2, 34);

                assertThat(insert.executeUpdate()).isEqualTo(3);
            }

            try (PreparedStatement select =
                    connection.prepareStatement("MATCH (p:Person {name: ?}) RETURN p.age AS age")) {
                select.setString(1, "Alice");

                try (ResultSet results = select.executeQuery()) {
                    results.next();
                    assertThat(results.getInt("age")).isEqualTo(34);
                }
            }
        }

        @Test
        void canBeReExecutedWithNewValues() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("CREATE (:Repeat {n: ?})")) {
                for (int value = 1; value <= 3; value++) {
                    statement.setInt(1, value);
                    statement.executeUpdate();
                }
            }

            try (Statement statement = connection.createStatement();
                    ResultSet results = statement.executeQuery("MATCH (r:Repeat) RETURN count(r) AS total")) {
                results.next();
                assertThat(results.getInt("total")).isEqualTo(3);
            }
        }

        @Test
        void keepValuesUntilCleared() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                statement.setString(1, "kept");
                statement.executeQuery().close();

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getString("value")).isEqualTo("kept");
                }

                statement.clearParameters();
                assertThatThrownBy(statement::executeQuery)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("1");
            }
        }

        @Test
        void rejectAnUnsetPlaceholder() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS a, ? AS b")) {
                statement.setInt(1, 1);

                assertThatThrownBy(statement::executeQuery).isInstanceOf(SQLException.class);
            }
        }

        @Test
        void rejectAnOutOfRangeIndex() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                assertThatThrownBy(() -> statement.setInt(2, 1)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.setInt(0, 1)).isInstanceOf(SQLException.class);
            }
        }

        @Test
        void reportTheirParameterCount() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS a, ? AS b")) {
                assertThat(statement.getParameterMetaData().getParameterCount()).isEqualTo(2);
            }
        }

        @Test
        void refuseTheStringTakingStatementMethods() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN 1")) {
                assertThatThrownBy(() -> statement.executeQuery("RETURN 2")).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> statement.executeUpdate("CREATE (:X)")).isInstanceOf(SQLException.class);
            }
        }

        @Test
        void refuseBatchExecution() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("CREATE (:Batched {n: ?})")) {
                statement.setInt(1, 1);

                assertThatThrownBy(statement::addBatch).isInstanceOf(SQLFeatureNotSupportedException.class);
            }
        }
    }

    @Nested
    @DisplayName("injection safety")
    class InjectionSafety {

        @Test
        void treatsCypherInAValueAsData() throws SQLException {
            String attack = "Alice'}) DETACH DELETE (n) CREATE (:Owned {x: '1";

            try (PreparedStatement statement = connection.prepareStatement("CREATE (:Person {name: ?})")) {
                statement.setString(1, attack);
                statement.executeUpdate();
            }

            try (Statement statement = connection.createStatement();
                    ResultSet results =
                            statement.executeQuery("MATCH (p:Person) RETURN p.name AS name, labels(p) AS labels")) {
                results.next();
                assertThat(results.getString("name")).isEqualTo(attack);
                assertThat(results.next()).isFalse();
            }

            try (Statement statement = connection.createStatement();
                    ResultSet results = statement.executeQuery("MATCH (o:Owned) RETURN count(o) AS total")) {
                results.next();
                assertThat(results.getInt("total")).isZero();
            }
        }

        @Test
        void keepsQuotesAndBackslashesIntact() throws SQLException {
            String awkward = "quote ' double \" backslash \\ newline \n done";

            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                statement.setString(1, awkward);

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getString("value")).isEqualTo(awkward);
                }
            }
        }

        @Test
        void leavesPlaceholdersInsideStringLiteralsAlone() throws SQLException {
            try (PreparedStatement statement =
                    connection.prepareStatement("RETURN 'is this a question?' AS literal, ? AS bound")) {
                statement.setInt(1, 1);

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getString("literal")).isEqualTo("is this a question?");
                    assertThat(results.getInt("bound")).isEqualTo(1);
                }
            }
        }
    }

    @Nested
    @DisplayName("named parameters")
    class Named {

        @Test
        void passCypherParametersThroughUntouched() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN $name AS value")) {
                statement.unwrap(FalkorDBPreparedStatement.class).setNamedObject("name", "Alice");

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getString("value")).isEqualTo("Alice");
                }
            }
        }

        @Test
        void reportNoPositionalParameters() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN $name AS value")) {
                assertThat(statement.getParameterMetaData().getParameterCount()).isZero();
            }
        }

        @Test
        void areVisibleThroughNativeSql() throws SQLException {
            assertThat(connection.nativeSQL("MATCH (n) WHERE n.id = ? RETURN n"))
                    .isEqualTo("MATCH (n) WHERE n.id = $p1 RETURN n");
        }
    }

    @Nested
    @DisplayName("setter coverage")
    class Setters {

        @Test
        void honourACalendarWhenBindingATimestamp() throws SQLException {
            java.util.Calendar utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            java.util.Calendar tokyo = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            java.sql.Timestamp instant = java.sql.Timestamp.from(java.time.Instant.parse("2024-03-01T15:30:00Z"));

            assertThat(boundTimestamp(instant, utc)).isEqualTo("2024-03-01T15:30");
            // The same instant is the next day in Tokyo, and the calendar is what decides.
            assertThat(boundTimestamp(instant, tokyo)).isEqualTo("2024-03-02T00:30");
        }

        @Test
        void honourACalendarWhenBindingADate() throws SQLException {
            java.util.Calendar utc = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
            java.sql.Date date = new java.sql.Date(
                    java.time.Instant.parse("2024-03-01T23:30:00Z").toEpochMilli());

            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS bound")) {
                statement.setDate(1, date, utc);

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getString("bound")).isEqualTo("2024-03-01");
                }
            }
        }

        private String boundTimestamp(java.sql.Timestamp value, java.util.Calendar cal) throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS bound")) {
                statement.setTimestamp(1, value, cal);

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    return results.getString("bound");
                }
            }
        }

        @Test
        void setNullBindsCypherNull() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? IS NULL AS missing")) {
                statement.setNull(1, Types.VARCHAR);

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getBoolean("missing")).isTrue();
                }
            }
        }

        @Test
        void numericSettersRoundTrip() throws SQLException {
            try (PreparedStatement statement =
                    connection.prepareStatement("RETURN ? AS b, ? AS s, ? AS i, ? AS l, ? AS f, ? AS d")) {
                statement.setByte(1, (byte) 1);
                statement.setShort(2, (short) 2);
                statement.setInt(3, 3);
                statement.setLong(4, 4L);
                statement.setFloat(5, 5.5f);
                statement.setDouble(6, 6.5d);

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getByte("b")).isEqualTo((byte) 1);
                    assertThat(results.getShort("s")).isEqualTo((short) 2);
                    assertThat(results.getInt("i")).isEqualTo(3);
                    assertThat(results.getLong("l")).isEqualTo(4L);
                    assertThat(results.getFloat("f")).isEqualTo(5.5f);
                    assertThat(results.getDouble("d")).isEqualTo(6.5d);
                }
            }
        }

        @Test
        void bigDecimalIsNarrowedToADouble() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                statement.setBigDecimal(1, new BigDecimal("12.5"));

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getBigDecimal("value")).isEqualByComparingTo("12.5");
                }
            }
        }

        @Test
        void booleanRoundTrips() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                statement.setBoolean(1, true);

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getBoolean("value")).isTrue();
                }
            }
        }

        @Test
        void bytesRoundTripThroughAnIntegerList() throws SQLException {
            byte[] payload = {1, 2, 3, -1};

            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                statement.setBytes(1, payload);

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getBytes("value")).containsExactly(payload);
                }
            }
        }

        @Test
        void temporalsAreBoundAsIsoStrings() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS day, ? AS moment")) {
                statement.setDate(1, Date.valueOf(LocalDate.of(2024, 3, 14)));
                statement.setTimestamp(2, Timestamp.valueOf("2024-03-14 01:59:26"));

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getString("day")).isEqualTo("2024-03-14");
                    assertThat(results.getString("moment")).startsWith("2024-03-14T01:59:26");
                    assertThat(results.getDate("day").toLocalDate()).isEqualTo(LocalDate.of(2024, 3, 14));
                }
            }
        }

        @Test
        void listsAreBoundAsCypherArrays() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                statement.setObject(1, List.of(1L, 2L, 3L));

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(readList(results, "value")).containsExactly(1L, 2L, 3L);
                }
            }
        }

        @Test
        void mapsAreBoundAsCypherMaps() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ?.name AS name")) {
                statement.setObject(1, java.util.Map.of("name", "Alice"));

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(results.getString("name")).isEqualTo("Alice");
                }
            }
        }

        @Test
        void refusesAContainerThatHoldsItself() throws SQLException {
            List<Object> cycle = new java.util.ArrayList<>();
            cycle.add("x");
            cycle.add(cycle);

            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                // Without a guard the encoder walks the cycle until the stack runs out, and a
                // StackOverflowError is not something a caller can be asked to handle.
                assertThatThrownBy(() -> statement.setObject(1, cycle))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("contains itself");
            }
        }

        @Test
        void refusesAMapThatHoldsItselfIndirectly() throws SQLException {
            java.util.Map<String, Object> outer = new java.util.LinkedHashMap<>();
            List<Object> inner = new java.util.ArrayList<>();
            inner.add(outer);
            outer.put("inner", inner);

            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                assertThatThrownBy(() -> statement.setObject(1, outer))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("contains itself");
            }
        }

        @Test
        void bindsTheSameListTwiceInOneParameter() throws SQLException {
            // Sharing a container is a tree, not a cycle, so the guard must let it through.
            List<Long> shared = List.of(1L, 2L);

            try (PreparedStatement statement = connection.prepareStatement("RETURN ?[0] AS value")) {
                statement.setObject(1, List.of(shared, shared));

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(readList(results, "value")).containsExactly(1L, 2L);
                }
            }
        }

        @Test
        void jdbcArraysAreBoundAsCypherArrays() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                statement.setArray(1, connection.createArrayOf("BIGINT", new Object[] {1L, 2L}));

                try (ResultSet results = statement.executeQuery()) {
                    results.next();
                    assertThat(readList(results, "value")).containsExactly(1L, 2L);
                }
            }
        }

        @Test
        void refuseStreamAndLobSetters() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                assertThatThrownBy(() -> statement.setBlob(1, (java.sql.Blob) null))
                        .isInstanceOf(SQLFeatureNotSupportedException.class);
                assertThatThrownBy(() -> statement.setAsciiStream(1, null, 0))
                        .isInstanceOf(SQLFeatureNotSupportedException.class);
            }
        }
    }

    private static List<Object> readList(ResultSet results, String column) throws SQLException {
        Object[] array = (Object[]) results.getArray(column).getArray();
        return new ArrayList<>(java.util.Arrays.asList(array));
    }

    @Nested
    @DisplayName("non-finite numbers")
    class NonFinite {

        @Test
        void areRejectedWhenBound() throws SQLException {
            try (PreparedStatement statement = connection.prepareStatement("RETURN ? AS value")) {
                assertThatThrownBy(() -> statement.setDouble(1, Double.NaN))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("non-finite");
                assertThatThrownBy(() -> statement.setFloat(1, Float.POSITIVE_INFINITY))
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("non-finite");
            }
        }
    }
}
