package com.falkordb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.sql.Date;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import com.falkordb.jdbc.internal.CypherQuery;
import com.falkordb.jdbc.internal.SQLErrors;

/**
 * A pre-translated Cypher statement with bindable parameters.
 *
 * <p>JDBC positional {@code ?} placeholders are rewritten once, at prepare time, into FalkorDB named
 * parameters {@code $p1}, {@code $p2}, … — the <em>n</em>th {@code ?} becomes {@code $p<n>}, so the
 * JDBC index and the Cypher name always agree. Bound values travel to the server in JFalkorDB's
 * parameter map and are never spliced into the query text, so a parameterised statement cannot be
 * used to inject Cypher.
 *
 * <pre>{@code
 * try (PreparedStatement ps = connection.prepareStatement(
 *          "MATCH (p:Person) WHERE p.age > ? AND p.city = ? RETURN p.name AS name")) {
 *     ps.setInt(1, 30);
 *     ps.setString(2, "Tel Aviv");
 *     try (ResultSet rs = ps.executeQuery()) { ... }
 * }
 * }</pre>
 *
 * <p>A statement written with Cypher's own {@code $name} parameters is passed through untouched;
 * bind those with {@link #setNamedObject(String, Object)}, reachable from a plain {@code
 * PreparedStatement} through {@link #unwrap(Class)}.
 */
public final class FalkorDBPreparedStatement extends FalkorDBStatement implements PreparedStatement {

    private final CypherQuery query;
    private final Map<String, Object> parameters = new LinkedHashMap<>();

    FalkorDBPreparedStatement(FalkorDBConnection connection, CypherQuery query) {
        super(connection);
        this.query = query;
    }

    // ---------------------------------------------------------------- execution

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkOpen();
        run(query, bindings());
        ResultSet result = getResultSet();
        if (result == null) {
            throw new SQLException(
                    "The statement returned no columns, so it has no result set; use executeUpdate or execute",
                    SQLErrors.STATE_GENERAL);
        }
        return result;
    }

    @Override
    public int executeUpdate() throws SQLException {
        long count = executeLargeUpdate();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Override
    public long executeLargeUpdate() throws SQLException {
        checkOpen();
        run(query, bindings());
        if (getResultSet() != null) {
            throw new SQLException(
                    "The statement returned a result set; use executeQuery or execute", SQLErrors.STATE_GENERAL);
        }
        return getLargeUpdateCount();
    }

    @Override
    public boolean execute() throws SQLException {
        checkOpen();
        run(query, bindings());
        return getResultSet() != null;
    }

    /**
     * Collects the bindings for this execution, refusing to run with a placeholder left unset rather
     * than letting the server report a confusing "missing parameter" error.
     */
    private Map<String, Object> bindings() throws SQLException {
        List<Integer> missing = new ArrayList<>();
        for (int i = 1; i <= query.parameterCount(); i++) {
            if (!parameters.containsKey(query.nameOf(i))) {
                missing.add(i);
            }
        }
        if (!missing.isEmpty()) {
            throw new SQLException(
                    "No value has been set for parameter" + (missing.size() > 1 ? "s " : " ") + missing,
                    SQLErrors.STATE_INVALID_PARAMETER);
        }
        return parameters;
    }

    // ---------------------------------------------------------------- parameter binding

    /**
     * Binds a value to a Cypher parameter by name, for statements written with {@code $name}
     * parameters rather than {@code ?} placeholders.
     *
     * <p>This is an extension to JDBC, which has no notion of named parameters. Reach it from a
     * plain {@code PreparedStatement} handle with {@code
     * statement.unwrap(FalkorDBPreparedStatement.class)}.
     *
     * @param name the parameter name, without the leading {@code $}
     * @param value the value to bind, converted as described in {@link #setObject(int, Object)}
     * @throws SQLException if the statement is closed, {@code name} is not a legal Cypher parameter
     *     name, or the value has no FalkorDB representation
     */
    public void setNamedObject(String name, Object value) throws SQLException {
        checkOpen();
        if (name == null || !name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new SQLException(
                    "Invalid Cypher parameter name: " + name + " (must match [A-Za-z_][A-Za-z0-9_]*)",
                    SQLErrors.STATE_INVALID_PARAMETER);
        }
        parameters.put(name, encode(value));
    }

    /**
     * Binds a value to a one-based JDBC parameter index.
     *
     * <p>Values are converted to the types FalkorDB can represent: numbers and booleans pass through,
     * {@code BigDecimal} is narrowed to {@code double} because FalkorDB stores every decimal as a
     * 64-bit float, temporal values are encoded as ISO-8601 strings, and collections, arrays and maps
     * are converted element by element.
     *
     * @param parameterIndex the one-based parameter index
     * @param x the value to bind
     * @throws SQLException if the index is out of range or the value has no FalkorDB representation
     */
    @Override
    public void setObject(int parameterIndex, Object x) throws SQLException {
        checkOpen();
        parameters.put(query.nameOf(parameterIndex), encode(x));
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setObject(int parameterIndex, Object x, int targetSqlType, int scaleOrLength) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType) throws SQLException {
        setObject(parameterIndex, null);
    }

    @Override
    public void setNull(int parameterIndex, int sqlType, String typeName) throws SQLException {
        setObject(parameterIndex, null);
    }

    @Override
    public void setBoolean(int parameterIndex, boolean x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setByte(int parameterIndex, byte x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setShort(int parameterIndex, short x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setInt(int parameterIndex, int x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setLong(int parameterIndex, long x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setFloat(int parameterIndex, float x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setDouble(int parameterIndex, double x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setBigDecimal(int parameterIndex, BigDecimal x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setString(int parameterIndex, String x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setNString(int parameterIndex, String value) throws SQLException {
        setObject(parameterIndex, value);
    }

    @Override
    public void setBytes(int parameterIndex, byte[] x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, Date x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setDate(int parameterIndex, Date x, Calendar cal) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setTime(int parameterIndex, Time x, Calendar cal) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setTimestamp(int parameterIndex, Timestamp x, Calendar cal) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setArray(int parameterIndex, java.sql.Array x) throws SQLException {
        setObject(parameterIndex, x);
    }

    @Override
    public void setURL(int parameterIndex, URL x) throws SQLException {
        setObject(parameterIndex, x == null ? null : x.toString());
    }

    @Override
    public void clearParameters() throws SQLException {
        checkOpen();
        parameters.clear();
    }

    /**
     * Converts a JDBC value into one of the types JFalkorDB can encode as a Cypher literal.
     *
     * <p>FalkorDB's value model has no decimal, binary or temporal parameter types, so those are
     * mapped onto the nearest thing it does have, and anything with no representation at all is
     * rejected rather than silently stringified.
     */
    private Object encode(Object value) throws SQLException {
        if (value == null
                || value instanceof String
                || value instanceof Boolean
                || value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long
                || value instanceof Float
                || value instanceof Double
                || value instanceof BigInteger
                || value instanceof Character) {
            return value;
        }
        // FalkorDB stores every decimal as a 64-bit double, so that is the only faithful target.
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof Time time) {
            return time.toLocalTime().toString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.util.Date date) {
            return Instant.ofEpochMilli(date.getTime())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDateTime()
                    .toString();
        }
        if (value instanceof LocalDate
                || value instanceof LocalTime
                || value instanceof LocalDateTime
                || value instanceof OffsetDateTime
                || value instanceof OffsetTime
                || value instanceof ZonedDateTime
                || value instanceof Instant
                || value instanceof Duration) {
            return value.toString();
        }
        if (value instanceof java.sql.Array array) {
            Object raw = array.getArray();
            return encode(raw);
        }
        if (value instanceof Collection<?> collection) {
            List<Object> encoded = new ArrayList<>(collection.size());
            for (Object element : collection) {
                encoded.add(encode(element));
            }
            return encoded;
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> encoded = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                encoded.add(encode(java.lang.reflect.Array.get(value, i)));
            }
            return encoded;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new SQLException(
                            "Cypher map parameter keys must be Strings, but got "
                                    + (entry.getKey() == null
                                            ? "null"
                                            : entry.getKey().getClass().getName()),
                            SQLErrors.STATE_INVALID_PARAMETER);
                }
                encoded.put(key, encode(entry.getValue()));
            }
            return encoded;
        }
        throw new SQLException(
                "Values of type " + value.getClass().getName()
                        + " cannot be sent to FalkorDB as a parameter; convert it to a String, number, boolean,"
                        + " list or map first",
                SQLErrors.STATE_INVALID_PARAMETER);
    }

    // ---------------------------------------------------------------- metadata

    @Override
    public ParameterMetaData getParameterMetaData() throws SQLException {
        checkOpen();
        return new FalkorDBParameterMetaData(query.parameterCount());
    }

    /**
     * The shape of this statement's result, if it is already known.
     *
     * <p>Cypher result columns depend on the data — FalkorDB reports no column types until a query
     * has run — so this returns {@code null} before the first execution, as JDBC permits, and the
     * previous execution's metadata afterwards.
     *
     * @return the result metadata, or {@code null} if the statement has not been executed
     * @throws SQLException if the statement is closed
     */
    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        ResultSet result = getResultSet();
        return result == null ? null : result.getMetaData();
    }

    /**
     * The Cypher that will be sent to FalkorDB, after {@code ?} placeholders have been rewritten.
     *
     * @return the translated statement
     */
    public String nativeCypher() {
        return query.cypher();
    }

    /**
     * The Cypher parameter names this statement binds to, in JDBC ordinal order, plus any names the
     * caller wrote themselves.
     *
     * @return the parameter names, sorted, without the leading {@code $}
     */
    public java.util.Set<String> parameterNames() {
        java.util.Set<String> names = new TreeSet<>(query.parameterNames());
        names.addAll(query.namedParameters());
        return names;
    }

    @Override
    public String toString() {
        return "FalkorDBPreparedStatement[" + query.cypher() + "]";
    }

    // ---------------------------------------------------------------- unsupported

    @Override
    public void addBatch() throws SQLException {
        throw SQLErrors.unsupported("Batch execution");
    }

    @Override
    public void setRef(int parameterIndex, java.sql.Ref x) throws SQLException {
        throw SQLErrors.unsupported("setRef(int, Ref)");
    }

    @Override
    public void setBlob(int parameterIndex, java.sql.Blob x) throws SQLException {
        throw SQLErrors.unsupported("setBlob(int, Blob)");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream) throws SQLException {
        throw SQLErrors.unsupported("setBlob(int, InputStream)");
    }

    @Override
    public void setBlob(int parameterIndex, InputStream inputStream, long length) throws SQLException {
        throw SQLErrors.unsupported("setBlob(int, InputStream, long)");
    }

    @Override
    public void setClob(int parameterIndex, java.sql.Clob x) throws SQLException {
        throw SQLErrors.unsupported("setClob(int, Clob)");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader) throws SQLException {
        throw SQLErrors.unsupported("setClob(int, Reader)");
    }

    @Override
    public void setClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw SQLErrors.unsupported("setClob(int, Reader, long)");
    }

    @Override
    public void setNClob(int parameterIndex, java.sql.NClob value) throws SQLException {
        throw SQLErrors.unsupported("setNClob(int, NClob)");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader) throws SQLException {
        throw SQLErrors.unsupported("setNClob(int, Reader)");
    }

    @Override
    public void setNClob(int parameterIndex, Reader reader, long length) throws SQLException {
        throw SQLErrors.unsupported("setNClob(int, Reader, long)");
    }

    @Override
    public void setRowId(int parameterIndex, java.sql.RowId x) throws SQLException {
        throw SQLErrors.unsupported("setRowId(int, RowId)");
    }

    @Override
    public void setSQLXML(int parameterIndex, java.sql.SQLXML xmlObject) throws SQLException {
        throw SQLErrors.unsupported("setSQLXML(int, SQLXML)");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x) throws SQLException {
        throw SQLErrors.unsupported("setAsciiStream(int, InputStream)");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw SQLErrors.unsupported("setAsciiStream(int, InputStream, int)");
    }

    @Override
    public void setAsciiStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw SQLErrors.unsupported("setAsciiStream(int, InputStream, long)");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x) throws SQLException {
        throw SQLErrors.unsupported("setBinaryStream(int, InputStream)");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw SQLErrors.unsupported("setBinaryStream(int, InputStream, int)");
    }

    @Override
    public void setBinaryStream(int parameterIndex, InputStream x, long length) throws SQLException {
        throw SQLErrors.unsupported("setBinaryStream(int, InputStream, long)");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader) throws SQLException {
        throw SQLErrors.unsupported("setCharacterStream(int, Reader)");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, int length) throws SQLException {
        throw SQLErrors.unsupported("setCharacterStream(int, Reader, int)");
    }

    @Override
    public void setCharacterStream(int parameterIndex, Reader reader, long length) throws SQLException {
        throw SQLErrors.unsupported("setCharacterStream(int, Reader, long)");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value) throws SQLException {
        throw SQLErrors.unsupported("setNCharacterStream(int, Reader)");
    }

    @Override
    public void setNCharacterStream(int parameterIndex, Reader value, long length) throws SQLException {
        throw SQLErrors.unsupported("setNCharacterStream(int, Reader, long)");
    }

    @Override
    @Deprecated
    public void setUnicodeStream(int parameterIndex, InputStream x, int length) throws SQLException {
        throw SQLErrors.unsupported("setUnicodeStream(int, InputStream, int)");
    }

    // The String-taking Statement methods are illegal on a PreparedStatement (JDBC 4.3, 13.1.2).

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        throw preparedOnly();
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        throw preparedOnly();
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        throw preparedOnly();
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        throw preparedOnly();
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw preparedOnly();
    }

    private static SQLException preparedOnly() {
        return new SQLException(
                "A PreparedStatement carries its own statement text; use the no-argument execute methods",
                SQLErrors.STATE_GENERAL);
    }
}
