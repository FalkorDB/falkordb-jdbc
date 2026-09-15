package com.falkordb.jdbc;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.falkordb.jdbc.internal.ColumnMeta;
import com.falkordb.jdbc.internal.FalkorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the {@link ResultSet} implementation over synthetic rows, so the JDBC accessor rules can
 * be pinned down without a server.
 */
class FalkorDBResultSetTest {

    private static ResultSet resultSet(List<ColumnMeta> columns, List<List<Object>> rows) {
        return new FalkorDBResultSet(columns, rows, null);
    }

    private static ResultSet oneRow(FalkorType type, Object value) throws SQLException {
        List<Object> row = new ArrayList<>();
        row.add(value);
        ResultSet rs = resultSet(List.of(ColumnMeta.of("value", type)), List.of(row));
        rs.next();
        return rs;
    }

    @Nested
    @DisplayName("cursor")
    class Cursor {

        @Test
        void startsBeforeTheFirstRow() throws SQLException {
            ResultSet rs = resultSet(List.of(ColumnMeta.of("n", FalkorType.INTEGER)), List.of(List.of(1L)));

            assertThat(rs.isBeforeFirst()).isTrue();
            assertThat(rs.getRow()).isZero();
        }

        @Test
        void walksEveryRowOnce() throws SQLException {
            ResultSet rs = resultSet(
                    List.of(ColumnMeta.of("n", FalkorType.INTEGER)), List.of(List.of(1L), List.of(2L), List.of(3L)));

            List<Long> seen = new ArrayList<>();
            while (rs.next()) {
                seen.add(rs.getLong(1));
            }

            assertThat(seen).containsExactly(1L, 2L, 3L);
            assertThat(rs.isAfterLast()).isTrue();
        }

        @Test
        void refusesAccessBeforeTheFirstNext() {
            ResultSet rs = resultSet(List.of(ColumnMeta.of("n", FalkorType.INTEGER)), List.of(List.of(1L)));

            assertThatThrownBy(() -> rs.getLong(1)).isInstanceOf(SQLException.class);
        }

        @Test
        void refusesAccessAfterClose() throws SQLException {
            ResultSet rs = resultSet(List.of(ColumnMeta.of("n", FalkorType.INTEGER)), List.of(List.of(1L)));
            rs.next();
            rs.close();

            assertThat(rs.isClosed()).isTrue();
            assertThatThrownBy(() -> rs.getLong(1)).isInstanceOf(SQLException.class);
        }

        @Test
        void isForwardOnlyAndReadOnly() throws SQLException {
            ResultSet rs = resultSet(List.of(), List.of());

            assertThat(rs.getType()).isEqualTo(ResultSet.TYPE_FORWARD_ONLY);
            assertThat(rs.getConcurrency()).isEqualTo(ResultSet.CONCUR_READ_ONLY);
            assertThatThrownBy(rs::beforeFirst).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> rs.updateString(1, "x")).isInstanceOf(SQLFeatureNotSupportedException.class);
        }
    }

    @Nested
    @DisplayName("column addressing")
    class Addressing {

        private ResultSet twoColumns() throws SQLException {
            ResultSet rs = resultSet(
                    List.of(ColumnMeta.of("name", FalkorType.STRING), ColumnMeta.of("age", FalkorType.INTEGER)),
                    List.of(List.of("Ada", 36L)));
            rs.next();
            return rs;
        }

        @Test
        void indexesAreOneBased() throws SQLException {
            ResultSet rs = twoColumns();

            assertThat(rs.getString(1)).isEqualTo("Ada");
            assertThat(rs.getLong(2)).isEqualTo(36L);
        }

        @Test
        void labelsWorkToo() throws SQLException {
            ResultSet rs = twoColumns();

            assertThat(rs.getString("name")).isEqualTo("Ada");
            assertThat(rs.getLong("age")).isEqualTo(36L);
        }

        @Test
        void labelsAreCaseInsensitive() throws SQLException {
            assertThat(twoColumns().getString("NAME")).isEqualTo("Ada");
        }

        @Test
        void findColumnReportsThePosition() throws SQLException {
            assertThat(twoColumns().findColumn("age")).isEqualTo(2);
        }

        @Test
        void unknownLabelsAreRejected() throws SQLException {
            ResultSet rs = twoColumns();

            assertThatThrownBy(() -> rs.getString("nope"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("nope");
        }

        @Test
        void indexesOutsideTheRowAreRejected() throws SQLException {
            ResultSet rs = twoColumns();

            assertThatThrownBy(() -> rs.getString(0)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> rs.getString(3)).isInstanceOf(SQLException.class);
        }
    }

    @Nested
    @DisplayName("wasNull")
    class Nulls {

        @Test
        void reportsTheLastColumnRead() throws SQLException {
            ResultSet rs = resultSet(
                    List.of(ColumnMeta.of("a", FalkorType.STRING), ColumnMeta.of("b", FalkorType.STRING)),
                    List.of(Arrays.asList(null, "set")));
            rs.next();

            assertThat(rs.getString(1)).isNull();
            assertThat(rs.wasNull()).isTrue();

            assertThat(rs.getString(2)).isEqualTo("set");
            assertThat(rs.wasNull()).isFalse();
        }

        @Test
        void primitiveGettersReturnZeroOrFalseForNull() throws SQLException {
            ResultSet rs = oneRow(FalkorType.INTEGER, null);

            assertThat(rs.getLong(1)).isZero();
            assertThat(rs.getInt(1)).isZero();
            assertThat(rs.getDouble(1)).isZero();
            assertThat(rs.getBoolean(1)).isFalse();
            assertThat(rs.wasNull()).isTrue();
        }

        @Test
        void objectGettersReturnNull() throws SQLException {
            ResultSet rs = oneRow(FalkorType.STRING, null);

            assertThat(rs.getObject(1)).isNull();
            assertThat(rs.getString(1)).isNull();
            assertThat(rs.getBigDecimal(1)).isNull();
            assertThat(rs.getDate(1)).isNull();
        }
    }

    @Nested
    @DisplayName("type mapping")
    class TypeMapping {

        @Test
        void integersWidenAndNarrow() throws SQLException {
            ResultSet rs = oneRow(FalkorType.INTEGER, 42L);

            assertThat(rs.getObject(1)).isInstanceOf(Long.class).isEqualTo(42L);
            assertThat(rs.getLong(1)).isEqualTo(42L);
            assertThat(rs.getInt(1)).isEqualTo(42);
            assertThat(rs.getShort(1)).isEqualTo((short) 42);
            assertThat(rs.getByte(1)).isEqualTo((byte) 42);
            assertThat(rs.getDouble(1)).isEqualTo(42d);
            assertThat(rs.getString(1)).isEqualTo("42");
            assertThat(rs.getBigDecimal(1)).isEqualByComparingTo(BigDecimal.valueOf(42));
        }

        @Test
        void narrowingThatWouldLoseDataThrows() throws SQLException {
            ResultSet rs = oneRow(FalkorType.INTEGER, (long) Integer.MAX_VALUE + 1);

            assertThatThrownBy(() -> rs.getInt(1)).isInstanceOf(SQLException.class);
        }

        @Test
        void doubles() throws SQLException {
            ResultSet rs = oneRow(FalkorType.DOUBLE, 1.5d);

            assertThat(rs.getObject(1)).isEqualTo(1.5d);
            assertThat(rs.getDouble(1)).isEqualTo(1.5d);
            assertThat(rs.getFloat(1)).isEqualTo(1.5f);
            assertThat(rs.getString(1)).isEqualTo("1.5");
        }

        @Test
        void booleans() throws SQLException {
            ResultSet rs = oneRow(FalkorType.BOOLEAN, true);

            assertThat(rs.getObject(1)).isEqualTo(true);
            assertThat(rs.getBoolean(1)).isTrue();
            assertThat(rs.getString(1)).isEqualTo("true");
        }

        @Test
        void listsBecomeSqlArrays() throws SQLException {
            ResultSet rs = oneRow(FalkorType.ARRAY, List.of(1L, 2L, 3L));

            Array array = rs.getArray(1);
            assertThat(array).isNotNull();
            assertThat((Object[]) array.getArray()).containsExactly(1L, 2L, 3L);
            assertThat(array.getBaseType()).isEqualTo(Types.BIGINT);
            assertThat(rs.getString(1)).isEqualTo("[1, 2, 3]");
        }

        @Test
        void arraySlicesAreOneBased() throws SQLException {
            Array array = oneRow(FalkorType.ARRAY, List.of("a", "b", "c")).getArray(1);

            assertThat((Object[]) array.getArray(1, 2)).containsExactly("a", "b");
            assertThat((Object[]) array.getArray(2, 2)).containsExactly("b", "c");
        }

        @Test
        void vectorsBecomeFloatArrays() throws SQLException {
            ResultSet rs = oneRow(FalkorType.VECTORF32, List.of(1.0f, 2.5f));

            assertThat(rs.getObject(1, float[].class)).containsExactly(1.0f, 2.5f);
            assertThat(rs.getArray(1).getBaseType()).isEqualTo(Types.REAL);
        }

        @Test
        void mapsArriveAsMaps() throws SQLException {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("a", 1L);
            ResultSet rs = oneRow(FalkorType.MAP, value);

            assertThat(rs.getObject(1)).isInstanceOf(Map.class).isEqualTo(value);
            assertThat(rs.getString(1)).isEqualTo("{a: 1}");
        }

        @Test
        void nodesArriveAsJFalkorDbEntities() throws SQLException {
            com.falkordb.graph_entities.Node node = FalkorTypeFixtures.node(1, "Person", Map.of("name", "Ada"));
            ResultSet rs = oneRow(FalkorType.NODE, node);

            assertThat(rs.getObject(1)).isSameAs(node);
            assertThat(rs.getObject(1, com.falkordb.graph_entities.Node.class)).isSameAs(node);
            assertThat(rs.getString(1)).contains(":Person").contains("Ada");
        }

        @Test
        void temporals() throws SQLException {
            ResultSet dates = oneRow(FalkorType.DATE, LocalDate.of(2024, 1, 15));
            assertThat(dates.getDate(1)).isEqualTo(Date.valueOf(LocalDate.of(2024, 1, 15)));
            assertThat(dates.getObject(1, LocalDate.class)).isEqualTo(LocalDate.of(2024, 1, 15));

            ResultSet times = oneRow(FalkorType.TIME, LocalTime.of(12, 30, 45));
            assertThat(times.getTime(1)).isEqualTo(Time.valueOf(LocalTime.of(12, 30, 45)));

            LocalDateTime moment = LocalDateTime.of(2024, 1, 15, 12, 30, 45);
            ResultSet timestamps = oneRow(FalkorType.DATETIME, moment);
            assertThat(timestamps.getTimestamp(1)).isEqualTo(Timestamp.valueOf(moment));
            assertThat(timestamps.getObject(1, LocalDateTime.class)).isEqualTo(moment);
        }

        @Test
        void durations() throws SQLException {
            ResultSet rs = oneRow(FalkorType.DURATION, Duration.ofMinutes(90));

            assertThat(rs.getObject(1)).isEqualTo(Duration.ofMinutes(90));
            assertThat(rs.getString(1)).isEqualTo("PT1H30M");
        }

        @Test
        void bytesRoundTripThroughIntegerLists() throws SQLException {
            ResultSet rs = oneRow(FalkorType.ARRAY, List.of(1L, 2L, 3L));

            assertThat(rs.getBytes(1)).containsExactly(1, 2, 3);
        }
    }

    @Nested
    @DisplayName("metadata")
    class Metadata {

        @Test
        void describesEachColumn() throws SQLException {
            ResultSetMetaData meta = resultSet(
                            List.of(
                                    ColumnMeta.of("name", FalkorType.STRING),
                                    ColumnMeta.of("age", FalkorType.INTEGER),
                                    ColumnMeta.of("friend", FalkorType.NODE)),
                            List.of())
                    .getMetaData();

            assertThat(meta.getColumnCount()).isEqualTo(3);
            assertThat(meta.getColumnLabel(1)).isEqualTo("name");
            assertThat(meta.getColumnType(1)).isEqualTo(Types.VARCHAR);
            assertThat(meta.getColumnTypeName(1)).isEqualTo("STRING");
            assertThat(meta.getColumnClassName(1)).isEqualTo(String.class.getName());

            assertThat(meta.getColumnType(2)).isEqualTo(Types.BIGINT);
            assertThat(meta.isSigned(2)).isTrue();
            assertThat(meta.isSigned(1)).isFalse();

            assertThat(meta.getColumnType(3)).isEqualTo(Types.JAVA_OBJECT);
            assertThat(meta.getColumnTypeName(3)).isEqualTo("NODE");
        }

        @Test
        void everyColumnIsReadOnlyAndNullable() throws SQLException {
            ResultSetMetaData meta = resultSet(List.of(ColumnMeta.of("n", FalkorType.INTEGER)), List.of())
                    .getMetaData();

            assertThat(meta.isReadOnly(1)).isTrue();
            assertThat(meta.isWritable(1)).isFalse();
            assertThat(meta.isNullable(1)).isEqualTo(ResultSetMetaData.columnNullable);
            assertThat(meta.isAutoIncrement(1)).isFalse();
        }

        @Test
        void infersScalarColumnsFromTheData() {
            ColumnMeta inferred = ColumnMeta.infer("v", List.of(List.of("text")), 0);

            assertThat(inferred.type()).isEqualTo(FalkorType.STRING);
        }

        @Test
        void anAllNullColumnIsReportedAsNull() {
            ColumnMeta inferred = ColumnMeta.infer("v", List.of(Arrays.asList((Object) null)), 0);

            assertThat(inferred.type()).isEqualTo(FalkorType.NULL);
        }

        @Test
        void aHeterogeneousColumnIsReportedAsUnknown() {
            ColumnMeta inferred = ColumnMeta.infer("v", List.of(List.of(1L), List.of("two")), 0);

            assertThat(inferred.type()).isEqualTo(FalkorType.UNKNOWN);
        }
    }
}
