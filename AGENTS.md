# AGENTS.md

Working notes for anyone — human or agent — changing this repository.

## What this is

A JDBC 4.3 driver for FalkorDB, built as a thin adapter over the official
[JFalkorDB](https://github.com/FalkorDB/JFalkorDB) client. The driver does **not** speak RESP or
implement any wire protocol; it translates between `java.sql` and JFalkorDB's API.

The guiding principle is honesty. JDBC is a relational API and FalkorDB is a graph database, so the
two do not line up perfectly. Where they do, we implement it properly. Where they do not, we throw
`SQLFeatureNotSupportedException` and document why. We never silently no-op, and we never pretend a
capability exists.

## Build and test

```bash
mvn verify            # the full gate: unit tests, integration tests, format check
mvn test              # unit tests only (*Test.java) - no Docker required
mvn failsafe:integration-test   # integration tests only (*IT.java) - needs Docker
mvn spotless:apply    # reformat
mvn spotless:check    # verify formatting (runs as part of verify)
```

Run a single test class or method:

```bash
mvn test -Dtest=CypherQueryTest
mvn test -Dtest='CypherQueryTest#rewritesPositionalPlaceholders'
mvn verify -Dit.test=TypeMappingIT
```

`mvn verify` must pass before anything is pushed. CI runs exactly that.

### Integration test environment

Integration tests start one shared FalkorDB container per JVM through Testcontainers.

| Variable | Effect |
| --- | --- |
| `FALKORDB_IMAGE` | Use a specific image or tag instead of the pinned digest |
| `FALKORDB_HOST` + `FALKORDB_PORT` | Use an already-running server; **both** are required, plus `FALKORDB_ALLOW_EXTERNAL=true`, because the tests destroy data |

The external-server path is for debugging against a particular build. The suite deletes data in the
graphs it uses, so only ever point it at a disposable instance.

## Layout

```
src/main/java/com/falkordb/jdbc/
├── FalkorDBDriver.java              java.sql.Driver; registration, acceptsURL, getPropertyInfo
├── FalkorDBConnection.java          owns the JFalkorDB Driver and its pool
├── FalkorDBStatement.java           execute/executeQuery/executeUpdate, timeouts, update counts
├── FalkorDBPreparedStatement.java   parameter binding and encoding
├── FalkorDBParameterMetaData.java   parameter count
├── FalkorDBResultSet.java           row cursor and every getter
├── FalkorDBResultSetMetaData.java   column labels, types, class names
├── FalkorDBDatabaseMetaData.java    graph introspection presented as JDBC metadata
├── FalkorDBArray.java               java.sql.Array over a Cypher list or vector
├── FalkorDBWrapper.java             shared unwrap/isWrapperFor
└── internal/
    ├── ConnectionSettings.java      URL and property parsing; the settings record
    ├── CypherQuery.java             the ? to $p1 rewriter
    ├── FalkorType.java              the type-mapping table
    ├── GraphValues.java             value rendering and conversion
    ├── ColumnMeta.java              one column's name and type
    ├── SQLErrors.java               GraphException to SQLException, with SQLStates
    └── DriverVersion.java           version constants, filtered from the POM
```

Everything under `internal` is implementation detail. It is not part of the public API and may
change without notice. The public surface is the `java.sql` interfaces plus the small number of
vendor extensions reachable through `unwrap`.

## Where the important decisions live

**`CypherQuery`** is security-critical. It rewrites JDBC's `?` placeholders into FalkorDB named
parameters while respecting string literals, comments and backtick-quoted identifiers, so a `?`
inside `'is this a question?'` is left alone. It must never be tempted into interpolating a value
into query text. If you touch it, add tests first.

**`FalkorType`** is the single source of truth for the type mapping, and the README's type table
mirrors it. Keep them in step.

**`GraphValues`** holds every conversion rule: how a value renders as a string, and how it coerces
to each Java type a getter can ask for.

**`ConnectionSettings`** parses the URL and merges properties. Precedence is deliberate:
`Properties` > URL query parameters > URL userinfo, so `getConnection(url, user, password)` wins
over credentials in the URL.

Unknown *URL query parameters* are rejected; unknown *`Properties` keys* are ignored. That
asymmetry is deliberate, not an oversight. A URL is hand-written and belongs entirely to the
driver, so a typo there is a mistake worth failing loudly on. A `Properties` object is routinely
shared with connection pools and BI tools that add their own unrelated keys — HikariCP's
`dataSourceProperties` and DBeaver both do this — so rejecting unknown keys would break callers
who did nothing wrong.

## Conventions

### Java

- Java 17. Records, sealed types, switch expressions and text blocks are welcome where they make the
  code clearer — not as decoration.
- Formatting is enforced by Spotless (Palantir Java Format). Run `mvn spotless:apply`.
- Public types and methods need Javadoc. CI builds it with `-Prelease` and fails on warnings.
- Comment *why*, not *what*. A comment that restates the code is noise; a comment explaining a
  non-obvious constraint (a JFalkorDB limitation, a JDBC rule, a protocol quirk) is valuable.

### Testing

- `*Test.java` is a unit test: fast, no server, run by Surefire.
- `*IT.java` is an integration test: needs a real FalkorDB, run by Failsafe.
- Test names are sentences — `nullBecomesSqlNull`, `rejectsAnOutOfRangeColumn` — and `@Nested`
  classes group them by behaviour.
- Assert against a real server rather than a mock wherever the server's behaviour is the thing in
  question. Several bugs in this driver were found precisely because the tests asked FalkorDB what
  it really does instead of trusting an assumption. Two examples worth remembering: FalkorDB has
  `localtime()`/`localdatetime()` but no `time()`/`datetime()`, and JFalkorDB's pool connects
  lazily, so a connection must be proven with a ping at open time.

### Commits and branches

- Conventional Commits: `feat(jdbc): ...`, `fix(jdbc): ...`, `test(jdbc): ...`, `docs: ...`,
  `ci: ...`, `chore: ...`.
- Branches are `<type>/<short-description>`. Never commit to `main`.
- A commit message should explain the reasoning, not list the diff.

## Facts worth knowing before you change something

**JFalkorDB decides the runtime types.** `ResultSetImpl.deserializeScalar` is the authority on what
Java class each FalkorDB value arrives as. `VALUE_INTEGER` is a `Long`, never an `Integer`.
`VALUE_VECTORF32` and `VALUE_ARRAY` are both a `List` — a vector is inferred from its elements all
being `Float`. FalkorDB's internal scalar type enum is package-private, so the driver re-derives the
type from the runtime class in `FalkorType.of`.

**Parameter names must be identifiers.** JFalkorDB sends parameters as a `CYPHER name=value` prefix,
and the names have to match `[A-Za-z_][A-Za-z0-9_]*`. That is why placeholders become `$p1` rather
than Neo4j's `$1`.

**FalkorDB rejects some parameter types outright**: `BigDecimal`, any `java.time` type, non-finite
floats, and maps with non-`String` keys. `FalkorDBPreparedStatement.encode` converts these before
they reach the client, and the README documents what each conversion costs.

**An empty result header means an update.** That is how the driver tells a write from a read:
`ResultSet.getHeader().getSchemaNames().isEmpty()` implies an update count derived from
`Statistics`, and anything else is a row set.

**`com.falkordb.Driver.getConnection()` hands out a pooled `Jedis`** that must be closed to return
it to the pool.

## Out of scope for now

SQL-to-Cypher translation, transactions beyond auto-commit, batch execution and `RowSet` support are
deliberately absent, and the README lists them as future work. If you add one, update both the
README's feature matrix and the unsupported-operation tests, so the documentation and the thrown
exceptions cannot drift apart.
