package com.falkordb.jdbc;

import java.sql.SQLException;
import java.sql.Wrapper;

import com.falkordb.jdbc.internal.SQLErrors;

/**
 * Shared {@link Wrapper} behaviour for this driver's JDBC objects.
 *
 * <p>Every JDBC object here unwraps to itself and to any interface it implements, which is what lets
 * callers reach the driver-specific extensions — for example {@link
 * FalkorDBPreparedStatement#setNamedObject(String, Object)} — from a plain {@code PreparedStatement}
 * handle.
 */
abstract class FalkorDBWrapper implements Wrapper {

    @Override
    public final <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException(
                getClass().getName() + " is not a wrapper for " + (iface == null ? "null" : iface.getName()),
                SQLErrors.STATE_GENERAL);
    }

    @Override
    public final boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }
}
