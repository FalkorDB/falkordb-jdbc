package com.falkordb.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.assertj.core.api.Assertions.assertThat;

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
        void indexesAreListed() throws SQLException {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE INDEX FOR (p:Person) ON (p.name)");
            }

            assertThat(names(metaData.getIndexInfo(null, null, "Person", false, false), "COLUMN_NAME"))
                    .contains("name");
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
            try (ResultSet keys = metaData.getPrimaryKeys(null, null, "Person")) {
                ResultSetMetaData columns = keys.getMetaData();
                // KEY_SEQ is a number even when no row is there to hold one.
                assertThat(columns.getColumnName(5)).isEqualTo("KEY_SEQ");
                assertThat(columns.getColumnType(5)).isEqualTo(java.sql.Types.BIGINT);
                assertThat(columns.getColumnName(6)).isEqualTo("PK_NAME");
                assertThat(columns.getColumnType(6)).isEqualTo(java.sql.Types.VARCHAR);
            }
            try (ResultSet types = metaData.getUDTs(null, null, "%", null)) {
                ResultSetMetaData columns = types.getMetaData();
                assertThat(columns.getColumnName(5)).isEqualTo("DATA_TYPE");
                assertThat(columns.getColumnType(5)).isEqualTo(java.sql.Types.BIGINT);
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
