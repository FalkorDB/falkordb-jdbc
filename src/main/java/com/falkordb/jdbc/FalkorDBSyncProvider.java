package com.falkordb.jdbc;

import java.io.InputStream;
import java.io.Reader;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import javax.sql.RowSet;
import javax.sql.RowSetInternal;
import javax.sql.RowSetReader;
import javax.sql.RowSetWriter;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.spi.SyncProvider;
import javax.sql.rowset.spi.SyncProviderException;

import com.falkordb.jdbc.internal.DriverVersion;
import com.falkordb.jdbc.internal.SQLErrors;

/**
 * Fills a disconnected {@link CachedRowSet} from a Cypher query, and refuses to write one back.
 *
 * <p>A row set reads its data through the {@link RowSetReader} its {@code SyncProvider} supplies. The
 * reference provider that ships with the JDK decides whether a command is a query by looking for the
 * word {@code select} in it, which no Cypher statement contains, so it would run every graph query as
 * an update and return nothing. This provider runs the command as a query, always — it is the only
 * kind of statement a row set can be populated from.
 *
 * <p>Write-back is not supported. The reference provider builds {@code UPDATE}, {@code INSERT} and
 * {@code DELETE} statements against the table a column came from, and a Cypher projection belongs to
 * no table, so {@link CachedRowSet#acceptChanges()} raises a {@link SyncProviderException} rather
 * than appearing to save changes that would never reach the graph. Edits made to a row set are
 * in-memory only; change the graph with a Cypher statement instead. This is why the provider reports
 * {@link #GRADE_NONE} and {@link #NONUPDATABLE_VIEW_SYNC}.
 *
 * <p>Row sets from {@link FalkorDBRowSetFactory} already use this provider. To install it on a row
 * set obtained elsewhere, register it first — {@link javax.sql.rowset.spi.SyncFactory#getInstance}
 * returns the JDK's provider for an unregistered identifier:
 *
 * <pre>{@code
 * SyncFactory.registerProvider(FalkorDBSyncProvider.ID);
 * rowSet.setSyncProvider(FalkorDBSyncProvider.ID);
 * }</pre>
 */
public final class FalkorDBSyncProvider extends SyncProvider implements Serializable {

    /**
     * The identifier this provider is registered and looked up under. It is the provider's class
     * name, because {@link javax.sql.rowset.spi.SyncFactory} instantiates a provider by loading its
     * identifier as a class.
     */
    public static final String ID = "com.falkordb.jdbc.FalkorDBSyncProvider";

    private static final long serialVersionUID = 1L;

    /** Required by {@link javax.sql.rowset.spi.SyncFactory}, which instantiates providers by name. */
    public FalkorDBSyncProvider() {}

    @Override
    public String getProviderID() {
        return ID;
    }

    @Override
    public RowSetReader getRowSetReader() {
        return new CypherReader();
    }

    @Override
    public RowSetWriter getRowSetWriter() {
        return new ReadOnlyWriter();
    }

    /**
     * Always {@link #GRADE_NONE}: this provider never writes a row set back, so it has no conflicts
     * to detect.
     *
     * @return {@link #GRADE_NONE}
     */
    @Override
    public int getProviderGrade() {
        return GRADE_NONE;
    }

    @Override
    public int supportsUpdatableView() {
        return NONUPDATABLE_VIEW_SYNC;
    }

    @Override
    public int getDataSourceLock() {
        return DATASOURCE_NO_LOCK;
    }

    /**
     * Accepts only {@link #DATASOURCE_NO_LOCK}. Nothing is written back, so there is nothing to lock,
     * and a request for any other level is rejected rather than quietly ignored.
     *
     * @param datasource_lock the requested lock level
     * @throws SyncProviderException if a lock level other than {@link #DATASOURCE_NO_LOCK} is asked
     *     for
     */
    @Override
    public void setDataSourceLock(int datasource_lock) throws SyncProviderException {
        if (datasource_lock != DATASOURCE_NO_LOCK) {
            throw new SyncProviderException(
                    "The FalkorDB row set provider does not lock the data source; only DATASOURCE_NO_LOCK is supported");
        }
    }

    @Override
    public String getVersion() {
        return DriverVersion.VERSION;
    }

    @Override
    public String getVendor() {
        return "FalkorDB";
    }

    // No toString() override on purpose: the JDK's WebRowSetXmlWriter writes the provider's name by
    // cutting a provider's toString() at the '@' of the default Object rendering, and throws
    // StringIndexOutOfBoundsException when there is none. WebRowSet.writeXml has to keep working.

    /**
     * Populates a row set by running its command as a Cypher query.
     *
     * <p>The connection comes from {@link CachedRowSet#execute(Connection)} if one was passed, and
     * from {@link DriverManager} and the row set's URL otherwise. A connection this reader opened is
     * closed again before it returns, which is what makes the row set disconnected.
     */
    private static final class CypherReader implements RowSetReader, Serializable {

        private static final long serialVersionUID = 1L;

        @Override
        public void readData(RowSetInternal caller) throws SQLException {
            if (!(caller instanceof CachedRowSet rowSet)) {
                throw new SQLException(
                        "The FalkorDB row set provider can only read into a CachedRowSet, but was given "
                                + caller.getClass().getName(),
                        SQLErrors.STATE_NOT_SUPPORTED);
            }
            String command = rowSet.getCommand();
            if (command == null || command.isBlank()) {
                throw new SQLException(
                        "The row set has no command; call setCommand(cypher) before execute()",
                        SQLErrors.STATE_GENERAL);
            }
            // Read before close(), which resets the row set's properties along with its rows.
            int queryTimeout = rowSet.getQueryTimeout();
            if (rowSet.size() > 0) {
                // populate() appends, so a re-execute has to start from an empty row set.
                rowSet.close();
            }
            Connection supplied = caller.getConnection();
            Connection connection = supplied != null ? supplied : open(rowSet);
            try (PreparedStatement statement = connection.prepareStatement(command)) {
                if (queryTimeout > 0) {
                    // Only when asked for: zero means "no limit" to a statement, which would
                    // discard the default the connection was opened with.
                    statement.setQueryTimeout(queryTimeout);
                }
                bind(statement, caller.getParams());
                try (ResultSet results = statement.executeQuery()) {
                    rowSet.populate(results);
                }
            } finally {
                if (supplied == null) {
                    connection.close();
                }
            }
        }

        private static Connection open(RowSet rowSet) throws SQLException {
            String url = rowSet.getUrl();
            if (url == null || url.isBlank()) {
                String hint = rowSet.getDataSourceName() == null
                        ? ""
                        : " A JNDI dataSourceName is not supported by this provider.";
                throw new SQLException(
                        "The row set has no URL; call setUrl(\"jdbc:falkordb://...\") or execute(Connection)." + hint,
                        SQLErrors.STATE_CONNECTION_REJECTED);
            }
            String user = rowSet.getUsername();
            return user == null
                    ? DriverManager.getConnection(url)
                    : DriverManager.getConnection(url, user, rowSet.getPassword());
        }

        /**
         * Binds the values a row set collected through its own setters.
         *
         * <p>{@link javax.sql.rowset.BaseRowSet} stores a plain value for the two-argument setters
         * and an array for the ones that carry extra information — a SQL type for {@code setNull}, a
         * calendar for a temporal, a target type and scale for {@code setObject}.
         */
        private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
            for (int i = 0; i < parameters.length; i++) {
                int index = i + 1;
                if (parameters[i] instanceof Object[] details) {
                    bindDetailed(statement, index, details);
                } else {
                    statement.setObject(index, parameters[i]);
                }
            }
        }

        private static void bindDetailed(PreparedStatement statement, int index, Object[] details) throws SQLException {
            Object value = details.length > 0 ? details[0] : null;
            if (value instanceof InputStream || value instanceof Reader) {
                throw SQLErrors.unsupported("Stream row set parameters");
            }
            if (details.length == 2 && details[1] instanceof Calendar calendar) {
                bindTemporal(statement, index, value, calendar);
                return;
            }
            if (details.length == 2 && details[1] instanceof Integer type) {
                if (value == null) {
                    statement.setNull(index, type);
                } else {
                    statement.setObject(index, value, type);
                }
                return;
            }
            if (details.length == 3 && details[1] instanceof Integer type) {
                if (value == null && details[2] instanceof String typeName) {
                    statement.setNull(index, type, typeName);
                    return;
                }
                if (details[2] instanceof Integer scale) {
                    statement.setObject(index, value, type, scale);
                    return;
                }
            }
            throw new SQLException(
                    "Row set parameter " + index + " was set in a form this driver cannot bind",
                    SQLErrors.STATE_INVALID_PARAMETER);
        }

        private static void bindTemporal(PreparedStatement statement, int index, Object value, Calendar calendar)
                throws SQLException {
            if (value instanceof Timestamp timestamp) {
                statement.setTimestamp(index, timestamp, calendar);
            } else if (value instanceof Time time) {
                statement.setTime(index, time, calendar);
            } else if (value instanceof Date date) {
                statement.setDate(index, date, calendar);
            } else {
                throw new SQLException(
                        "Row set parameter " + index + " was given a calendar but is not a date, time or timestamp",
                        SQLErrors.STATE_INVALID_PARAMETER);
            }
        }
    }

    /** Rejects every attempt to write a row set back to the graph. */
    private static final class ReadOnlyWriter implements RowSetWriter, Serializable {

        private static final long serialVersionUID = 1L;

        @Override
        public boolean writeData(RowSetInternal caller) throws SyncProviderException {
            throw new SyncProviderException(
                    "A FalkorDB row set is read-only: its rows come from a Cypher projection, "
                            + "which belongs to no table and cannot be written back. Change the graph with a Cypher statement.");
        }
    }
}
