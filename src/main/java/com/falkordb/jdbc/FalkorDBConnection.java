package com.falkordb.jdbc;

import java.io.IOException;
import java.sql.CallableStatement;
import java.sql.ClientInfoStatus;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLClientInfoException;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Executor;

import com.falkordb.FalkorDB;
import com.falkordb.Graph;
import com.falkordb.GraphContextGenerator;
import com.falkordb.jdbc.internal.ConnectionSettings;
import com.falkordb.jdbc.internal.CypherQuery;
import com.falkordb.jdbc.internal.FalkorType;
import com.falkordb.jdbc.internal.SQLErrors;

/**
 * A connection to one FalkorDB graph.
 *
 * <p>The connection owns a JFalkorDB {@link com.falkordb.Driver} and the Jedis connection pool
 * behind it, and closes both when it is closed. The graph named in the JDBC URL is exposed as the
 * JDBC catalog, so {@link #setCatalog(String)} switches graphs.
 *
 * <h2>Transactions</h2>
 *
 * <p>FalkorDB executes each query atomically but offers no multi-statement transaction this driver
 * can expose through JDBC, so the connection is permanently in auto-commit mode. {@link
 * #setAutoCommit(boolean) setAutoCommit(false)}, savepoints and non-{@code TRANSACTION_NONE}
 * isolation levels all raise {@link java.sql.SQLFeatureNotSupportedException} rather than pretending
 * to work.
 *
 * <h2>Read-only mode</h2>
 *
 * <p>{@link #setReadOnly(boolean) setReadOnly(true)} routes every subsequent query through
 * FalkorDB's read-only command, which a replica will serve and which rejects a statement that tries
 * to write.
 */
public final class FalkorDBConnection extends FalkorDBWrapper implements Connection {

    private final ConnectionSettings settings;
    private final com.falkordb.Driver driver;
    // Guarded by statementsLock. Collections.newSetFromMap(synchronizedMap(...)) would not do: the
    // set's internal mutex is the wrapped map, so synchronizing on the set itself would guard
    // iteration with a different monitor than the one add/remove take.
    private final Set<Statement> statements = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object statementsLock = new Object();
    private final Properties clientInfo = new Properties();

    private GraphContextGenerator graph;
    private String graphName;
    private boolean readOnly;
    private volatile boolean closed;
    private SQLWarning warnings;

    FalkorDBConnection(ConnectionSettings settings) throws SQLException {
        this.settings = settings;
        this.graphName = settings.graphName();
        this.readOnly = settings.readOnly();
        com.falkordb.Driver built = null;
        try {
            built = build(settings);
            this.driver = built;
            this.graph = driver.graph(graphName);
        } catch (RuntimeException e) {
            // The constructor is aborting, so this object never reaches the caller and nobody can
            // close it; the pool has to be released here or it leaks for every failed attempt.
            closeQuietly(built);
            throw SQLErrors.translate("Failed to connect to FalkorDB at " + settings.host() + ":" + settings.port(), e);
        }
        verifyReachable();
    }

    /**
     * Proves the server is reachable before handing the connection back.
     *
     * <p>JFalkorDB's pool connects lazily, so without this an unreachable host would only surface at
     * the first query. JDBC callers reasonably expect {@code DriverManager.getConnection} itself to
     * fail, and connection pools rely on that to reject a bad endpoint at checkout time.
     */
    private void verifyReachable() throws SQLException {
        try (redis.clients.jedis.Jedis probe = driver.getConnection()) {
            probe.ping();
        } catch (RuntimeException e) {
            // Mirror close(): the graph is released before the pool, so this failure path cannot
            // diverge from the normal one if JFalkorDB ever gives the graph handle its own state.
            closeQuietly(graph);
            closeQuietly();
            throw SQLErrors.connectionFailed(
                    "Failed to connect to FalkorDB at " + settings.host() + ":" + settings.port(), e);
        }
    }

    private void closeQuietly() {
        closeQuietly(driver);
    }

    private static void closeQuietly(com.falkordb.Driver toClose) {
        if (toClose == null) {
            return;
        }
        try {
            toClose.close();
        } catch (Exception ignored) {
            // the connection attempt already failed; nothing useful to report from cleanup
        }
    }

    private static void closeQuietly(GraphContextGenerator toClose) {
        if (toClose == null) {
            return;
        }
        try {
            toClose.close();
        } catch (Exception ignored) {
            // we are already unwinding from a failure that the caller will report
        }
    }

    private static com.falkordb.Driver build(ConnectionSettings settings) {
        FalkorDB.Builder builder = FalkorDB.builder().host(settings.host()).port(settings.port());
        if (settings.user().isPresent()) {
            builder.credentials(settings.user().get(), settings.password().orElse(""));
        } else if (settings.password().isPresent()) {
            builder.credentials(settings.password().get());
        }
        if (settings.ssl()) {
            builder.ssl(true);
        }
        settings.connectionTimeout().ifPresent(builder::connectionTimeout);
        settings.socketTimeout().ifPresent(builder::socketTimeout);
        settings.poolMaxTotal().ifPresent(builder::poolMaxTotal);
        settings.poolMaxIdle().ifPresent(builder::poolMaxIdle);
        settings.poolMaxWait().ifPresent(builder::poolMaxWait);
        return builder.build();
    }

    // ---------------------------------------------------------------- statements

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        return track(new FalkorDBStatement(this));
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
        requireForwardOnlyReadOnly(resultSetType, resultSetConcurrency);
        return createStatement();
    }

    @Override
    public Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability)
            throws SQLException {
        requireForwardOnlyReadOnly(resultSetType, resultSetConcurrency);
        requireCloseCursorsAtCommit(resultSetHoldability);
        return createStatement();
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkOpen();
        return track(new FalkorDBPreparedStatement(this, CypherQuery.translate(sql)));
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency)
            throws SQLException {
        requireForwardOnlyReadOnly(resultSetType, resultSetConcurrency);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        requireForwardOnlyReadOnly(resultSetType, resultSetConcurrency);
        requireCloseCursorsAtCommit(resultSetHoldability);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
        requireGeneratedKeysFlag(autoGeneratedKeys);
        return prepareStatement(sql);
    }

    @Override
    public PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
        throw SQLErrors.unsupported("prepareStatement(String, int[])");
    }

    @Override
    public PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
        throw SQLErrors.unsupported("prepareStatement(String, String[])");
    }

    @Override
    public CallableStatement prepareCall(String sql) throws SQLException {
        throw SQLErrors.unsupported("CallableStatement; invoke FalkorDB procedures with Cypher's CALL clause");
    }

    @Override
    public CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
        return prepareCall(sql);
    }

    @Override
    public CallableStatement prepareCall(
            String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
        return prepareCall(sql);
    }

    /**
     * Registers a statement so {@link #close()} can close it.
     *
     * <p>The closed check is repeated here under the lock. Without it, a {@code createStatement()}
     * that passed {@link #checkOpen()} just before a concurrent {@code close()} could register after
     * close() had already taken its snapshot, handing the caller a statement that nothing will ever
     * close.
     */
    private <T extends Statement> T track(T statement) throws SQLException {
        synchronized (statementsLock) {
            if (closed) {
                throw SQLErrors.closed("Connection");
            }
            statements.add(statement);
        }
        return statement;
    }

    void forget(Statement statement) {
        synchronized (statementsLock) {
            statements.remove(statement);
        }
    }

    private void requireForwardOnlyReadOnly(int resultSetType, int resultSetConcurrency) throws SQLException {
        checkOpen();
        if (resultSetType != ResultSet.TYPE_FORWARD_ONLY) {
            throw SQLErrors.unsupported("ResultSet type other than TYPE_FORWARD_ONLY");
        }
        if (resultSetConcurrency != ResultSet.CONCUR_READ_ONLY) {
            throw SQLErrors.unsupported("ResultSet concurrency other than CONCUR_READ_ONLY");
        }
    }

    /**
     * FalkorDB has no transactions to hold a cursor across, so only {@link
     * ResultSet#CLOSE_CURSORS_AT_COMMIT} can be honoured. Silently downgrading a request for {@link
     * ResultSet#HOLD_CURSORS_OVER_COMMIT} would be the kind of quiet no-op this driver avoids.
     */
    private void requireCloseCursorsAtCommit(int resultSetHoldability) throws SQLException {
        if (resultSetHoldability == ResultSet.HOLD_CURSORS_OVER_COMMIT) {
            throw SQLErrors.unsupported("HOLD_CURSORS_OVER_COMMIT");
        }
        if (resultSetHoldability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw new SQLException(
                    "Invalid result set holdability: " + resultSetHoldability, SQLErrors.STATE_INVALID_PARAMETER);
        }
    }

    /**
     * JDBC defines exactly two values here, so anything else is a programming error rather than a
     * request for a feature; reporting it beats treating it as {@code NO_GENERATED_KEYS}.
     */
    static void requireGeneratedKeysFlag(int autoGeneratedKeys) throws SQLException {
        if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
            throw SQLErrors.unsupported("RETURN_GENERATED_KEYS");
        }
        if (autoGeneratedKeys != Statement.NO_GENERATED_KEYS) {
            throw new SQLException(
                    "Invalid generated-keys flag: " + autoGeneratedKeys, SQLErrors.STATE_INVALID_PARAMETER);
        }
    }

    /**
     * Translates a statement into the Cypher that would actually be sent, rewriting {@code ?}
     * placeholders into {@code $p1}, {@code $p2}, … There is no SQL-to-Cypher translation, so a
     * statement without placeholders is returned unchanged.
     *
     * @param sql the statement text
     * @return the Cypher that would be sent to FalkorDB
     * @throws SQLException if the connection is closed or the statement cannot be translated
     */
    @Override
    public String nativeSQL(String sql) throws SQLException {
        checkOpen();
        return CypherQuery.translate(sql).cypher();
    }

    // ---------------------------------------------------------------- transactions

    @Override
    public boolean getAutoCommit() throws SQLException {
        checkOpen();
        return true;
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        checkOpen();
        if (!autoCommit) {
            throw SQLErrors.unsupported("Disabling auto-commit; every FalkorDB query commits on its own");
        }
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        throw new SQLException(
                "commit() is not valid while auto-commit is enabled, and this driver is always in auto-commit mode",
                SQLErrors.STATE_GENERAL);
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        throw new SQLException(
                "rollback() is not valid while auto-commit is enabled, and this driver is always in auto-commit mode",
                SQLErrors.STATE_GENERAL);
    }

    @Override
    public void rollback(Savepoint savepoint) throws SQLException {
        throw SQLErrors.unsupported("Savepoints");
    }

    @Override
    public Savepoint setSavepoint() throws SQLException {
        throw SQLErrors.unsupported("Savepoints");
    }

    @Override
    public Savepoint setSavepoint(String name) throws SQLException {
        throw SQLErrors.unsupported("Savepoints");
    }

    @Override
    public void releaseSavepoint(Savepoint savepoint) throws SQLException {
        throw SQLErrors.unsupported("Savepoints");
    }

    @Override
    public int getTransactionIsolation() throws SQLException {
        checkOpen();
        return TRANSACTION_NONE;
    }

    @Override
    public void setTransactionIsolation(int level) throws SQLException {
        checkOpen();
        if (level != TRANSACTION_NONE) {
            throw SQLErrors.unsupported("Transaction isolation level " + level + "; FalkorDB reports TRANSACTION_NONE");
        }
    }

    @Override
    public int getHoldability() throws SQLException {
        checkOpen();
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public void setHoldability(int holdability) throws SQLException {
        checkOpen();
        if (holdability != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
            throw SQLErrors.unsupported("Holdability other than CLOSE_CURSORS_AT_COMMIT");
        }
    }

    // ---------------------------------------------------------------- session state

    @Override
    public boolean isReadOnly() throws SQLException {
        checkOpen();
        return readOnly;
    }

    /**
     * Puts the connection into read-only mode, so subsequent statements use FalkorDB's read-only
     * query command. A replica can serve those, and the server rejects any that would write.
     *
     * @param readOnly whether to run subsequent queries read-only
     * @throws SQLException if the connection is closed
     */
    @Override
    public void setReadOnly(boolean readOnly) throws SQLException {
        checkOpen();
        this.readOnly = readOnly;
    }

    @Override
    public String getCatalog() throws SQLException {
        checkOpen();
        return graphName;
    }

    /**
     * Rebinds the connection to a different graph. FalkorDB models each graph as an independent key,
     * so this is the JDBC equivalent of switching database.
     *
     * @param catalog the graph name; a null or blank name leaves the connection unchanged
     * @throws SQLException if the connection is closed
     */
    @Override
    public void setCatalog(String catalog) throws SQLException {
        checkOpen();
        if (catalog == null || catalog.isBlank() || catalog.equals(graphName)) {
            return;
        }
        GraphContextGenerator replacement;
        try {
            replacement = driver.graph(catalog);
        } catch (RuntimeException e) {
            throw SQLErrors.translate("Failed to switch to graph \"" + catalog + "\"", e);
        }
        // Closing a JFalkorDB graph only drops its schema cache; the driver and its pool are untouched.
        try {
            graph.close();
        } catch (RuntimeException e) {
            // Leaving the connection on a context we failed to close would be worse than failing:
            // release the replacement and report, so the caller knows the catalog did not change.
            closeQuietly(replacement);
            throw SQLErrors.translate("Failed to switch to graph \"" + catalog + "\"", e);
        }
        graph = replacement;
        graphName = catalog;
    }

    @Override
    public String getSchema() throws SQLException {
        checkOpen();
        return null;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        checkOpen();
        if (schema != null && !schema.isBlank()) {
            throw SQLErrors.unsupported("Schemas; FalkorDB graphs are exposed as catalogs instead");
        }
    }

    @Override
    public Properties getClientInfo() throws SQLException {
        checkOpen();
        Properties copy = new Properties();
        copy.putAll(clientInfo);
        return copy;
    }

    @Override
    public String getClientInfo(String name) throws SQLException {
        checkOpen();
        return clientInfo.getProperty(name);
    }

    @Override
    public void setClientInfo(String name, String value) throws SQLClientInfoException {
        requireOpenForClientInfo(name == null ? Set.of() : Set.of(name));
        if (name == null) {
            throw new SQLClientInfoException("Client info name must not be null", Map.of());
        }
        if (value == null) {
            clientInfo.remove(name);
        } else {
            clientInfo.setProperty(name, value);
        }
    }

    @Override
    public void setClientInfo(Properties properties) throws SQLClientInfoException {
        requireOpenForClientInfo(properties == null ? Set.of() : properties.stringPropertyNames());
        clientInfo.clear();
        if (properties != null) {
            clientInfo.putAll(properties);
        }
    }

    /**
     * Refuses a client-info update on a closed connection.
     *
     * <p>JDBC requires this to be reported as a {@link SQLClientInfoException} carrying the status of
     * every property that could not be set, so a pool that configures a connection after closing it
     * sees a failure rather than a silent write.
     */
    private void requireOpenForClientInfo(Set<String> names) throws SQLClientInfoException {
        if (!closed) {
            return;
        }
        Map<String, ClientInfoStatus> failures = new java.util.HashMap<>();
        for (String name : names) {
            failures.put(name, ClientInfoStatus.REASON_UNKNOWN);
        }
        throw new SQLClientInfoException("Connection is closed", SQLErrors.STATE_OBJECT_CLOSED, failures);
    }

    @Override
    public Map<String, Class<?>> getTypeMap() throws SQLException {
        checkOpen();
        return Map.of();
    }

    @Override
    public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
        checkOpen();
        if (map != null && !map.isEmpty()) {
            throw SQLErrors.unsupported("Custom type maps");
        }
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

    // ---------------------------------------------------------------- metadata & lifecycle

    @Override
    public DatabaseMetaData getMetaData() throws SQLException {
        checkOpen();
        return new FalkorDBDatabaseMetaData(this);
    }

    @Override
    public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException {
        checkOpen();
        FalkorType declared = FalkorType.byName(typeName);
        if (typeName != null && declared == null) {
            throw new SQLException(
                    "FalkorDB cannot store elements of type \"" + typeName + "\"", SQLErrors.STATE_INVALID_PARAMETER);
        }
        return new FalkorDBArray(elements == null ? List.of() : java.util.Arrays.asList(elements), declared);
    }

    @Override
    public java.sql.Blob createBlob() throws SQLException {
        throw SQLErrors.unsupported("createBlob()");
    }

    @Override
    public java.sql.Clob createClob() throws SQLException {
        throw SQLErrors.unsupported("createClob()");
    }

    @Override
    public java.sql.NClob createNClob() throws SQLException {
        throw SQLErrors.unsupported("createNClob()");
    }

    @Override
    public java.sql.SQLXML createSQLXML() throws SQLException {
        throw SQLErrors.unsupported("createSQLXML()");
    }

    @Override
    public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException {
        throw SQLErrors.unsupported("createStruct(String, Object[])");
    }

    @Override
    public boolean isValid(int timeout) throws SQLException {
        if (timeout < 0) {
            throw new SQLException("Validation timeout must not be negative", SQLErrors.STATE_INVALID_PARAMETER);
        }
        if (closed) {
            return false;
        }
        try {
            long millis = timeout == 0 ? 0L : timeout * 1000L;
            com.falkordb.ResultSet probe =
                    millis > 0 ? graph.readOnlyQuery("RETURN 1", millis) : graph.readOnlyQuery("RETURN 1");
            return probe.size() == 1;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public void close() throws SQLException {
        if (closed) {
            return;
        }
        // Publish the closed flag and take the snapshot together, so a statement can never be
        // registered between the two and escape being closed.
        Collection<Statement> open;
        synchronized (statementsLock) {
            // Re-check under the lock: two callers can pass the unlocked check above, and closing
            // the graph context and the pool twice is not safe.
            if (closed) {
                return;
            }
            closed = true;
            open = List.copyOf(statements);
            statements.clear();
        }
        for (Statement statement : open) {
            try {
                statement.close();
            } catch (SQLException e) {
                warnings = merge(warnings, new SQLWarning("Failed to close a statement", SQLErrors.STATE_GENERAL, e));
            }
        }

        // Always release the pool, even if closing the graph context fails, and report the first
        // failure with any later one attached rather than letting the last one win.
        SQLException failure = null;
        try {
            graph.close();
        } catch (RuntimeException e) {
            failure = new SQLException("Failed to close the FalkorDB graph context", SQLErrors.STATE_GENERAL, e);
        }
        try {
            driver.close();
        } catch (IOException | RuntimeException e) {
            SQLException poolFailure =
                    new SQLException("Failed to close the FalkorDB connection pool", SQLErrors.STATE_GENERAL, e);
            if (failure == null) {
                failure = poolFailure;
            } else {
                failure.setNextException(poolFailure);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private Collection<Statement> openStatements() {
        synchronized (statementsLock) {
            return List.copyOf(statements);
        }
    }

    private static SQLWarning merge(SQLWarning existing, SQLWarning addition) {
        if (existing == null) {
            return addition;
        }
        existing.setNextWarning(addition);
        return existing;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void abort(Executor executor) throws SQLException {
        if (executor == null) {
            throw new SQLException("Executor must not be null", SQLErrors.STATE_INVALID_PARAMETER);
        }
        executor.execute(() -> {
            try {
                close();
            } catch (SQLException ignored) {
                // abort() is best-effort by contract; the connection is unusable either way.
            }
        });
    }

    @Override
    public void setNetworkTimeout(Executor executor, int milliseconds) throws SQLException {
        throw SQLErrors.unsupported("setNetworkTimeout(Executor, int); set socketTimeout on the JDBC URL instead");
    }

    @Override
    public int getNetworkTimeout() throws SQLException {
        checkOpen();
        return settings.socketTimeout()
                .map(timeout -> (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE))
                .orElse(0);
    }

    @Override
    public String toString() {
        return "FalkorDBConnection[" + settings.toRedactedUrl() + "]";
    }

    // ---------------------------------------------------------------- internals

    /** The JFalkorDB graph handle backing this connection. */
    Graph graph() {
        return graph;
    }

    /** The JFalkorDB driver, used by {@link FalkorDBDatabaseMetaData} to query server-wide state. */
    com.falkordb.Driver falkorDriver() {
        return driver;
    }

    /** The resolved settings this connection was opened with. */
    ConnectionSettings settings() {
        return settings;
    }

    /** The graph this connection is currently bound to. */
    String graphName() {
        return graphName;
    }

    /** The statement-level query timeout implied by the connection's {@code queryTimeout} property. */
    int defaultQueryTimeoutSeconds() {
        if (settings.queryTimeoutMillis().isEmpty()) {
            return 0;
        }
        long millis = settings.queryTimeoutMillis().getAsLong();
        // Round up, so a sub-second setting becomes a one-second limit rather than "no limit".
        // Computed as a quotient plus a remainder test; millis + 999 would overflow near Long.MAX_VALUE
        // and wrap to a negative "no limit".
        long seconds = millis / 1000L + (millis % 1000L == 0L ? 0L : 1L);
        return (int) Math.min(seconds, Integer.MAX_VALUE);
    }

    /**
     * Verifies the connection is usable.
     *
     * @throws SQLException if the connection is closed
     */
    void checkOpen() throws SQLException {
        if (closed) {
            throw SQLErrors.closed("Connection");
        }
    }
}
