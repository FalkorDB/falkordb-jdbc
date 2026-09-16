# FalkorDB JDBC Driver

A JDBC 4.3 driver for [FalkorDB](https://www.falkordb.com), so you can reach a FalkorDB graph from
any JDBC-compatible tool or application — DBeaver, JetBrains DataGrip, Apache Spark, Kafka Connect,
JasperReports, or your own JVM code.

Cypher is the query language. You write Cypher, the driver hands it to FalkorDB, and the results
come back as an ordinary `java.sql.ResultSet` with graph values mapped onto sensible SQL types. It
is a thin, honest layer over the official [JFalkorDB](https://github.com/FalkorDB/JFalkorDB) client,
not a SQL emulation: anything JDBC asks for that FalkorDB cannot do throws
`SQLFeatureNotSupportedException` rather than quietly doing nothing.

The design follows the [Neo4j JDBC Driver v6](https://github.com/neo4j/neo4j-jdbc) — Cypher as the
native language, a graph-flavoured `DatabaseMetaData`, and translation of JDBC's `?` placeholders
into native named parameters — but the implementation is entirely independent.

## Requirements

| | |
| --- | --- |
| Java | 17 or newer |
| FalkorDB | 4.x or newer |
| JDBC | 4.3 |

## Installation

```xml
<dependency>
  <groupId>com.falkordb</groupId>
  <artifactId>falkordb-jdbc</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Gradle:

```kotlin
implementation("com.falkordb:falkordb-jdbc:0.1.0-SNAPSHOT")
```

The driver registers itself through `META-INF/services/java.sql.Driver`, so there is nothing to
configure — `DriverManager` finds it on the classpath. `Class.forName("com.falkordb.jdbc.FalkorDBDriver")`
still works if a tool insists on naming a driver class.

## Quickstart

```java
import java.sql.*;

try (Connection connection = DriverManager.getConnection("jdbc:falkordb://localhost:6379/social");
     Statement statement = connection.createStatement();
     ResultSet results = statement.executeQuery("MATCH (p:Person) RETURN p.name AS name, p.age AS age")) {

    while (results.next()) {
        System.out.println(results.getString("name") + " is " + results.getInt("age"));
    }
}
```

Writes report an update count derived from the query statistics:

```java
try (Statement statement = connection.createStatement()) {
    int changed = statement.executeUpdate("CREATE (:Person {name: 'Alice', age: 34})");
    // 3: one node created plus two properties set
}
```

### Parameters

Use a `PreparedStatement` and let the driver bind values. Positional `?` placeholders are rewritten
to FalkorDB's native named parameters (`$p1`, `$p2`, …) and sent alongside the query — values are
**never** interpolated into the Cypher text, so a query cannot be subverted by its own data.

```java
try (PreparedStatement statement =
        connection.prepareStatement("MATCH (p:Person) WHERE p.age > ? RETURN p.name AS name")) {
    statement.setInt(1, 30);

    try (ResultSet results = statement.executeQuery()) {
        while (results.next()) {
            System.out.println(results.getString("name"));
        }
    }
}
```

Cypher that already uses named parameters is passed through untouched. Bind those by name through
the driver's own interface:

```java
try (PreparedStatement statement = connection.prepareStatement("MATCH (p:Person {name: $name}) RETURN p")) {
    statement.unwrap(FalkorDBPreparedStatement.class).setNamedObject("name", "Alice");
    // ...
}
```

`Connection.nativeSQL(String)` shows what the server will actually receive.

Rewriting happens only for a `PreparedStatement`, because that is the only place JDBC gives `?` its
bind-marker meaning. Text handed to a plain `Statement` reaches FalkorDB exactly as written.

### Graph values

Nodes, relationships, paths and points come back as the JFalkorDB types, with a readable
`getString()` rendering for tools that only display text:

```java
try (ResultSet results = statement.executeQuery("MATCH (p:Person) RETURN p")) {
    while (results.next()) {
        Node node = results.getObject("p", Node.class);
        System.out.println(node.getProperty("name").getValue());
        System.out.println(results.getString("p")); // (:Person {name: "Alice", age: 34})
    }
}
```

## Row sets

`FalkorDBRowSetFactory` hands out the disconnected `javax.sql.RowSet` implementations, wired to
`FalkorDBSyncProvider` so that a row set's command is run as a Cypher query:

```java
RowSetFactory factory = new FalkorDBRowSetFactory();

CachedRowSet rowSet = factory.createCachedRowSet();
rowSet.setUrl("jdbc:falkordb://localhost:6379/social");
rowSet.setCommand("MATCH (p:Person) WHERE p.age > ? RETURN p.name AS name");
rowSet.setInt(1, 30);
rowSet.execute();              // or execute(connection), which leaves the connection open

while (rowSet.next()) {
    System.out.println(rowSet.getString("name"));
}
```

A row set can equally snapshot a result the driver has already produced, with
`populate(ResultSet)`; that needs no URL or command, and the rows outlive the connection they came
from.

| Row set | Notes |
| --- | --- |
| `CachedRowSet` | Scrollable, disconnected copy of a query's rows |
| `WebRowSet` | A `CachedRowSet` that reads and writes itself as XML |
| `FilteredRowSet` | A `CachedRowSet` narrowed by an in-memory `Predicate` |
| `JoinRowSet` | Joins row sets in memory; the reference implementation decides the row order |
| `JdbcRowSet` | **Not supported** — it stays connected and needs a scrollable, updatable `ResultSet` |

The JDK's own provider decides whether a command is a query by looking for the word `select` in it,
which no Cypher statement contains, so a row set that kept it would run every graph query as an
update and come back empty. `FalkorDBRowSetFactory` installs `FalkorDBSyncProvider` instead, and
verifies afterwards that it took — `SyncFactory` answers a lookup for an unregistered provider with
the default one rather than failing. A row set obtained elsewhere can be wired up by hand:

```java
SyncFactory.registerProvider(FalkorDBSyncProvider.ID);
rowSet.setSyncProvider(FalkorDBSyncProvider.ID);
```

Four limitations are worth knowing:

- **Row sets are read-only.** Write-back builds `UPDATE`, `INSERT` and `DELETE` statements against
  the table a column came from, and a Cypher projection belongs to no table, so `acceptChanges()`
  throws a `SyncProviderException` instead of appearing to save. Edits stay in memory; change the
  graph with a Cypher statement.
- **The command must be a read query.** A row set is populated from rows, so a command that returns
  none fails the same way `Statement.executeQuery` does on a write.
- **`setPageSize` is not supported.** Paging reaches past the provider into the JDK's own reader.
- **Temporal columns are cached as `java.time` values**, the ones `ResultSet.getObject` returns, so
  read them with `getObject` rather than `getDate`, `getTime` or `getTimestamp`.

## Connection URL

```
jdbc:falkordb://[user[:password]@]host[:port]/<graph>[?key=value&...]
```

| Part | Default | Notes |
| --- | --- | --- |
| `host` | `localhost` | |
| `port` | `6379` | |
| `graph` | *required* | The FalkorDB graph name; JDBC treats it as the catalog |
| `user` / `password` | none | See precedence below |

TLS is available either as a scheme — `jdbc:falkordb+ssl://`, `jdbc:falkordb+s://` or
`jdbc:falkordbs://` — or as `?ssl=true`.

### Connection properties

Every property below can be given as a URL query parameter or in the `Properties` passed to
`DriverManager.getConnection`.

An unrecognised **query parameter** is rejected, so a typo in a URL fails loudly instead of quietly
changing what you connect as — `?passwrod=secret` would otherwise connect with no password at all.
An unrecognised **property** is ignored, because pools, BI tools and application servers routinely
add keys of their own to the `Properties` they pass down.

| Property | Default | Meaning |
| --- | --- | --- |
| `user` | none | User name for authentication |
| `password` | none | Password for authentication |
| `graph` | from URL path | Overrides the graph in the URL |
| `ssl` | `false` | Connect over TLS |
| `connectionTimeout` | client default | TCP connect timeout, in milliseconds |
| `socketTimeout` | client default | Socket read timeout, in milliseconds; `0` means no deadline |
| `queryTimeout` | none | Default server-side query timeout for new statements, in milliseconds |
| `poolMaxTotal` | client default | Maximum pooled connections |
| `poolMaxIdle` | client default | Maximum idle pooled connections |
| `poolMaxWait` | client default | Milliseconds to wait for a pooled connection; negative waits forever |
| `readOnly` | `false` | Start read-only, routing queries through FalkorDB's read-only path |

**Precedence:** `Properties` beat URL query parameters, which beat the URL's userinfo. This means
`DriverManager.getConnection(url, user, password)` wins over credentials embedded in the URL, which
is what callers expect.

```java
Properties properties = new Properties();
properties.setProperty("ssl", "true");
properties.setProperty("connectionTimeout", "2000");

DriverManager.getConnection("jdbc:falkordb://graph.example.com/social", properties);
```

The connection is proven at open time: `getConnection` pings the server, so an unreachable host
fails immediately with SQLState `08006` rather than at the first query.

### Read-only connections

`connection.setReadOnly(true)` routes every subsequent query through JFalkorDB's read-only path, so
it can be served by a replica and the server rejects writes. It can also be set up front with
`?readOnly=true`.

## Type mapping

FalkorDB values map onto JDBC types as follows. The `getObject` column is what a plain
`getObject(int)` returns; `getObject(int, Class<T>)` converts more widely.

| FalkorDB type | `java.sql.Types` | `getObject` | `getString` renders as |
| --- | --- | --- | --- |
| `NULL` | `NULL` | `null` | `null` |
| `STRING` | `VARCHAR` | `String` | the string |
| `INTEGER` (64-bit) | `BIGINT` | `Long` | `42` |
| `BOOLEAN` | `BOOLEAN` | `Boolean` | `true` |
| `DOUBLE` | `DOUBLE` | `Double` | `3.5` |
| `ARRAY` | `ARRAY` | `java.sql.Array` | `[1, 2, 3]` |
| `VECTORF32` | `ARRAY` (base type `REAL`) | `java.sql.Array` | `[1.0, 2.0]` |
| `MAP` | `JAVA_OBJECT` | `Map<String, Object>` | `{name: "Alice"}` |
| `NODE` | `JAVA_OBJECT` | `Node` | `(:Person {name: "Alice"})` |
| `RELATIONSHIP` | `JAVA_OBJECT` | `Edge` | `[:KNOWS {since: 2020}]` |
| `PATH` | `JAVA_OBJECT` | `Path` | `(:A)-[:R]->(:B)` |
| `POINT` | `JAVA_OBJECT` | `Point` | `point({latitude: 32.07, longitude: 34.79})` |
| `DATE` | `DATE` | `LocalDate` | `2024-03-14` |
| `TIME` | `TIME` | `LocalTime` | `01:59:26` |
| `DATETIME` | `TIMESTAMP` | `LocalDateTime` | `2024-03-14T01:59:26` |
| `DURATION` | `JAVA_OBJECT` | `Duration` | `PT1H30M` |

The usual JDBC conversions apply on top: `getInt`/`getLong`/`getDouble`/`getBigDecimal` on any
number or numeric string, `getBoolean` on numbers and `"true"`/`"false"`, `getDate`/`getTime`/
`getTimestamp` between the temporal types, and `wasNull()` after any getter. Columns are 1-based,
and column labels are matched case-insensitively.

### Parameter encoding

Values you bind are encoded as FalkorDB parameters, which accept a narrower set of types than JDBC
offers. The driver converts where it can and is explicit about what that costs:

| You bind | FalkorDB receives | Note |
| --- | --- | --- |
| `String`, `Boolean`, `Byte`/`Short`/`Integer`/`Long`, `Float`/`Double` | the same value | |
| `BigDecimal` | `double` | lossy beyond a double's precision |
| `BigInteger` | integer | |
| `byte[]` | list of integers | `getBytes` reverses it |
| `java.sql.Date`/`Time`/`Timestamp`, `java.time` types | ISO-8601 `String` | FalkorDB has no temporal parameter type; wrap with `date()`/`localtime()`/`localdatetime()` in Cypher to rebuild a temporal |
| `List`, arrays, `java.sql.Array` | Cypher list | |
| `Map` with `String` keys | Cypher map | non-`String` keys are rejected |
| `null` | `null` | |

## JDBC feature matrix

### Supported

| Feature | Notes |
| --- | --- |
| `DriverManager` auto-registration | via `META-INF/services` |
| `Statement` | `executeQuery`, `executeUpdate`, `execute`, `getResultSet`, `getUpdateCount` |
| `PreparedStatement` | positional `?` and native `$name` parameters |
| `ResultSet` | forward-only, read-only, by-index and by-label access |
| `ResultSetMetaData` | column count, labels, types, class names |
| `DatabaseMetaData` | product/driver identity, catalogs, labels as tables, relationship types, columns, indexes, procedures, type info |
| `ParameterMetaData` | parameter count |
| `java.sql.Array` | for Cypher lists and vectors, with `getResultSet()` and slicing |
| `RowSet` | disconnected `CachedRowSet`, `WebRowSet`, `FilteredRowSet` and `JoinRowSet`, via `FalkorDBRowSetFactory` |
| Query timeouts | `Statement.setQueryTimeout` maps to FalkorDB's server-side timeout |
| Read-only connections | `setReadOnly(true)` routes through the read-only path |
| Connection pooling knobs | `poolMaxTotal`, `poolMaxIdle`, `poolMaxWait` |
| TLS | scheme or `?ssl=true` |
| Error mapping | `GraphException` becomes a typed `SQLException` with a real SQLState |

### Not supported

Each of these throws `SQLFeatureNotSupportedException` — the driver never silently ignores a request.

| Feature | Why |
| --- | --- |
| Transactions, savepoints, isolation levels | Auto-commit is permanently on; FalkorDB executes each query atomically on its own. `getTransactionIsolation()` reports `TRANSACTION_NONE` |
| Batch execution | `addBatch`/`executeBatch` |
| Scrollable and updatable result sets | Results are forward-only and read-only |
| `JdbcRowSet` | A connected row set needs a scrollable, updatable `ResultSet`; use a `CachedRowSet`. See [Row sets](#row-sets) |
| Generated keys | Cypher `RETURN`s what it creates instead |
| `CallableStatement` | Call procedures with Cypher's `CALL` |
| LOBs, `SQLXML`, `RowId`, `Ref`, streams | No FalkorDB equivalent |
| `Statement.cancel()` | Use `setQueryTimeout` |
| SQL-to-Cypher translation | Cypher is the query language; see below |

`setMaxRows` is accepted but cannot be enforced — FalkorDB materialises a whole response before the
driver sees it — so it raises a `SQLWarning` and leaves the rows intact. Use a Cypher `LIMIT`.

The `supports*` flags on `DatabaseMetaData` that describe **SQL grammar** all report `false`,
including `supportsColumnAliasing` and `supportsTableCorrelationNames`. Cypher spells aliasing and
correlation names the way SQL does, but a tool that trusted a `true` there would generate SQL, and
this driver accepts only Cypher. `allTablesAreSelectable` and `allProceduresAreCallable` report
`false` for the same reason: whatever the current user is permitted to do, no label is reachable by
`SELECT` and no procedure is reachable by `prepareCall`. They will all be revisited if a translation
layer is added.

`queryTimeout` is carried to the server in milliseconds exactly as configured. JDBC's
`Statement.getQueryTimeout()` can only answer in whole seconds, so it rounds up — a connection
opened with `?queryTimeout=1500` reports `2` while still aborting at 1500 ms.

`createArrayOf(typeName, elements)` converts the elements to the type you name, and rejects any
element that type cannot hold, so `getBaseType()` and `getArray()` always agree.

## Future work

- **SQL-to-Cypher translation** (`enableSQLTranslation`), so existing SQL tooling can query a graph
  without being rewritten.
- **Transactions** beyond auto-commit, tracking what FalkorDB itself supports.
- **Batch execution** for `addBatch`/`executeBatch`.

## Building

```bash
mvn verify          # unit tests, then integration tests against a throwaway container
mvn test            # unit tests only, no Docker needed
mvn spotless:apply  # format
```

Integration tests use [Testcontainers](https://testcontainers.com) and need a working Docker daemon.
To run them against a server you already have, set both `FALKORDB_HOST` and `FALKORDB_PORT`, plus
`FALKORDB_ALLOW_EXTERNAL=true` to confirm it is disposable — the tests create and delete graphs. To pin
a different image, set `FALKORDB_IMAGE`. Use a disposable instance — the suite writes to and clears
its graphs.

See [AGENTS.md](AGENTS.md) for the architecture and layout.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
