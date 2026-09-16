package com.falkordb.jdbc;

import java.io.StringReader;
import java.io.StringWriter;
import java.sql.Array;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import javax.sql.RowSet;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.FilteredRowSet;
import javax.sql.rowset.JoinRowSet;
import javax.sql.rowset.Predicate;
import javax.sql.rowset.RowSetFactory;
import javax.sql.rowset.WebRowSet;
import javax.sql.rowset.spi.SyncProviderException;

import com.falkordb.graph_entities.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Disconnected {@link RowSet} use against a real server, through {@link FalkorDBRowSetFactory}. */
class RowSetIT {

    private static final String GRAPH = "jdbc-rowset";

    private final RowSetFactory factory = new FalkorDBRowSetFactory();

    @BeforeEach
    void seed() throws SQLException {
        try (Connection connection = TestServer.connect(GRAPH);
                Statement statement = connection.createStatement()) {
            statement.execute("MATCH (n) DETACH DELETE n");
            statement.executeUpdate("CREATE (:Person {name: 'Alice', age: 34}), (:Person {name: 'Bob', age: 20}),"
                    + " (:Person {name: 'Carol', age: 51})");
        }
    }

    private CachedRowSet executed(String cypher) throws SQLException {
        CachedRowSet rowSet = factory.createCachedRowSet();
        rowSet.setUrl(TestServer.url(GRAPH));
        rowSet.setCommand(cypher);
        rowSet.execute();
        return rowSet;
    }

    private static List<String> names(CachedRowSet rowSet) throws SQLException {
        List<String> names = new ArrayList<>();
        rowSet.beforeFirst();
        while (rowSet.next()) {
            names.add(rowSet.getString("name"));
        }
        return names;
    }

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        void runsTheCommandAsACypherQuery() throws SQLException {
            CachedRowSet rowSet = executed("MATCH (p:Person) RETURN p.name AS name ORDER BY p.name");

            assertThat(rowSet.size()).isEqualTo(3);
            assertThat(names(rowSet)).containsExactly("Alice", "Bob", "Carol");
        }

        @Test
        void bindsPositionalParameters() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.setUrl(TestServer.url(GRAPH));
            rowSet.setCommand("MATCH (p:Person) WHERE p.age > ? RETURN p.name AS name ORDER BY p.name");
            rowSet.setInt(1, 30);
            rowSet.execute();

            assertThat(names(rowSet)).containsExactly("Alice", "Carol");
        }

        @Test
        void bindsANullParameter() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.setUrl(TestServer.url(GRAPH));
            rowSet.setCommand("MATCH (p:Person) WHERE p.name = coalesce(?, 'Bob') RETURN p.name AS name");
            rowSet.setNull(1, Types.VARCHAR);
            rowSet.execute();

            assertThat(names(rowSet)).containsExactly("Bob");
        }

        @Test
        void replacesTheRowsEachTime() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.setUrl(TestServer.url(GRAPH));
            rowSet.setCommand("MATCH (p:Person) WHERE p.age > ? RETURN p.name AS name ORDER BY p.name");
            rowSet.setInt(1, 30);
            rowSet.execute();

            rowSet.setInt(1, 50);
            rowSet.execute();

            assertThat(rowSet.size()).isEqualTo(1);
            assertThat(names(rowSet)).containsExactly("Carol");
        }

        @Test
        void usesASuppliedConnectionAndLeavesItOpen() throws SQLException {
            try (Connection connection = TestServer.connect(GRAPH)) {
                CachedRowSet rowSet = factory.createCachedRowSet();
                rowSet.setCommand("MATCH (p:Person) RETURN p.name AS name ORDER BY p.name");

                rowSet.execute(connection);

                assertThat(names(rowSet)).containsExactly("Alice", "Bob", "Carol");
                assertThat(connection.isClosed()).isFalse();
            }
        }

        @Test
        void cachesGraphValues() throws SQLException {
            CachedRowSet rowSet = executed("MATCH (p:Person {name: 'Alice'}) RETURN p AS person, [1, 2, 3] AS numbers");
            rowSet.next();

            assertThat(rowSet.getObject("person")).isInstanceOf(Node.class);
            Array numbers = rowSet.getArray("numbers");
            assertThat((Object[]) numbers.getArray()).containsExactly(1L, 2L, 3L);
        }

        @Test
        void reportsAWriteThatReturnsNothing() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.setUrl(TestServer.url(GRAPH));
            rowSet.setCommand("CREATE (:Person {name: 'Dave'})");

            assertThatThrownBy(rowSet::execute).isInstanceOf(SQLException.class).hasMessageContaining("executeUpdate");
        }
    }

    @Nested
    @DisplayName("disconnected use")
    class Disconnected {

        @Test
        void outlivesTheConnectionItWasPopulatedFrom() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            try (Connection connection = TestServer.connect(GRAPH);
                    Statement statement = connection.createStatement();
                    ResultSet results =
                            statement.executeQuery("MATCH (p:Person) RETURN p.name AS name ORDER BY p.name")) {
                rowSet.populate(results);
            }

            assertThat(names(rowSet)).containsExactly("Alice", "Bob", "Carol");
        }

        @Test
        void filtersRowsInMemory() throws SQLException {
            FilteredRowSet rowSet = factory.createFilteredRowSet();
            rowSet.setUrl(TestServer.url(GRAPH));
            rowSet.setCommand("MATCH (p:Person) RETURN p.name AS name, p.age AS age ORDER BY p.name");
            rowSet.execute();

            rowSet.setFilter(new OlderThan(30));

            List<String> matched = new ArrayList<>();
            rowSet.beforeFirst();
            while (rowSet.next()) {
                matched.add(rowSet.getString("name"));
            }
            assertThat(matched).containsExactly("Alice", "Carol");
        }

        @Test
        void joinsTwoRowSets() throws SQLException {
            CachedRowSet ages = executed("MATCH (p:Person) RETURN p.name AS name, p.age AS age ORDER BY p.name");
            CachedRowSet shouts =
                    executed("MATCH (p:Person) RETURN p.name AS name, toUpper(p.name) AS shout ORDER BY p.name");

            JoinRowSet joined = factory.createJoinRowSet();
            joined.addRowSet(ages, "name");
            joined.addRowSet(shouts, "name");

            List<String> shouted = new ArrayList<>();
            while (joined.next()) {
                shouted.add(joined.getString("shout"));
            }
            // The reference implementation decides the order of a join, so only membership is asserted.
            assertThat(shouted).containsExactlyInAnyOrder("ALICE", "BOB", "CAROL");
        }

        @Test
        void writesAndReadsItselfAsXml() throws Exception {
            WebRowSet written = factory.createWebRowSet();
            written.setUrl(TestServer.url(GRAPH));
            written.setCommand("MATCH (p:Person) RETURN p.name AS name ORDER BY p.name");
            written.execute();

            StringWriter xml = new StringWriter();
            written.writeXml(xml);

            WebRowSet read = factory.createWebRowSet();
            read.readXml(new StringReader(xml.toString()));

            assertThat(names(read)).containsExactly("Alice", "Bob", "Carol");
        }
    }

    @Nested
    @DisplayName("write-back")
    class WriteBack {

        @Test
        void refusesToSendChangesToTheGraph() throws SQLException {
            CachedRowSet rowSet = executed("MATCH (p:Person) RETURN p.name AS name ORDER BY p.name");
            rowSet.next();
            rowSet.updateString("name", "Not Alice");
            rowSet.updateRow();

            try (Connection connection = TestServer.connect(GRAPH)) {
                assertThatThrownBy(() -> rowSet.acceptChanges(connection))
                        .isInstanceOf(SyncProviderException.class)
                        .hasMessageContaining("read-only");
            }

            assertThat(names(executed("MATCH (p:Person) RETURN p.name AS name ORDER BY p.name")))
                    .containsExactly("Alice", "Bob", "Carol");
        }
    }

    /** Keeps only the rows of people older than a given age. */
    private static final class OlderThan implements Predicate {

        private final int age;

        private OlderThan(int age) {
            this.age = age;
        }

        @Override
        public boolean evaluate(RowSet rowSet) {
            try {
                return rowSet.getLong("age") > age;
            } catch (SQLException failure) {
                return false;
            }
        }

        @Override
        public boolean evaluate(Object value, int column) {
            return true;
        }

        @Override
        public boolean evaluate(Object value, String columnName) {
            return true;
        }
    }
}
