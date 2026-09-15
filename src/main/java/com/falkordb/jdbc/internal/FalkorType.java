package com.falkordb.jdbc.internal;

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

/**
 * The FalkorDB value types this driver recognises, and how each one is projected onto JDBC.
 *
 * <p>FalkorDB tags every scalar it returns with an internal type code, but JFalkorDB consumes that
 * code during deserialization and hands back a plain Java object, so the driver re-derives the type
 * from the runtime class of the value (see {@link #of(Object)}). The mapping below is the single
 * source of truth behind {@link java.sql.ResultSetMetaData} and is reproduced in the project README.
 *
 * <table>
 *   <caption>FalkorDB to JDBC type mapping</caption>
 *   <tr><th>FalkorDB</th><th>Java class</th><th>{@link Types}</th></tr>
 *   <tr><td>NULL</td><td>{@code null}</td><td>NULL</td></tr>
 *   <tr><td>STRING</td><td>{@link String}</td><td>VARCHAR</td></tr>
 *   <tr><td>INTEGER (64-bit)</td><td>{@link Long}</td><td>BIGINT</td></tr>
 *   <tr><td>BOOLEAN</td><td>{@link Boolean}</td><td>BOOLEAN</td></tr>
 *   <tr><td>DOUBLE</td><td>{@link Double}</td><td>DOUBLE</td></tr>
 *   <tr><td>ARRAY</td><td>{@link List}</td><td>ARRAY</td></tr>
 *   <tr><td>VECTORF32</td><td>{@code List<Float>}</td><td>ARRAY</td></tr>
 *   <tr><td>MAP</td><td>{@link Map}</td><td>JAVA_OBJECT</td></tr>
 *   <tr><td>NODE</td><td>{@link Node}</td><td>JAVA_OBJECT</td></tr>
 *   <tr><td>EDGE</td><td>{@link Edge}</td><td>JAVA_OBJECT</td></tr>
 *   <tr><td>PATH</td><td>{@link Path}</td><td>JAVA_OBJECT</td></tr>
 *   <tr><td>POINT</td><td>{@link Point}</td><td>JAVA_OBJECT</td></tr>
 *   <tr><td>DATE</td><td>{@link LocalDate}</td><td>DATE</td></tr>
 *   <tr><td>TIME</td><td>{@link LocalTime}</td><td>TIME</td></tr>
 *   <tr><td>DATETIME</td><td>{@link LocalDateTime}</td><td>TIMESTAMP</td></tr>
 *   <tr><td>DURATION</td><td>{@link Duration}</td><td>JAVA_OBJECT</td></tr>
 * </table>
 */
public enum FalkorType {

    /** The Cypher {@code null} value. */
    NULL(Types.NULL, "NULL", Object.class, 0, 4),

    /** A UTF-8 string. */
    STRING(Types.VARCHAR, "STRING", String.class, 0, Integer.MAX_VALUE),

    /** A signed 64-bit integer. */
    INTEGER(Types.BIGINT, "INTEGER", Long.class, 19, 20),

    /**
     * A 16-bit integer. FalkorDB has no such type; this exists only so driver-generated {@link
     * java.sql.DatabaseMetaData} result sets can declare the {@code SMALLINT} columns the JDBC
     * specification mandates, such as {@code KEY_SEQ}. It is never inferred from a value.
     */
    METADATA_SMALLINT(Types.SMALLINT, "SMALLINT", Short.class, 5, 6),

    /**
     * A 32-bit integer. As with {@link #METADATA_SMALLINT}, this exists only to satisfy the JDBC
     * metadata schema — {@code DATA_TYPE}, {@code NULLABLE} and {@code ORDINAL_POSITION} are all
     * specified as {@code INTEGER} — and is never inferred from a value.
     */
    METADATA_INTEGER(Types.INTEGER, "INTEGER", Integer.class, 10, 11),

    /** A boolean. */
    BOOLEAN(Types.BOOLEAN, "BOOLEAN", Boolean.class, 1, 5),

    /** An IEEE-754 double. */
    DOUBLE(Types.DOUBLE, "DOUBLE", Double.class, 17, 24),

    /** A heterogeneous Cypher list. */
    ARRAY(Types.ARRAY, "ARRAY", java.sql.Array.class, 0, Integer.MAX_VALUE),

    /** A 32-bit float vector, as produced by {@code vecf32()}. */
    VECTORF32(Types.ARRAY, "VECTORF32", java.sql.Array.class, 0, Integer.MAX_VALUE),

    /** A Cypher map with string keys. */
    MAP(Types.JAVA_OBJECT, "MAP", Map.class, 0, Integer.MAX_VALUE),

    /** A graph node. */
    NODE(Types.JAVA_OBJECT, "NODE", Node.class, 0, Integer.MAX_VALUE),

    /** A graph relationship. */
    RELATIONSHIP(Types.JAVA_OBJECT, "RELATIONSHIP", Edge.class, 0, Integer.MAX_VALUE),

    /** A path of alternating nodes and relationships. */
    PATH(Types.JAVA_OBJECT, "PATH", Path.class, 0, Integer.MAX_VALUE),

    /** A geospatial point. */
    POINT(Types.JAVA_OBJECT, "POINT", Point.class, 0, Integer.MAX_VALUE),

    /** A calendar date without a time zone. */
    DATE(Types.DATE, "DATE", LocalDate.class, 10, 10),

    /** A wall-clock time without a time zone. */
    TIME(Types.TIME, "TIME", LocalTime.class, 8, 8),

    /** A local date and time without a time zone. */
    DATETIME(Types.TIMESTAMP, "DATETIME", LocalDateTime.class, 19, 19),

    /** A duration. */
    DURATION(Types.JAVA_OBJECT, "DURATION", Duration.class, 0, 32),

    /** A value whose type the driver does not recognise; surfaced as-is. */
    UNKNOWN(Types.OTHER, "UNKNOWN", Object.class, 0, Integer.MAX_VALUE);

    private final int sqlType;
    private final String typeName;
    private final Class<?> javaType;
    private final int precision;
    private final int displaySize;

    FalkorType(int sqlType, String typeName, Class<?> javaType, int precision, int displaySize) {
        this.sqlType = sqlType;
        this.typeName = typeName;
        this.javaType = javaType;
        this.precision = precision;
        this.displaySize = displaySize;
    }

    /**
     * The {@link Types} constant reported for this type.
     *
     * @return a {@code java.sql.Types} constant
     */
    public int sqlType() {
        return sqlType;
    }

    /**
     * FalkorDB's own name for this type, as reported by {@link
     * java.sql.ResultSetMetaData#getColumnTypeName(int)}.
     *
     * @return the type name
     */
    public String typeName() {
        return typeName;
    }

    /**
     * The Java class {@link java.sql.ResultSet#getObject(int)} returns for this type.
     *
     * @return the Java class
     */
    public Class<?> javaType() {
        return javaType;
    }

    /**
     * The precision reported by {@link java.sql.ResultSetMetaData#getPrecision(int)}; {@code 0} for
     * types with no meaningful precision.
     *
     * @return the precision
     */
    public int precision() {
        return precision;
    }

    /**
     * The width reported by {@link java.sql.ResultSetMetaData#getColumnDisplaySize(int)}.
     *
     * @return the display size
     */
    public int displaySize() {
        return displaySize;
    }

    /**
     * Whether values of this type compare and sort as numbers, which is what {@link
     * java.sql.ResultSetMetaData#isSigned(int)} asks about.
     *
     * @return {@code true} for the numeric types
     */
    public boolean isSigned() {
        return this == INTEGER || this == DOUBLE;
    }

    /**
     * Classifies a deserialized FalkorDB value.
     *
     * <p>A {@code vecf32} vector and an ordinary Cypher list are both delivered as a {@link List}, so
     * they are told apart by their elements: JFalkorDB decodes vector components to {@link Float} and
     * every other numeric list element to {@link Long} or {@link Double}, so a non-empty list of
     * {@code Float} is a vector.
     *
     * @param value a value taken from a FalkorDB record; may be {@code null}
     * @return the matching type, or {@link #UNKNOWN} for anything unrecognised
     */
    public static FalkorType of(Object value) {
        if (value == null) {
            return NULL;
        }
        if (value instanceof String) {
            return STRING;
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return INTEGER;
        }
        if (value instanceof Boolean) {
            return BOOLEAN;
        }
        if (value instanceof Double || value instanceof Float) {
            return DOUBLE;
        }
        if (value instanceof List<?> list) {
            return isFloatVector(list) ? VECTORF32 : ARRAY;
        }
        if (value instanceof Map) {
            return MAP;
        }
        if (value instanceof Node) {
            return NODE;
        }
        if (value instanceof Edge) {
            return RELATIONSHIP;
        }
        if (value instanceof Path) {
            return PATH;
        }
        if (value instanceof Point) {
            return POINT;
        }
        if (value instanceof LocalDate) {
            return DATE;
        }
        if (value instanceof LocalTime) {
            return TIME;
        }
        if (value instanceof LocalDateTime) {
            return DATETIME;
        }
        if (value instanceof Duration) {
            return DURATION;
        }
        return UNKNOWN;
    }

    private static boolean isFloatVector(List<?> list) {
        if (list.isEmpty()) {
            return false;
        }
        for (Object element : list) {
            if (!(element instanceof Float)) {
                return false;
            }
        }
        return true;
    }
}
