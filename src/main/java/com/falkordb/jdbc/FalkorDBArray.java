package com.falkordb.jdbc;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.falkordb.jdbc.internal.ColumnMeta;
import com.falkordb.jdbc.internal.FalkorType;
import com.falkordb.jdbc.internal.GraphValues;
import com.falkordb.jdbc.internal.SQLErrors;

/**
 * A {@link java.sql.Array} over a Cypher list or a {@code vecf32} vector.
 *
 * <p>The element type is inferred from the list's contents: a list whose elements all share one
 * FalkorDB type reports that type, and a mixed list reports {@link java.sql.Types#OTHER}, since
 * Cypher lists are heterogeneous by nature.
 */
public final class FalkorDBArray extends FalkorDBWrapper implements java.sql.Array {

    private final List<Object> elements;
    private final FalkorType elementType;
    private boolean freed;

    FalkorDBArray(Collection<?> elements) {
        this.elements = new ArrayList<>(elements);
        this.elementType = inferElementType(this.elements);
    }

    private static FalkorType inferElementType(List<Object> elements) {
        FalkorType resolved = null;
        for (Object element : elements) {
            if (element == null) {
                continue;
            }
            FalkorType current = FalkorType.of(element);
            if (resolved == null) {
                resolved = current;
            } else if (resolved != current) {
                return FalkorType.UNKNOWN;
            }
        }
        return resolved == null ? FalkorType.NULL : resolved;
    }

    @Override
    public String getBaseTypeName() throws SQLException {
        checkValid();
        return elementType.typeName();
    }

    @Override
    public int getBaseType() throws SQLException {
        checkValid();
        return elementType.sqlType();
    }

    @Override
    public Object getArray() throws SQLException {
        checkValid();
        return elements.toArray();
    }

    @Override
    public Object getArray(Map<String, Class<?>> map) throws SQLException {
        requireNoTypeMap(map);
        return getArray();
    }

    @Override
    public Object getArray(long index, int count) throws SQLException {
        checkValid();
        return slice(index, count).toArray();
    }

    @Override
    public Object getArray(long index, int count, Map<String, Class<?>> map) throws SQLException {
        requireNoTypeMap(map);
        return getArray(index, count);
    }

    @Override
    public java.sql.ResultSet getResultSet() throws SQLException {
        checkValid();
        return toResultSet(elements, 1);
    }

    @Override
    public java.sql.ResultSet getResultSet(Map<String, Class<?>> map) throws SQLException {
        requireNoTypeMap(map);
        return getResultSet();
    }

    @Override
    public java.sql.ResultSet getResultSet(long index, int count) throws SQLException {
        checkValid();
        return toResultSet(slice(index, count), index);
    }

    @Override
    public java.sql.ResultSet getResultSet(long index, int count, Map<String, Class<?>> map) throws SQLException {
        requireNoTypeMap(map);
        return getResultSet(index, count);
    }

    @Override
    public void free() {
        freed = true;
        elements.clear();
    }

    @Override
    public String toString() {
        return freed ? "FalkorDBArray[freed]" : GraphValues.renderNested(elements);
    }

    private java.sql.ResultSet toResultSet(List<Object> slice, long firstIndex) {
        List<ColumnMeta> columns = List.of(
                ColumnMeta.of("INDEX", FalkorType.INTEGER, java.sql.ResultSetMetaData.columnNoNulls),
                ColumnMeta.of("VALUE", elementType));
        List<List<Object>> rows = new ArrayList<>(slice.size());
        for (int i = 0; i < slice.size(); i++) {
            rows.add(java.util.Arrays.asList(firstIndex + i, slice.get(i)));
        }
        return new FalkorDBResultSet(columns, rows, null);
    }

    /** JDBC array indices are one-based, matching {@code getArray(long index, int count)}'s contract. */
    private List<Object> slice(long index, int count) throws SQLException {
        if (index < 1 || index > (long) elements.size() + 1) {
            throw new SQLException(
                    "Array index " + index + " is out of range for an array of length " + elements.size(),
                    SQLErrors.STATE_INVALID_PARAMETER);
        }
        if (count < 0) {
            throw new SQLException("Array element count must not be negative", SQLErrors.STATE_INVALID_PARAMETER);
        }
        int from = (int) (index - 1);
        int to = (int) Math.min((long) from + count, elements.size());
        return elements.subList(from, to);
    }

    private void checkValid() throws SQLException {
        if (freed) {
            throw SQLErrors.closed("Array");
        }
    }

    private void requireNoTypeMap(Map<String, Class<?>> map) throws SQLException {
        checkValid();
        if (map != null && !map.isEmpty()) {
            throw new SQLFeatureNotSupportedException(
                    "Custom type maps are not supported by the FalkorDB JDBC driver", SQLErrors.STATE_NOT_SUPPORTED);
        }
    }
}
