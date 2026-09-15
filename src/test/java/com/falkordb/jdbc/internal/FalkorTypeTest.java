package com.falkordb.jdbc.internal;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import com.falkordb.graph_entities.Edge;
import com.falkordb.graph_entities.Node;
import com.falkordb.graph_entities.Path;
import com.falkordb.graph_entities.Point;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class FalkorTypeTest {

    static Node node(long id, String label, Map<String, Object> properties) {
        Node node = new Node();
        node.setId(id);
        node.addLabel(label);
        properties.forEach(node::addProperty);
        return node;
    }

    static Edge edge(long id, String type, long source, long target, Map<String, Object> properties) {
        Edge edge = new Edge();
        edge.setId(id);
        edge.setRelationshipType(type);
        edge.setSource(source);
        edge.setDestination(target);
        properties.forEach(edge::addProperty);
        return edge;
    }

    @Nested
    @DisplayName("classifies the concrete classes JFalkorDB deserializes to")
    class Classification {

        @Test
        void nullIsNull() {
            assertThat(FalkorType.of(null)).isEqualTo(FalkorType.NULL);
        }

        @Test
        void stringsAreVarchar() {
            assertThat(FalkorType.of("hello")).isEqualTo(FalkorType.STRING);
            assertThat(FalkorType.STRING.sqlType()).isEqualTo(Types.VARCHAR);
        }

        @Test
        void integersAreBigint() {
            assertThat(FalkorType.of(42L)).isEqualTo(FalkorType.INTEGER);
            assertThat(FalkorType.INTEGER.sqlType()).isEqualTo(Types.BIGINT);
            assertThat(FalkorType.INTEGER.javaType()).isEqualTo(Long.class);
        }

        @Test
        void booleansAreBoolean() {
            assertThat(FalkorType.of(true)).isEqualTo(FalkorType.BOOLEAN);
            assertThat(FalkorType.BOOLEAN.sqlType()).isEqualTo(Types.BOOLEAN);
        }

        @Test
        void doublesAreDouble() {
            assertThat(FalkorType.of(1.5d)).isEqualTo(FalkorType.DOUBLE);
            assertThat(FalkorType.DOUBLE.sqlType()).isEqualTo(Types.DOUBLE);
        }

        @Test
        void listsAreArrays() {
            assertThat(FalkorType.of(List.of(1L, 2L))).isEqualTo(FalkorType.ARRAY);
            assertThat(FalkorType.ARRAY.sqlType()).isEqualTo(Types.ARRAY);
        }

        @Test
        void aListOfFloatsIsAVector() {
            assertThat(FalkorType.of(List.of(1.0f, 2.0f))).isEqualTo(FalkorType.VECTORF32);
            assertThat(FalkorType.VECTORF32.sqlType()).isEqualTo(Types.ARRAY);
        }

        @Test
        void anEmptyListIsAnOrdinaryArray() {
            assertThat(FalkorType.of(List.of())).isEqualTo(FalkorType.ARRAY);
        }

        @Test
        void aMixedListIsNotAVector() {
            assertThat(FalkorType.of(List.of(1.0f, 2L))).isEqualTo(FalkorType.ARRAY);
        }

        @Test
        void mapsAreJavaObjects() {
            assertThat(FalkorType.of(Map.of("a", 1L))).isEqualTo(FalkorType.MAP);
            assertThat(FalkorType.MAP.sqlType()).isEqualTo(Types.JAVA_OBJECT);
        }

        @Test
        void graphEntitiesAreJavaObjects() {
            assertThat(FalkorType.of(node(1, "Person", Map.of()))).isEqualTo(FalkorType.NODE);
            assertThat(FalkorType.of(edge(1, "KNOWS", 1, 2, Map.of()))).isEqualTo(FalkorType.RELATIONSHIP);
            assertThat(FalkorType.of(new Path(List.of(), List.of()))).isEqualTo(FalkorType.PATH);
            assertThat(FalkorType.of(new Point(1.0, 2.0))).isEqualTo(FalkorType.POINT);

            assertThat(FalkorType.NODE.sqlType()).isEqualTo(Types.JAVA_OBJECT);
            assertThat(FalkorType.RELATIONSHIP.sqlType()).isEqualTo(Types.JAVA_OBJECT);
            assertThat(FalkorType.PATH.sqlType()).isEqualTo(Types.JAVA_OBJECT);
            assertThat(FalkorType.POINT.sqlType()).isEqualTo(Types.JAVA_OBJECT);
        }

        @Test
        void temporalsMapOntoTheJdbcTemporalTypes() {
            assertThat(FalkorType.of(LocalDate.of(2024, 1, 15))).isEqualTo(FalkorType.DATE);
            assertThat(FalkorType.of(LocalTime.of(12, 30))).isEqualTo(FalkorType.TIME);
            assertThat(FalkorType.of(LocalDateTime.of(2024, 1, 15, 12, 30))).isEqualTo(FalkorType.DATETIME);
            assertThat(FalkorType.of(Duration.ofHours(2))).isEqualTo(FalkorType.DURATION);

            assertThat(FalkorType.DATE.sqlType()).isEqualTo(Types.DATE);
            assertThat(FalkorType.TIME.sqlType()).isEqualTo(Types.TIME);
            assertThat(FalkorType.DATETIME.sqlType()).isEqualTo(Types.TIMESTAMP);
        }

        @Test
        void anythingElseIsUnknown() {
            assertThat(FalkorType.of(new Object())).isEqualTo(FalkorType.UNKNOWN);
            assertThat(FalkorType.UNKNOWN.sqlType()).isEqualTo(Types.OTHER);
        }

        @Test
        void onlyNumbersAreSigned() {
            assertThat(FalkorType.INTEGER.isSigned()).isTrue();
            assertThat(FalkorType.DOUBLE.isSigned()).isTrue();
            assertThat(FalkorType.STRING.isSigned()).isFalse();
            assertThat(FalkorType.NODE.isSigned()).isFalse();
        }
    }

    @Nested
    @DisplayName("rendering for getString")
    class Rendering {

        @Test
        void scalarsRenderPlainly() {
            assertThat(GraphValues.render("hello")).isEqualTo("hello");
            assertThat(GraphValues.render(42L)).isEqualTo("42");
            assertThat(GraphValues.render(true)).isEqualTo("true");
            assertThat(GraphValues.render(null)).isNull();
        }

        @Test
        void stringsAreQuotedOnlyWhenNested() {
            assertThat(GraphValues.render(List.of("a", "b"))).isEqualTo("[\"a\", \"b\"]");
        }

        @Test
        void mapsRenderAsCypherMaps() {
            assertThat(GraphValues.render(new java.util.LinkedHashMap<>(Map.of("n", 1L))))
                    .isEqualTo("{n: 1}");
        }

        @Test
        void nodesRenderWithLabelsAndProperties() {
            String rendered = GraphValues.render(node(7, "Person", Map.of("name", "Ada")));

            assertThat(rendered)
                    .contains(":Person")
                    .contains("name: \"Ada\"")
                    .startsWith("(")
                    .endsWith(")");
        }

        @Test
        void relationshipsRenderWithTypeAndEndpoints() {
            String rendered = GraphValues.render(edge(3, "KNOWS", 1, 2, Map.of("since", 2020L)));

            assertThat(rendered).contains(":KNOWS").contains("since: 2020").contains("->");
        }

        @Test
        void pointsRenderWithCoordinates() {
            assertThat(GraphValues.render(new Point(32.07, 34.79)))
                    .contains("latitude")
                    .contains("longitude");
        }
    }

    @Nested
    @DisplayName("conversions")
    class Conversions {

        @Test
        void booleansFollowTheUsualRules() throws SQLException {
            assertThat(GraphValues.asBoolean(null)).isFalse();
            assertThat(GraphValues.asBoolean(true)).isTrue();
            assertThat(GraphValues.asBoolean(0L)).isFalse();
            assertThat(GraphValues.asBoolean(1L)).isTrue();
            assertThat(GraphValues.asBoolean(2L)).isTrue();
            assertThat(GraphValues.asBoolean("true")).isTrue();
            assertThat(GraphValues.asBoolean("false")).isFalse();
            assertThat(GraphValues.asBoolean("0")).isFalse();
        }

        @Test
        void narrowingChecksRange() throws SQLException {
            assertThat(GraphValues.asLong(42L, Integer.MIN_VALUE, Integer.MAX_VALUE, "int"))
                    .isEqualTo(42L);

            assertThatThrownBy(() -> GraphValues.asLong(Long.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, "int"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("int");
        }

        @Test
        void nullsBecomeZero() throws SQLException {
            assertThat(GraphValues.asLong(null, Long.MIN_VALUE, Long.MAX_VALUE, "long"))
                    .isZero();
            assertThat(GraphValues.asDouble(null)).isZero();
        }

        @Test
        void stringsAreParsedAsNumbers() throws SQLException {
            assertThat(GraphValues.asLong("17", Long.MIN_VALUE, Long.MAX_VALUE, "long"))
                    .isEqualTo(17L);
            assertThat(GraphValues.asDouble("1.25")).isEqualTo(1.25d, within(1e-9));
        }

        @Test
        void aDoubleWithAFractionIsNotAnInteger() {
            assertThatThrownBy(() -> GraphValues.asLong(1.5d, Long.MIN_VALUE, Long.MAX_VALUE, "long"))
                    .isInstanceOf(SQLException.class);
        }

        @Test
        void aDoubleWithoutAFractionIsAnInteger() throws SQLException {
            assertThat(GraphValues.asLong(2.0d, Long.MIN_VALUE, Long.MAX_VALUE, "long"))
                    .isEqualTo(2L);
        }

        @Test
        void nonNumericTextIsRefused() {
            assertThatThrownBy(() -> GraphValues.asDouble("not a number")).isInstanceOf(SQLException.class);
        }

        @Test
        void graphEntitiesCannotBecomeNumbers() {
            assertThatThrownBy(() -> GraphValues.asDouble(node(1, "Person", Map.of())))
                    .isInstanceOf(SQLException.class);
        }

        @Test
        void bigDecimalsComeFromNumbersAndText() throws SQLException {
            assertThat(GraphValues.asBigDecimal(5L)).isEqualByComparingTo(BigDecimal.valueOf(5));
            assertThat(GraphValues.asBigDecimal("2.50")).isEqualByComparingTo(new BigDecimal("2.50"));
            assertThat(GraphValues.asBigDecimal(null)).isNull();
        }

        @Test
        void temporalsConvertBetweenEachOtherWhereItIsMeaningful() throws SQLException {
            LocalDateTime moment = LocalDateTime.of(2024, 1, 15, 12, 30, 45);

            assertThat(GraphValues.asLocalDate(moment)).isEqualTo(LocalDate.of(2024, 1, 15));
            assertThat(GraphValues.asLocalTime(moment)).isEqualTo(LocalTime.of(12, 30, 45));
            assertThat(GraphValues.asLocalDateTime(LocalDate.of(2024, 1, 15)))
                    .isEqualTo(LocalDateTime.of(2024, 1, 15, 0, 0));
        }

        @Test
        void isoTextIsAcceptedForTemporals() throws SQLException {
            assertThat(GraphValues.asLocalDate("2024-01-15")).isEqualTo(LocalDate.of(2024, 1, 15));
            assertThat(GraphValues.asLocalTime("12:30:45")).isEqualTo(LocalTime.of(12, 30, 45));
            assertThat(GraphValues.asLocalDateTime("2024-01-15T12:30:45"))
                    .isEqualTo(LocalDateTime.of(2024, 1, 15, 12, 30, 45));
        }

        @Test
        void nullTemporalsStayNull() throws SQLException {
            assertThat(GraphValues.asLocalDate(null)).isNull();
            assertThat(GraphValues.asLocalTime(null)).isNull();
            assertThat(GraphValues.asLocalDateTime(null)).isNull();
        }

        @Test
        void bytesComeFromTextAndLists() throws SQLException {
            assertThat(GraphValues.asBytes("hi")).isEqualTo(new byte[] {'h', 'i'});
            assertThat(GraphValues.asBytes(List.of(1L, 2L))).isEqualTo(new byte[] {1, 2});
            assertThat(GraphValues.asBytes(null)).isNull();
        }

        @Test
        void typedGetObjectHonoursTheRequestedClass() throws SQLException {
            assertThat(GraphValues.as(42L, Integer.class, ZoneOffset.UTC)).isEqualTo(42);
            assertThat(GraphValues.as(42L, String.class, ZoneOffset.UTC)).isEqualTo("42");
            assertThat(GraphValues.as("2024-01-15", LocalDate.class, ZoneOffset.UTC))
                    .isEqualTo(LocalDate.of(2024, 1, 15));
            assertThat(GraphValues.as(null, Integer.class, ZoneOffset.UTC)).isNull();
        }

        @Test
        void typedGetObjectRefusesImpossibleRequests() {
            assertThatThrownBy(() -> GraphValues.as("hello", Integer.class, ZoneOffset.UTC))
                    .isInstanceOf(SQLException.class);
        }
    }

    @Nested
    @DisplayName("declared type names")
    class Names {

        @Test
        void resolvesFalkorAndStandardSqlSpellings() {
            assertThat(FalkorType.byName("STRING")).isEqualTo(FalkorType.STRING);
            assertThat(FalkorType.byName("varchar")).isEqualTo(FalkorType.STRING);
            assertThat(FalkorType.byName("BIGINT")).isEqualTo(FalkorType.INTEGER);
            assertThat(FalkorType.byName(" Integer ")).isEqualTo(FalkorType.INTEGER);
            assertThat(FalkorType.byName("REAL")).isEqualTo(FalkorType.DOUBLE);
            assertThat(FalkorType.byName("VECTORF32")).isEqualTo(FalkorType.VECTORF32);
        }

        @Test
        void rejectsNamesFalkorDbCannotStore() {
            assertThat(FalkorType.byName("STRUCT")).isNull();
            assertThat(FalkorType.byName(null)).isNull();
        }

        @Test
        void refusesToResolveTheMetadataOnlyHelpers() {
            // These exist to type DatabaseMetaData columns; they are not storable FalkorDB types,
            // so createArrayOf("SMALLINT", ...) must not quietly resolve to one.
            assertThat(FalkorType.METADATA_SMALLINT.metadataOnly()).isTrue();
            assertThat(FalkorType.METADATA_INTEGER.metadataOnly()).isTrue();
            assertThat(FalkorType.byName("SMALLINT")).isEqualTo(FalkorType.INTEGER);
            for (FalkorType type : FalkorType.values()) {
                if (!type.metadataOnly() && type != FalkorType.UNKNOWN) {
                    assertThat(FalkorType.byName(type.typeName())).isNotNull();
                }
            }
        }

        @Test
        void neverInfersTheHelperTypesFromAValue() {
            for (Object value : new Object[] {1L, (short) 1, 1, "x", 1.5d, 1.5f, true, null}) {
                assertThat(FalkorType.of(value).metadataOnly()).isFalse();
            }
        }
    }
}
