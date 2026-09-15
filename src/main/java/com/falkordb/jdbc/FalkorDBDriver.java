package com.falkordb.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import com.falkordb.jdbc.internal.ConnectionSettings;
import com.falkordb.jdbc.internal.DriverVersion;
import com.falkordb.jdbc.internal.SQLErrors;

/**
 * The JDBC driver for FalkorDB.
 *
 * <p>The driver registers itself with {@link DriverManager} through {@code
 * META-INF/services/java.sql.Driver}, so no {@code Class.forName} call is needed:
 *
 * <pre>{@code
 * try (Connection connection = DriverManager.getConnection("jdbc:falkordb://localhost:6379/social");
 *      Statement statement = connection.createStatement();
 *      ResultSet rs = statement.executeQuery("MATCH (p:Person) RETURN p.name AS name")) {
 *     while (rs.next()) {
 *         System.out.println(rs.getString("name"));
 *     }
 * }
 * }</pre>
 *
 * <p>Accepted URLs take the form
 *
 * <pre>{@code
 * jdbc:falkordb://[user[:password]@]host[:port]/<graphName>[?key=value&...]
 * jdbc:falkordb+ssl://[user[:password]@]host[:port]/<graphName>[?key=value&...]
 * }</pre>
 *
 * <p>Cypher is the native query language: statement text is sent to FalkorDB as written, apart from
 * rewriting JDBC {@code ?} placeholders into Cypher named parameters. There is no SQL-to-Cypher
 * translation layer.
 *
 * @see ConnectionSettings for the full list of supported connection properties
 */
public final class FalkorDBDriver extends FalkorDBWrapper implements java.sql.Driver {

    private static final Logger PARENT_LOGGER = Logger.getLogger("com.falkordb.jdbc");

    static {
        try {
            DriverManager.registerDriver(new FalkorDBDriver());
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates a driver instance. Applications normally use the instance registered with {@link
     * DriverManager} rather than constructing one, but the public constructor keeps the class usable
     * as a plain {@code javax.sql.DataSource}-style component and satisfies the {@code
     * ServiceLoader} contract.
     */
    public FalkorDBDriver() {
        // Required by ServiceLoader and by tools that instantiate the driver reflectively.
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        // The JDBC contract is to return null - not to throw - for a URL belonging to another driver,
        // so DriverManager can go on to offer it to the next registered driver.
        if (!acceptsURL(url)) {
            return null;
        }
        return new FalkorDBConnection(ConnectionSettings.parse(url, info));
    }

    @Override
    public boolean acceptsURL(String url) {
        return ConnectionSettings.acceptsUrl(url);
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        Properties supplied = info == null ? new Properties() : info;
        List<DriverPropertyInfo> result = new ArrayList<>(ConnectionSettings.KNOWN_PROPERTIES.size());
        for (ConnectionSettings.Known known : ConnectionSettings.KNOWN_PROPERTIES) {
            DriverPropertyInfo property = new DriverPropertyInfo(known.name(), supplied.getProperty(known.name()));
            property.description = known.description();
            property.required = false;
            property.choices =
                    known.choices().length == 0 ? null : known.choices().clone();
            result.add(property);
        }
        return result.toArray(new DriverPropertyInfo[0]);
    }

    @Override
    public int getMajorVersion() {
        return DriverVersion.MAJOR;
    }

    @Override
    public int getMinorVersion() {
        return DriverVersion.MINOR;
    }

    /**
     * Always {@code false}. FalkorDB speaks Cypher, not SQL, so the driver cannot pass the JDBC
     * compliance test suite and must not claim to.
     *
     * @return {@code false}
     */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        return PARENT_LOGGER;
    }

    /**
     * Translates a statement exactly as {@link Connection#nativeSQL(String)} would, without needing a
     * live connection. Useful for inspecting how {@code ?} placeholders are rewritten.
     *
     * @param statement the Cypher text
     * @return the Cypher that would be sent to FalkorDB
     * @throws SQLException if the statement cannot be translated
     */
    public static String translate(String statement) throws SQLException {
        return com.falkordb.jdbc.internal.CypherQuery.translate(statement).cypher();
    }

    /**
     * Reports this driver's version.
     *
     * @return the full version string
     */
    public static String version() {
        return DriverVersion.VERSION;
    }

    static SQLFeatureNotSupportedException unsupported(String feature) {
        return SQLErrors.unsupported(feature);
    }
}
