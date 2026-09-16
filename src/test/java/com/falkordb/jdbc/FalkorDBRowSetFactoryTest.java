package com.falkordb.jdbc;

import java.io.StringWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.LocalDate;
import java.util.List;
import javax.sql.rowset.CachedRowSet;
import javax.sql.rowset.RowSetFactory;
import javax.sql.rowset.RowSetProvider;
import javax.sql.rowset.WebRowSet;
import javax.sql.rowset.spi.SyncProvider;
import javax.sql.rowset.spi.SyncProviderException;

import com.falkordb.jdbc.internal.ColumnMeta;
import com.falkordb.jdbc.internal.DriverVersion;
import com.falkordb.jdbc.internal.FalkorType;
import com.falkordb.jdbc.internal.SQLErrors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises {@link FalkorDBRowSetFactory} and {@link FalkorDBSyncProvider} over synthetic rows, so
 * the row set contract can be pinned down without a server.
 */
class FalkorDBRowSetFactoryTest {

    private final RowSetFactory factory = new FalkorDBRowSetFactory();

    private static ResultSet people() {
        return new FalkorDBResultSet(
                List.of(ColumnMeta.of("name", FalkorType.STRING), ColumnMeta.of("age", FalkorType.INTEGER)),
                List.of(List.of("Ada", 36L), List.of("Grace", 45L)),
                null);
    }

    @Nested
    @DisplayName("creation")
    class Creation {

        @Test
        void wiresEveryDisconnectedRowSetToTheFalkorDBProvider() throws SQLException {
            List<CachedRowSet> rowSets = List.of(
                    factory.createCachedRowSet(),
                    factory.createWebRowSet(),
                    factory.createFilteredRowSet(),
                    factory.createJoinRowSet());

            for (CachedRowSet rowSet : rowSets) {
                assertThat(rowSet.getSyncProvider()).isInstanceOf(FalkorDBSyncProvider.class);
                assertThat(rowSet.getSyncProvider().getProviderID()).isEqualTo(FalkorDBSyncProvider.ID);
            }
        }

        @Test
        void refusesAJdbcRowSet() {
            assertThatThrownBy(factory::createJdbcRowSet)
                    .isInstanceOf(SQLFeatureNotSupportedException.class)
                    .hasMessageContaining("JdbcRowSet")
                    .satisfies(thrown ->
                            assertThat(((SQLException) thrown).getSQLState()).isEqualTo(SQLErrors.STATE_NOT_SUPPORTED));
        }

        @Test
        void isReachableByName() throws SQLException {
            RowSetFactory named = RowSetProvider.newFactory("com.falkordb.jdbc.FalkorDBRowSetFactory", null);

            assertThat(named).isInstanceOf(FalkorDBRowSetFactory.class);
            assertThat(named.createCachedRowSet().getSyncProvider()).isInstanceOf(FalkorDBSyncProvider.class);
        }
    }

    @Nested
    @DisplayName("provider")
    class Provider {

        @Test
        void isIdentifiedByItsClassName() {
            assertThat(FalkorDBSyncProvider.ID).isEqualTo(FalkorDBSyncProvider.class.getName());
        }

        @Test
        void neverSynchronises() throws SQLException {
            SyncProvider provider = factory.createCachedRowSet().getSyncProvider();

            assertThat(provider.getProviderGrade()).isEqualTo(SyncProvider.GRADE_NONE);
            assertThat(provider.supportsUpdatableView()).isEqualTo(SyncProvider.NONUPDATABLE_VIEW_SYNC);
            assertThat(provider.getDataSourceLock()).isEqualTo(SyncProvider.DATASOURCE_NO_LOCK);
            assertThat(provider.getVendor()).isEqualTo("FalkorDB");
            assertThat(provider.getVersion()).isEqualTo(DriverVersion.VERSION);
        }

        @Test
        void refusesToLockTheDataSource() throws SQLException {
            SyncProvider provider = factory.createCachedRowSet().getSyncProvider();

            assertThatThrownBy(() -> provider.setDataSourceLock(SyncProvider.DATASOURCE_ROW_LOCK))
                    .isInstanceOf(SyncProviderException.class);
            assertThat(provider.getDataSourceLock()).isEqualTo(SyncProvider.DATASOURCE_NO_LOCK);
        }

        @Test
        void refusesToWriteChangesBack() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.populate(people());
            rowSet.next();
            rowSet.updateString("name", "Ada Lovelace");
            rowSet.updateRow();

            assertThatThrownBy(rowSet::acceptChanges)
                    .isInstanceOf(SyncProviderException.class)
                    .hasMessageContaining("read-only");
        }
    }

    @Nested
    @DisplayName("execute")
    class Execution {

        @Test
        void requiresACommand() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.setUrl("jdbc:falkordb://localhost:6379/social");

            assertThatThrownBy(rowSet::execute).isInstanceOf(SQLException.class).hasMessageContaining("setCommand");
        }

        @Test
        void requiresAUrlOrAConnection() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.setCommand("MATCH (p:Person) RETURN p.name AS name");

            assertThatThrownBy(rowSet::execute)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("setUrl")
                    .satisfies(thrown -> assertThat(((SQLException) thrown).getSQLState())
                            .isEqualTo(SQLErrors.STATE_CONNECTION_REJECTED));
        }
    }

    @Nested
    @DisplayName("populate")
    class Populate {

        @Test
        void copiesEveryRowOfADriverResultSet() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.populate(people());

            assertThat(rowSet.size()).isEqualTo(2);
            assertThat(rowSet.getMetaData().getColumnLabel(1)).isEqualTo("name");
            assertThat(rowSet.next()).isTrue();
            assertThat(rowSet.getString("name")).isEqualTo("Ada");
            assertThat(rowSet.getLong("age")).isEqualTo(36L);
        }

        @Test
        void scrollsOverAForwardOnlyResultSet() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.populate(people());

            assertThat(rowSet.last()).isTrue();
            assertThat(rowSet.getString("name")).isEqualTo("Grace");
            assertThat(rowSet.previous()).isTrue();
            assertThat(rowSet.getString("name")).isEqualTo("Ada");
        }

        @Test
        void keepsTemporalsAsJavaTimeValues() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.populate(new FalkorDBResultSet(
                    List.of(ColumnMeta.of("day", FalkorType.DATE)), List.of(List.of(LocalDate.of(2024, 5, 17))), null));
            rowSet.next();

            assertThat(rowSet.getObject("day")).isEqualTo(LocalDate.of(2024, 5, 17));
            // The reference implementation casts a cached value to java.sql.Date, so the typed
            // getters cannot read what the driver stored. Read temporals with getObject instead.
            assertThatThrownBy(() -> rowSet.getDate("day")).isInstanceOf(ClassCastException.class);
        }

        @Test
        void survivesBeingCopied() throws SQLException {
            CachedRowSet rowSet = factory.createCachedRowSet();
            rowSet.populate(people());

            CachedRowSet copy = rowSet.createCopy();

            assertThat(copy.getSyncProvider()).isInstanceOf(FalkorDBSyncProvider.class);
            assertThat(copy.size()).isEqualTo(2);
        }

        @Test
        void writesItselfAsXml() throws Exception {
            WebRowSet rowSet = factory.createWebRowSet();
            rowSet.populate(people());

            StringWriter xml = new StringWriter();
            rowSet.writeXml(xml);

            assertThat(xml.toString()).contains("FalkorDBSyncProvider").contains("Ada");
        }
    }
}
