/**
 * A JDBC 4.3 driver for <a href="https://www.falkordb.com">FalkorDB</a>, built on the official
 * JFalkorDB client.
 *
 * <p>Cypher is the driver's native query language: a statement is sent to FalkorDB as written. There
 * is no SQL-to-Cypher translation, so {@code SELECT * FROM users} is a syntax error, not a query.
 *
 * <h2>Getting started</h2>
 *
 * <p>The driver registers itself through {@code META-INF/services/java.sql.Driver}, so nothing needs
 * to be loaded explicitly:
 *
 * <pre>{@code
 * String url = "jdbc:falkordb://localhost:6379/social";
 * try (Connection connection = DriverManager.getConnection(url);
 *      Statement statement = connection.createStatement();
 *      ResultSet rs = statement.executeQuery("MATCH (p:Person) RETURN p.name AS name, p.age AS age")) {
 *     while (rs.next()) {
 *         System.out.println(rs.getString("name") + " is " + rs.getInt("age"));
 *     }
 * }
 * }</pre>
 *
 * <h2>URL format</h2>
 *
 * <pre>{@code
 * jdbc:falkordb://[user[:password]@]host[:port]/<graph>[?key=value&...]
 * jdbc:falkordb+ssl://...      (TLS; +s and falkordbs are accepted too)
 * }</pre>
 *
 * <p>The path segment names the graph, which the driver exposes as the JDBC catalog. See {@link
 * com.falkordb.jdbc.FalkorDBDriver} for the supported query parameters.
 *
 * <h2>How graph values reach JDBC</h2>
 *
 * <p>FalkorDB's scalars map onto the obvious JDBC types — string to {@code VARCHAR}, its 64-bit
 * integer to {@code BIGINT}, double to {@code DOUBLE}, boolean to {@code BOOLEAN}, list to {@link
 * java.sql.Array}, and the temporal types to their {@code java.time} equivalents. Graph-shaped
 * values that SQL has no notion of — nodes, relationships, paths, points and maps — arrive as {@code
 * JAVA_OBJECT}: {@link java.sql.ResultSet#getObject(int)} hands back the JFalkorDB entity itself,
 * while {@link java.sql.ResultSet#getString(int)} renders it in Cypher-like form so that generic
 * tools have something useful to show.
 *
 * <h2>What this driver does not do</h2>
 *
 * <p>FalkorDB has no multi-statement transactions, so connections are permanently in auto-commit
 * mode and savepoints, isolation levels and rollback raise {@link
 * java.sql.SQLFeatureNotSupportedException}. Result sets are forward-only and read-only. Batch
 * execution, generated keys, {@code CallableStatement} and LOBs are likewise unsupported. Every such
 * operation throws rather than silently doing nothing.
 */
package com.falkordb.jdbc;
