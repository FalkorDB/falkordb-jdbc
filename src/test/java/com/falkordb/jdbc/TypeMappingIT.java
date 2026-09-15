package com.falkordb.jdbc;

import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import com.falkordb.graph_entities.Edge;
import com.falkordb.graph_entities.Node;
import com.falkordb.graph_entities.Path;
import com.falkordb.graph_entities.Point;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trips every FalkorDB scalar type through the driver and asserts the documented JDBC mapping.
 *
 * <p>These are the tests that keep {@code FalkorType}'s table honest: the expectations here are read
 * off a live server rather than off the client's source.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TypeMappingIT {

    private Connection connection;
    private Statement statement;

    @BeforeAll
    void connect() throws SQLException {
        connection = TestServer.connect("jdbc-types");
        statement = connection.createStatement();
        statement.execute("MATCH (n) DETACH DELETE n");
    }

    @AfterAll
    void disconnect() throws SQLException {
        connection.close();
    }

    @Nested
    @DisplayName("scalars")
    class Scalars {

        @Test
        void nullBecomesSqlNull() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN null AS value")) {
                results.next();

                assertThat(results.getObject("value")).isNull();
                assertThat(results.wasNull()).isTrue();
                assertThat(results.getString("value")).isNull();
                assertThat(results.getInt("value")).isZero();
                assertThat(results.getBoolean("value")).isFalse();
                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.NULL);
            }
        }

        @Test
        void stringBecomesVarchar() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 'hello' AS value")) {
                results.next();

                assertThat(results.getObject("value")).isEqualTo("hello");
                assertThat(results.getString("value")).isEqualTo("hello");
                assertThat(results.wasNull()).isFalse();
                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.VARCHAR);
                assertThat(results.getMetaData().getColumnClassName(1)).isEqualTo("java.lang.String");
            }
        }

        @Test
        void integerBecomesBigint() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 42 AS value")) {
                results.next();

                assertThat(results.getObject("value")).isEqualTo(42L);
                assertThat(results.getLong("value")).isEqualTo(42L);
                assertThat(results.getInt("value")).isEqualTo(42);
                assertThat(results.getShort("value")).isEqualTo((short) 42);
                assertThat(results.getDouble("value")).isEqualTo(42.0);
                assertThat(results.getString("value")).isEqualTo("42");
                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.BIGINT);
            }
        }

        @Test
        void booleanBecomesBoolean() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN true AS yes, false AS no")) {
                results.next();

                assertThat(results.getObject("yes")).isEqualTo(Boolean.TRUE);
                assertThat(results.getBoolean("yes")).isTrue();
                assertThat(results.getBoolean("no")).isFalse();
                assertThat(results.getString("yes")).isEqualTo("true");
                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.BOOLEAN);
            }
        }

        @Test
        void doubleBecomesDouble() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 3.5 AS value")) {
                results.next();

                assertThat(results.getObject("value")).isEqualTo(3.5d);
                assertThat(results.getDouble("value")).isEqualTo(3.5d);
                assertThat(results.getFloat("value")).isEqualTo(3.5f);
                assertThat(results.getBigDecimal("value")).isEqualByComparingTo("3.5");
                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.DOUBLE);
            }
        }

        @Test
        void integersRoundTripAtFullWidth() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 9223372036854775807 AS value")) {
                results.next();

                assertThat(results.getLong("value")).isEqualTo(Long.MAX_VALUE);
            }
        }
    }

    @Nested
    @DisplayName("collections")
    class Collections {

        @Test
        void listBecomesAnArray() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN [1, 2, 3] AS value")) {
                results.next();

                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.ARRAY);
                java.sql.Array array = results.getArray("value");
                assertThat((Object[]) array.getArray()).containsExactly(1L, 2L, 3L);
                assertThat(array.getBaseType()).isEqualTo(Types.BIGINT);
                @SuppressWarnings("unchecked")
                List<Object> list = results.getObject("value", List.class);
                assertThat(list).containsExactly(1L, 2L, 3L);
                assertThat(results.getString("value")).isEqualTo("[1, 2, 3]");
            }
        }

        @Test
        void heterogeneousListsReportAnUnknownElementType() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN [1, 'two', true] AS value")) {
                results.next();

                assertThat(results.getArray("value").getBaseType()).isEqualTo(Types.OTHER);
                assertThat((Object[]) results.getArray("value").getArray()).containsExactly(1L, "two", true);
            }
        }

        @Test
        void arraysCanBeSlicedAndIterated() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN [10, 20, 30] AS value")) {
                results.next();
                java.sql.Array array = results.getArray("value");

                assertThat((Object[]) array.getArray(2, 2)).containsExactly(20L, 30L);

                try (ResultSet elements = array.getResultSet()) {
                    elements.next();
                    assertThat(elements.getInt("INDEX")).isEqualTo(1);
                    assertThat(elements.getLong("VALUE")).isEqualTo(10L);
                }
            }
        }

        @Test
        void mapBecomesAJavaObject() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN {name: 'Alice', age: 34} AS value")) {
                results.next();

                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.JAVA_OBJECT);
                @SuppressWarnings("unchecked")
                Map<String, Object> map = results.getObject("value", Map.class);
                assertThat(map).containsEntry("name", "Alice").containsEntry("age", 34L);
                assertThat(results.getString("value")).contains("name: \"Alice\"");
            }
        }

        @Test
        void vectorBecomesAFloatArray() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN vecf32([1.0, 2.0]) AS value")) {
                results.next();

                assertThat(results.getObject("value", float[].class)).containsExactly(1.0f, 2.0f);
                assertThat(results.getArray("value").getBaseType()).isEqualTo(Types.REAL);
                assertThat(results.getMetaData().getColumnTypeName(1)).isEqualTo("VECTORF32");
            }
        }

        @Test
        void aVectorDescribesItsComponentsConsistently() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN vecf32([1.0, 2.0]) AS value")) {
                results.next();
                Array vector = results.getArray("value");

                // The row view must agree with getBaseType(); reporting DOUBLE here while
                // getBaseType() says REAL would make the two views of one array contradict.
                try (ResultSet rows = vector.getResultSet()) {
                    ResultSetMetaData columns = rows.getMetaData();
                    assertThat(columns.getColumnType(2)).isEqualTo(vector.getBaseType());
                    assertThat(columns.getColumnTypeName(2)).isEqualTo(vector.getBaseTypeName());
                    assertThat(columns.getColumnClassName(2)).isEqualTo(Float.class.getName());

                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getObject(2)).isInstanceOf(Float.class).isEqualTo(1.0f);
                }
            }
        }

        @Test
        void refusesAStartIndexPastTheEndOfTheArray() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN [1, 2, 3] AS value")) {
                results.next();
                Array array = results.getArray("value");

                assertThat((Object[]) array.getArray(3, 5)).hasSize(1);
                assertThatThrownBy(() -> array.getArray(4, 0)).isInstanceOf(SQLException.class);
                assertThatThrownBy(() -> array.getArray(0, 1)).isInstanceOf(SQLException.class);
            }
        }

        @Test
        void stillAllowsTheOnlyIndexAnEmptyArrayHas() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN [] AS value")) {
                results.next();
                Array empty = results.getArray("value");

                assertThat((Object[]) empty.getArray(1, 0)).isEmpty();
                try (ResultSet rows = empty.getResultSet()) {
                    assertThat(rows.next()).isFalse();
                }
            }
        }

        @Test
        void getBytesRefusesAListItWouldHaveToTruncate() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN [1, 2] AS value")) {
                results.next();

                assertThat(results.getBytes("value")).containsExactly((byte) 1, (byte) 2);
            }
            try (ResultSet results = statement.executeQuery("RETURN [1.5, 2.0] AS value")) {
                results.next();

                assertThatThrownBy(() -> results.getBytes("value")).isInstanceOf(SQLException.class);
            }
        }
    }

    @Nested
    @DisplayName("temporals")
    class Temporals {

        @Test
        void dateBecomesASqlDate() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN date('2024-03-14') AS value")) {
                results.next();

                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.DATE);
                assertThat(results.getObject("value")).isEqualTo(LocalDate.of(2024, 3, 14));
                assertThat(results.getDate("value").toLocalDate()).isEqualTo(LocalDate.of(2024, 3, 14));
                assertThat(results.getObject("value", LocalDate.class)).isEqualTo(LocalDate.of(2024, 3, 14));
                assertThat(results.getString("value")).isEqualTo("2024-03-14");
            }
        }

        @Test
        void localtimeBecomesASqlTime() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN localtime('01:59:26') AS value")) {
                results.next();

                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.TIME);
                assertThat(results.getObject("value", LocalTime.class)).isEqualTo(LocalTime.of(1, 59, 26));
                assertThat(results.getTime("value").toLocalTime()).isEqualTo(LocalTime.of(1, 59, 26));
            }
        }

        @Test
        void localdatetimeBecomesATimestamp() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN localdatetime('2024-03-14T01:59:26') AS value")) {
                results.next();

                assertThat(results.getMetaData().getColumnType(1)).isEqualTo(Types.TIMESTAMP);
                assertThat(results.getObject("value", LocalDateTime.class))
                        .isEqualTo(LocalDateTime.of(2024, 3, 14, 1, 59, 26));
                assertThat(results.getTimestamp("value").toLocalDateTime())
                        .isEqualTo(LocalDateTime.of(2024, 3, 14, 1, 59, 26));
                assertThat(results.getDate("value").toLocalDate()).isEqualTo(LocalDate.of(2024, 3, 14));
            }
        }

        @Test
        void durationBecomesAJavaTimeDuration() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN duration({hours: 1, minutes: 30}) AS value")) {
                results.next();

                assertThat(results.getObject("value", Duration.class)).isEqualTo(Duration.ofMinutes(90));
                assertThat(results.getMetaData().getColumnTypeName(1)).isEqualTo("DURATION");
            }
        }
    }

    @Nested
    @DisplayName("graph entities")
    class Entities {

        @Test
        void nodeIsExposedAsAJavaObject() throws SQLException {
            statement.executeUpdate("CREATE (:Person:Employee {name: 'Alice', age: 34})");

            try (ResultSet results = statement.executeQuery("MATCH (p:Person) RETURN p")) {
                results.next();
                ResultSetMetaData metaData = results.getMetaData();

                assertThat(metaData.getColumnType(1)).isEqualTo(Types.JAVA_OBJECT);
                assertThat(metaData.getColumnTypeName(1)).isEqualTo("NODE");
                assertThat(metaData.getColumnClassName(1)).isEqualTo(Node.class.getName());

                Node node = results.getObject("p", Node.class);
                assertThat(node.getNumberOfLabels()).isEqualTo(2);
                assertThat(node.getProperty("name").getValue()).isEqualTo("Alice");
                assertThat(results.getString("p")).contains(":Person:Employee").contains("name: \"Alice\"");
            }
        }

        @Test
        void edgeIsExposedAsAJavaObject() throws SQLException {
            statement.executeUpdate("CREATE (:A {id: 1})-[:KNOWS {since: 2020}]->(:B {id: 2})");

            try (ResultSet results = statement.executeQuery("MATCH ()-[r:KNOWS]->() RETURN r")) {
                results.next();

                assertThat(results.getMetaData().getColumnTypeName(1)).isEqualTo("RELATIONSHIP");
                Edge edge = results.getObject("r", Edge.class);
                assertThat(edge.getRelationshipType()).isEqualTo("KNOWS");
                assertThat(edge.getProperty("since").getValue()).isEqualTo(2020L);
                assertThat(results.getString("r")).contains(":KNOWS").contains("since: 2020");
            }
        }

        @Test
        void pathIsExposedAsAJavaObject() throws SQLException {
            statement.executeUpdate("CREATE (:Start {id: 1})-[:LEADS_TO]->(:End {id: 2})");

            try (ResultSet results = statement.executeQuery("MATCH path = (:Start)-[:LEADS_TO]->(:End) RETURN path")) {
                results.next();

                assertThat(results.getMetaData().getColumnTypeName(1)).isEqualTo("PATH");
                Path path = results.getObject("path", Path.class);
                assertThat(path.length()).isEqualTo(1);
                assertThat(path.nodeCount()).isEqualTo(2);
                assertThat(results.getString("path")).contains(":Start").contains(":LEADS_TO");
            }
        }

        @Test
        void pointIsExposedAsAJavaObject() throws SQLException {
            try (ResultSet results =
                    statement.executeQuery("RETURN point({latitude: 32.07, longitude: 34.79}) AS value")) {
                results.next();

                assertThat(results.getMetaData().getColumnTypeName(1)).isEqualTo("POINT");
                Point point = results.getObject("value", Point.class);
                assertThat(point.getLatitude()).isCloseTo(32.07, org.assertj.core.data.Offset.offset(0.001));
                assertThat(results.getString("value")).contains("latitude");
            }
        }
    }

    @Nested
    @DisplayName("access rules")
    class AccessRules {

        @Test
        void columnsAreOneBased() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 'a' AS first, 'b' AS second")) {
                results.next();

                assertThat(results.getString(1)).isEqualTo("a");
                assertThat(results.getString(2)).isEqualTo("b");
            }
        }

        @Test
        void wasNullTracksTheLastRead() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 1 AS present, null AS absent")) {
                results.next();

                results.getInt("present");
                assertThat(results.wasNull()).isFalse();
                results.getInt("absent");
                assertThat(results.wasNull()).isTrue();
                results.getInt("present");
                assertThat(results.wasNull()).isFalse();
            }
        }

        @Test
        void getObjectWithAClassConverts() throws SQLException {
            try (ResultSet results = statement.executeQuery("RETURN 42 AS value")) {
                results.next();

                assertThat(results.getObject("value", Long.class)).isEqualTo(42L);
                assertThat(results.getObject("value", Integer.class)).isEqualTo(42);
                assertThat(results.getObject("value", String.class)).isEqualTo("42");
                assertThat(results.getObject("value", Double.class)).isEqualTo(42.0);
            }
        }
    }
}
