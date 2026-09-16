package com.falkordb.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.falkordb.Graph;
import com.falkordb.Statistics;
import com.falkordb.jdbc.internal.CypherQuery;
import com.falkordb.jdbc.internal.SQLErrors;

/**
 * Executes Cypher against FalkorDB.
 *
 * <p>The statement text is sent to the server as written; this driver performs no SQL-to-Cypher
 * translation. Whether a statement yields rows or an update count is decided by the response: a
 * query whose header declares columns produces a {@link ResultSet}, and one that declares none
 * produces an update count derived from the query's {@link Statistics}.
 *
 * <p>Following the JDBC contract, {@link #executeQuery(String)} rejects a statement that returned no
 * columns and {@link #executeUpdate(String)} rejects one that did, so neither silently discards part
 * of the response. Use {@link #execute(String)} when a statement may do either.
 */
public class FalkorDBStatement extends FalkorDBWrapper implements Statement {

    private static final int NO_UPDATE_COUNT = -1;

    /** The connection that created this statement. */
    final FalkorDBConnection connection;

    private FalkorDBResultSet resultSet;

    /**
     * Every result set this statement has produced that is still open, including ones no longer
     * reachable through {@link #resultSet} — a result set retained by {@code
     * getMoreResults(KEEP_CURRENT_RESULT)}, or one handed out by {@link #getGeneratedKeys()}. JDBC
     * closes a {@code closeOnCompletion} statement only once *all* of its dependent result sets are
     * closed, and {@link #close()} has to close whatever is left, so a single reference is not
     * enough. Identity, not equality: two result sets are the same only if they are the same object.
     */
    // Guarded by dependentsLock, for the reason FalkorDBConnection guards its statements: a pool
    // may close a connection from a reaper thread while the borrowing thread is still closing what
    // it produced, and iterating this set while that thread removes itself from it would throw.
    // The lock is never held across a call out to a result set or to the connection, so it cannot
    // deadlock against the connection's own lock.
    private final Set<FalkorDBResultSet> dependents = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Object dependentsLock = new Object();

    private long updateCount = NO_UPDATE_COUNT;
    // In milliseconds, the unit FalkorDB takes and the unit the queryTimeout property is
    // documented in. JDBC's accessors speak whole seconds, but a limit that arrives from the URL
    // never passes through them and so is not rounded.
    private long queryTimeoutMillis;
    private long maxRows;
    private int fetchSize;
    private boolean poolable;
    private boolean closeOnCompletion;
    private volatile boolean closed;
    private SQLWarning warnings;

    FalkorDBStatement(FalkorDBConnection connection) {
        this.connection = connection;
        this.queryTimeoutMillis = connection.defaultQueryTimeoutMillis();
    }

    // ---------------------------------------------------------------- execution

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        checkOpen();
        run(CypherQuery.literal(sql), Map.of());
        if (resultSet == null) {
            throw new SQLException(
                    "The statement returned no columns, so it has no result set; use executeUpdate or execute",
                    SQLErrors.STATE_GENERAL);
        }
        return resultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        long count = executeLargeUpdate(sql);
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Override
    public long executeLargeUpdate(String sql) throws SQLException {
        checkOpen();
        run(CypherQuery.literal(sql), Map.of());
        if (resultSet != null) {
            throw new SQLException(
                    "The statement returned a result set; use executeQuery or execute", SQLErrors.STATE_GENERAL);
        }
        return updateCount;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkOpen();
        run(CypherQuery.literal(sql), Map.of());
        return resultSet != null;
    }

    /**
     * Sends a statement to FalkorDB and records the outcome as either the current result set or the
     * current update count.
     *
     * @param query the translated statement
     * @param parameters the parameter bindings, keyed by Cypher parameter name
     * @throws SQLException if the server rejects the statement or the connection fails
     */
    final void run(CypherQuery query, Map<String, Object> parameters) throws SQLException {
        closeCurrentResultSet();
        // Closing that result set can have closed this statement, if it was the last dependent of a
        // closeOnCompletion statement; executing on it now would be executing on a closed statement.
        checkOpen();
        resultSet = null;
        updateCount = NO_UPDATE_COUNT;

        Graph graph = connection.graph();
        String cypher = query.cypher();
        long timeout = timeoutMillis();
        boolean readOnly = connection.isReadOnly();

        com.falkordb.ResultSet response;
        try {
            response = send(graph, cypher, parameters, timeout, readOnly);
        } catch (RuntimeException e) {
            throw SQLErrors.translate("Failed to execute Cypher statement", e);
        }

        if (response.getHeader().getSchemaNames().isEmpty()) {
            updateCount = updateCountOf(response.getStatistics());
        } else {
            FalkorDBResultSet rows = FalkorDBResultSet.of(response, this);
            if (maxRows > 0 && response.size() > maxRows) {
                rows.addWarning(new SQLWarning(
                        "The result set holds " + response.size() + " rows, more than the requested maxRows of "
                                + maxRows + "; FalkorDB returns a whole response at once, so no rows were discarded",
                        SQLErrors.STATE_GENERAL));
            }
            synchronized (dependentsLock) {
                dependents.add(rows);
            }
            resultSet = rows;
        }
    }

    /**
     * Picks the JFalkorDB overload matching the request: read-only queries go through FalkorDB's
     * read-only path so they can be served by a replica, and a timeout is only attached when one was
     * actually configured, since JFalkorDB treats every supplied timeout as binding.
     */
    private static com.falkordb.ResultSet send(
            Graph graph, String cypher, Map<String, Object> parameters, long timeout, boolean readOnly) {
        if (parameters.isEmpty()) {
            if (readOnly) {
                return timeout > 0 ? graph.readOnlyQuery(cypher, timeout) : graph.readOnlyQuery(cypher);
            }
            return timeout > 0 ? graph.query(cypher, timeout) : graph.query(cypher);
        }
        if (readOnly) {
            return timeout > 0
                    ? graph.readOnlyQuery(cypher, parameters, timeout)
                    : graph.readOnlyQuery(cypher, parameters);
        }
        return timeout > 0 ? graph.query(cypher, parameters, timeout) : graph.query(cypher, parameters);
    }

    /**
     * Derives a JDBC update count from FalkorDB's query statistics: the number of entities the
     * statement created or removed, plus the number of properties it set.
     *
     * <p>Schema effects — labels added, indices created or dropped — are deliberately excluded, since
     * JDBC's update count describes affected rows, not schema changes.
     *
     * @param statistics the statistics FalkorDB reported
     * @return the number of affected entities
     */
    static long updateCountOf(Statistics statistics) {
        return (long) statistics.nodesCreated()
                + statistics.nodesDeleted()
                + statistics.relationshipsCreated()
                + statistics.relationshipsDeleted()
                + statistics.propertiesSet();
    }

    /**
     * The limit actually sent to FalkorDB, in milliseconds. Package-private because {@link
     * #getQueryTimeout()} can only answer in whole seconds and so cannot report a sub-second limit
     * faithfully.
     */
    long timeoutMillis() {
        return Math.max(queryTimeoutMillis, 0L);
    }

    // ---------------------------------------------------------------- results

    @Override
    public ResultSet getResultSet() throws SQLException {
        checkOpen();
        return resultSet;
    }

    @Override
    public int getUpdateCount() throws SQLException {
        long count = getLargeUpdateCount();
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    @Override
    public long getLargeUpdateCount() throws SQLException {
        checkOpen();
        return updateCount;
    }

    /**
     * Called by a result set this statement produced once it has been closed, so {@link
     * #closeOnCompletion()} can take effect. The statement closes only when the last of its
     * dependent result sets has gone, which is what JDBC specifies.
     */
    void resultSetClosed(FalkorDBResultSet source) throws SQLException {
        boolean last;
        synchronized (dependentsLock) {
            dependents.remove(source);
            if (resultSet == source) {
                resultSet = null;
            }
            last = closeOnCompletion && !closed && dependents.isEmpty();
        }
        if (last) {
            close();
        }
    }

    @Override
    public boolean getMoreResults() throws SQLException {
        return getMoreResults(CLOSE_CURRENT_RESULT);
    }

    @Override
    public boolean getMoreResults(int current) throws SQLException {
        checkOpen();
        if (current != CLOSE_CURRENT_RESULT && current != KEEP_CURRENT_RESULT && current != CLOSE_ALL_RESULTS) {
            throw new SQLException(
                    "Expected CLOSE_CURRENT_RESULT, KEEP_CURRENT_RESULT or CLOSE_ALL_RESULTS, but was " + current,
                    SQLErrors.STATE_INVALID_PARAMETER);
        }
        if (current == CLOSE_ALL_RESULTS) {
            // Including any retained by an earlier KEEP_CURRENT_RESULT, and any generated keys.
            for (FalkorDBResultSet dependent : snapshotDependents()) {
                dependent.close();
            }
        } else if (current == CLOSE_CURRENT_RESULT) {
            closeCurrentResultSet();
        }
        // Deliberately not re-checking that the statement is still open: under closeOnCompletion,
        // closing the last dependent above closes this statement, and that is a success, not a
        // failure to report. close() leaves it closed with its dependents cleared either way.
        // A retained result set stays open and stays tracked in `dependents`, so it is still closed
        // by close() and still counts towards closeOnCompletion.
        resultSet = null;
        updateCount = NO_UPDATE_COUNT;
        return false;
    }

    /**
     * Always an empty result set. FalkorDB assigns internal ids to created entities but does not
     * report them out of band; return them explicitly with {@code RETURN id(n)} instead.
     *
     * @return an empty result set
     * @throws SQLException if the statement is closed
     */
    @Override
    public ResultSet getGeneratedKeys() throws SQLException {
        checkOpen();
        FalkorDBResultSet keys = new FalkorDBResultSet(List.of(), List.of(), this);
        synchronized (dependentsLock) {
            dependents.add(keys);
        }
        return keys;
    }

    // ---------------------------------------------------------------- configuration

    @Override
    public int getQueryTimeout() throws SQLException {
        checkOpen();
        // Rounded up, because JDBC has only whole seconds to answer with and reporting 1 for a
        // 1500 ms limit would understate it. getQueryTimeout is therefore not always the round trip
        // of a queryTimeout URL property; the limit actually applied is the one configured.
        long seconds = queryTimeoutMillis / 1000L + (queryTimeoutMillis % 1000L == 0L ? 0L : 1L);
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    /**
     * Sets the server-side time limit for this statement, passed to FalkorDB with each query. A value
     * of {@code 0} removes the limit.
     *
     * @param seconds the limit in seconds
     * @throws SQLException if the statement is closed or {@code seconds} is negative
     */
    @Override
    public void setQueryTimeout(int seconds) throws SQLException {
        checkOpen();
        if (seconds < 0) {
            throw new SQLException("Query timeout must not be negative", SQLErrors.STATE_INVALID_PARAMETER);
        }
        queryTimeoutMillis = seconds * 1000L;
    }

    @Override
    public int getMaxRows() throws SQLException {
        checkOpen();
        if (maxRows > Integer.MAX_VALUE) {
            throw new SQLException(
                    "The row limit of " + maxRows + " does not fit in an int; use getLargeMaxRows()",
                    SQLErrors.STATE_INVALID_PARAMETER);
        }
        return (int) maxRows;
    }

    /**
     * Records a row limit. FalkorDB materialises a whole response before the driver sees it, so the
     * limit cannot be enforced without discarding data the server already produced. Setting a limit
     * therefore raises a {@link SQLWarning} on this statement immediately — while the caller can still
     * react — and a second warning on any result set that actually exceeds it. Use a Cypher {@code
     * LIMIT} clause to genuinely bound a result.
     *
     * @param max the requested maximum, or {@code 0} for no limit
     * @throws SQLException if the statement is closed or {@code max} is negative
     */
    @Override
    public void setMaxRows(int max) throws SQLException {
        checkOpen();
        if (max < 0) {
            throw new SQLException("Max rows must not be negative", SQLErrors.STATE_INVALID_PARAMETER);
        }
        setLargeMaxRows(max);
    }

    @Override
    public long getLargeMaxRows() throws SQLException {
        checkOpen();
        return maxRows;
    }

    /**
     * Records a row limit that may exceed {@code int}. The value is kept exactly: clamping it would
     * silently answer {@code getLargeMaxRows} with a limit the caller never asked for.
     *
     * @param max the requested maximum, or {@code 0} for no limit
     * @throws SQLException if the statement is closed or {@code max} is negative
     */
    @Override
    public void setLargeMaxRows(long max) throws SQLException {
        checkOpen();
        if (max < 0) {
            throw new SQLException("Max rows must not be negative", SQLErrors.STATE_INVALID_PARAMETER);
        }
        maxRows = max;
        if (max > 0) {
            addWarning(new SQLWarning(
                    "maxRows of " + max + " cannot be enforced: FalkorDB returns a whole response at once, so the "
                            + "driver would have to discard rows the server already produced. Use a Cypher LIMIT "
                            + "clause instead",
                    SQLErrors.STATE_GENERAL));
        }
    }

    private void addWarning(SQLWarning warning) {
        if (warnings == null) {
            warnings = warning;
        } else {
            warnings.setNextWarning(warning);
        }
    }

    @Override
    public int getMaxFieldSize() throws SQLException {
        checkOpen();
        return 0;
    }

    @Override
    public void setMaxFieldSize(int max) throws SQLException {
        checkOpen();
        if (max != 0) {
            throw SQLErrors.unsupported("setMaxFieldSize(int) with a non-zero limit");
        }
    }

    @Override
    public void setEscapeProcessing(boolean enable) throws SQLException {
        checkOpen();
        if (enable) {
            throw SQLErrors.unsupported("JDBC escape processing");
        }
    }

    @Override
    public void setCursorName(String name) throws SQLException {
        throw SQLErrors.unsupported("setCursorName(String)");
    }

    @Override
    public void setFetchDirection(int direction) throws SQLException {
        checkOpen();
        if (direction != ResultSet.FETCH_FORWARD) {
            throw SQLErrors.unsupported("Fetch direction other than FETCH_FORWARD");
        }
    }

    @Override
    public int getFetchDirection() throws SQLException {
        checkOpen();
        return ResultSet.FETCH_FORWARD;
    }

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
    public int getResultSetConcurrency() throws SQLException {
        checkOpen();
        return ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public int getResultSetType() throws SQLException {
        checkOpen();
        return ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public int getResultSetHoldability() throws SQLException {
        checkOpen();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public java.sql.Connection getConnection() throws SQLException {
        checkOpen();
        return connection;
    }

    @Override
    public void setPoolable(boolean poolable) throws SQLException {
        checkOpen();
        this.poolable = poolable;
    }

    @Override
    public boolean isPoolable() throws SQLException {
        checkOpen();
        return poolable;
    }

    @Override
    public void closeOnCompletion() throws SQLException {
        checkOpen();
        closeOnCompletion = true;
    }

    @Override
    public boolean isCloseOnCompletion() throws SQLException {
        checkOpen();
        return closeOnCompletion;
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

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void close() throws SQLException {
        List<FalkorDBResultSet> open;
        synchronized (dependentsLock) {
            if (closed) {
                return;
            }
            // Set before closing the result sets: those calls come back through resultSetClosed(),
            // and the flag is what stops closeOnCompletion from recursing. Taking the flag and the
            // snapshot together is also what makes a concurrent close a no-op rather than a second
            // pass over the same result sets.
            closed = true;
            // Close every result set still open, not just the current one: getMoreResults(
            // KEEP_CURRENT_RESULT) and getGeneratedKeys() both hand out ones this field does not
            // hold.
            open = List.copyOf(dependents);
            dependents.clear();
        }
        for (FalkorDBResultSet dependent : open) {
            dependent.close();
        }
        resultSet = null;
        connection.forget(this);
    }

    private List<FalkorDBResultSet> snapshotDependents() {
        synchronized (dependentsLock) {
            return List.copyOf(dependents);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    private void closeCurrentResultSet() throws SQLException {
        if (resultSet != null) {
            resultSet.close();
        }
    }

    /**
     * Always throws. FalkorDB executes a query to completion on the server before replying, and the
     * client offers no way to interrupt one, so a silent no-op here would be a lie.
     *
     * @throws SQLException always
     */
    @Override
    public void cancel() throws SQLException {
        throw SQLErrors.unsupported("cancel()");
    }

    // ---------------------------------------------------------------- generated keys & batching

    @Override
    public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException {
        FalkorDBConnection.requireGeneratedKeysFlag(autoGeneratedKeys);
        return executeUpdate(sql);
    }

    @Override
    public int executeUpdate(String sql, int[] columnIndexes) throws SQLException {
        throw SQLErrors.unsupported("executeUpdate(String, int[])");
    }

    @Override
    public int executeUpdate(String sql, String[] columnNames) throws SQLException {
        throw SQLErrors.unsupported("executeUpdate(String, String[])");
    }

    @Override
    public boolean execute(String sql, int autoGeneratedKeys) throws SQLException {
        FalkorDBConnection.requireGeneratedKeysFlag(autoGeneratedKeys);
        return execute(sql);
    }

    @Override
    public boolean execute(String sql, int[] columnIndexes) throws SQLException {
        throw SQLErrors.unsupported("execute(String, int[])");
    }

    @Override
    public boolean execute(String sql, String[] columnNames) throws SQLException {
        throw SQLErrors.unsupported("execute(String, String[])");
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        throw SQLErrors.unsupported("Batch execution");
    }

    @Override
    public void clearBatch() throws SQLException {
        throw SQLErrors.unsupported("Batch execution");
    }

    @Override
    public int[] executeBatch() throws SQLException {
        throw SQLErrors.unsupported("Batch execution");
    }

    @Override
    public long[] executeLargeBatch() throws SQLException {
        throw SQLErrors.unsupported("Batch execution");
    }

    /** The graph this statement runs against, reported as the JDBC catalog of its result sets. */
    String catalogName() {
        return connection.graphName();
    }

    /**
     * Verifies the statement is usable.
     *
     * @throws SQLException if this statement or its connection is closed
     */
    final void checkOpen() throws SQLException {
        if (closed) {
            throw SQLErrors.closed("Statement");
        }
        connection.checkOpen();
    }
}
