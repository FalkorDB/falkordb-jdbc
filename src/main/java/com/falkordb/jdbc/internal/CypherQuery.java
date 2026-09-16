package com.falkordb.jdbc.internal;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A Cypher statement whose JDBC positional placeholders have been rewritten into FalkorDB named
 * parameters.
 *
 * <p>JDBC speaks in ordinal {@code ?} placeholders while Cypher only has named {@code $name}
 * parameters, so the driver rewrites the <em>query text</em> once, at prepare time, turning the
 * <em>n</em>th {@code ?} into {@code $p<n>}. Values are then bound by name and sent through
 * JFalkorDB's parameter map. Values are never interpolated into the query text, so a parameterised
 * statement cannot be used to inject Cypher.
 *
 * <p>A statement that already uses named parameters and contains no {@code ?} is passed through
 * byte-for-byte; its parameters can be bound with {@link
 * com.falkordb.jdbc.FalkorDBPreparedStatement#setNamedObject(String, Object)}.
 *
 * <p>Rewriting is literal-aware: a {@code ?} inside a single-quoted string, a double-quoted string, a
 * backtick-quoted identifier, a {@code //} line comment or a {@code /* *}{@code /} block comment is
 * left alone.
 *
 * @param original the statement exactly as the caller supplied it
 * @param cypher the statement as it will be sent to FalkorDB
 * @param parameterNames the generated names, in ordinal order, so index 0 holds JDBC parameter 1
 * @param namedParameters names of {@code $name} parameters the caller wrote themselves
 */
public record CypherQuery(String original, String cypher, List<String> parameterNames, Set<String> namedParameters) {

    /** Prefix of every generated parameter name; the JDBC ordinal is appended to it. */
    public static final String GENERATED_PREFIX = "p";

    /**
     * Canonical constructor, defensively copying the collections so the record is deeply immutable.
     *
     * @param original the statement exactly as the caller supplied it
     * @param cypher the statement as it will be sent to FalkorDB
     * @param parameterNames the generated names, in ordinal order
     * @param namedParameters names of caller-written {@code $name} parameters
     */
    public CypherQuery {
        parameterNames = List.copyOf(parameterNames);
        namedParameters = Set.copyOf(namedParameters);
    }

    /**
     * The number of JDBC {@code ?} placeholders found, which is also the highest legal parameter
     * index.
     *
     * @return the ordinal parameter count
     */
    public int parameterCount() {
        return parameterNames.size();
    }

    /**
     * The FalkorDB parameter name bound to a one-based JDBC parameter index.
     *
     * @param jdbcIndex the one-based parameter index
     * @return the generated name, without the leading {@code $}
     * @throws SQLException if the index is outside {@code 1..}{@link #parameterCount()}
     */
    public String nameOf(int jdbcIndex) throws SQLException {
        if (jdbcIndex < 1 || jdbcIndex > parameterNames.size()) {
            throw new SQLException(
                    "Parameter index " + jdbcIndex + " is out of range; this statement has " + parameterNames.size()
                            + " parameter(s)",
                    SQLErrors.STATE_INVALID_PARAMETER);
        }
        return parameterNames.get(jdbcIndex - 1);
    }

    /**
     * Wraps statement text that must reach FalkorDB exactly as written.
     *
     * <p>JDBC gives {@code ?} its bind-marker meaning only in a {@link java.sql.PreparedStatement}
     * or {@link java.sql.CallableStatement}. A plain {@link java.sql.Statement} carries literal text,
     * so rewriting a {@code ?} there would invent a {@code $p1} that nothing can ever bind, and
     * could reject text FalkorDB accepts when a hand-written {@code $p1} appears alongside it.
     *
     * @param statement the Cypher text supplied by the caller
     * @return the statement, unrewritten and with no ordinal parameters
     * @throws SQLException if {@code statement} is {@code null}
     */
    public static CypherQuery literal(String statement) throws SQLException {
        if (statement == null) {
            throw new SQLException("Query text must not be null", SQLErrors.STATE_SYNTAX);
        }
        return new CypherQuery(statement, statement, List.of(), Set.of());
    }

    /**
     * Rewrites a statement's positional placeholders and inventories its named parameters.
     *
     * @param statement the Cypher text supplied by the caller
     * @return the translated statement
     * @throws SQLException if {@code statement} is {@code null}, or if it mixes {@code ?} placeholders
     *     with a hand-written parameter whose name collides with a generated one
     */
    public static CypherQuery translate(String statement) throws SQLException {
        if (statement == null) {
            throw new SQLException("Query text must not be null", SQLErrors.STATE_SYNTAX);
        }

        StringBuilder out = new StringBuilder(statement.length() + 16);
        List<String> generated = new ArrayList<>();
        Set<String> named = new LinkedHashSet<>();
        int i = 0;
        int length = statement.length();

        while (i < length) {
            char c = statement.charAt(i);
            switch (c) {
                case '\'', '"' -> i = copyQuoted(statement, i, c, true, out);
                case '`' -> i = copyQuoted(statement, i, '`', false, out);
                case '/' -> i = copyCommentOrSlash(statement, i, out);
                case '?' -> {
                    String name = GENERATED_PREFIX + (generated.size() + 1);
                    generated.add(name);
                    out.append('$').append(name);
                    i++;
                }
                case '$' -> i = copyNamedParameter(statement, i, out, named);
                default -> {
                    out.append(c);
                    i++;
                }
            }
        }

        if (!generated.isEmpty()) {
            for (String name : named) {
                if (generated.contains(name)) {
                    throw new SQLException(
                            "Cypher parameter $" + name + " collides with the name generated for JDBC parameter "
                                    + (generated.indexOf(name) + 1)
                                    + "; use either '?' placeholders or your own $names, or rename $" + name,
                            SQLErrors.STATE_INVALID_PARAMETER);
                }
            }
        }

        return new CypherQuery(statement, out.toString(), generated, named);
    }

    /**
     * Copies a quoted run starting at {@code start} verbatim.
     *
     * @param backslashEscapes whether a backslash escapes the next character, as it does in Cypher
     *     string literals but not in backtick-quoted identifiers, where a literal backtick is written
     *     by doubling it
     * @return the index just past the closing quote, or the end of input for an unterminated literal,
     *     which is left for the server to diagnose
     */
    private static int copyQuoted(String text, int start, char quote, boolean backslashEscapes, StringBuilder out) {
        out.append(quote);
        int i = start + 1;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (backslashEscapes && c == '\\' && i + 1 < text.length()) {
                out.append(c).append(text.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == quote) {
                // A doubled quote is an escaped quote, not the end of the run.
                if (!backslashEscapes && i + 1 < text.length() && text.charAt(i + 1) == quote) {
                    out.append(quote).append(quote);
                    i += 2;
                    continue;
                }
                out.append(quote);
                return i + 1;
            }
            out.append(c);
            i++;
        }
        return i;
    }

    /** Copies a {@code //} or {@code /* *}{@code /} comment, or the lone slash that is neither. */
    private static int copyCommentOrSlash(String text, int start, StringBuilder out) {
        if (start + 1 < text.length() && text.charAt(start + 1) == '/') {
            int end = start;
            while (end < text.length() && text.charAt(end) != '\n' && text.charAt(end) != '\r') {
                end++;
            }
            out.append(text, start, end);
            return end;
        }
        if (start + 1 < text.length() && text.charAt(start + 1) == '*') {
            int end = text.indexOf("*/", start + 2);
            int stop = end < 0 ? text.length() : end + 2;
            out.append(text, start, stop);
            return stop;
        }
        out.append('/');
        return start + 1;
    }

    /** Copies a {@code $name} or {@code $`name`} reference and records the name it mentions. */
    private static int copyNamedParameter(String text, int start, StringBuilder out, Set<String> named) {
        out.append('$');
        int i = start + 1;
        if (i < text.length() && text.charAt(i) == '`') {
            StringBuilder quoted = new StringBuilder();
            int end = copyQuoted(text, i, '`', false, quoted);
            out.append(quoted);
            String inner = quoted.substring(1, Math.max(1, quoted.length() - 1));
            named.add(inner.replace("``", "`"));
            return end;
        }
        int end = i;
        while (end < text.length() && (Character.isLetterOrDigit(text.charAt(end)) || text.charAt(end) == '_')) {
            end++;
        }
        if (end > i) {
            named.add(text.substring(i, end));
            out.append(text, i, end);
        }
        return end == i ? i : end;
    }
}
