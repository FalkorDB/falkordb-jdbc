package com.falkordb.jdbc;

import java.sql.SQLException;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.FilteredRowSet;
import javax.sql.rowset.JdbcRowSet;
import javax.sql.rowset.JoinRowSet;
import javax.sql.rowset.RowSetFactory;
import javax.sql.rowset.RowSetProvider;
import javax.sql.rowset.WebRowSet;
import javax.sql.rowset.spi.SyncFactory;

import com.falkordb.jdbc.internal.SQLErrors;

/**
 * Hands out {@link javax.sql.RowSet} implementations that can be filled from a Cypher query.
 *
 * <p>The row sets themselves are the platform's reference implementations — this factory adds only
 * what makes them work against a graph: {@link FalkorDBSyncProvider}, which runs the row set's
 * command as a query rather than guessing from its text, and which refuses to write changes back.
 *
 * <pre>{@code
 * RowSetFactory factory = new FalkorDBRowSetFactory();
 *
 * CachedRowSet rowSet = factory.createCachedRowSet();
 * rowSet.setUrl("jdbc:falkordb://localhost:6379/social");
 * rowSet.setCommand("MATCH (p:Person) WHERE p.age > ? RETURN p.name AS name");
 * rowSet.setInt(1, 30);
 * rowSet.execute();
 *
 * while (rowSet.next()) {
 *     System.out.println(rowSet.getString("name"));
 * }
 * }</pre>
 *
 * <p>A row set can equally be filled from a result set the driver already produced, with {@link
 * CachedRowSet#populate(java.sql.ResultSet)}; no URL or command is needed for that.
 *
 * <p>The disconnected row sets — {@link CachedRowSet}, {@link WebRowSet}, {@link FilteredRowSet} and
 * {@link JoinRowSet} — are supported. {@link JdbcRowSet} is not: it is a thin cover over a live
 * {@link java.sql.ResultSet} and needs it to be scrollable and updatable, which this driver's result
 * sets are not. {@link #createJdbcRowSet()} therefore throws {@link
 * java.sql.SQLFeatureNotSupportedException} instead of handing back a row set that fails on first
 * use.
 *
 * <p>Two further limits are inherited from the reference implementation. Paging with {@code
 * setPageSize} is not supported, because it reaches past the provider into the JDK's own reader. And
 * a temporal column is cached as the {@code java.time} value {@link
 * java.sql.ResultSet#getObject(int)} returns, so read it with {@code getObject} rather than {@code
 * getDate}, {@code getTime} or {@code getTimestamp}.
 */
public final class FalkorDBRowSetFactory implements RowSetFactory {

    /**
     * The JDK's own factory, used when this one has been installed as the platform default and
     * {@link RowSetProvider#newFactory()} would otherwise return this factory again.
     */
    private static final String REFERENCE_FACTORY = "com.sun.rowset.RowSetFactoryImpl";

    /** Creates a factory. */
    public FalkorDBRowSetFactory() {}

    /**
     * {@inheritDoc}
     *
     * @return a disconnected row set that reads through {@link FalkorDBSyncProvider}
     * @throws SQLException if the row set cannot be created or the provider cannot be installed
     */
    @Override
    public CachedRowSet createCachedRowSet() throws SQLException {
        return configure(platform().createCachedRowSet());
    }

    /**
     * {@inheritDoc}
     *
     * @return a disconnected row set that reads through {@link FalkorDBSyncProvider} and can write
     *     itself out as XML
     * @throws SQLException if the row set cannot be created or the provider cannot be installed
     */
    @Override
    public WebRowSet createWebRowSet() throws SQLException {
        return configure(platform().createWebRowSet());
    }

    /**
     * {@inheritDoc}
     *
     * @return a disconnected row set that reads through {@link FalkorDBSyncProvider} and applies a
     *     {@link javax.sql.rowset.Predicate}
     * @throws SQLException if the row set cannot be created or the provider cannot be installed
     */
    @Override
    public FilteredRowSet createFilteredRowSet() throws SQLException {
        return configure(platform().createFilteredRowSet());
    }

    /**
     * {@inheritDoc}
     *
     * @return a row set that joins other row sets in memory
     * @throws SQLException if the row set cannot be created or the provider cannot be installed
     */
    @Override
    public JoinRowSet createJoinRowSet() throws SQLException {
        return configure(platform().createJoinRowSet());
    }

    /**
     * Always throws. A {@link JdbcRowSet} stays connected and delegates to a live {@link
     * java.sql.ResultSet}, which it requires to be scrollable and updatable; this driver's result
     * sets are forward-only and read-only. Use {@link #createCachedRowSet()} instead.
     *
     * @return never returns
     * @throws java.sql.SQLFeatureNotSupportedException always
     */
    @Override
    public JdbcRowSet createJdbcRowSet() throws SQLException {
        throw SQLErrors.unsupported("JdbcRowSet, which needs a scrollable and updatable ResultSet,");
    }

    @Override
    public String toString() {
        return "FalkorDBRowSetFactory";
    }

    /**
     * Installs {@link FalkorDBSyncProvider} on a row set.
     *
     * <p>{@link javax.sql.rowset.spi.SyncFactory} answers a lookup for an unregistered provider with
     * the JDK's default one instead of failing, so the provider is registered first and the row set
     * is asked afterwards what it ended up with. A row set that silently kept the default provider
     * would run every Cypher command as an update and come back empty.
     */
    private static <T extends CachedRowSet> T configure(T rowSet) throws SQLException {
        SyncFactory.registerProvider(FalkorDBSyncProvider.ID);
        rowSet.setSyncProvider(FalkorDBSyncProvider.ID);
        if (!(rowSet.getSyncProvider() instanceof FalkorDBSyncProvider)) {
            throw new SQLException(
                    "Could not install " + FalkorDBSyncProvider.ID + " on a "
                            + rowSet.getClass().getName() + "; it is using "
                            + rowSet.getSyncProvider().getProviderID() + " instead",
                    SQLErrors.STATE_GENERAL);
        }
        return rowSet;
    }

    /** The factory that builds the row sets themselves. */
    private static RowSetFactory platform() throws SQLException {
        RowSetFactory factory = RowSetProvider.newFactory();
        return factory instanceof FalkorDBRowSetFactory ? RowSetProvider.newFactory(REFERENCE_FACTORY, null) : factory;
    }
}
