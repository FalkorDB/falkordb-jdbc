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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
                column("TABLE_CAT", FalkorType.STRING),
                column("TABLE_SCHEM", FalkorType.STRING),
                column("TABLE_NAME", FalkorType.STRING),
                column("COLUMN_NAME", FalkorType.STRING),
                column("DATA_TYPE", FalkorType.INTEGER),
                column("TYPE_NAME", FalkorType.STRING),
                column("COLUMN_SIZE", FalkorType.INTEGER),
                column("BUFFER_LENGTH", FalkorType.INTEGER),
                column("DECIMAL_DIGITS", FalkorType.INTEGER),
                column("NUM_PREC_RADIX", FalkorType.INTEGER),
                column("NULLABLE", FalkorType.INTEGER),
                column("REMARKS", FalkorType.STRING),
                column("COLUMN_DEF", FalkorType.STRING),
                column("SQL_DATA_TYPE", FalkorType.INTEGER),
                column("SQL_DATETIME_SUB", FalkorType.INTEGER),
                column("CHAR_OCTET_LENGTH", FalkorType.INTEGER),
                column("ORDINAL_POSITION", FalkorType.INTEGER),
                column("IS_NULLABLE", FalkorType.STRING),
                column("SCOPE_CATALOG", FalkorType.STRING),
                column("SCOPE_SCHEMA", FalkorType.STRING),
                column("SCOPE_TABLE", FalkorType.STRING),
                column("SOURCE_DATA_TYPE", FalkorType.INTEGER),
                column("IS_AUTOINCREMENT", FalkorType.STRING),
                column("IS_GENERATEDCOLUMN", FalkorType.STRING));
        if (!matchesCatalog(catalog)) {
            return result(columns, List.of());
        }

        Pattern tables = like(tableNamePattern);
        Pattern properties = like(columnNamePattern);
        String graph = connection.graphName();
        List<List<Object>> rows = new ArrayList<>();

        for (String label : labelsInUse()) {
            if (tables.matcher(label).matches()) {
                collectProperties(graph, label, "MATCH (e:`" + escape(label) + "`)", properties, rows);
            }
        }
        for (String type : relationshipTypesInUse()) {
            if (tables.matcher(type).matches()) {
                collectProperties(graph, type, "MATCH ()-[e:`" + escape(type) + "`]->()", properties, rows);
            }
        }
        return result(columns, rows);
    }

    private void collectProperties(
            String graph, String table, String match, Pattern properties, List<List<Object>> into) throws SQLException {
        String cypher = match + " WITH e LIMIT " + SAMPLE_LIMIT
                + " UNWIND keys(e) AS key RETURN key, collect(DISTINCT e[key])[0] AS sample, count(*) AS present";
        int ordinal = 0;
        for (Record record : query(cypher)) {
            Object key = record.getValue(0);
            if (key == null || !properties.matcher(key.toString()).matches()) {
                continue;
            }
            FalkorType type = FalkorType.of(record.getValue(1));
            ordinal++;
            List<Object> row = new ArrayList<>(24);
            row.add(graph);
            row.add(null);
            row.add(table);
            row.add(key.toString());
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
                column("TABLE_CAT", FalkorType.STRING),
                column("TABLE_SCHEM", FalkorType.STRING),
                column("TABLE_NAME", FalkorType.STRING),
                column("NON_UNIQUE", FalkorType.BOOLEAN),
                column("INDEX_QUALIFIER", FalkorType.STRING),
                column("INDEX_NAME", FalkorType.STRING),
                column("TYPE", FalkorType.INTEGER),
                column("ORDINAL_POSITION", FalkorType.INTEGER),
                column("COLUMN_NAME", FalkorType.STRING),
                column("ASC_OR_DESC", FalkorType.STRING),
                column("CARDINALITY", FalkorType.INTEGER),
                column("PAGES", FalkorType.INTEGER),
                column("FILTER_CONDITION", FalkorType.STRING));
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
                column("PROCEDURE_CAT", FalkorType.STRING),
                column("PROCEDURE_SCHEM", FalkorType.STRING),
                column("PROCEDURE_NAME", FalkorType.STRING),
                column("reserved1", FalkorType.STRING),
                column("reserved2", FalkorType.STRING),
                column("reserved3", FalkorType.STRING),
                column("REMARKS", FalkorType.STRING),
                column("PROCEDURE_TYPE", FalkorType.INTEGER),
                column("SPECIFIC_NAME", FalkorType.STRING));
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
        return result(columns, rows);
    }

    /** Describes every type this driver can return, mirroring the driver's type-mapping table. */
    @Override
    public ResultSet getTypeInfo() throws SQLException {
        List<ColumnMeta> columns = List.of(
                column("TYPE_NAME", FalkorType.STRING),
                column("DATA_TYPE", FalkorType.INTEGER),
                column("PRECISION", FalkorType.INTEGER),
                column("LITERAL_PREFIX", FalkorType.STRING),
                column("LITERAL_SUFFIX", FalkorType.STRING),
                column("CREATE_PARAMS", FalkorType.STRING),
                column("NULLABLE", FalkorType.INTEGER),
                column("CASE_SENSITIVE", FalkorType.BOOLEAN),
                column("SEARCHABLE", FalkorType.INTEGER),
                column("UNSIGNED_ATTRIBUTE", FalkorType.BOOLEAN),
                column("FIXED_PREC_SCALE", FalkorType.BOOLEAN),
                column("AUTO_INCREMENT", FalkorType.BOOLEAN),
                column("LOCAL_TYPE_NAME", FalkorType.STRING),
                column("MINIMUM_SCALE", FalkorType.INTEGER),
                column("MAXIMUM_SCALE", FalkorType.INTEGER),
                column("SQL_DATA_TYPE", FalkorType.INTEGER),
                column("SQL_DATETIME_SUB", FalkorType.INTEGER),
                column("NUM_PREC_RADIX", FalkorType.INTEGER));
        List<List<Object>> rows = new ArrayList<>();
        for (FalkorType type : FalkorType.values()) {
            if (type == FalkorType.NULL || type == FalkorType.UNKNOWN) {
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
        return result(columns, rows);
    }

    // ---------------------------------------------------------------- introspection helpers

    private Set<String> labelsInUse() throws SQLException {
        Set<String> labels = new LinkedHashSet<>();
        for (Record record : query("CALL db.labels()")) {
            String label = asString(record.getValue(0));
            // db.labels() keeps reporting a label after its last node is deleted; skip those.
            if (label != null && !label.isEmpty() && exists("MATCH (e:`" + escape(label) + "`) RETURN 1 LIMIT 1")) {
                labels.add(label);
            }
        }
        return labels;
    }

    private Set<String> relationshipTypesInUse() throws SQLException {
        Set<String> types = new LinkedHashSet<>();
        for (Record record : query("CALL db.relationshipTypes()")) {
            String type = asString(record.getValue(0));
            if (type != null && !type.isEmpty() && exists("MATCH ()-[e:`" + escape(type) + "`]->() RETURN 1 LIMIT 1")) {
                types.add(type);
            }
        }
        return types;
    }

    private boolean exists(String cypher) throws SQLException {
        return query(cypher).size() > 0;
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
     * rows instead of failing the metadata call.
     */
    private Iterable<Record> queryOrEmpty(String cypher) {
        try {
            return connection.graph().readOnlyQuery(cypher);
        } catch (RuntimeException e) {
            return List.of();
        }
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
        return new FalkorDBResultSet(columns, rows, null);
    }

    private ResultSet empty(String... labels) throws SQLException {
        List<ColumnMeta> columns = new ArrayList<>(labels.length);
        for (String label : labels) {
            columns.add(column(label, FalkorType.STRING));
        }
        return result(columns, List.of());
    }

    // ---------------------------------------------------------------- relational concepts with no graph analogue

    @Override
    public ResultSet getPrimaryKeys(String catalog, String schema, String table) throws SQLException {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "COLUMN_NAME", "KEY_SEQ", "PK_NAME");
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
        return empty("FUNCTION_CAT", "FUNCTION_SCHEM", "FUNCTION_NAME", "REMARKS", "FUNCTION_TYPE", "SPECIFIC_NAME");
    }

    @Override
    public ResultSet getFunctionColumns(
            String catalog, String schemaPattern, String functionNamePattern, String columnNamePattern)
            throws SQLException {
        return empty(
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
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "GRANTOR", "GRANTEE", "PRIVILEGE", "IS_GRANTABLE");
    }

    @Override
    public ResultSet getBestRowIdentifier(String catalog, String schema, String table, int scope, boolean nullable)
            throws SQLException {
        return empty(
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
        return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "CLASS_NAME", "DATA_TYPE", "REMARKS", "BASE_TYPE");
    }

    @Override
    public ResultSet getSuperTypes(String catalog, String schemaPattern, String typeNamePattern) throws SQLException {
        return empty("TYPE_CAT", "TYPE_SCHEM", "TYPE_NAME", "SUPERTYPE_CAT", "SUPERTYPE_SCHEM", "SUPERTYPE_NAME");
    }

    @Override
    public ResultSet getSuperTables(String catalog, String schemaPattern, String tableNamePattern) throws SQLException {
        return empty("TABLE_CAT", "TABLE_SCHEM", "TABLE_NAME", "SUPERTABLE_NAME");
    }

    @Override
    public ResultSet getAttributes(
            String catalog, String schemaPattern, String typeNamePattern, String attributeNamePattern)
            throws SQLException {
        return empty(
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
        return result(
                List.of(
                        column("NAME", FalkorType.STRING),
                        column("MAX_LEN", FalkorType.INTEGER),
                        column("DEFAULT_VALUE", FalkorType.STRING),
                        column("DESCRIPTION", FalkorType.STRING)),
                List.of());
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

    @Override
    public String getTimeDateFunctions() {
        return String.join(",", "date", "datetime", "duration", "localtime", "time", "timestamp");
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

    @Override
    public boolean allProceduresAreCallable() {
        return true;
    }

    @Override
    public boolean allTablesAreSelectable() {
        return true;
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
        return true;
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
        return true;
    }

    @Override
    public boolean supportsDifferentTableCorrelationNames() {
        return false;
    }

    @Override
    public boolean supportsExpressionsInOrderBy() {
        return true;
    }

    @Override
    public boolean supportsOrderByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupBy() {
        return true;
    }

    @Override
    public boolean supportsGroupByUnrelated() {
        return true;
    }

    @Override
    public boolean supportsGroupByBeyondSelect() {
        return true;
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
        return true;
    }

    @Override
    public boolean supportsFullOuterJoins() {
        return false;
    }

    @Override
    public boolean supportsLimitedOuterJoins() {
        return true;
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

    @Override
    public boolean supportsStoredProcedures() {
        return false;
    }

    @Override
    public boolean supportsSubqueriesInComparisons() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInExists() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInIns() {
        return true;
    }

    @Override
    public boolean supportsSubqueriesInQuantifieds() {
        return false;
    }

    @Override
    public boolean supportsCorrelatedSubqueries() {
        return true;
    }

    @Override
    public boolean supportsUnion() {
        return true;
    }

    @Override
    public boolean supportsUnionAll() {
        return true;
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

    @Override
    public boolean supportsBatchUpdates() {
        return false;
    }

    @Override
    public boolean supportsSavepoints() {
        return false;
    }

    @Override
    public boolean supportsNamedParameters() {
        return true;
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
