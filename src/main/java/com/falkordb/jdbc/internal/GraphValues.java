package com.falkordb.jdbc.internal;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

import com.falkordb.graph_entities.Edge;
import com.falkordb.graph_entities.GraphEntity;
import com.falkordb.graph_entities.Node;
import com.falkordb.graph_entities.Path;
import com.falkordb.graph_entities.Point;
import com.falkordb.graph_entities.Property;

/**
 * Converts deserialized FalkorDB values into the Java types JDBC callers ask for, and renders them
 * as text for {@link java.sql.ResultSet#getString(int)}.
 *
 * <p>Conversions are deliberately narrow: a value is converted when the meaning is unambiguous
 * (a {@code STRING} holding digits to a {@code long}, an {@code INTEGER} to a {@code double}) and
 * refused with a {@link SQLException} otherwise, so a typo in a query surfaces as an error instead of
 * a silent zero. Numeric narrowing that would lose the value is refused for the same reason.
 */
public final class GraphValues {

    private GraphValues() {}

    // ---------------------------------------------------------------- rendering

    /**
     * Renders a value the way {@code getString} should present it: strings come back unquoted,
     * everything else is rendered in Cypher-like notation.
     *
     * @param value the value to render; may be {@code null}
     * @return the rendering, or {@code null} for a null value
     */
    public static String render(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String s) {
            return s;
        }
        return renderNested(value);
    }

    /**
     * Renders a value as it appears inside a larger structure, where a string must be quoted so it is
     * distinguishable from an identifier or a number.
     *
     * @param value the value to render; may be {@code null}
     * @return the rendering; the literal {@code "null"} for a null value
     */
    public static String renderNested(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof String s) {
            return quote(s);
        }
        if (value instanceof Node node) {
            return renderNode(node);
        }
        if (value instanceof Edge edge) {
            return renderEdge(edge);
        }
        if (value instanceof Path path) {
            return renderPath(path);
        }
        if (value instanceof Point point) {
            return "point({latitude: " + point.getLatitude() + ", longitude: " + point.getLongitude() + "})";
        }
        if (value instanceof Map<?, ?> map) {
            return renderMap(map);
        }
        if (value instanceof Collection<?> collection) {
            StringJoiner joiner = new StringJoiner(", ", "[", "]");
            collection.forEach(element -> joiner.add(renderNested(element)));
            return joiner.toString();
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        if (value.getClass().isArray()) {
            return renderNested(boxArray(value));
        }
        return String.valueOf(value);
    }

    private static String renderNode(Node node) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < node.getNumberOfLabels(); i++) {
            sb.append(':').append(escapeIdentifier(node.getLabel(i)));
        }
        if (node.getNumberOfProperties() > 0) {
            if (sb.length() > 1) {
                sb.append(' ');
            }
            sb.append(renderProperties(node));
        }
        return sb.append(')').toString();
    }

    private static String renderEdge(Edge edge) {
        StringBuilder sb = new StringBuilder("(")
                .append(edge.getSource())
                .append(")-[:")
                .append(escapeIdentifier(edge.getRelationshipType()));
        if (edge.getNumberOfProperties() > 0) {
            sb.append(' ').append(renderProperties(edge));
        }
        return sb.append("]->(").append(edge.getDestination()).append(')').toString();
    }

    private static String renderPath(Path path) {
        StringBuilder sb = new StringBuilder();
        List<Node> nodes = path.getNodes();
        List<Edge> edges = path.getEdges();
        for (int i = 0; i < nodes.size(); i++) {
            sb.append(renderNode(nodes.get(i)));
            if (i < edges.size()) {
                Edge edge = edges.get(i);
                // Render the arrow in traversal order rather than storage order, so a path walked
                // against the relationship's direction still reads left to right.
                boolean forward = edge.getSource() == nodes.get(i).getId();
                sb.append(forward ? "-[:" : "<-[:").append(escapeIdentifier(edge.getRelationshipType()));
                if (edge.getNumberOfProperties() > 0) {
                    sb.append(' ').append(renderProperties(edge));
                }
                sb.append(forward ? "]->" : "]-");
            }
        }
        return sb.toString();
    }

    private static String renderProperties(GraphEntity entity) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        for (String name : sorted(entity.getEntityPropertyNames())) {
            Property<?> property = entity.getProperty(name);
            joiner.add(escapeIdentifier(name) + ": " + renderNested(property == null ? null : property.getValue()));
        }
        return joiner.toString();
    }

    private static String renderMap(Map<?, ?> map) {
        StringJoiner joiner = new StringJoiner(", ", "{", "}");
        // Sorted so the rendering of a HashMap-backed Cypher map is stable across calls and JVMs.
        new TreeMap<String, Object>(toStringKeyed(map))
                .forEach((key, mapped) -> joiner.add(escapeIdentifier(key) + ": " + renderNested(mapped)));
        return joiner.toString();
    }

    private static Map<String, Object> toStringKeyed(Map<?, ?> map) {
        Map<String, Object> result = new TreeMap<>();
        map.forEach((key, mapped) -> result.put(String.valueOf(key), mapped));
        return result;
    }

    private static List<String> sorted(Collection<String> names) {
        List<String> result = new ArrayList<>(names);
        result.sort(String::compareTo);
        return result;
    }

    private static String escapeIdentifier(String name) {
        if (name == null) {
            return "``";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean legal = Character.isLetterOrDigit(c) || c == '_';
            if (!legal || (i == 0 && Character.isDigit(c))) {
                return '`' + name.replace("`", "``") + '`';
            }
        }
        return name.isEmpty() ? "``" : name;
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    private static List<Object> boxArray(Object array) {
        int length = java.lang.reflect.Array.getLength(array);
        List<Object> boxed = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            boxed.add(java.lang.reflect.Array.get(array, i));
        }
        return boxed;
    }

    // ---------------------------------------------------------------- scalar conversions

    /**
     * Converts a value to a {@code boolean}, following the usual JDBC rules: a null is {@code false},
     * a number is true when non-zero, and a string is matched case-insensitively against the common
     * spellings.
     *
     * @param value the value to convert
     * @return the boolean value
     * @throws SQLException if the value has no boolean interpretation
     */
    public static boolean asBoolean(Object value) throws SQLException {
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        if (value instanceof String s) {
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "true", "t", "yes", "y", "on", "1" -> true;
                case "false", "f", "no", "n", "off", "0", "" -> false;
                default -> throw SQLErrors.cannotConvert(value, "boolean");
            };
        }
        throw SQLErrors.cannotConvert(value, "boolean");
    }

    /**
     * Converts a value to an integral type, refusing a conversion that would not round-trip.
     *
     * @param value the value to convert
     * @param min the lowest value the target type can hold
     * @param max the highest value the target type can hold
     * @param target the target type name, used in error messages
     * @return the integral value, {@code 0} for a null value
     * @throws SQLException if the value is not numeric, or does not fit the target type
     */
    public static long asLong(Object value, long min, long max, String target) throws SQLException {
        if (value == null) {
            return 0L;
        }
        long result;
        if (value instanceof Long l) {
            result = l;
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            result = ((Number) value).longValue();
        } else if (value instanceof Boolean b) {
            result = b ? 1L : 0L;
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
                throw SQLErrors.cannotConvert(value, target);
            }
            result = (long) d;
        } else if (value instanceof BigInteger bi) {
            try {
                result = bi.longValueExact();
            } catch (ArithmeticException e) {
                throw SQLErrors.cannotConvert(value, target);
            }
        } else if (value instanceof BigDecimal bd) {
            try {
                result = bd.longValueExact();
            } catch (ArithmeticException e) {
                throw SQLErrors.cannotConvert(value, target);
            }
        } else if (value instanceof String s) {
            result = parseLong(s.trim(), value, target);
        } else {
            throw SQLErrors.cannotConvert(value, target);
        }
        if (result < min || result > max) {
            throw new SQLException(
                    "Value " + result + " is out of range for " + target, SQLErrors.STATE_DATA_CONVERSION);
        }
        return result;
    }

    private static long parseLong(String text, Object original, String target) throws SQLException {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            try {
                return new BigDecimal(text).longValueExact();
            } catch (ArithmeticException | NumberFormatException nested) {
                throw SQLErrors.cannotConvert(original, target);
            }
        }
    }

    /**
     * Converts a value to a {@code double}.
     *
     * @param value the value to convert
     * @return the double value, {@code 0} for a null value
     * @throws SQLException if the value is not numeric
     */
    public static double asDouble(Object value) throws SQLException {
        if (value == null) {
            return 0d;
        }
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1d : 0d;
        }
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw SQLErrors.cannotConvert(value, "double");
            }
        }
        throw SQLErrors.cannotConvert(value, "double");
    }

    /**
     * Converts a value to a {@link BigDecimal}.
     *
     * @param value the value to convert
     * @return the decimal value, or {@code null} for a null value
     * @throws SQLException if the value is not numeric
     */
    public static BigDecimal asBigDecimal(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Double || value instanceof Float) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        if (value instanceof Boolean b) {
            return b ? BigDecimal.ONE : BigDecimal.ZERO;
        }
        if (value instanceof String s) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                throw SQLErrors.cannotConvert(value, "java.math.BigDecimal");
            }
        }
        throw SQLErrors.cannotConvert(value, "java.math.BigDecimal");
    }

    /**
     * Converts a value to a byte array, decoding a string as UTF-8.
     *
     * <p>FalkorDB has no binary type, so {@link
     * com.falkordb.jdbc.FalkorDBPreparedStatement#setBytes(int, byte[])} stores a byte array as a
     * Cypher list of integers. A list of integers in byte range is therefore decoded back to bytes
     * here, so that a {@code setBytes}/{@code getBytes} round trip returns what was written.
     *
     * @param value the value to convert
     * @return the bytes, or {@code null} for a null value
     * @throws SQLException if the value has no byte representation
     */
    public static byte[] asBytes(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof String s) {
            return s.getBytes(StandardCharsets.UTF_8);
        }
        if (value instanceof List<?> list) {
            byte[] bytes = new byte[list.size()];
            for (int i = 0; i < bytes.length; i++) {
                Object element = list.get(i);
                if (!(element instanceof Number number) || element instanceof Double || element instanceof Float) {
                    throw SQLErrors.cannotConvert(value, "byte[]");
                }
                long asLong = number.longValue();
                if (asLong < Byte.MIN_VALUE || asLong > 255) {
                    throw SQLErrors.cannotConvert(value, "byte[]");
                }
                bytes[i] = (byte) asLong;
            }
            return bytes;
        }
        throw SQLErrors.cannotConvert(value, "byte[]");
    }

    // ---------------------------------------------------------------- temporal conversions

    /**
     * Converts a value to a {@link LocalDate}, accepting FalkorDB's own date and datetime types, an
     * ISO-8601 string, and an epoch-second integer.
     *
     * @param value the value to convert
     * @return the date, or {@code null} for a null value
     * @throws SQLException if the value is not date-like
     */
    public static LocalDate asLocalDate(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate d) {
            return d;
        }
        if (value instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (value instanceof java.util.Date d) {
            return Instant.ofEpochMilli(d.getTime()).atZone(ZoneOffset.UTC).toLocalDate();
        }
        if (value instanceof Number n) {
            return epochSecondsUtc(n.longValue()).toLocalDate();
        }
        if (value instanceof String s) {
            try {
                return LocalDate.parse(s.trim());
            } catch (DateTimeParseException e) {
                return asLocalDateTime(s).toLocalDate();
            }
        }
        throw SQLErrors.cannotConvert(value, "java.time.LocalDate");
    }

    /**
     * Converts a value to a {@link LocalTime}.
     *
     * @param value the value to convert
     * @return the time, or {@code null} for a null value
     * @throws SQLException if the value is not time-like
     */
    public static LocalTime asLocalTime(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalTime t) {
            return t;
        }
        if (value instanceof LocalDateTime dt) {
            return dt.toLocalTime();
        }
        if (value instanceof java.sql.Time t) {
            return t.toLocalTime();
        }
        if (value instanceof java.util.Date d) {
            return Instant.ofEpochMilli(d.getTime()).atZone(ZoneOffset.UTC).toLocalTime();
        }
        if (value instanceof Number n) {
            return epochSecondsUtc(n.longValue()).toLocalTime();
        }
        if (value instanceof String s) {
            try {
                return LocalTime.parse(s.trim());
            } catch (DateTimeParseException e) {
                return asLocalDateTime(s).toLocalTime();
            }
        }
        throw SQLErrors.cannotConvert(value, "java.time.LocalTime");
    }

    /**
     * Converts a value to a {@link LocalDateTime}.
     *
     * @param value the value to convert
     * @return the timestamp, or {@code null} for a null value
     * @throws SQLException if the value is not timestamp-like
     */
    public static LocalDateTime asLocalDateTime(Object value) throws SQLException {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dt) {
            return dt;
        }
        if (value instanceof LocalDate d) {
            return d.atStartOfDay();
        }
        if (value instanceof LocalTime t) {
            return t.atDate(LocalDate.EPOCH);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        }
        if (value instanceof java.util.Date d) {
            return Instant.ofEpochMilli(d.getTime()).atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toLocalDateTime();
        }
        if (value instanceof Number n) {
            return epochSecondsUtc(n.longValue());
        }
        if (value instanceof String s) {
            String text = s.trim();
            try {
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException e) {
                try {
                    return OffsetDateTime.parse(text).toLocalDateTime();
                } catch (DateTimeParseException nested) {
                    try {
                        return LocalDate.parse(text).atStartOfDay();
                    } catch (DateTimeParseException alsoNested) {
                        throw SQLErrors.cannotConvert(value, "java.time.LocalDateTime");
                    }
                }
            }
        }
        throw SQLErrors.cannotConvert(value, "java.time.LocalDateTime");
    }

    private static LocalDateTime epochSecondsUtc(long seconds) {
        // FalkorDB stores temporal values as UTC epoch seconds, which is what JFalkorDB decodes.
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- generic conversion

    /**
     * Converts a value to an arbitrary requested type, backing {@link
     * java.sql.ResultSet#getObject(int, Class)}.
     *
     * @param value the value to convert
     * @param type the requested type
     * @param zone the time zone used when a local temporal has to be given an instant
     * @param <T> the requested type
     * @return the converted value, or {@code null} for a null value
     * @throws SQLException if {@code type} is {@code null} or the conversion is not defined
     */
    public static <T> T as(Object value, Class<T> type, ZoneId zone) throws SQLException {
        if (type == null) {
            throw new SQLException("Target type must not be null", SQLErrors.STATE_INVALID_PARAMETER);
        }
        if (value == null) {
            return null;
        }
        if (type == Object.class) {
            return type.cast(value);
        }
        if (type.isInstance(value) && type != Object.class) {
            return type.cast(value);
        }
        Object converted = convert(value, type, zone);
        if (converted == null) {
            throw SQLErrors.cannotConvert(value, type.getName());
        }
        return type.cast(converted);
    }

    private static Object convert(Object value, Class<?> type, ZoneId zone) throws SQLException {
        if (type == String.class) {
            return render(value);
        }
        if (type == Boolean.class || type == boolean.class) {
            return asBoolean(value);
        }
        if (type == Byte.class || type == byte.class) {
            return (byte) asLong(value, Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
        }
        if (type == Short.class || type == short.class) {
            return (short) asLong(value, Short.MIN_VALUE, Short.MAX_VALUE, "short");
        }
        if (type == Integer.class || type == int.class) {
            return (int) asLong(value, Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
        }
        if (type == Long.class || type == long.class) {
            return asLong(value, Long.MIN_VALUE, Long.MAX_VALUE, "long");
        }
        if (type == Float.class || type == float.class) {
            return (float) asDouble(value);
        }
        if (type == Double.class || type == double.class) {
            return asDouble(value);
        }
        if (type == BigDecimal.class) {
            return asBigDecimal(value);
        }
        if (type == BigInteger.class) {
            return BigInteger.valueOf(asLong(value, Long.MIN_VALUE, Long.MAX_VALUE, "java.math.BigInteger"));
        }
        if (type == byte[].class) {
            return asBytes(value);
        }
        if (type == float[].class) {
            return asFloatArray(value);
        }
        if (type == double[].class) {
            return asDoubleArray(value);
        }
        if (type == LocalDate.class) {
            return asLocalDate(value);
        }
        if (type == LocalTime.class) {
            return asLocalTime(value);
        }
        if (type == LocalDateTime.class) {
            return asLocalDateTime(value);
        }
        if (type == java.sql.Date.class) {
            return java.sql.Date.valueOf(asLocalDate(value));
        }
        if (type == java.sql.Time.class) {
            return java.sql.Time.valueOf(asLocalTime(value));
        }
        if (type == java.sql.Timestamp.class) {
            return java.sql.Timestamp.valueOf(asLocalDateTime(value));
        }
        if (type == Instant.class) {
            return asLocalDateTime(value).atZone(zone).toInstant();
        }
        if (type == OffsetDateTime.class) {
            return asLocalDateTime(value).atZone(zone).toOffsetDateTime();
        }
        if (type == Duration.class) {
            return asDuration(value);
        }
        if (type == List.class || type == Collection.class) {
            return value instanceof Collection<?> collection
                    ? new ArrayList<>(collection)
                    : (value.getClass().isArray() ? boxArray(value) : null);
        }
        if (type == Map.class) {
            return value instanceof Map<?, ?> map ? toStringKeyed(map) : null;
        }
        return null;
    }

    /**
     * Unpacks a {@code vecf32} vector, or any list of numbers, into a primitive float array. This is
     * how a vector column is most naturally consumed, so {@code getObject(i, float[].class)} is the
     * driver's idiomatic accessor for one.
     *
     * @param value the value to convert
     * @return the components, or {@code null} if the value is not a list of numbers
     */
    private static float[] asFloatArray(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        float[] components = new float[list.size()];
        for (int i = 0; i < components.length; i++) {
            if (!(list.get(i) instanceof Number number)) {
                return null;
            }
            components[i] = number.floatValue();
        }
        return components;
    }

    private static double[] asDoubleArray(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        double[] components = new double[list.size()];
        for (int i = 0; i < components.length; i++) {
            if (!(list.get(i) instanceof Number number)) {
                return null;
            }
            components[i] = number.doubleValue();
        }
        return components;
    }

    private static Duration asDuration(Object value) throws SQLException {
        if (value instanceof Duration d) {
            return d;
        }
        if (value instanceof Number n) {
            return Duration.ofSeconds(n.longValue());
        }
        if (value instanceof String s) {
            try {
                return Duration.parse(s.trim());
            } catch (java.time.format.DateTimeParseException e) {
                throw SQLErrors.cannotConvert(value, "java.time.Duration");
            }
        }
        throw SQLErrors.cannotConvert(value, "java.time.Duration");
    }
}
