package com.falkordb.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link DatabaseMetaData} against a real server: labels as tables, relationship types, catalogs. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatabaseMetaDataIT {

    private Connection connection;
    private DatabaseMetaData metaData;

    @BeforeAll
    void connect() throws SQLException {
        connection = TestServer.connect("jdbc-metadata");
        try (Statement statement = connection.createStatement()) {
            statement.execute("MATCH (n) DETACH DELETE n");
            statement.executeUpdate(
                    """
                    CREATE (alice:Person {name: 'Alice', age: 34}),
                           (acme:Company {name: 'Acme'}),
                           (alice)-[:WORKS_AT {since: 2020}]->(acme)
                    """);
        }
        metaData = connection.getMetaData();
    }

    @AfterAll
    void disconnect() throws SQLException {
        connection.close();
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        void reportsTheProductNameAndVersion() throws SQLException {
            assertThat(metaData.getDatabaseProductName()).isEqualTo("FalkorDB");
            assertThat(metaData.getDatabaseProductVersion()).isNotBlank();
            assertThat(metaData.getDatabaseMajorVersion()).isPositive();
        }

        @Test
        void reportsTheDriverNameAndVersion() throws SQLException {
            assertThat(metaData.getDriverName()).isEqualTo("FalkorDB JDBC Driver");
            assertThat(metaData.getDriverVersion()).isNotBlank();
            assertThat(metaData.getJDBCMajorVersion()).isEqualTo(4);
            assertThat(metaData.getJDBCMinorVersion()).isEqualTo(3);
        }

        @Test
        void reportsTheConnectionUrlAndUser() throws SQLException {
            assertThat(metaData.getURL()).startsWith("jdbc:falkordb://");
            assertThat(metaData.getUserName()).isNotNull();
            assertThat(metaData.getConnection()).isSameAs(connection);
        }

        @Test
        void isNotSqlCompliant() throws SQLException {
            assertThat(metaData.supportsANSI92EntryLevelSQL()).isFalse();
            assertThat(metaData.supportsTransactions()).isFalse();
            assertThat(metaData.isReadOnly()).isFalse();
            // This asks about the JDBC {call ...} escape, which the driver does not translate, even
            // though FalkorDB's procedures are reachable through Cypher CALL and getProcedures().
            assertThat(metaData.supportsStoredProcedures()).isFalse();
            // Cypher spells aliasing and correlation names the way SQL does, but a tool told these
            // are supported emits SQL, and the driver accepts only Cypher.
            assertThat(metaData.supportsColumnAliasing()).isFalse();
            assertThat(metaData.supportsTableCorrelationNames()).isFalse();
        }

        @Test
        void listsCypherKeywordsAndFunctions() throws SQLException {
            assertThat(metaData.getSQLKeywords()).contains("MATCH").contains("MERGE");
            assertThat(metaData.getNumericFunctions()).isNotBlank();
            assertThat(metaData.getStringFunctions()).contains("toUpper");
        }
    }

    @Nested
    @DisplayName("catalogs")
    class Catalogs {

        @Test
        void listTheGraphsOnTheServer() throws SQLException {
            assertThat(names(metaData.getCatalogs(), "TABLE_CAT")).contains("jdbc-metadata");
        }

        @Test
        void areNamedGraph() throws SQLException {
            assertThat(metaData.getCatalogTerm()).isEqualTo("graph");
            assertThat(metaData.supportsCatalogsInDataManipulation()).isFalse();
        }

        @Test
        void haveNoSchemas() throws SQLException {
            assertThat(names(metaData.getSchemas(), "TABLE_SCHEM")).isEmpty();
        }
    }

    @Nested
    @DisplayName("tables")
    class Tables {

        @Test
        void areTheGraphsLabels() throws SQLException {
            List<String> tables = names(metaData.getTables(null, null, "%", null), "TABLE_NAME");

            assertThat(tables).contains("Person", "Company");
        }

        @Test
        void carryTheirLabelAsTheirType() throws SQLException {
            try (ResultSet tables = metaData.getTables(null, null, "Person", null)) {
                assertThat(tables.next()).isTrue();
                assertThat(tables.getString("TABLE_NAME")).isEqualTo("Person");
                assertThat(tables.getString("TABLE_TYPE")).isEqualTo("TABLE");
                assertThat(tables.getString("TABLE_CAT")).isEqualTo("jdbc-metadata");
                assertThat(tables.next()).isFalse();
            }
        }

        @Test
        void includeRelationshipTypes() throws SQLException {
            List<String> relationships =
                    names(metaData.getTables(null, null, "%", new String[] {"RELATIONSHIP"}), "TABLE_NAME");

            assertThat(relationships).contains("WORKS_AT");
        }

        @Test
        void canBeFilteredByPattern() throws SQLException {
            assertThat(names(metaData.getTables(null, null, "Per%", null), "TABLE_NAME"))
                    .containsExactly("Person");
        }

        @Test
        void areAlsoReportedByTableType() throws SQLException {
            assertThat(names(metaData.getTableTypes(), "TABLE_TYPE")).contains("TABLE", "RELATIONSHIP");
        }
    }

    @Nested
    @DisplayName("columns")
    class Columns {

        @Test
        void areTheSampledPropertiesOfALabel() throws SQLException {
            List<String> columns = names(metaData.getColumns(null, null, "Person", "%"), "COLUMN_NAME");

            assertThat(columns).contains("name", "age");
        }

        @Test
        void carryATypeInferredFromTheData() throws SQLException {
            try (ResultSet columns = metaData.getColumns(null, null, "Person", "age")) {
                assertThat(columns.next()).isTrue();
                assertThat(columns.getString("COLUMN_NAME")).isEqualTo("age");
                assertThat(columns.getInt("DATA_TYPE")).isEqualTo(java.sql.Types.BIGINT);
                assertThat(columns.getString("TYPE_NAME")).isEqualTo("INTEGER");
                assertThat(columns.getInt("NULLABLE")).isEqualTo(DatabaseMetaData.columnNullable);
            }
        }

        @Test
        void reportAMixedPropertyAsUntyped() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE (:Mixed {v: 1}), (:Mixed {v: 'text'})");
            }

            try (ResultSet columns = metaData.getColumns(null, null, "Mixed", "v")) {
                assertThat(columns.next()).isTrue();
                // No single JDBC type describes a property that holds both an integer and a string.
                assertThat(columns.getInt("DATA_TYPE")).isEqualTo(java.sql.Types.OTHER);
                assertThat(columns.getString("TYPE_NAME")).isEqualTo("UNKNOWN");
            }
        }

        @Test
        void numberColumnsByTheirPlaceInTheTableNotTheFilter() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE (:Ordered {a: 1, b: 2, c: 3})");
            }

            int unfiltered;
            try (ResultSet columns = metaData.getColumns(null, null, "Ordered", "c")) {
                assertThat(columns.next()).isTrue();
                unfiltered = columns.getInt("ORDINAL_POSITION");
            }

            // 'c' is the third property; asking only for it must not renumber it to the first.
            assertThat(unfiltered).isEqualTo(3);
        }

        @Test
        void areNumberedStablyAcrossCalls() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE (:Stable {zeta: 1, alpha: 2, mu: 3})");
            }

            assertThat(ordinals("Stable")).isEqualTo(ordinals("Stable")).containsExactly(1, 2, 3);
            // keys(e) may answer in a different order each call, so the driver imposes one.
            assertThat(names(metaData.getColumns(null, null, "Stable", "%"), "COLUMN_NAME"))
                    .containsExactly("alpha", "mu", "zeta");
        }

        @Test
        void areOrderedByTableThenOrdinalPosition() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE (:SortBravo {b: 1}), (:SortAlpha {a: 1, z: 2})");
            }

            List<String> seen = new ArrayList<>();
            try (ResultSet columns = metaData.getColumns(null, null, "Sort%", "%")) {
                while (columns.next()) {
                    seen.add(columns.getString("TABLE_NAME") + "." + columns.getInt("ORDINAL_POSITION"));
                }
            }

            // Created Bravo first, but the rows come back ordered by table then ordinal.
            assertThat(seen).containsExactly("SortAlpha.1", "SortAlpha.2", "SortBravo.1");
        }

        @Test
        void describeEveryMatchingLabelInOneSamplingQuery() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE (:BatchOne {p: 1}), (:BatchTwo {q: 2}), (:BatchThree {r: 3})");
            }

            long before = graphQueriesServed();
            List<String> seen = names(metaData.getColumns(null, null, "Batch%", "%"), "COLUMN_NAME");
            long queries = graphQueriesServed() - before;

            assertThat(seen).containsExactlyInAnyOrder("p", "q", "r");
            // Five today: the label and relationship-type catalogues, an in-use probe for each, and
            // one UNION ALL that samples all three labels. Sampling per label would make it seven,
            // which is the cost BI tools feel when they poll getColumns.
            assertThat(queries).isLessThanOrEqualTo(5);
        }

        /** Counts the graph queries the server has executed, from Redis {@code INFO commandstats}. */
        private long graphQueriesServed() {
            try (redis.clients.jedis.Jedis probe =
                    new redis.clients.jedis.Jedis(TestServer.host(), TestServer.port())) {
                long total = 0;
                for (String line : probe.info("commandstats").split("\r?\n")) {
                    // FalkorDB registers its commands as graph.QUERY, so the case is not uniform.
                    String name = line.toLowerCase(java.util.Locale.ROOT);
                    if (name.startsWith("cmdstat_graph.query:") || name.startsWith("cmdstat_graph.ro_query:")) {
                        java.util.regex.Matcher calls =
                                java.util.regex.Pattern.compile("calls=(\\d+)").matcher(line);
                        if (calls.find()) {
                            total += Long.parseLong(calls.group(1));
                        }
                    }
                }
                return total;
            }
        }

        @Test
        void keepALabelAndARelationshipTypeOfTheSameNameApart() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        """
                        CREATE (a:Twin {nodeSide: 1}), (b:Twin {nodeSide: 2}),
                               (a)-[:Twin {edgeSide: 3}]->(b)
                        """);
            }

            List<String> seen = names(metaData.getColumns(null, null, "Twin", "%"), "COLUMN_NAME");

            // The batched query tags its branches by position, because the name does not tell a
            // label and a relationship type apart.
            assertThat(seen).containsExactlyInAnyOrder("nodeSide", "edgeSide");
        }

        private List<Integer> ordinals(String table) throws SQLException {
            List<Integer> found = new ArrayList<>();
            try (ResultSet columns = metaData.getColumns(null, null, table, "%")) {
                while (columns.next()) {
                    found.add(columns.getInt("ORDINAL_POSITION"));
                }
            }
            return found;
        }

        @Test
        void areEmptyForAnUnknownLabel() throws SQLException {
            assertThat(names(metaData.getColumns(null, null, "NoSuchLabel", "%"), "COLUMN_NAME"))
                    .isEmpty();
        }

        @Test
        void listTheGraphsPropertyKeys() throws SQLException {
            List<String> keys = new ArrayList<>();
            try (Statement statement = connection.createStatement();
                    ResultSet results = statement.executeQuery("CALL db.propertyKeys()")) {
                while (results.next()) {
                    keys.add(results.getString(1));
                }
            }

            assertThat(keys).contains("name", "age", "since");
        }
    }

    @Nested
    @DisplayName("procedures and indexes")
    class ProceduresAndIndexes {

        @Test
        void advertisesOnlyTemporalFunctionsThatExist() throws SQLException {
            String functions = metaData.getTimeDateFunctions();

            assertThat(functions)
                    .contains("localtime")
                    .contains("localdatetime")
                    .contains("date");
            for (String function : functions.split(",")) {
                // duration() is the only advertised temporal function that needs an argument.
                String call = "duration".equals(function) ? "duration({hours: 1})" : function + "()";
                try (Statement statement = connection.createStatement();
                        ResultSet results = statement.executeQuery("RETURN " + call + " IS NOT NULL AS ok")) {
                    results.next();
                    assertThat(results.getBoolean("ok"))
                            .as("%s should exist", call)
                            .isTrue();
                }
            }
        }

        @Test
        void proceduresAreListed() throws SQLException {
            assertThat(names(metaData.getProcedures(null, null, "%"), "PROCEDURE_NAME"))
                    .contains("db.labels");
        }

        @Test
        void proceduresAreOrderedByName() throws SQLException {
            List<String> names = names(metaData.getProcedures(null, null, "%"), "PROCEDURE_NAME");

            assertThat(names).isSorted();
        }

        @Test
        void indexesAreListed() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE INDEX FOR (p:Person) ON (p.name)");
            }

            assertThat(names(metaData.getIndexInfo(null, null, "Person", false, false), "COLUMN_NAME"))
                    .contains("name");
        }

        @Test
        void indexesAreOrderedAsJdbcRequires() throws SQLException {
            // Distinct labels, so this does not collide with the index another test creates.
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE (:IdxBeta {k: 1}), (:IdxAlpha {k: 1})");
                statement.execute("CREATE INDEX FOR (b:IdxBeta) ON (b.k)");
                statement.execute("CREATE INDEX FOR (a:IdxAlpha) ON (a.k)");
            }

            List<String> seen = new ArrayList<>();
            try (ResultSet indexes = metaData.getIndexInfo(null, null, null, false, false)) {
                while (indexes.next()) {
                    seen.add(indexes.getString("INDEX_NAME") + "#" + indexes.getInt("ORDINAL_POSITION"));
                }
            }

            // Every row here is non-unique and of the same TYPE, so the ordering falls to
            // INDEX_NAME then ORDINAL_POSITION.
            assertThat(seen).isSorted();
        }
    }

    @Nested
    @DisplayName("type info")
    class TypeInfo {

        @Test
        void describesEveryFalkorDbType() throws SQLException {
            List<String> types = names(metaData.getTypeInfo(), "TYPE_NAME");

            assertThat(types).contains("STRING", "INTEGER", "BOOLEAN", "DOUBLE", "NODE", "RELATIONSHIP", "POINT");
        }

        @Test
        void omitsTheVectorElementType() throws SQLException {
            List<String> types = names(metaData.getTypeInfo(), "TYPE_NAME");

            // FLOAT32 only ever describes the elements of a vecf32 vector; FalkorDB has no 32-bit
            // scalar, so offering it as a column type would advertise something unreachable.
            assertThat(types).doesNotContain("FLOAT32").contains("VECTORF32");
        }

        @Test
        void isOrderedByDataType() throws SQLException {
            List<Integer> dataTypes = new ArrayList<>();
            try (ResultSet types = metaData.getTypeInfo()) {
                while (types.next()) {
                    dataTypes.add(types.getInt("DATA_TYPE"));
                }
            }

            assertThat(dataTypes).isSorted();
        }
    }

    @Nested
    @DisplayName("empty result sets")
    class EmptyResults {

        @Test
        void areReturnedForRelationalOnlyConcepts() throws SQLException {
            assertThat(metaData.getPrimaryKeys(null, null, "Person").next()).isFalse();
            assertThat(metaData.getImportedKeys(null, null, "Person").next()).isFalse();
            assertThat(metaData.getExportedKeys(null, null, "Person").next()).isFalse();
            assertThat(metaData.getUDTs(null, null, "%", null).next()).isFalse();
            assertThat(metaData.getSuperTypes(null, null, "%").next()).isFalse();
        }

        @Test
        void haveTheJdbcMandatedColumns() throws SQLException {
            assertThat(metaData.getPrimaryKeys(null, null, "Person")
                            .getMetaData()
                            .getColumnCount())
                    .isEqualTo(6);
        }

        @Test
        void haveTheJdbcMandatedColumnTypes() throws SQLException {
            // The specification fixes these, and it is deliberately not uniform: DECIMAL_DIGITS is
            // SMALLINT for getBestRowIdentifier but INTEGER for getColumns.
            assertColumnTypes(
                    metaData.getPrimaryKeys(null, null, "Person"),
                    Map.of("KEY_SEQ", Types.SMALLINT, "PK_NAME", Types.VARCHAR));
            assertColumnTypes(
                    metaData.getUDTs(null, null, "%", null),
                    Map.of("DATA_TYPE", Types.INTEGER, "BASE_TYPE", Types.SMALLINT));
            assertColumnTypes(
                    metaData.getImportedKeys(null, null, "Person"),
                    Map.of(
                            "KEY_SEQ", Types.SMALLINT,
                            "UPDATE_RULE", Types.SMALLINT,
                            "DELETE_RULE", Types.SMALLINT,
                            "DEFERRABILITY", Types.SMALLINT));
            assertColumnTypes(
                    metaData.getBestRowIdentifier(null, null, "Person", 0, true),
                    Map.of(
                            "SCOPE", Types.SMALLINT,
                            "DATA_TYPE", Types.INTEGER,
                            "COLUMN_SIZE", Types.INTEGER,
                            "DECIMAL_DIGITS", Types.SMALLINT,
                            "PSEUDO_COLUMN", Types.SMALLINT));
            assertColumnTypes(
                    metaData.getColumns(null, null, "Person", "%"),
                    Map.of(
                            "DATA_TYPE", Types.INTEGER,
                            "NULLABLE", Types.INTEGER,
                            "DECIMAL_DIGITS", Types.INTEGER,
                            "ORDINAL_POSITION", Types.INTEGER,
                            "SOURCE_DATA_TYPE", Types.SMALLINT));
            assertColumnTypes(
                    metaData.getIndexInfo(null, null, "Person", false, true),
                    Map.of(
                            "NON_UNIQUE", Types.BOOLEAN,
                            "TYPE", Types.SMALLINT,
                            "ORDINAL_POSITION", Types.SMALLINT,
                            "CARDINALITY", Types.BIGINT,
                            "PAGES", Types.BIGINT));
            assertColumnTypes(metaData.getProcedures(null, null, "%"), Map.of("PROCEDURE_TYPE", Types.SMALLINT));
            assertColumnTypes(
                    metaData.getTypeInfo(),
                    Map.of(
                            "DATA_TYPE", Types.INTEGER,
                            "PRECISION", Types.INTEGER,
                            "NULLABLE", Types.SMALLINT,
                            "CASE_SENSITIVE", Types.BOOLEAN,
                            "SEARCHABLE", Types.SMALLINT,
                            "MINIMUM_SCALE", Types.SMALLINT));
        }

        @Test
        void doesNotAdvertiseTheDriversOwnMetadataHelperTypes() throws SQLException {
            // METADATA_SMALLINT/METADATA_INTEGER exist to type DatabaseMetaData columns. They are
            // not types FalkorDB can store, so getTypeInfo() must not offer them.
            List<String> names = names(metaData.getTypeInfo(), "TYPE_NAME");

            assertThat(names).doesNotContain("SMALLINT");
            assertThat(names).containsOnlyOnce("INTEGER");
            assertThat(names).contains("STRING", "DOUBLE", "BOOLEAN");
        }

        @Test
        void returnsValuesOfTheJavaTypeEachColumnAdvertises() throws SQLException {
            // getColumnClassName() promising Integer while getObject() hands back a Long would make
            // the metadata contract a lie.
            try (ResultSet types = metaData.getTypeInfo()) {
                ResultSetMetaData columns = types.getMetaData();
                assertThat(types.next()).isTrue();
                for (int i = 1; i <= columns.getColumnCount(); i++) {
                    Object value = types.getObject(i);
                    if (value != null) {
                        assertThat(value)
                                .describedAs("column %s", columns.getColumnName(i))
                                .isInstanceOf(Class.forName(columns.getColumnClassName(i)));
                    }
                }
            } catch (ClassNotFoundException e) {
                throw new AssertionError(e);
            }
        }

        @Test
        void typesClientInfoMaxLengthAsAJdbcInteger() throws SQLException {
            assertColumnTypes(metaData.getClientInfoProperties(), Map.of("MAX_LEN", Types.INTEGER));
        }

        @Test
        void doesNotClaimSqlGrammarItCannotExecute() throws SQLException {
            // The driver sends Cypher unchanged. Claiming SQL joins or unions would make a client
            // generate SQL that the statement layer cannot run.
            assertThat(metaData.supportsMinimumSQLGrammar()).isFalse();
            assertThat(metaData.supportsOuterJoins()).isFalse();
            assertThat(metaData.supportsLimitedOuterJoins()).isFalse();
            assertThat(metaData.supportsGroupBy()).isFalse();
            assertThat(metaData.supportsGroupByUnrelated()).isFalse();
            assertThat(metaData.supportsGroupByBeyondSelect()).isFalse();
            assertThat(metaData.supportsOrderByUnrelated()).isFalse();
            assertThat(metaData.supportsExpressionsInOrderBy()).isFalse();
            assertThat(metaData.supportsUnion()).isFalse();
            assertThat(metaData.supportsUnionAll()).isFalse();
            assertThat(metaData.supportsSubqueriesInComparisons()).isFalse();
            assertThat(metaData.supportsSubqueriesInExists()).isFalse();
            assertThat(metaData.supportsSubqueriesInIns()).isFalse();
            assertThat(metaData.supportsCorrelatedSubqueries()).isFalse();
        }

        @Test
        void doesNotClaimNamedParametersWhileRejectingCallableStatements() throws SQLException {
            assertThat(metaData.supportsNamedParameters()).isFalse();
            assertThatThrownBy(() -> connection.prepareCall("CALL db.labels()"))
                    .isInstanceOf(SQLFeatureNotSupportedException.class);
        }

        private void assertColumnTypes(ResultSet results, Map<String, Integer> expected) throws SQLException {
            try (results) {
                ResultSetMetaData columns = results.getMetaData();
                Map<String, Integer> actual = new LinkedHashMap<>();
                for (int i = 1; i <= columns.getColumnCount(); i++) {
                    if (expected.containsKey(columns.getColumnName(i))) {
                        actual.put(columns.getColumnName(i), columns.getColumnType(i));
                    }
                }
                assertThat(actual).containsExactlyInAnyOrderEntriesOf(expected);
            }
        }
    }

    private static List<String> names(ResultSet results, String column) throws SQLException {
        List<String> values = new ArrayList<>();
        try (results) {
            while (results.next()) {
                values.add(results.getString(column));
            }
        }
        return values;
    }
}
