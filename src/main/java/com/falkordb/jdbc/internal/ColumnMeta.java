package com.falkordb.jdbc.internal;

import java.sql.ResultSetMetaData;

/**
 * A single column of a {@link java.sql.ResultSet}, resolved to its JDBC-facing description.
 *
 * <p>FalkorDB's result header only distinguishes scalars from nodes and relationships, so for a
 * scalar column the concrete type is inferred from the data (see {@link #infer}). A column that is
 * empty or entirely null is reported as {@link FalkorType#NULL}, which is the honest answer: nothing
 * in the response says what it would have held.
 *
 * @param label the column name as written in the {@code RETURN} clause
 * @param type the FalkorDB type of the column's values
 * @param nullable one of the {@code ResultSetMetaData.column*} nullability constants
 */
public record ColumnMeta(String label, FalkorType type, int nullable) {

    /**
     * Describes a column whose type was inferred from data.
     *
     * @param label the column label
     * @param type the inferred type
     * @return the column description, always reported as nullable
     */
    public static ColumnMeta of(String label, FalkorType type) {
        return new ColumnMeta(label, type, ResultSetMetaData.columnNullable);
    }

    /**
     * Describes a column of a driver-generated result set, such as one returned by {@link
     * java.sql.DatabaseMetaData}, whose type is known up front.
     *
     * @param label the column label
     * @param type the column type
     * @param nullable one of the {@code ResultSetMetaData.column*} constants
     * @return the column description
     */
    public static ColumnMeta of(String label, FalkorType type, int nullable) {
        return new ColumnMeta(label, type, nullable);
    }

    /**
     * Infers a column's type from the values actually present in it.
     *
     * <p>The first non-null value decides. If the remaining values disagree with it — which a query
     * such as {@code UNWIND [1, "two"] AS v RETURN v} produces — the column is reported as {@link
     * FalkorType#UNKNOWN}, since no single JDBC type describes it.
     *
     * @param label the column label
     * @param rows every row of the result set
     * @param index the zero-based position of this column within a row
     * @return the column description
     */
    public static ColumnMeta infer(String label, Iterable<java.util.List<Object>> rows, int index) {
        FalkorType resolved = null;
        for (java.util.List<Object> row : rows) {
            Object value = index < row.size() ? row.get(index) : null;
            if (value == null) {
                continue;
            }
            FalkorType current = FalkorType.of(value);
            if (resolved == null) {
                resolved = current;
            } else if (resolved != current) {
                return of(label, FalkorType.UNKNOWN);
            }
        }
        return of(label, resolved == null ? FalkorType.NULL : resolved);
    }
}
