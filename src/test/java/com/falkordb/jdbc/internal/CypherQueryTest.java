package com.falkordb.jdbc.internal;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CypherQueryTest {

    private static String translated(String statement) throws SQLException {
        return CypherQuery.translate(statement).cypher();
    }

    @Nested
    @DisplayName("placeholder rewriting")
    class Rewriting {

        @Test
        void numbersPlaceholdersFromOne() throws SQLException {
            CypherQuery query = CypherQuery.translate("MATCH (p:Person) WHERE p.age > ? AND p.city = ? RETURN p");

            assertThat(query.cypher()).isEqualTo("MATCH (p:Person) WHERE p.age > $p1 AND p.city = $p2 RETURN p");
            assertThat(query.parameterCount()).isEqualTo(2);
            assertThat(query.parameterNames()).containsExactly("p1", "p2");
        }

        @Test
        void mapsJdbcIndexToGeneratedName() throws SQLException {
            CypherQuery query = CypherQuery.translate("RETURN ?, ?, ?");

            assertThat(query.nameOf(1)).isEqualTo("p1");
            assertThat(query.nameOf(2)).isEqualTo("p2");
            assertThat(query.nameOf(3)).isEqualTo("p3");
        }

        @Test
        void rejectsOutOfRangeIndexes() throws SQLException {
            CypherQuery query = CypherQuery.translate("RETURN ?");

            assertThatThrownBy(() -> query.nameOf(0)).isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> query.nameOf(2))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("1 parameter");
        }

        @Test
        void keepsTheOriginalText() throws SQLException {
            String statement = "CREATE (:Person {name: ?})";

            assertThat(CypherQuery.translate(statement).original()).isEqualTo(statement);
        }

        @Test
        void leavesStatementsWithoutPlaceholdersAlone() throws SQLException {
            String statement = "MATCH (n) RETURN count(n) AS total";

            CypherQuery query = CypherQuery.translate(statement);

            assertThat(query.cypher()).isEqualTo(statement);
            assertThat(query.parameterCount()).isZero();
        }

        @Test
        void rejectsNullStatements() {
            assertThatThrownBy(() -> CypherQuery.translate(null)).isInstanceOf(SQLException.class);
        }
    }

    @Nested
    @DisplayName("leaves quoted and commented text untouched")
    class LiteralAware {

        @Test
        void singleQuotedStrings() throws SQLException {
            assertThat(translated("RETURN 'is this a ?', ?")).isEqualTo("RETURN 'is this a ?', $p1");
        }

        @Test
        void doubleQuotedStrings() throws SQLException {
            assertThat(translated("RETURN \"what ? about\", ?")).isEqualTo("RETURN \"what ? about\", $p1");
        }

        @Test
        void backslashEscapedQuotesInsideStrings() throws SQLException {
            assertThat(translated("RETURN 'it\\'s ?', ?")).isEqualTo("RETURN 'it\\'s ?', $p1");
        }

        @Test
        void backtickQuotedIdentifiers() throws SQLException {
            assertThat(translated("MATCH (n:`Odd ? Label`) WHERE n.x = ? RETURN n"))
                    .isEqualTo("MATCH (n:`Odd ? Label`) WHERE n.x = $p1 RETURN n");
        }

        @Test
        void doubledBackticksInsideIdentifiers() throws SQLException {
            assertThat(translated("MATCH (n:`a``b ?`) RETURN ?")).isEqualTo("MATCH (n:`a``b ?`) RETURN $p1");
        }

        @Test
        void lineComments() throws SQLException {
            assertThat(translated("MATCH (n) // why ?\nRETURN ?")).isEqualTo("MATCH (n) // why ?\nRETURN $p1");
        }

        @Test
        void blockComments() throws SQLException {
            assertThat(translated("MATCH (n) /* ? and ? */ RETURN ?")).isEqualTo("MATCH (n) /* ? and ? */ RETURN $p1");
        }

        @Test
        void unterminatedLiteralsAreLeftForTheServerToReject() throws SQLException {
            assertThat(translated("RETURN 'unterminated ?")).isEqualTo("RETURN 'unterminated ?");
        }

        @Test
        void divisionIsNotAComment() throws SQLException {
            assertThat(translated("RETURN ? / 2")).isEqualTo("RETURN $p1 / 2");
        }
    }

    @Nested
    @DisplayName("hand-written named parameters")
    class NamedParameters {

        @Test
        void arePassedThroughUntouched() throws SQLException {
            String statement = "MATCH (p:Person) WHERE p.name = $name RETURN p";

            CypherQuery query = CypherQuery.translate(statement);

            assertThat(query.cypher()).isEqualTo(statement);
            assertThat(query.parameterCount()).isZero();
            assertThat(query.namedParameters()).containsExactly("name");
        }

        @Test
        void areInventoriedAlongsidePlaceholders() throws SQLException {
            CypherQuery query = CypherQuery.translate("MATCH (p) WHERE p.a = $alpha AND p.b = ? RETURN p");

            assertThat(query.cypher()).isEqualTo("MATCH (p) WHERE p.a = $alpha AND p.b = $p1 RETURN p");
            assertThat(query.namedParameters()).containsExactly("alpha");
            assertThat(query.parameterNames()).containsExactly("p1");
        }

        @Test
        void backtickQuotedNamesAreRecognised() throws SQLException {
            CypherQuery query = CypherQuery.translate("RETURN $`odd name`");

            assertThat(query.cypher()).isEqualTo("RETURN $`odd name`");
            assertThat(query.namedParameters()).containsExactly("odd name");
        }

        @Test
        void aPlaceholderGeneratedNameMustNotCollide() {
            assertThatThrownBy(() -> CypherQuery.translate("RETURN $p1, ?"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("collides");
        }

        @Test
        void aNamedParameterThatLooksGeneratedIsFineWithoutPlaceholders() throws SQLException {
            CypherQuery query = CypherQuery.translate("RETURN $p1");

            assertThat(query.cypher()).isEqualTo("RETURN $p1");
            assertThat(query.parameterCount()).isZero();
        }

        @ParameterizedTest
        @ValueSource(strings = {"RETURN $p2, ?, ?", "RETURN ?, $p1"})
        void collisionsAreDetectedWhicheverSideTheyAppear(String statement) {
            assertThatThrownBy(() -> CypherQuery.translate(statement)).isInstanceOf(SQLException.class);
        }

        @Test
        void aDollarNotFollowedByANameIsLeftAlone() throws SQLException {
            assertThat(translated("RETURN '$' + ?")).isEqualTo("RETURN '$' + $p1");
        }
    }
}
