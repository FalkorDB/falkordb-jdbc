package com.falkordb.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowIdLifetime;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.falkordb.Record;
import com.falkordb.jdbc.internal.ColumnMeta;
import com.falkordb.jdbc.internal.DriverVersion;
import com.falkordb.jdbc.internal.FalkorType;
import com.falkordb.jdbc.internal.SQLErrors;

/**
 * Describes the FalkorDB server and this driver's JDBC capabilities.
 *
 * <p>A graph has no fixed schema, so the relational view offered here is a projection of what the
 * graph currently contains, discovered with FalkorDB's own introspection procedures:
 *
 * <ul>
 *   <li>a <b>graph</b> is a catalog — {@link #getCatalogs()} lists them with {@code GRAPH.LIST}
 *   <li>a <b>node label</b> is a {@code TABLE} — from {@code CALL db.labels()}
 *   <li>a <b>relationship type</b> is a {@code RELATIONSHIP} — from {@code CALL
 *       db.relationshipTypes()}
 *   <li>a <b>property key</b> is a column — sampled from the entities that actually carry it
 * </ul>
 *
 * <p>There are no schemas: FalkorDB has nothing between the server and a graph, so {@link
 * #getSchemas()} is empty and every {@code TABLE_SCHEM} is null.
 *
 * <p>Because labels are not tables, a property that is present on some nodes and absent on others is
 * reported as a nullable column, and the column's type comes from sampling. Two nodes with the same
 * label may genuinely disagree about a property's type, in which case the column is reported as
 * {@link Types#OTHER}.
 */
public final class FalkorDBDatabaseMetaData extends FalkorDBWrapper implements DatabaseMetaData {

    /** How many entities per label to sample when working out a label's properties and their types. */
    private static final int SAMPLE_LIMIT = 100;

    private static final String TABLE = "TABLE";
    private static final String RELATIONSHIP = "RELATIONSHIP";

    /** Matches the graph module's entry in Redis's {@code INFO modules} output. */
    private static final Pattern MODULE_VERSION =
            Pattern.compile("name=(?:graph|falkordb)\\b[^\\r\\n]*?\\bver=(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern REDIS_VERSION = Pattern.compile("redis_version:([^\\r\\n]+)");

    private final FalkorDBConnection connection;

    FalkorDBDatabaseMetaData(FalkorDBConnection connection) {
        this.connection = connection;
    }

    // ---------------------------------------------------------------- product identity

    @Override
    public String getDatabaseProductName() {
        return "FalkorDB";
    }

    /**
     * The server version, read from the graph module's entry in {@code INFO modules}.
     *
     * <p>FalkorDB encodes its version as a single integer, {@code major * 10000 + minor * 100 +
     * patch}. If the module is not reported — which happens against a Redis-compatible stand-in — the
     * Redis version is used, and if the server cannot be reached at all the answer is {@code
     * "unknown"} rather than an exception, since metadata calls are often made for display.
     *
     * @return the server version, never null
     */
    @Override
    public String getDatabaseProductVersion() {
        try (redis.clients.jedis.Jedis jedis = connection.falkorDriver().getConnection()) {
            String modules = jedis.info("modules");
            if (modules != null) {
                Matcher matcher = MODULE_VERSION.matcher(modules);
                if (matcher.find()) {
                    int encoded = Integer.parseInt(matcher.group(1));
                    return (encoded / 10000) + "." + ((encoded / 100) % 100) + "." + (encoded % 100);
                }
            }
            String server = jedis.info("server");
            if (server != null) {
                Matcher matcher = REDIS_VERSION.matcher(server);
                if (matcher.find()) {
                    return matcher.group(1).trim();
                }
            }
        } catch (RuntimeException e) {
            return "unknown";
        }
        return "unknown";
    }

    @Override
    public int getDatabaseMajorVersion() {
        return versionPart(0);
    }

    @Override
    public int getDatabaseMinorVersion() {
        return versionPart(1);
    }

    private int versionPart(int index) {
        String[] parts = getDatabaseProductVersion().split("[.\\-+]");
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String getDriverName() {
        return DriverVersion.NAME;
    }

    @Override
    public String getDriverVersion() {
        return FalkorDBDriver.version();
    }

    @Override
    public int getDriverMajorVersion() {
        return DriverVersion.MAJOR;
    }

    @Override
    public int getDriverMinorVersion() {
        return DriverVersion.MINOR;
    }

    @Override
    public int getJDBCMajorVersion() {
        return 4;
    }

    @Override
    public int getJDBCMinorVersion() {
        return 3;
    }

    @Override
    public String getURL() {
        return connection.settings().toRedactedUrl();
    }

    @Override
    public String getUserName() {
        return connection.settings().user().orElse("");
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public String toString() {
        return "FalkorDBDatabaseMetaData[" + getURL() + "]";
    }

    // ---------------------------------------------------------------- catalogs, schemas, tables

    @Override
    public ResultSet getCatalogs() throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try {
            for (String name : connection.falkorDriver().listGraphs()) {
                rows.add(List.of(name));
            }
        } catch (RuntimeException e) {
            throw SQLErrors.translate("GRAPH.LIST", e);
        }
        rows.sort((a, b) -> String.valueOf(a.get(0)).compareTo(String.valueOf(b.get(0))));
        return result(List.of(column("TABLE_CAT", FalkorType.STRING)), rows);
    }

    @Override
    public ResultSet getSchemas() throws SQLException {
        return result(
                List.of(column("TABLE_SCHEM", FalkorType.STRING), column("TABLE_CATALOG", FalkorType.STRING)),
                List.of());
    }

    @Override
    public ResultSet getSchemas(String catalog, String schemaPattern) throws SQLException {
        return getSchemas();
    }

    @Override
    public ResultSet getTableTypes() throws SQLException {
        return result(List.of(column("TABLE_TYPE", FalkorType.STRING)), List.of(List.of(RELATIONSHIP), List.of(TABLE)));
    }

    /**
     * Lists node labels as {@code TABLE}s and relationship types as {@code RELATIONSHIP}s.
     *
     * <p>A label that no node currently carries is omitted: FalkorDB remembers labels after the last
     * node using them is deleted, and reporting those would describe tables that cannot be queried.
     *
     * @param catalog the graph name, or null/empty to mean the connection's graph; a different graph
     *     yields no rows, since a connection can only introspect the graph it is bound to
     * @param schemaPattern ignored — FalkorDB has no schemas
     * @param tableNamePattern a SQL {@code LIKE} pattern, or null for all
     * @param types {@code TABLE}, {@code RELATIONSHIP}, or null for both
     * @return one row per matching label or relationship type
     * @throws SQLException if the introspection query fails
     */
    @Override
    public ResultSet getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types)
            throws SQLException {
        List<ColumnMeta> columns = List.of(
                column("TABLE_CAT", FalkorType.STRING),
                column("TABLE_SCHEM", FalkorType.STRING),
                column("TABLE_NAME", FalkorType.STRING),
                column("TABLE_TYPE", FalkorType.STRING),
                column("REMARKS", FalkorType.STRING),
                column("TYPE_CAT", FalkorType.STRING),
                column("TYPE_SCHEM", FalkorType.STRING),
                column("TYPE_NAME", FalkorType.STRING),
                column("SELF_REFERENCING_COL_NAME", FalkorType.STRING),
                column("REF_GENERATION", FalkorType.STRING));
        if (!matchesCatalog(catalog)) {
            return result(columns, List.of());
        }

        Set<String> wanted = types == null ? Set.of(TABLE, RELATIONSHIP) : Set.copyOf(Arrays.asList(types));
        Pattern name = like(tableNamePattern);
        String graph = connection.graphName();
        List<List<Object>> rows = new ArrayList<>();

        if (wanted.contains(TABLE)) {
            for (String label : labelsInUse()) {
                if (name.matcher(label).matches()) {
                    rows.add(tableRow(graph, label, TABLE, "Nodes labelled :" + label));
                }
            }
        }
        if (wanted.contains(RELATIONSHIP)) {
            for (String type : relationshipTypesInUse()) {
                if (name.matcher(type).matches()) {
                    rows.add(tableRow(graph, type, RELATIONSHIP, "Relationships of type :" + type));
                }
            }
        }
        rows.sort((a, b) -> {
            int byType = String.valueOf(a.get(3)).compareTo(String.valueOf(b.get(3)));
            return byType != 0 ? byType : String.valueOf(a.get(2)).compareTo(String.valueOf(b.get(2)));
        });
        return result(columns, rows);
    }

    private static List<Object> tableRow(String graph, String name, String type, String remarks) {
        List<Object> row = new ArrayList<>(10);
        row.add(graph);
        row.add(null);
        row.add(name);
        row.add(type);
        row.add(remarks);
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(null);
        row.add(null);
        return row;
    }

    /**
     * Lists the property keys carried by each label or relationship type, with the type each property
     * is observed to hold.
     *
     * <p>The graph is sampled rather than scanned: at most {@value #SAMPLE_LIMIT} entities per label
     * contribute, which keeps the call cheap on a large graph at the cost of possibly missing a
     * property that only a few entities carry.
     *
     * @param catalog the graph name, or null/empty for the connection's graph
     * @param schemaPattern ignored
     * @param tableNamePattern a SQL {@code LIKE} pattern over label and relationship-type names
     * @param columnNamePattern a SQL {@code LIKE} pattern over property keys
     * @return one row per property key per label
     * @throws SQLException if the introspection query fails
     */
    @Override
    public ResultSet getColumns(String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
            throws SQLException {
        List<ColumnMeta> columns = List.of(
                col("getColumns", "TABLE_CAT"),
                col("getColumns", "TABLE_SCHEM"),
                col("getColumns", "TABLE_NAME"),
                col("getColumns", "COLUMN_NAME"),
                col("getColumns", "DATA_TYPE"),
                col("getColumns", "TYPE_NAME"),
                col("getColumns", "COLUMN_SIZE"),
                col("getColumns", "BUFFER_LENGTH"),
                col("getColumns", "DECIMAL_DIGITS"),
                col("getColumns", "NUM_PREC_RADIX"),
                col("getColumns", "NULLABLE"),
                col("getColumns", "REMARKS"),
                col("getColumns", "COLUMN_DEF"),
                col("getColumns", "SQL_DATA_TYPE"),
                col("getColumns", "SQL_DATETIME_SUB"),
                col("getColumns", "CHAR_OCTET_LENGTH"),
                col("getColumns", "ORDINAL_POSITION"),
                col("getColumns", "IS_NULLABLE"),
                col("getColumns", "SCOPE_CATALOG"),
                col("getColumns", "SCOPE_SCHEMA"),
                col("getColumns", "SCOPE_TABLE"),
                col("getColumns", "SOURCE_DATA_TYPE"),
                col("getColumns", "IS_AUTOINCREMENT"),
                col("getColumns", "IS_GENERATEDCOLUMN"));
        if (!matchesCatalog(catalog)) {
            return result(columns, List.of());
        }

        Pattern tables = like(tableNamePattern);
        Pattern properties = like(columnNamePattern);
        String graph = connection.graphName();
        List<List<Object>> rows = new ArrayList<>();

        List<String> names = new ArrayList<>();
        List<String> matches = new ArrayList<>();
        for (String label : labelsInUse()) {
            if (tables.matcher(label).matches()) {
                names.add(label);
                matches.add("MATCH (e:`" + escape(label) + "`)");
            }
        }
        for (String type : relationshipTypesInUse()) {
            if (tables.matcher(type).matches()) {
                names.add(type);
                matches.add("MATCH ()-[e:`" + escape(type) + "`]->()");
            }
        }
        collectProperties(graph, names, matches, properties, rows);
        // JDBC fixes this ordering, and tools rely on it to pair a column with its position.
        rows.sort(Comparator.comparing((List<Object> row) -> String.valueOf(row.get(0)))
                .thenComparing(row -> String.valueOf(row.get(1)))
                .thenComparing(row -> String.valueOf(row.get(2)))
                .thenComparingLong(row -> (Long) row.get(16)));
        return result(columns, rows);
    }

    /**
     * Resolves the JDBC type of a property from every distinct value sampled for it. A property
     * that holds more than one type — which a schemaless graph permits — has no single JDBC type,
     * so it is reported as {@link FalkorType#UNKNOWN} rather than as whichever type happened to be
     * collected first.
     */
    private static FalkorType sampledType(Object samples) {
        if (!(samples instanceof Collection<?> values)) {
            return FalkorType.of(samples);
        }
        FalkorType resolved = null;
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            FalkorType current = FalkorType.of(value);
            if (resolved == null) {
                resolved = current;
            } else if (resolved != current) {
                return FalkorType.UNKNOWN;
            }
        }
        return resolved == null ? FalkorType.NULL : resolved;
    }

    private void collectProperties(
            String graph, List<String> tables, List<String> matches, Pattern properties, List<List<Object>> into)
            throws SQLException {
        if (tables.isEmpty()) {
            return;
        }
        // One sampling query per label is what BI tools feel when they poll getColumns, so every
        // table's sample is asked for in a single round trip. The branches are tagged by position
        // rather than by name because a label and a relationship type may share one.
        StringBuilder cypher = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) {
                cypher.append(" UNION ALL ");
            }
            cypher.append(matches.get(i))
                    .append(" WITH e LIMIT ")
                    .append(SAMPLE_LIMIT)
                    .append(" UNWIND keys(e) AS key RETURN ")
                    .append(i)
                    .append(" AS tag, key, collect(DISTINCT e[key]) AS samples");
        }
        Map<Integer, Map<String, Object>> sampled = new LinkedHashMap<>();
        for (Record record : query(cypher.toString())) {
            Object tag = record.getValue(0);
            Object key = record.getValue(1);
            if (!(tag instanceof Number index) || key == null) {
                continue;
            }
            // A sorted map because a graph has no column order of its own: keys(e) may answer in a
            // different order each call, and ORDINAL_POSITION has to be stable between them.
            sampled.computeIfAbsent(index.intValue(), i -> new TreeMap<>()).put(key.toString(), record.getValue(2));
        }
        for (Map.Entry<Integer, Map<String, Object>> entry : sampled.entrySet()) {
            String table = tables.get(entry.getKey());
            int ordinal = 0;
            for (Map.Entry<String, Object> property : entry.getValue().entrySet()) {
                String key = property.getKey();
                // Counted before the filter: ORDINAL_POSITION is the property's place in the table,
                // and asking for a subset of columns must not renumber them.
                ordinal++;
                if (!properties.matcher(key).matches()) {
                    continue;
                }
                appendColumn(graph, table, key, sampledType(property.getValue()), ordinal, into);
            }
        }
    }

    private void appendColumn(
            String graph, String table, String key, FalkorType type, int ordinal, List<List<Object>> into) {
        {
            List<Object> row = new ArrayList<>(24);
            row.add(graph);
            row.add(null);
            row.add(table);
            row.add(key);
            row.add((long) type.sqlType());
            row.add(type.typeName());
            row.add((long) type.precision());
            row.add(null);
            row.add(null);
            row.add(type.sqlType() == Types.BIGINT || type.sqlType() == Types.DOUBLE ? 10L : null);
            // A graph never requires a property, so every column is nullable.
            row.add((long) columnNullable);
            row.add(null);
            row.add(null);
            row.add(null);
            row.add(null);
            row.add(type.sqlType() == Types.VARCHAR ? (long) type.precision() : null);
            row.add((long) ordinal);
            row.add("YES");
            row.add(null);
            row.add(null);
            row.add(null);
            row.add(null);
            row.add("NO");
            row.add("NO");
            into.add(row);
        }
    }

    /**
     * Lists the indexes FalkorDB has built, from {@code CALL db.indexes()}.
     *
     * @param catalog the graph name, or null/empty for the connection's graph
     * @param schema ignored
     * @param table the label or relationship type, or null for all
     * @param unique when true, no rows are returned — a FalkorDB index is never a uniqueness
     *     constraint expressed as an index
     * @param approximate ignored
     * @return one row per indexed property
     * @throws SQLException if the introspection query fails
     */
    @Override
    public ResultSet getIndexInfo(String catalog, String schema, String table, boolean unique, boolean approximate)
            throws SQLException {
        List<ColumnMeta> columns = List.of(
                col("getIndexInfo", "TABLE_CAT"),
                col("getIndexInfo", "TABLE_SCHEM"),
                col("getIndexInfo", "TABLE_NAME"),
                col("getIndexInfo", "NON_UNIQUE"),
                col("getIndexInfo", "INDEX_QUALIFIER"),
                col("getIndexInfo", "INDEX_NAME"),
                col("getIndexInfo", "TYPE"),
                col("getIndexInfo", "ORDINAL_POSITION"),
                col("getIndexInfo", "COLUMN_NAME"),
                col("getIndexInfo", "ASC_OR_DESC"),
                col("getIndexInfo", "CARDINALITY"),
                col("getIndexInfo", "PAGES"),
                col("getIndexInfo", "FILTER_CONDITION"));
        if (unique || !matchesCatalog(catalog)) {
            return result(columns, List.of());
        }

        List<List<Object>> rows = new ArrayList<>();
        for (Record record : queryOrEmpty("CALL db.indexes()")) {
            String label = record.containsKey("label") ? asString(record.getValue("label")) : null;
            if (label == null || (table != null && !table.equals(label))) {
                continue;
            }
            Object properties = record.containsKey("properties") ? record.getValue("properties") : null;
            int ordinal = 0;
            for (Object property : properties instanceof List<?> list ? list : List.of()) {
                ordinal++;
                List<Object> row = new ArrayList<>(13);
                row.add(connection.graphName());
                row.add(null);
                row.add(label);
                row.add(Boolean.TRUE);
                row.add(null);
                row.add(label + "_" + property);
                row.add((long) tableIndexOther);
                row.add((long) ordinal);
                row.add(asString(property));
                row.add(null);
                row.add(null);
                row.add(null);
                row.add(null);
                rows.add(row);
            }
        }
        // JDBC fixes this ordering: NON_UNIQUE, TYPE, INDEX_NAME, then ORDINAL_POSITION.
        rows.sort(Comparator.comparing((List<Object> row) -> (Boolean) row.get(3))
                .thenComparingLong(row -> (Long) row.get(6))
                .thenComparing(row -> String.valueOf(row.get(5)))
                .thenComparingLong(row -> (Long) row.get(7)));
        return result(columns, rows);
    }

    /**
     * Lists the procedures the server exposes, from {@code CALL dbms.procedures()}.
     *
     * @param catalog the graph name, or null/empty for the connection's graph
     * @param schemaPattern ignored
     * @param procedureNamePattern a SQL {@code LIKE} pattern, or null for all
     * @return one row per procedure
     * @throws SQLException if the introspection query fails
     */
    @Override
    public ResultSet getProcedures(String catalog, String schemaPattern, String procedureNamePattern)
            throws SQLException {
        List<ColumnMeta> columns = List.of(
                col("getProcedures", "PROCEDURE_CAT"),
                col("getProcedures", "PROCEDURE_SCHEM"),
                col("getProcedures", "PROCEDURE_NAME"),
                col("getProcedures", "reserved1"),
                col("getProcedures", "reserved2"),
                col("getProcedures", "reserved3"),
                col("getProcedures", "REMARKS"),
                col("getProcedures", "PROCEDURE_TYPE"),
                col("getProcedures", "SPECIFIC_NAME"));
        if (!matchesCatalog(catalog)) {
            return result(columns, List.of());
        }

        Pattern name = like(procedureNamePattern);
        List<List<Object>> rows = new ArrayList<>();
        for (Record record : queryOrEmpty("CALL dbms.procedures()")) {
            String procedure = record.containsKey("name") ? asString(record.getValue("name")) : null;
            if (procedure == null || !name.matcher(procedure).matches()) {
                continue;
            }
            List<Object> row = new ArrayList<>(9);
            row.add(connection.graphName());
            row.add(null);
            row.add(procedure);
            row.add(null);
            row.add(null);
            row.add(null);
            row.add(record.containsKey("mode") ? "mode=" + asString(record.getValue("mode")) : null);
            row.add((long) procedureResultUnknown);
            row.add(procedure);
            rows.add(row);
        }
        // JDBC fixes this ordering: catalog, schema, name, then the specific name.
        rows.sort(Comparator.comparing((List<Object> row) -> String.valueOf(row.get(0)))
                .thenComparing(row -> String.valueOf(row.get(1)))
                .thenComparing(row -> String.valueOf(row.get(2)))
                .thenComparing(row -> String.valueOf(row.get(8))));
        return result(columns, rows);
    }

    /** Describes every type this driver can return, mirroring the driver's type-mapping table. */
    @Override
    public ResultSet getTypeInfo() throws SQLException {
        List<ColumnMeta> columns = List.of(
                col("getTypeInfo", "TYPE_NAME"),
                col("getTypeInfo", "DATA_TYPE"),
                col("getTypeInfo", "PRECISION"),
                col("getTypeInfo", "LITERAL_PREFIX"),
                col("getTypeInfo", "LITERAL_SUFFIX"),
                col("getTypeInfo", "CREATE_PARAMS"),
                col("getTypeInfo", "NULLABLE"),
                col("getTypeInfo", "CASE_SENSITIVE"),
                col("getTypeInfo", "SEARCHABLE"),
                col("getTypeInfo", "UNSIGNED_ATTRIBUTE"),
                col("getTypeInfo", "FIXED_PREC_SCALE"),
                col("getTypeInfo", "AUTO_INCREMENT"),
                col("getTypeInfo", "LOCAL_TYPE_NAME"),
                col("getTypeInfo", "MINIMUM_SCALE"),
                col("getTypeInfo", "MAXIMUM_SCALE"),
                col("getTypeInfo", "SQL_DATA_TYPE"),
                col("getTypeInfo", "SQL_DATETIME_SUB"),
                col("getTypeInfo", "NUM_PREC_RADIX"));
        List<List<Object>> rows = new ArrayList<>();
        for (FalkorType type : FalkorType.values()) {
            if (type == FalkorType.NULL || type == FalkorType.UNKNOWN || !type.isColumnType()) {
                continue;
            }
            boolean text = type == FalkorType.STRING;
            List<Object> row = new ArrayList<>(18);
            row.add(type.typeName());
            row.add((long) type.sqlType());
            row.add((long) type.precision());
            row.add(text ? "'" : null);
            row.add(text ? "'" : null);
            row.add(null);
            row.add((long) typeNullable);
            row.add(text);
            row.add((long) (text ? typeSearchable : typePredBasic));
            row.add(Boolean.FALSE);
            row.add(Boolean.FALSE);
            row.add(Boolean.FALSE);
            row.add(type.typeName());
            row.add(0L);
            row.add(0L);
            row.add(null);
            row.add(null);
            row.add(type == FalkorType.INTEGER || type == FalkorType.DOUBLE ? 10L : null);
            rows.add(row);
        }
        // JDBC requires getTypeInfo() to be ordered by how closely each type maps to its DATA_TYPE,
        // which in practice means ascending DATA_TYPE.
        rows.sort(java.util.Comparator.comparingLong(row -> (Long) row.get(1)));
        return result(columns, rows);
    }

    // ---------------------------------------------------------------- introspection helpers

    private Set<String> labelsInUse() throws SQLException {
        return inUse("CALL db.labels()", label -> "MATCH (e:`" + escape(label) + "`)");
    }

    private Set<String> relationshipTypesInUse() throws SQLException {
        return inUse("CALL db.relationshipTypes()", type -> "MATCH ()-[e:`" + escape(type) + "`]->()");
    }

    /**
     * Lists the labels or relationship types that still have at least one entity.
     *
     * <p>{@code db.labels()} keeps reporting a label after its last node is deleted, so each
     * candidate has to be probed. Probing them one at a time costs a round trip per label, which
     * BI tools feel sharply because they poll metadata; instead every probe is sent as one {@code
     * UNION ALL}, keeping the cheap per-label index lookups but paying for a single round trip.
     *
     * @param catalogue the procedure listing the candidate names
     * @param match builds the {@code MATCH} clause that finds one entity for a name
     */
    private Set<String> inUse(String catalogue, java.util.function.UnaryOperator<String> match) throws SQLException {
        List<String> candidates = new ArrayList<>();
        for (Record record : query(catalogue)) {
            String name = asString(record.getValue(0));
            if (name != null && !name.isEmpty()) {
                candidates.add(name);
            }
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }
        StringBuilder probe = new StringBuilder();
        for (String name : candidates) {
            if (probe.length() > 0) {
                probe.append(" UNION ALL ");
            }
            probe.append(match.apply(name))
                    .append(" RETURN ")
                    .append(quote(name))
                    .append(" AS name LIMIT 1");
        }
        Set<String> present = new LinkedHashSet<>();
        for (Record record : query(probe.toString())) {
            String name = asString(record.getValue(0));
            if (name != null) {
                present.add(name);
            }
        }
        // Preserve the catalogue's ordering rather than the order the probe happened to return.
        Set<String> ordered = new LinkedHashSet<>();
        for (String name : candidates) {
            if (present.contains(name)) {
                ordered.add(name);
            }
        }
        return ordered;
    }

    /** Renders a name as a single-quoted Cypher string literal. */
    private static String quote(String name) {
        return "'" + name.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private com.falkordb.ResultSet query(String cypher) throws SQLException {
        try {
            return connection.graph().readOnlyQuery(cypher);
        } catch (RuntimeException e) {
            throw SQLErrors.translate(cypher, e);
        }
    }

    /**
     * Runs an introspection query whose procedure may not exist on every FalkorDB build, yielding no
     * rows if it is genuinely absent.
     *
     * <p>Only a missing procedure is swallowed. A dropped connection or a rejected credential must
     * not be reported as "this graph has no indexes" - that would leave tooling unable to tell an
     * empty schema from a broken connection.
     */
    private Iterable<Record> queryOrEmpty(String cypher) throws SQLException {
        try {
            return connection.graph().readOnlyQuery(cypher);
        } catch (RuntimeException e) {
            if (isMissingProcedure(e)) {
                return List.of();
            }
            throw SQLErrors.translate("Failed to read FalkorDB metadata with \"" + cypher + "\"", e);
        }
    }

    private static boolean isMissingProcedure(RuntimeException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        String text = message.toLowerCase(java.util.Locale.ROOT);
        return text.contains("unknown procedure") || text.contains("procedure not found");
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    /** Escapes a backtick-quoted Cypher identifier. */
    private static String escape(String identifier) {
        return identifier.replace("`", "``");
    }

    private boolean matchesCatalog(String catalog) {
        return catalog == null || catalog.isEmpty() || catalog.equals(connection.graphName());
    }

    /** Compiles a SQL {@code LIKE} pattern — {@code %} and {@code _} — into a regular expression. */
    private static Pattern like(String pattern) {
        if (pattern == null || "%".equals(pattern)) {
            return Pattern.compile(".*", Pattern.DOTALL);
        }
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                literal.append(pattern.charAt(++i));
            } else if (c == '%' || c == '_') {
                if (literal.length() > 0) {
                    regex.append(Pattern.quote(literal.toString()));
                    literal.setLength(0);
                }
                regex.append(c == '%' ? ".*" : ".");
            } else {
                literal.append(c);
            }
        }
        if (literal.length() > 0) {
            regex.append(Pattern.quote(literal.toString()));
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    private static ColumnMeta column(String label, FalkorType type) {
        return ColumnMeta.of(label, type, ResultSetMetaData.columnNullable);
    }

    private static ResultSet result(List<ColumnMeta> columns, List<List<Object>> rows) {
        List<List<Object>> typed = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            List<Object> converted = new ArrayList<>(row.size());
            for (int i = 0; i < row.size(); i++) {
                converted.add(narrow(row.get(i), columns.get(i).type()));
            }
            typed.add(converted);
        }
        return new FalkorDBResultSet(columns, typed, null);
    }

    /**
     * Boxes a metadata value as the Java type its column advertises.
     *
     * <p>JDBC fixes several metadata columns as {@code SMALLINT} or {@code INTEGER}, which are
     * narrower than the 64-bit integer FalkorDB itself has. Rows are built with plain {@code long}
     * literals, so without this {@code getObject} would hand back a {@link Long} for a column whose
     * {@code getColumnClassName()} promises {@link Integer}.
     */
    private static Object narrow(Object value, FalkorType type) {
        if (!(value instanceof Number number)) {
            return value;
        }
        if (type == FalkorType.METADATA_SMALLINT) {
            return (short) number.longValue();
        }
        if (type == FalkorType.METADATA_INTEGER) {
            return (int) number.longValue();
        }
        return value;
    }

    /**
     * The JDBC-mandated type of every non-string column of a {@link java.sql.DatabaseMetaData}
     * result set, per method. The specification fixes these, and it is not consistent across
     * methods — {@code DECIMAL_DIGITS} is {@code SMALLINT} for {@code getBestRowIdentifier} but
     * {@code INTEGER} for {@code getColumns} — so the table is keyed by method rather than by
     * column name alone. Any column not named here is a string.
     */
    private static final Map<String, Map<String, FalkorType>> METADATA_COLUMN_TYPES = metadataColumnTypes();

    private static Map<String, Map<String, FalkorType>> metadataColumnTypes() {
        Map<String, Map<String, FalkorType>> byMethod = new HashMap<>();
        byMethod.put(
                "getCrossReference",
                Map.ofEntries(
                        Map.entry("DEFERRABILITY", FalkorType.METADATA_SMALLINT),
                        Map.entry("DELETE_RULE", FalkorType.METADATA_SMALLINT),
                        Map.entry("KEY_SEQ", FalkorType.METADATA_SMALLINT),
                        Map.entry("UPDATE_RULE", FalkorType.METADATA_SMALLINT)));
        byMethod.put(
                "getAttributes",
                Map.ofEntries(
                        Map.entry("ATTR_SIZE", FalkorType.METADATA_INTEGER),
                        Map.entry("CHAR_OCTET_LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("DECIMAL_DIGITS", FalkorType.METADATA_INTEGER),
                        Map.entry("NULLABLE", FalkorType.METADATA_INTEGER),
                        Map.entry("NUM_PREC_RADIX", FalkorType.METADATA_INTEGER),
                        Map.entry("ORDINAL_POSITION", FalkorType.METADATA_INTEGER),
                        Map.entry("SOURCE_DATA_TYPE", FalkorType.METADATA_SMALLINT),
                        Map.entry("SQL_DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("SQL_DATETIME_SUB", FalkorType.METADATA_INTEGER)));
        byMethod.put(
                "getBestRowIdentifier",
                Map.ofEntries(
                        Map.entry("BUFFER_LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("COLUMN_SIZE", FalkorType.METADATA_INTEGER),
                        Map.entry("DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("DECIMAL_DIGITS", FalkorType.METADATA_SMALLINT),
                        Map.entry("PSEUDO_COLUMN", FalkorType.METADATA_SMALLINT),
                        Map.entry("SCOPE", FalkorType.METADATA_SMALLINT)));
        byMethod.put("getClientInfoProperties", Map.ofEntries(Map.entry("MAX_LEN", FalkorType.METADATA_INTEGER)));
        byMethod.put(
                "getColumns",
                Map.ofEntries(
                        Map.entry("BUFFER_LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("CHAR_OCTET_LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("COLUMN_SIZE", FalkorType.METADATA_INTEGER),
                        Map.entry("DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("DECIMAL_DIGITS", FalkorType.METADATA_INTEGER),
                        Map.entry("NULLABLE", FalkorType.METADATA_INTEGER),
                        Map.entry("NUM_PREC_RADIX", FalkorType.METADATA_INTEGER),
                        Map.entry("ORDINAL_POSITION", FalkorType.METADATA_INTEGER),
                        Map.entry("SOURCE_DATA_TYPE", FalkorType.METADATA_SMALLINT),
                        Map.entry("SQL_DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("SQL_DATETIME_SUB", FalkorType.METADATA_INTEGER)));
        byMethod.put(
                "getFunctionColumns",
                Map.ofEntries(
                        Map.entry("CHAR_OCTET_LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("COLUMN_TYPE", FalkorType.METADATA_SMALLINT),
                        Map.entry("DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("NULLABLE", FalkorType.METADATA_SMALLINT),
                        Map.entry("ORDINAL_POSITION", FalkorType.METADATA_INTEGER),
                        Map.entry("PRECISION", FalkorType.METADATA_INTEGER),
                        Map.entry("RADIX", FalkorType.METADATA_SMALLINT),
                        Map.entry("SCALE", FalkorType.METADATA_SMALLINT)));
        byMethod.put("getFunctions", Map.ofEntries(Map.entry("FUNCTION_TYPE", FalkorType.METADATA_SMALLINT)));
        byMethod.put(
                "getIndexInfo",
                Map.ofEntries(
                        Map.entry("CARDINALITY", FalkorType.INTEGER),
                        Map.entry("NON_UNIQUE", FalkorType.BOOLEAN),
                        Map.entry("ORDINAL_POSITION", FalkorType.METADATA_SMALLINT),
                        Map.entry("PAGES", FalkorType.INTEGER),
                        Map.entry("TYPE", FalkorType.METADATA_SMALLINT)));
        byMethod.put("getPrimaryKeys", Map.ofEntries(Map.entry("KEY_SEQ", FalkorType.METADATA_SMALLINT)));
        byMethod.put(
                "getProcedureColumns",
                Map.ofEntries(
                        Map.entry("CHAR_OCTET_LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("COLUMN_TYPE", FalkorType.METADATA_SMALLINT),
                        Map.entry("DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("NULLABLE", FalkorType.METADATA_SMALLINT),
                        Map.entry("ORDINAL_POSITION", FalkorType.METADATA_INTEGER),
                        Map.entry("PRECISION", FalkorType.METADATA_INTEGER),
                        Map.entry("RADIX", FalkorType.METADATA_SMALLINT),
                        Map.entry("SCALE", FalkorType.METADATA_SMALLINT),
                        Map.entry("SQL_DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("SQL_DATETIME_SUB", FalkorType.METADATA_INTEGER)));
        byMethod.put("getProcedures", Map.ofEntries(Map.entry("PROCEDURE_TYPE", FalkorType.METADATA_SMALLINT)));
        byMethod.put(
                "getPseudoColumns",
                Map.ofEntries(
                        Map.entry("CHAR_OCTET_LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("COLUMN_SIZE", FalkorType.METADATA_INTEGER),
                        Map.entry("DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("DECIMAL_DIGITS", FalkorType.METADATA_INTEGER),
                        Map.entry("NUM_PREC_RADIX", FalkorType.METADATA_INTEGER)));
        byMethod.put(
                "getTypeInfo",
                Map.ofEntries(
                        Map.entry("AUTO_INCREMENT", FalkorType.BOOLEAN),
                        Map.entry("CASE_SENSITIVE", FalkorType.BOOLEAN),
                        Map.entry("DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("FIXED_PREC_SCALE", FalkorType.BOOLEAN),
                        Map.entry("MAXIMUM_SCALE", FalkorType.METADATA_SMALLINT),
                        Map.entry("MINIMUM_SCALE", FalkorType.METADATA_SMALLINT),
                        Map.entry("NULLABLE", FalkorType.METADATA_SMALLINT),
                        Map.entry("NUM_PREC_RADIX", FalkorType.METADATA_INTEGER),
                        Map.entry("PRECISION", FalkorType.METADATA_INTEGER),
                        Map.entry("SEARCHABLE", FalkorType.METADATA_SMALLINT),
                        Map.entry("SQL_DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("SQL_DATETIME_SUB", FalkorType.METADATA_INTEGER),
                        Map.entry("UNSIGNED_ATTRIBUTE", FalkorType.BOOLEAN)));
        byMethod.put(
                "getUDTs",
                Map.ofEntries(
                        Map.entry("BASE_TYPE", FalkorType.METADATA_SMALLINT),
                        Map.entry("DATA_TYPE", FalkorType.METADATA_INTEGER)));
        byMethod.put(
                "getVersionColumns",
                Map.ofEntries(
                        Map.entry("BUFFER_LENGTH", FalkorType.METADATA_INTEGER),
                        Map.entry("COLUMN_SIZE", FalkorType.METADATA_INTEGER),
                        Map.entry("DATA_TYPE", FalkorType.METADATA_INTEGER),
                        Map.entry("DECIMAL_DIGITS", FalkorType.METADATA_SMALLINT),
                        Map.entry("PSEUDO_COLUMN", FalkorType.METADATA_SMALLINT),
                        Map.entry("SCOPE", FalkorType.METADATA_SMALLINT)));
        return Map.copyOf(byMethod);
    }

    /** Describes one column of the named {@link java.sql.DatabaseMetaData} result set. */
    private static ColumnMeta col(String method, String label) {
        return column(
                label, METADATA_COLUMN_TYPES.getOrDefault(method, Map.of()).getOrDefault(label, FalkorType.STRING));
    }

    private ResultSet empty(String method, String... labels) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>(labels.length);
        for (String label : labels) {
            columns.add(col(method, label));
        }
        return result(columns, List.of());
    }

    // ---------------------------------------------------------------- relational concepts with no graph analogue

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        return empty("getPrimaryKeys", "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
    }

    @Override
    public ResultSet getImportedKeys(String catalog, String schema, String table) throws SQLException {
        return foreignKeys();
    }

    @Override
    public ResultSet getExportedKeys(String catalog, String schema, String table) throws SQLException {
        return foreignKeys();
    }

    @Override
    public ResultSet getCrossReference(
            String parentCatalog,
            String parentSchema,
            String parentTable,
            String foreignCatalog,
            String foreignSchema,
            String foreignTable)
            throws SQLException {
        return foreignKeys();
    }

    private ResultSet foreignKeys() throws SQLException {
        return empty(
                "getCrossReference",
                "PKTABLE_CAT",
                "PKTABLE_SCHEM",
                "PKTABLE_NAME",
                "PKCOLUMN_NAME",
                "FKTABLE_CAT",
                "FKTABLE_SCHEM",
                "FKTABLE_NAME",
                "FKCOLUMN_NAME",
                "KEY_SEQ",
                "UPDATE_RULE",
                "DELETE_RULE",
                "FK_NAME",
                "PK_NAME",
                "DEFERRABILITY");
    }

    @Override
    public ResultSet getProcedureColumns(
            String catalog, String schemaPattern, String procedureNamePattern, String columnNamePattern)
            throws SQLException {
        return empty(
                "getProcedureColumns",
                "PROCEDURE_CAT",
                "PROCEDURE_SCHEM",
                "PROCEDURE_NAME",
                "COLUMN_NAME",
                "COLUMN_TYPE",
                "DATA_TYPE",
                "TYPE_NAME",
                "PRECISION",
                "LENGTH",
                "SCALE",
                "RADIX",
                "NULLABLE",
                "REMARKS",
                "COLUMN_DEF",
                "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB",
                "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION",
                "IS_NULLABLE",
                "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getFunctions(String catalog, String schemaPattern, String functionNamePattern)
            throws SQLException {
        return empty(
                "getFunctions",
                "FUNCTION_CAT",
                "FUNCTION_SCHEM",
                "FUNCTION_NAME",
                "REMARKS",
                "FUNCTION_TYPE",
                "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getFunctionColumns(
            String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern)
            throws SQLException {
        return empty(
                "getFunctionColumns",
                "FUNCTION_CAT",
                "FUNCTION_SCHEM",
                "FUNCTION_NAME",
                "COLUMN_NAME",
                "COLUMN_TYPE",
                "DATA_TYPE",
                "TYPE_NAME",
                "PRECISION",
                "LENGTH",
                "SCALE",
                "RADIX",
                "NULLABLE",
                "REMARKS",
                "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION",
                "IS_NULLABLE",
                "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern)
            throws SQLException {
        return empty(
                "getColumnPrivileges",
                "TABLE_CAT",
                "TABLE_SCHEM",
                "TABLE_NAME",
                "COLUMN_NAME",
                "GRANTOR",
                "GRANTEE",
                "PRIVILEGE",
                "IS_GRANTABLE");
    }

    @Override
    public ResultSet getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern)
            throws SQLException {
        return empty(
                "getTablePrivileges",
                "TABLE_CAT",
                "TABLE_SCHEM",
                "TABLE_NAME",
                "GRANTOR",
                "GRANTEE",
                "PRIVILEGE",
                "IS_GRANTABLE");
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        return empty(
                "getBestRowIdentifier",
                "SCOPE",
                "COLUMN_NAME",
                "DATA_TYPE",
                "TYPE_NAME",
                "COLUMN_SIZE",
                "BUFFER_LENGTH",
                "DECIMAL_DIGITS",
                "PSEUDO_COLUMN");
    }

    @Override
    public ResultSet getVersionColumns(String catalog, String schema, String table) throws SQLException {
        return empty(
                "getVersionColumns",
                "SCOPE",
                "COLUMN_NAME",
                "DATA_TYPE",
                "TYPE_NAME",
                "COLUMN_SIZE",
                "BUFFER_LENGTH",
                "DECIMAL_DIGITS",
                "PSEUDO_COLUMN");
    }

    @Override
    public ResultSet getUDTs(String catalog, String schemaPattern, String typeNamePattern, int[] types)
            throws SQLException {
        return empty(
                "getUDTs", "TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE");
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        return empty(
                "getSuperTypes",
                "TYPE_CAT",
                "TYPE_SCHEM",
                "TYPE_NAME",
                "SUPERTYPE_CAT",
                "SUPERTYPE_SCHEM",
                "SUPERTYPE_NAME");
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return empty("getSuperTables", "TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
    }

    @Override
    public ResultSet getAttributes(
            String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern)
            throws SQLException {
        return empty(
                "getAttributes",
                "TYPE_CAT",
                "TYPE_SCHEM",
                "TYPE_NAME",
                "ATTR_NAME",
                "DATA_TYPE",
                "ATTR_TYPE_NAME",
                "ATTR_SIZE",
                "DECIMAL_DIGITS",
                "NUM_PREC_RADIX",
                "NULLABLE",
                "REMARKS",
                "ATTR_DEF",
                "SQL_DATA_TYPE",
                "SQL_DATETIME_SUB",
                "CHAR_OCTET_LENGTH",
                "ORDINAL_POSITION",
                "IS_NULLABLE",
                "SCOPE_CATALOG",
                "SCOPE_SCHEMA",
                "SCOPE_TABLE",
                "SOURCE_DATA_TYPE");
    }

    @Override
    public ResultSet getPseudoColumns(
            String catalog, String schemaPattern, String tableNamePattern, String columnNamePattern)
            throws SQLException {
        return empty(
                "getPseudoColumns",
                "TABLE_CAT",
                "TABLE_SCHEM",
                "TABLE_NAME",
                "COLUMN_NAME",
                "DATA_TYPE",
                "COLUMN_SIZE",
                "DECIMAL_DIGITS",
                "NUM_PREC_RADIX",
                "COLUMN_USAGE",
                "REMARKS",
                "CHAR_OCTET_LENGTH",
                "IS_NULLABLE");
    }

    @Override
    public ResultSet getClientInfoProperties() throws SQLException {
        return empty("getClientInfoProperties", "NAME", "MAX_LEN", "DEFAULT_VALUE", "DESCRIPTION");
    }

    // ---------------------------------------------------------------- syntax and naming

    @Override
    public String getSQLKeywords() {
        // Cypher clauses a user might otherwise expect to be free to use as an identifier.
        return String.join(
                ",",
                "CALL",
                "CREATE",
                "DELETE",
                "DETACH",
                "FOREACH",
                "MATCH",
                "MERGE",
                "OPTIONAL",
                "REMOVE",
                "RETURN",
                "SET",
                "UNION",
                "UNWIND",
                "WITH",
                "YIELD");
    }

    @Override
    public String getNumericFunctions() {
        return String.join(",", "abs", "ceil", "e", "exp", "floor", "log", "log10", "rand", "round", "sign", "sqrt");
    }

    @Override
    public String getStringFunctions() {
        return String.join(
                ",",
                "left",
                "lTrim",
                "replace",
                "reverse",
                "right",
                "rTrim",
                "size",
                "split",
                "substring",
                "toLower",
                "toString",
                "toUpper",
                "trim");
    }

    @Override
    public String getSystemFunctions() {
        return String.join(",", "id", "labels", "type", "properties", "keys", "startNode", "endNode");
    }

    /**
     * {@return FalkorDB's temporal functions} Note that FalkorDB provides {@code localtime()} and
     * {@code localdatetime()}, not Cypher's zoned {@code time()} and {@code datetime()}; tools
     * generate queries from this list, so advertising the latter would produce invalid Cypher.
     */
    @Override
    public String getTimeDateFunctions() {
        return String.join(",", "date", "duration", "localdatetime", "localtime", "timestamp");
    }

    @Override
    public String getIdentifierQuoteString() {
        return "`";
    }

    @Override
    public String getSearchStringEscape() {
        return "\\";
    }

    @Override
    public String getExtraNameCharacters() {
        return "";
    }

    @Override
    public String getCatalogTerm() {
        return "graph";
    }

    @Override
    public String getSchemaTerm() {
        return "";
    }

    @Override
    public String getProcedureTerm() {
        return "procedure";
    }

    @Override
    public String getCatalogSeparator() {
        return ".";
    }

    @Override
    public boolean isCatalogAtStart() {
        return true;
    }

    @Override
    public boolean supportsMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean supportsMixedCaseQuotedIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseIdentifiers() {
        return true;
    }

    @Override
    public boolean storesUpperCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesLowerCaseQuotedIdentifiers() {
        return false;
    }

    @Override
    public boolean storesMixedCaseQuotedIdentifiers() {
        return true;
    }

    // ---------------------------------------------------------------- capabilities

    /**
     * {@return false, because no procedure is reachable through a {@link java.sql.CallableStatement}}
     *
     * <p>Read narrowly this flag is about privileges, and FalkorDB has no per-procedure ACL. But
     * the question a JDBC client asks it is whether the procedures {@link #getProcedures} just
     * listed can be <em>called</em>, and every {@code prepareCall} overload on this driver throws,
     * as {@link #supportsStoredProcedures()} already says. Answering {@code true} would describe a
     * path that does not exist. The procedures are still reachable, through Cypher {@code CALL}.
     */
    @Override
    public boolean allProceduresAreCallable() {
        return false;
    }

    /**
     * {@return false, because no table is reachable through a SQL {@code SELECT}}
     *
     * <p>The tables {@link #getTables} returns are graph labels and relationship types, and this
     * driver accepts only Cypher, so nothing can be used "in a {@code SELECT} statement" in the
     * sense this flag means. It reports {@code false} for the same reason {@link
     * #supportsColumnAliasing()} and {@link #supportsTableCorrelationNames()} do: a tool that
     * trusted a {@code true} would generate SQL the statement layer rejects. This will be revisited
     * if SQL-to-Cypher translation is added.
     */
    @Override
    public boolean allTablesAreSelectable() {
        return false;
    }

    @Override
    public boolean isReadOnly() throws SQLException {
        return connection.isReadOnly();
    }

    @Override
    public boolean nullsAreSortedHigh() {
        return true;
    }

    @Override
    public boolean nullsAreSortedLow() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtStart() {
        return false;
    }

    @Override
    public boolean nullsAreSortedAtEnd() {
        return false;
    }

    @Override
    public boolean usesLocalFiles() {
        return false;
    }

    @Override
    public boolean usesLocalFilePerTable() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithAddColumn() {
        return false;
    }

    @Override
    public boolean supportsAlterTableWithDropColumn() {
        return false;
    }

    @Override
    public boolean supportsColumnAliasing() {
        return false;
    }

    @Override
    public boolean nullPlusNonNullIsNull() {
        return true;
    }

    @Override
    public boolean supportsConvert() {
        return false;
    }

    @Override
    public boolean supportsConvert(int fromType, int toType) {
        return false;
    }

    @Override
    public boolean supportsTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    /**
     * The {@code supports*} methods below describe SQL grammar. This driver sends Cypher unchanged
     * and has no SQL-to-Cypher translation, so it reports {@code false} for all of them, matching
     * {@link #supportsMinimumSQLGrammar()}. Cypher has its own {@code ORDER BY}, aggregation and
     * {@code UNION}, but a client that took a {@code true} here would generate SQL this driver
     * cannot execute. That includes column aliasing and table correlation names: Cypher spells both
     * of them the same way SQL does, but a tool told they are supported emits {@code SELECT a AS b
     * FROM t x}, which this driver rejects like any other SQL. These will be revisited if a
     * translation layer is added.
     */
    @Override
    public boolean supportsExpressionsInOrderBy() {
        return false;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return false;
    }

    @Override
    public boolean supportsGroupBy() {
        return false;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return false;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return false;
    }

    @Override
    public boolean supportsLikeEscapeClause() {
        return false;
    }

    @Override
    public boolean supportsMultipleResultSets() {
        return false;
    }

    @Override
    public boolean supportsMultipleTransactions() {
        return false;
    }

    @Override
    public boolean supportsNonNullableColumns() {
        return false;
    }

    @Override
    public boolean supportsMinimumSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsCoreSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsExtendedSQLGrammar() {
        return false;
    }

    @Override
    public boolean supportsANSI92EntryLevelSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92IntermediateSQL() {
        return false;
    }

    @Override
    public boolean supportsANSI92FullSQL() {
        return false;
    }

    @Override
    public boolean supportsIntegrityEnhancementFacility() {
        return false;
    }

    @Override
    public boolean supportsOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsSchemasInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsSchemasInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsSchemasInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsSchemasInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInDataManipulation() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInProcedureCalls() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInTableDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInIndexDefinitions() {
        return false;
    }

    @Override
    public boolean supportsCatalogsInPrivilegeDefinitions() {
        return false;
    }

    @Override
    public boolean supportsPositionedDelete() {
        return false;
    }

    @Override
    public boolean supportsPositionedUpdate() {
        return false;
    }

    @Override
    public boolean supportsSelectForUpdate() {
        return false;
    }

    /**
     * {@return {@code false}} This method asks specifically about the JDBC stored-procedure escape
     * syntax, {@code {call ...}}, which the driver does not translate - {@link
     * java.sql.Connection#prepareCall} throws. FalkorDB's own procedures are perfectly usable
     * through Cypher's {@code CALL} on an ordinary {@link java.sql.Statement}, and are listed by
     * {@link #getProcedures}, but that is a different thing from what JDBC is asking here.
     */
    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return false;
    }

    @Override
    public boolean supportsUnion() {
        return false;
    }

    @Override
    public boolean supportsUnionAll() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenCursorsAcrossRollback() {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossCommit() {
        return false;
    }

    @Override
    public boolean supportsOpenStatementsAcrossRollback() {
        return false;
    }

    @Override
    public boolean supportsTransactions() {
        return false;
    }

    @Override
    public boolean supportsTransactionIsolationLevel(int level) {
        return level == Connection.TRANSACTION_NONE;
    }

    @Override
    public boolean supportsDataDefinitionAndDataManipulationTransactions() {
        return false;
    }

    @Override
    public boolean supportsDataManipulationTransactionsOnly() {
        return false;
    }

    @Override
    public boolean dataDefinitionCausesTransactionCommit() {
        return false;
    }

    @Override
    public boolean dataDefinitionIgnoredInTransactions() {
        return false;
    }

    @Override
    public int getDefaultTransactionIsolation() {
        return Connection.TRANSACTION_NONE;
    }

    /**
     * Batch updates are supported, but not atomically: FalkorDB has no client-side transaction, so a
     * batch is sent statement by statement and stops at the first failure with the earlier
     * statements already committed.
     *
     * @return {@code true}
     */
    @Override
    public boolean supportsBatchUpdates() {
        return true;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    /**
     * Named parameters are a callable-statement feature, and every {@code prepareCall} overload is
     * rejected. FalkorDB's own {@code $name} binding is reachable through the vendor extension
     * {@code FalkorDBPreparedStatement.setNamedObject}, which is not what this flag describes.
     */
    @Override
    public boolean supportsNamedParameters() {
        return false;
    }

    @Override
    public boolean supportsMultipleOpenResults() {
        return false;
    }

    @Override
    public boolean supportsGetGeneratedKeys() {
        return false;
    }

    @Override
    public boolean generatedKeyAlwaysReturned() {
        return false;
    }

    @Override
    public boolean supportsResultSetType(int type) {
        return type == ResultSet.TYPE_FORWARD_ONLY;
    }

    @Override
    public boolean supportsResultSetConcurrency(int type, int concurrency) {
        return type == ResultSet.TYPE_FORWARD_ONLY && concurrency == ResultSet.CONCUR_READ_ONLY;
    }

    @Override
    public boolean supportsResultSetHoldability(int holdability) {
        return holdability == ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public int getResultSetHoldability() {
        return ResultSet.CLOSE_CURSORS_AT_COMMIT;
    }

    @Override
    public boolean ownUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean ownInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersUpdatesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersDeletesAreVisible(int type) {
        return false;
    }

    @Override
    public boolean othersInsertsAreVisible(int type) {
        return false;
    }

    @Override
    public boolean updatesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean deletesAreDetected(int type) {
        return false;
    }

    @Override
    public boolean insertsAreDetected(int type) {
        return false;
    }

    @Override
    public boolean locatorsUpdateCopy() {
        return false;
    }

    @Override
    public boolean supportsStatementPooling() {
        return false;
    }

    @Override
    public boolean supportsStoredFunctionsUsingCallSyntax() {
        return false;
    }

    @Override
    public boolean autoCommitFailureClosesAllResultSets() {
        return false;
    }

    @Override
    public RowIdLifetime getRowIdLifetime() {
        return RowIdLifetime.ROWID_UNSUPPORTED;
    }

    @Override
    public boolean doesMaxRowSizeIncludeBlobs() {
        return false;
    }

    // ---------------------------------------------------------------- limits
    //
    // FalkorDB imposes no fixed limit on any of these; zero is JDBC's way of saying "unknown or
    // unlimited".

    @Override
    public int getMaxBinaryLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxCharLiteralLength() {
        return 0;
    }

    @Override
    public int getMaxColumnNameLength() {
        return 0;
    }

    @Override
    public int getMaxColumnsInGroupBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInIndex() {
        return 0;
    }

    @Override
    public int getMaxColumnsInOrderBy() {
        return 0;
    }

    @Override
    public int getMaxColumnsInSelect() {
        return 0;
    }

    @Override
    public int getMaxColumnsInTable() {
        return 0;
    }

    @Override
    public int getMaxConnections() {
        return connection.settings().poolMaxTotal().orElse(0);
    }

    @Override
    public int getMaxCursorNameLength() {
        return 0;
    }

    @Override
    public int getMaxIndexLength() {
        return 0;
    }

    @Override
    public int getMaxSchemaNameLength() {
        return 0;
    }

    @Override
    public int getMaxProcedureNameLength() {
        return 0;
    }

    @Override
    public int getMaxCatalogNameLength() {
        return 0;
    }

    @Override
    public int getMaxRowSize() {
        return 0;
    }

    @Override
    public int getMaxStatementLength() {
        return 0;
    }

    @Override
    public int getMaxStatements() {
        return 0;
    }

    @Override
    public int getMaxTableNameLength() {
        return 0;
    }

    @Override
    public int getMaxTablesInSelect() {
        return 0;
    }

    @Override
    public int getMaxUserNameLength() {
        return 0;
    }

    @Override
    public boolean supportsSharding() {
        return false;
    }

    /**
     * FalkorDB reports errors with SQL:2003-style SQLSTATEs assigned by this driver, not with X/Open
     * codes.
     */
    @Override
    public int getSQLStateType() {
        return sqlStateSQL;
    }
}
