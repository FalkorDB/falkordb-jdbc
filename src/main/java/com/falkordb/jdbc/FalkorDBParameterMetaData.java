package com.falkordb.jdbc;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

import com.falkordb.jdbc.internal.SQLErrors;

/**
 * Describes a {@link FalkorDBPreparedStatement}'s parameters.
 *
 * <p>Only the parameter count is genuinely known. Cypher is not statically typed and FalkorDB does
 * not describe a prepared statement's parameters, so every other question is answered with the
 * "unknown" value JDBC provides for exactly this situation rather than with a guess.
 */
public final class FalkorDBParameterMetaData extends FalkorDBWrapper implements ParameterMetaData {

    private final int parameterCount;

    FalkorDBParameterMetaData(int parameterCount) {
        this.parameterCount = parameterCount;
    }

    @Override
    public int getParameterCount() {
        return parameterCount;
    }

    @Override
    public int isNullable(int param) throws SQLException {
        check(param);
        return parameterNullableUnknown;
    }

    /**
     * Always {@code false}. A Cypher parameter has no declared type — this metadata reports every
     * parameter as {@link java.sql.Types#OTHER} — so it may just as well be bound to a string, a
     * list or a map. Claiming it is a signed number would assert a numeric contract the driver
     * cannot know holds.
     *
     * @param param the parameter, 1-based
     * @return {@code false}
     * @throws SQLException if {@code param} is out of range
     */
    @Override
    public boolean isSigned(int param) throws SQLException {
        check(param);
        return false;
    }

    @Override
    public int getPrecision(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getScale(int param) throws SQLException {
        check(param);
        return 0;
    }

    @Override
    public int getParameterType(int param) throws SQLException {
        check(param);
        return Types.OTHER;
    }

    @Override
    public String getParameterTypeName(int param) throws SQLException {
        check(param);
        return "ANY";
    }

    @Override
    public String getParameterClassName(int param) throws SQLException {
        check(param);
        return Object.class.getName();
    }

    @Override
    public int getParameterMode(int param) throws SQLException {
        check(param);
        return parameterModeIn;
    }

    @Override
    public String toString() {
        return "FalkorDBParameterMetaData[count=" + parameterCount + "]";
    }

    private void check(int param) throws SQLException {
        if (param < 1 || param > parameterCount) {
            throw new SQLException(
                    "Parameter index " + param + " is out of range; this statement has " + parameterCount
                            + " parameter(s)",
                    SQLErrors.STATE_INVALID_PARAMETER);
        }
    }
}
