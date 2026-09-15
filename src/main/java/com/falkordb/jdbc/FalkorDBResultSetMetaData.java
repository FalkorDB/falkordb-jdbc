package com.falkordb.jdbc;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import com.falkordb.jdbc.internal.ColumnMeta;
import com.falkordb.jdbc.internal.FalkorType;
import com.falkordb.jdbc.internal.SQLErrors;

/**
 * Describes the columns of a {@link FalkorDBResultSet}.
 *
 * <p>FalkorDB's response header names each column and says only whether it holds a node, a
 * relationship, or some scalar, so the concrete type of a scalar column is inferred from the values
 * the query actually returned. A column with no rows, or only nulls, is therefore reported as {@link
 * java.sql.Types#NULL}, and a column whose values disagree as {@link java.sql.Types#OTHER}.
 */
public final class FalkorDBResultSetMetaData extends FalkorDBWrapper implements ResultSetMetaData {

    private final List<ColumnMeta> columns;
    private final String catalog;

    FalkorDBResultSetMetaData(List<ColumnMeta> columns, String catalog) {
        this.columns = columns;
        this.catalog = catalog == null ? "" : catalog;
    }

    @Override
    public int getColumnCount() {
        return columns.size();
    }

    @Override
    public boolean isAutoIncrement(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isCaseSensitive(int column) throws SQLException {
        return type(column) == FalkorType.STRING;
    }

    @Override
    public boolean isSearchable(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isCurrency(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public int isNullable(int column) throws SQLException {
        return meta(column).nullable();
    }

    @Override
    public boolean isSigned(int column) throws SQLException {
        return type(column).isSigned();
    }

    @Override
    public int getColumnDisplaySize(int column) throws SQLException {
        return type(column).displaySize();
    }

    @Override
    public String getColumnLabel(int column) throws SQLException {
        return meta(column).label();
    }

    @Override
    public String getColumnName(int column) throws SQLException {
        return meta(column).label();
    }

    /**
     * Always the empty string. A Cypher projection is an expression, not a column of a named table,
     * so there is no schema to report.
     *
     * @param column the one-based column index
     * @return the empty string
     * @throws SQLException if the index is out of range
     */
    @Override
    public String getSchemaName(int column) throws SQLException {
        check(column);
        return "";
    }

    @Override
    public int getPrecision(int column) throws SQLException {
        return type(column).precision();
    }

    @Override
    public int getScale(int column) throws SQLException {
        check(column);
        return 0;
    }

    /**
     * Always the empty string, for the same reason as {@link #getSchemaName(int)}.
     *
     * @param column the one-based column index
     * @return the empty string
     * @throws SQLException if the index is out of range
     */
    @Override
    public String getTableName(int column) throws SQLException {
        check(column);
        return "";
    }

    @Override
    public String getCatalogName(int column) throws SQLException {
        check(column);
        return catalog;
    }

    @Override
    public int getColumnType(int column) throws SQLException {
        return type(column).sqlType();
    }

    @Override
    public String getColumnTypeName(int column) throws SQLException {
        return type(column).typeName();
    }

    @Override
    public boolean isReadOnly(int column) throws SQLException {
        check(column);
        return true;
    }

    @Override
    public boolean isWritable(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public boolean isDefinitelyWritable(int column) throws SQLException {
        check(column);
        return false;
    }

    @Override
    public String getColumnClassName(int column) throws SQLException {
        return type(column).javaType().getName();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("FalkorDBResultSetMetaData[");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(columns.get(i).label())
                    .append(':')
                    .append(columns.get(i).type().typeName());
        }
        return sb.append(']').toString();
    }

    private FalkorType type(int column) throws SQLException {
        return meta(column).type();
    }

    private ColumnMeta meta(int column) throws SQLException {
        check(column);
        return columns.get(column - 1);
    }

    private void check(int column) throws SQLException {
        if (column < 1 || column > columns.size()) {
            throw new SQLException(
                    "Column index " + column + " is out of range; this result set has " + columns.size() + " column(s)",
                    SQLErrors.STATE_INVALID_COLUMN);
        }
    }
}
