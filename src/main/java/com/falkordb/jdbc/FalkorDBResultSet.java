package com.falkordb.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.falkordb.Header;
import com.falkordb.Record;
import com.falkordb.jdbc.internal.ColumnMeta;
import com.falkordb.jdbc.internal.FalkorType;
import com.falkordb.jdbc.internal.GraphValues;
import com.falkordb.jdbc.internal.SQLErrors;

/**
 * A forward-only, read-only {@link ResultSet} over a FalkorDB query response.
 *
 * <p>JFalkorDB materialises a whole response before returning it, so this result set is a cursor
 * over an in-memory list rather than a streaming one; {@link #setFetchSize(int)} is accepted and
 * ignored for that reason.
 *
 * <p>Columns are addressed one-based, as JDBC requires. Values are converted on read according to
 * {@link GraphValues}; a conversion that cannot be performed without changing the value raises a
 * {@link SQLException} rather than silently returning a default.
 */
public final class FalkorDBResultSet extends FalkorDBWrapper implements ResultSet {

    private final List<ColumnMeta> columns;
    private final List<List<Object>> rows;
    private final FalkorDBStatement statement;
    private final Map<String, Integer> labelIndex;
    private final ZoneId zone = ZoneId.systemDefault();

    private int cursor = -1;
    private boolean wasNull;
    private boolean closed;
    private int fetchSize;
    private SQLWarning warnings;

    FalkorDBResultSet(List<ColumnMeta> columns, List<List<Object>> rows, FalkorDBStatement statement) {
        this.columns = List.copyOf(columns);
        this.rows = rows;
        this.statement = statement;
        this.labelIndex = new HashMap<>(columns.size() * 2);
        for (int i = 0; i < this.columns.size(); i++) {
            // First occurrence wins, matching ResultSet.findColumn's contract for duplicate labels.
            labelIndex.putIfAbsent(this.columns.get(i).label().toLowerCase(Locale.ROOT), i + 1);
        }
    }

    /**
     * Adapts a JFalkorDB response, inferring each column's type from the values it holds.
     *
     * @param source the response returned by JFalkorDB
     * @param statement the statement that produced it, for {@link #getStatement()}
     * @return the JDBC result set
     */
    static FalkorDBResultSet of(com.falkordb.ResultSet source, FalkorDBStatement statement) {
        Header header = source.getHeader();
        List<String> names = header.getSchemaNames();
        List<List<Object>> rows = new ArrayList<>(source.size());
        for (Record record : source) {
            rows.add(record.values());
        }
        List<ColumnMeta> columns = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            columns.add(columnOf(header, names, rows, i));
        }
        return new FalkorDBResultSet(columns, rows, statement);
    }

    /**
     * Resolves one column's type. The response header already identifies node and relationship
     * columns definitively, so those are trusted even when the column happens to be empty; everything
     * else is inferred from the data.
     */
    private static ColumnMeta columnOf(Header header, List<String> names, List<List<Object>> rows, int index) {
        List<Header.ResultSetColumnTypes> types = header.getSchemaTypes();
        Header.ResultSetColumnTypes declared = index < types.size() ? types.get(index) : null;
        String label = names.get(index);
        if (declared == Header.ResultSetColumnTypes.COLUMN_NODE) {
            return ColumnMeta.of(label, FalkorType.NODE);
        }
        if (declared == Header.ResultSetColumnTypes.COLUMN_RELATION) {
            return ColumnMeta.of(label, FalkorType.RELATIONSHIP);
        }
        return ColumnMeta.infer(label, rows, index);
    }

    // ---------------------------------------------------------------- cursor

    @Override
    public boolean next() throws SQLException {
        checkOpen();
        if (cursor >= rows.size()) {
            return false;
        }
        cursor++;
        return cursor < rows.size();
    }

    /**
     * {@inheritDoc}
     *
     * <p>If the creating statement asked to be closed on completion, closing its last open result set
     * closes the statement too.
     */
    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        closed = true;
        if (statement != null) {
            statement.resultSetClosed(this);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public boolean wasNull() throws SQLException {
        checkOpen();
        return wasNull;
    }

    @Override
    public boolean isBeforeFirst() throws SQLException {
        checkOpen();
        return cursor < 0 && !rows.isEmpty();
    }

    @Override
    public boolean isAfterLast() throws SQLException {
        checkOpen();
        return cursor >= rows.size() && !rows.isEmpty();
    }

    @Override
    public boolean isFirst() throws SQLException {
        checkOpen();
        return cursor == 0 && !rows.isEmpty();
    }

    @Override
    public boolean isLast() throws SQLException {
        checkOpen();
        return !rows.isEmpty() && cursor == rows.size() - 1;
    }

    @Override
    public int getRow() throws SQLException {
        checkOpen();
        return cursor >= 0 && cursor < rows.size() ? cursor + 1 : 0;
    }

    @Override
    public void beforeFirst() throws SQLException {
        throw forwardOnly("beforeFirst()");
    }

    @Override
    public void afterLast() throws SQLException {
        throw forwardOnly("afterLast()");
    }

    @Override
    public boolean first() throws SQLException {
        throw forwardOnly("first()");
    }

    @Override
    public boolean last() throws SQLException {
        throw forwardOnly("last()");
    }

    @Override
    public boolean absolute(int row) throws SQLException {
        throw forwardOnly("absolute(int)");
    }

    @Override
    public boolean relative(int rows) throws SQLException {
        throw forwardOnly("relative(int)");
    }

    @Override
    public boolean previous() throws SQLException {
        throw forwardOnly("previous()");
    }

    // ---------------------------------------------------------------- value access

    @Override
    public int findColumn(String columnLabel) throws SQLException {
        checkOpen();
        Integer index = columnLabel == null ? null : labelIndex.get(columnLabel.toLowerCase(Locale.ROOT));
        if (index == null) {
            throw new SQLException(
                    "No column named \"" + columnLabel + "\"; available columns are " + labels(),
                    SQLErrors.STATE_INVALID_COLUMN);
        }
        return index;
    }

    private List<String> labels() {
        return columns.stream().map(ColumnMeta::label).toList();
    }

    /**
     * Reads the raw value at a one-based column index and records whether it was SQL NULL, which is
     * what a subsequent {@link #wasNull()} reports.
     */
    private Object raw(int columnIndex) throws SQLException {
        checkOpen();
        if (cursor < 0) {
            throw new SQLException(
                    "ResultSet is positioned before the first row; call next() first",
                    SQLErrors.STATE_INVALID_CURSOR_STATE);
        }
        if (cursor >= rows.size()) {
            throw new SQLException("ResultSet is positioned after the last row", SQLErrors.STATE_INVALID_CURSOR_STATE);
        }
        if (columnIndex < 1 || columnIndex > columns.size()) {
            throw new SQLException(
                    "Column index " + columnIndex + " is out of range; this result set has " + columns.size()
                            + " column(s)",
                    SQLErrors.STATE_INVALID_COLUMN);
        }
        List<Object> row = rows.get(cursor);
        Object value = columnIndex <= row.size() ? row.get(columnIndex - 1) : null;
        wasNull = value == null;
        return value;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Returns the default Java type for the column's SQL type, so a Cypher list or vector - whose
     * SQL type is {@link java.sql.Types#ARRAY} - arrives as a {@link java.sql.Array} rather than the
     * client's raw {@link List}, and agrees with {@link #getArray(int)} for the same column. Maps and
     * graph entities have no JDBC counterpart and are returned as they come from the client.
     */
    @Override
    public Object getObject(int columnIndex) throws SQLException {
        Object value = raw(columnIndex);
        if (value instanceof Collection<?> collection) {
            return new FalkorDBArray(collection);
        }
        return value;
    }

    @Override
    public Object getObject(String columnLabel) throws SQLException {
        return getObject(findColumn(columnLabel));
    }

    @Override
    public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
        // GraphValues has no java.sql.Array conversion, so ask for one explicitly here; otherwise a
        // legitimate getObject(i, Array.class) would fail where getObject(i) and getArray(i) succeed.
        if (type == java.sql.Array.class) {
            return type.cast(getArray(columnIndex));
        }
        return GraphValues.as(raw(columnIndex), type, zone);
    }

    @Override
    public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
        return getObject(findColumn(columnLabel), type);
    }

    @Override
    public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
        if (map != null && !map.isEmpty()) {
            throw SQLErrors.unsupported("getObject(int, Map)");
        }
        return getObject(columnIndex);
    }

    @Override
    public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
        return getObject(findColumn(columnLabel), map);
    }

    @Override
    public String getString(int columnIndex) throws SQLException {
        return GraphValues.render(raw(columnIndex));
    }

    @Override
    public String getString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    @Override
    public boolean getBoolean(int columnIndex) throws SQLException {
        return GraphValues.asBoolean(raw(columnIndex));
    }

    @Override
    public boolean getBoolean(String columnLabel) throws SQLException {
        return getBoolean(findColumn(columnLabel));
    }

    @Override
    public byte getByte(int columnIndex) throws SQLException {
        return (byte) GraphValues.asLong(raw(columnIndex), Byte.MIN_VALUE, Byte.MAX_VALUE, "byte");
    }

    @Override
    public byte getByte(String columnLabel) throws SQLException {
        return getByte(findColumn(columnLabel));
    }

    @Override
    public short getShort(int columnIndex) throws SQLException {
        return (short) GraphValues.asLong(raw(columnIndex), Short.MIN_VALUE, Short.MAX_VALUE, "short");
    }

    @Override
    public short getShort(String columnLabel) throws SQLException {
        return getShort(findColumn(columnLabel));
    }

    @Override
    public int getInt(int columnIndex) throws SQLException {
        return (int) GraphValues.asLong(raw(columnIndex), Integer.MIN_VALUE, Integer.MAX_VALUE, "int");
    }

    @Override
    public int getInt(String columnLabel) throws SQLException {
        return getInt(findColumn(columnLabel));
    }

    @Override
    public long getLong(int columnIndex) throws SQLException {
        return GraphValues.asLong(raw(columnIndex), Long.MIN_VALUE, Long.MAX_VALUE, "long");
    }

    @Override
    public long getLong(String columnLabel) throws SQLException {
        return getLong(findColumn(columnLabel));
    }

    @Override
    public float getFloat(int columnIndex) throws SQLException {
        return GraphValues.asFloat(raw(columnIndex));
    }

    @Override
    public float getFloat(String columnLabel) throws SQLException {
        return getFloat(findColumn(columnLabel));
    }

    @Override
    public double getDouble(int columnIndex) throws SQLException {
        return GraphValues.asDouble(raw(columnIndex));
    }

    @Override
    public double getDouble(String columnLabel) throws SQLException {
        return getDouble(findColumn(columnLabel));
    }

    @Override
    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        return GraphValues.asBigDecimal(raw(columnIndex));
    }

    @Override
    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        return getBigDecimal(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal value = getBigDecimal(columnIndex);
        return value == null ? null : value.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override
    @Deprecated
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        return getBigDecimal(findColumn(columnLabel), scale);
    }

    @Override
    public byte[] getBytes(int columnIndex) throws SQLException {
        return GraphValues.asBytes(raw(columnIndex));
    }

    @Override
    public byte[] getBytes(String columnLabel) throws SQLException {
        return getBytes(findColumn(columnLabel));
    }

    @Override
    public Date getDate(int columnIndex) throws SQLException {
        LocalDate value = GraphValues.asLocalDate(raw(columnIndex));
        return value == null ? null : Date.valueOf(value);
    }

    @Override
    public Date getDate(String columnLabel) throws SQLException {
        return getDate(findColumn(columnLabel));
    }

    @Override
    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        LocalDate value = GraphValues.asLocalDate(raw(columnIndex));
        if (value == null) {
            return null;
        }
        return cal == null
                ? Date.valueOf(value)
                : new Date(value.atStartOfDay(cal.getTimeZone().toZoneId())
                        .toInstant()
                        .toEpochMilli());
    }

    @Override
    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        return getDate(findColumn(columnLabel), cal);
    }

    @Override
    public Time getTime(int columnIndex) throws SQLException {
        LocalTime value = GraphValues.asLocalTime(raw(columnIndex));
        return value == null ? null : Time.valueOf(value);
    }

    @Override
    public Time getTime(String columnLabel) throws SQLException {
        return getTime(findColumn(columnLabel));
    }

    @Override
    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        LocalTime value = GraphValues.asLocalTime(raw(columnIndex));
        if (value == null) {
            return null;
        }
        return cal == null
                ? Time.valueOf(value)
                : new Time(value.atDate(LocalDate.EPOCH)
                        .atZone(cal.getTimeZone().toZoneId())
                        .toInstant()
                        .toEpochMilli());
    }

    @Override
    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        return getTime(findColumn(columnLabel), cal);
    }

    @Override
    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        LocalDateTime value = GraphValues.asLocalDateTime(raw(columnIndex));
        return value == null ? null : Timestamp.valueOf(value);
    }

    @Override
    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        return getTimestamp(findColumn(columnLabel));
    }

    @Override
    public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
        LocalDateTime value = GraphValues.asLocalDateTime(raw(columnIndex));
        if (value == null) {
            return null;
        }
        return cal == null
                ? Timestamp.valueOf(value)
                : Timestamp.from(value.atZone(cal.getTimeZone().toZoneId()).toInstant());
    }

    @Override
    public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
        return getTimestamp(findColumn(columnLabel), cal);
    }

    @Override
    public java.sql.Array getArray(int columnIndex) throws SQLException {
        Object value = raw(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> collection) {
            return new FalkorDBArray(collection);
        }
        throw SQLErrors.cannotConvert(value, "java.sql.Array");
    }

    @Override
    public java.sql.Array getArray(String columnLabel) throws SQLException {
        return getArray(findColumn(columnLabel));
    }

    @Override
    public URL getURL(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        if (value == null) {
            return null;
        }
        try {
            return URI.create(value).toURL();
        } catch (MalformedURLException | IllegalArgumentException e) {
            throw SQLErrors.cannotConvert(value, "java.net.URL");
        }
    }

    @Override
    public URL getURL(String columnLabel) throws SQLException {
        return getURL(findColumn(columnLabel));
    }

    @Override
    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null : new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        return getAsciiStream(findColumn(columnLabel));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null : new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @Deprecated
    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        return getUnicodeStream(findColumn(columnLabel));
    }

    @Override
    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        byte[] value = getBytes(columnIndex);
        return value == null ? null : new ByteArrayInputStream(value);
    }

    @Override
    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        return getBinaryStream(findColumn(columnLabel));
    }

    @Override
    public Reader getCharacterStream(int columnIndex) throws SQLException {
        String value = getString(columnIndex);
        return value == null ? null : new StringReader(value);
    }

    @Override
    public Reader getCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public Reader getNCharacterStream(int columnIndex) throws SQLException {
        return getCharacterStream(columnIndex);
    }

    @Override
    public Reader getNCharacterStream(String columnLabel) throws SQLException {
        return getCharacterStream(findColumn(columnLabel));
    }

    @Override
    public String getNString(int columnIndex) throws SQLException {
        return getString(columnIndex);
    }

    @Override
    public String getNString(String columnLabel) throws SQLException {
        return getString(findColumn(columnLabel));
    }

    // ---------------------------------------------------------------- metadata & housekeeping

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        checkOpen();
        String catalog = statement == null ? "" : statement.catalogName();
        return new FalkorDBResultSetMetaData(columns, catalog);
    }

    @Override
    public Statement getStatement() {
        return statement;
    }

    @Override
    public SQLWarning getWarnings() throws SQLException {
        checkOpen();
        return warnings;
    }

    @Override
    public void clearWarnings() throws SQLException {
        checkOpen();
        warnings = null;
    }

    void addWarning(SQLWarning warning) {
        if (warnings == null) {
            warnings = warning;
        } else {
            warnings.setNextWarning(warning);
        }
    }

    /**
     * Always the empty string. FalkorDB has no named cursors, and {@link #getConcurrency()} reports a
     * read-only result set, so no positioned update can refer to one.
     *
     * @return the empty string
     * @throws SQLException if the result set is closed
     */
    @Override
    public String getCursorName() throws SQLException {
        checkOpen();
        return "";
    }

    @Override
    public int getType() throws SQLException {
        checkOpen();
        return TYPE_FORWARD_ONLY;
    }

    @Override
    public int getConcurrency() throws SQLException {
        checkOpen();
        return CONCUR_READ_ONLY;
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != FETCH_FORWARD) {
            throw SQLErrors.unsupported("Fetch direction other than FETCH_FORWARD");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return FETCH_FORWARD;
    }

    /**
     * Accepted and ignored: JFalkorDB delivers a whole response in one round trip, so there is no
     * fetch size to tune. The value is remembered so {@link #getFetchSize()} echoes it back.
     *
     * @param rows the requested fetch size
     * @throws SQLException if the result set is closed or {@code rows} is negative
     */
    @Override
    public void setFetchSize(int rows) throws SQLException {
        checkOpen();
        if (rows < 0) {
            throw new SQLException("Fetch size must not be negative", SQLErrors.STATE_INVALID_PARAMETER);
        }
        fetchSize = rows;
    }

    @Override
    public int getFetchSize() throws SQLException {
        checkOpen();
        return fetchSize;
    }

    @Override
    public boolean rowUpdated() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowInserted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public boolean rowDeleted() throws SQLException {
        checkOpen();
        return false;
    }

    @Override
    public void refreshRow() throws SQLException {
        throw SQLErrors.unsupported("refreshRow()");
    }

    @Override
    public java.sql.Ref getRef(int columnIndex) throws SQLException {
        throw SQLErrors.unsupported("getRef(int)");
    }

    @Override
    public java.sql.Ref getRef(String columnLabel) throws SQLException {
        throw SQLErrors.unsupported("getRef(String)");
    }

    @Override
    public java.sql.Blob getBlob(int columnIndex) throws SQLException {
        throw SQLErrors.unsupported("getBlob(int)");
    }

    @Override
    public java.sql.Blob getBlob(String columnLabel) throws SQLException {
        throw SQLErrors.unsupported("getBlob(String)");
    }

    @Override
    public java.sql.Clob getClob(int columnIndex) throws SQLException {
        throw SQLErrors.unsupported("getClob(int)");
    }

    @Override
    public java.sql.Clob getClob(String columnLabel) throws SQLException {
        throw SQLErrors.unsupported("getClob(String)");
    }

    @Override
    public java.sql.NClob getNClob(int columnIndex) throws SQLException {
        throw SQLErrors.unsupported("getNClob(int)");
    }

    @Override
    public java.sql.NClob getNClob(String columnLabel) throws SQLException {
        throw SQLErrors.unsupported("getNClob(String)");
    }

    @Override
    public java.sql.SQLXML getSQLXML(int columnIndex) throws SQLException {
        throw SQLErrors.unsupported("getSQLXML(int)");
    }

    @Override
    public java.sql.SQLXML getSQLXML(String columnLabel) throws SQLException {
        throw SQLErrors.unsupported("getSQLXML(String)");
    }

    @Override
    public java.sql.RowId getRowId(int columnIndex) throws SQLException {
        throw SQLErrors.unsupported("getRowId(int)");
    }

    @Override
    public java.sql.RowId getRowId(String columnLabel) throws SQLException {
        throw SQLErrors.unsupported("getRowId(String)");
    }

    // ---------------------------------------------------------------- updates (unsupported)

    private static SQLException readOnly(String operation) {
        return SQLErrors.unsupported(operation + " on a read-only ResultSet");
    }

    private SQLException forwardOnly(String operation) throws SQLException {
        checkOpen();
        return SQLErrors.unsupported(operation + " on a TYPE_FORWARD_ONLY ResultSet");
    }

    @Override
    public void updateNull(int columnIndex) throws SQLException {
        throw readOnly("updateNull");
    }

    @Override
    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw readOnly("updateBoolean");
    }

    @Override
    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw readOnly("updateByte");
    }

    @Override
    public void updateShort(int columnIndex, short x) throws SQLException {
        throw readOnly("updateShort");
    }

    @Override
    public void updateInt(int columnIndex, int x) throws SQLException {
        throw readOnly("updateInt");
    }

    @Override
    public void updateLong(int columnIndex, long x) throws SQLException {
        throw readOnly("updateLong");
    }

    @Override
    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw readOnly("updateFloat");
    }

    @Override
    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw readOnly("updateDouble");
    }

    @Override
    public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
        throw readOnly("updateBigDecimal");
    }

    @Override
    public void updateString(int columnIndex, String x) throws SQLException {
        throw readOnly("updateString");
    }

    @Override
    public void updateBytes(int columnIndex, byte[] x) throws SQLException {
        throw readOnly("updateBytes");
    }

    @Override
    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw readOnly("updateDate");
    }

    @Override
    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw readOnly("updateTime");
    }

    @Override
    public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
        throw readOnly("updateTimestamp");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
        throw readOnly("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
        throw readOnly("updateCharacterStream");
    }

    @Override
    public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw readOnly("updateObject");
    }

    @Override
    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw readOnly("updateObject");
    }

    @Override
    public void updateNull(String columnLabel) throws SQLException {
        throw readOnly("updateNull");
    }

    @Override
    public void updateBoolean(String columnLabel, boolean x) throws SQLException {
        throw readOnly("updateBoolean");
    }

    @Override
    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw readOnly("updateByte");
    }

    @Override
    public void updateShort(String columnLabel, short x) throws SQLException {
        throw readOnly("updateShort");
    }

    @Override
    public void updateInt(String columnLabel, int x) throws SQLException {
        throw readOnly("updateInt");
    }

    @Override
    public void updateLong(String columnLabel, long x) throws SQLException {
        throw readOnly("updateLong");
    }

    @Override
    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw readOnly("updateFloat");
    }

    @Override
    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw readOnly("updateDouble");
    }

    @Override
    public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
        throw readOnly("updateBigDecimal");
    }

    @Override
    public void updateString(String columnLabel, String x) throws SQLException {
        throw readOnly("updateString");
    }

    @Override
    public void updateBytes(String columnLabel, byte[] x) throws SQLException {
        throw readOnly("updateBytes");
    }

    @Override
    public void updateDate(String columnLabel, Date x) throws SQLException {
        throw readOnly("updateDate");
    }

    @Override
    public void updateTime(String columnLabel, Time x) throws SQLException {
        throw readOnly("updateTime");
    }

    @Override
    public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
        throw readOnly("updateTimestamp");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw readOnly("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
        throw readOnly("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
        throw readOnly("updateCharacterStream");
    }

    @Override
    public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
        throw readOnly("updateObject");
    }

    @Override
    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw readOnly("updateObject");
    }

    @Override
    public void insertRow() throws SQLException {
        throw readOnly("insertRow");
    }

    @Override
    public void updateRow() throws SQLException {
        throw readOnly("updateRow");
    }

    @Override
    public void deleteRow() throws SQLException {
        throw readOnly("deleteRow");
    }

    @Override
    public void cancelRowUpdates() throws SQLException {
        throw readOnly("cancelRowUpdates");
    }

    @Override
    public void moveToInsertRow() throws SQLException {
        throw readOnly("moveToInsertRow");
    }

    @Override
    public void moveToCurrentRow() throws SQLException {
        throw readOnly("moveToCurrentRow");
    }

    @Override
    public void updateRef(int columnIndex, java.sql.Ref x) throws SQLException {
        throw readOnly("updateRef");
    }

    @Override
    public void updateRef(String columnLabel, java.sql.Ref x) throws SQLException {
        throw readOnly("updateRef");
    }

    @Override
    public void updateBlob(int columnIndex, java.sql.Blob x) throws SQLException {
        throw readOnly("updateBlob");
    }

    @Override
    public void updateBlob(String columnLabel, java.sql.Blob x) throws SQLException {
        throw readOnly("updateBlob");
    }

    @Override
    public void updateClob(int columnIndex, java.sql.Clob x) throws SQLException {
        throw readOnly("updateClob");
    }

    @Override
    public void updateClob(String columnLabel, java.sql.Clob x) throws SQLException {
        throw readOnly("updateClob");
    }

    @Override
    public void updateArray(int columnIndex, java.sql.Array x) throws SQLException {
        throw readOnly("updateArray");
    }

    @Override
    public void updateArray(String columnLabel, java.sql.Array x) throws SQLException {
        throw readOnly("updateArray");
    }

    @Override
    public void updateRowId(int columnIndex, java.sql.RowId x) throws SQLException {
        throw readOnly("updateRowId");
    }

    @Override
    public void updateRowId(String columnLabel, java.sql.RowId x) throws SQLException {
        throw readOnly("updateRowId");
    }

    @Override
    public void updateNString(int columnIndex, String nString) throws SQLException {
        throw readOnly("updateNString");
    }

    @Override
    public void updateNString(String columnLabel, String nString) throws SQLException {
        throw readOnly("updateNString");
    }

    @Override
    public void updateNClob(int columnIndex, java.sql.NClob nClob) throws SQLException {
        throw readOnly("updateNClob");
    }

    @Override
    public void updateNClob(String columnLabel, java.sql.NClob nClob) throws SQLException {
        throw readOnly("updateNClob");
    }

    @Override
    public void updateSQLXML(int columnIndex, java.sql.SQLXML xmlObject) throws SQLException {
        throw readOnly("updateSQLXML");
    }

    @Override
    public void updateSQLXML(String columnLabel, java.sql.SQLXML xmlObject) throws SQLException {
        throw readOnly("updateSQLXML");
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly("updateNCharacterStream");
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly("updateNCharacterStream");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
        throw readOnly("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
        throw readOnly("updateCharacterStream");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
        throw readOnly("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly("updateCharacterStream");
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
        throw readOnly("updateBlob");
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
        throw readOnly("updateBlob");
    }

    @Override
    public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw readOnly("updateClob");
    }

    @Override
    public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly("updateClob");
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
        throw readOnly("updateNClob");
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
        throw readOnly("updateNClob");
    }

    @Override
    public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly("updateNCharacterStream");
    }

    @Override
    public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw readOnly("updateNCharacterStream");
    }

    @Override
    public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
        throw readOnly("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
        throw readOnly("updateCharacterStream");
    }

    @Override
    public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly("updateAsciiStream");
    }

    @Override
    public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
        throw readOnly("updateBinaryStream");
    }

    @Override
    public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
        throw readOnly("updateCharacterStream");
    }

    @Override
    public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
        throw readOnly("updateBlob");
    }

    @Override
    public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
        throw readOnly("updateBlob");
    }

    @Override
    public void updateClob(int columnIndex, Reader reader) throws SQLException {
        throw readOnly("updateClob");
    }

    @Override
    public void updateClob(String columnLabel, Reader reader) throws SQLException {
        throw readOnly("updateClob");
    }

    @Override
    public void updateNClob(int columnIndex, Reader reader) throws SQLException {
        throw readOnly("updateNClob");
    }

    @Override
    public void updateNClob(String columnLabel, Reader reader) throws SQLException {
        throw readOnly("updateNClob");
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw SQLErrors.closed("ResultSet");
        }
    }
}
