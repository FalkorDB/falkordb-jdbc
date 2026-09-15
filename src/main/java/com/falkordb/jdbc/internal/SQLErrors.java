package com.falkordb.jdbc.internal;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.util.Locale;
import java.util.regex.Pattern;

import redis.clients.jedis.exceptions.JedisConnectionException;

/**
 * Translates failures raised by JFalkorDB, Jedis and this driver's own validation into the
 * {@link SQLException} hierarchy, with a best-effort SQLState.
 *
 * <p>FalkorDB reports query problems as free-text error strings rather than as codes, so the
 * classification below matches on message content. An unrecognised message degrades to the generic
 * {@code HY000}, never to a wrong-but-specific code.
 */
public final class SQLErrors {

    /** SQLState: syntax error or access rule violation. */
    public static final String STATE_SYNTAX = "42000";

    /** SQLState: invalid authorization specification. */
    public static final String STATE_AUTHORIZATION = "28000";

    /** SQLState: connection failure. */
    public static final String STATE_CONNECTION_FAILURE = "08006";

    /** SQLState: SQL client unable to establish a connection. */
    public static final String STATE_CONNECTION_REJECTED = "08001";

    /** SQLState: the statement was cancelled, which is how a server-side timeout surfaces. */
    public static final String STATE_QUERY_CANCELED = "57014";

    /** SQLState: invalid parameter value. */
    public static final String STATE_INVALID_PARAMETER = "22023";

    /** SQLState: data exception - the value could not be converted to the requested type. */
    public static final String STATE_DATA_CONVERSION = "22018";

    /** SQLState: feature not supported. */
    public static final String STATE_NOT_SUPPORTED = "0A000";

    /** SQLState: invalid cursor state, used for out-of-band {@code ResultSet} access. */
    public static final String STATE_INVALID_CURSOR_STATE = "24000";

    /** SQLState: invalid object name, used when a column label does not exist. */
    public static final String STATE_INVALID_COLUMN = "42S22";

    /** SQLState: an object is closed. */
    public static final String STATE_OBJECT_CLOSED = "HY010";

    /** SQLState: a general, unclassified error. */
    public static final String STATE_GENERAL = "HY000";

    private SQLErrors() {}

    /**
     * Wraps a failure from the initial connection handshake. Everything that goes wrong while opening
     * a connection is reported in SQLState class {@code 08} (connection exception), except an
     * authentication rejection, which keeps its more specific {@code 28000}.
     *
     * @param message context describing the endpoint being reached
     * @param cause the underlying failure
     * @return the exception to throw
     */
    public static SQLException connectionFailed(String message, RuntimeException cause) {
        SQLException translated = translate(message, cause);
        if (translated instanceof SQLInvalidAuthorizationSpecException) {
            return translated;
        }
        return new SQLNonTransientConnectionException(translated.getMessage(), STATE_CONNECTION_FAILURE, cause);
    }

    /**
     * Wraps a failure thrown while talking to FalkorDB in the most specific {@link SQLException}
     * subtype the message supports.
     *
     * @param message context describing what the driver was doing
     * @param cause the underlying failure
     * @return the exception to throw
     */
    public static SQLException translate(String message, RuntimeException cause) {
        String detail = message + ": " + describe(cause);
        if (cause instanceof JedisConnectionException) {
            return new SQLNonTransientConnectionException(detail, STATE_CONNECTION_FAILURE, cause);
        }
        if (cause instanceof IllegalArgumentException) {
            // JFalkorDB validates parameter names and value types before sending the query.
            return new SQLException(detail, STATE_INVALID_PARAMETER, cause);
        }
        String text = describe(cause).toLowerCase(Locale.ROOT);
        if (isTimeout(text)) {
            return new SQLTimeoutException(detail, STATE_QUERY_CANCELED, cause);
        }
        if (isAuthentication(text)) {
            return new SQLInvalidAuthorizationSpecException(detail, STATE_AUTHORIZATION, cause);
        }
        if (isSyntax(text)) {
            return new SQLSyntaxErrorException(detail, STATE_SYNTAX, cause);
        }
        return new SQLException(detail, STATE_GENERAL, cause);
    }

    private static boolean isTimeout(String text) {
        return text.contains("timed out")
                || text.contains("timeout")
                || text.contains("execution time exceeded")
                || text.contains("query's execution time");
    }

    private static boolean isAuthentication(String text) {
        return text.contains("wrongpass")
                || text.contains("noauth")
                || text.contains("invalid password")
                || text.contains("invalid username")
                || text.contains("authentication required")
                || text.contains("noperm");
    }

    private static boolean isSyntax(String text) {
        return text.contains("syntax error")
                || text.contains("invalid input")
                || text.contains("unable to parse")
                || text.contains("not registered")
                || text.contains("unknown function")
                || text.contains("procedure name")
                || text.contains("type mismatch")
                || text.contains("redeclaration")
                || (text.contains("variable") && text.contains("not defined"));
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    /**
     * Builds the exception reported for a URL this driver cannot make sense of.
     *
     * <p>The URL is echoed back so the caller can see what was rejected, but any credentials in it
     * are redacted first: a rejected URL routinely ends up in an exception message, a log file or a
     * bug report, and a password must not travel with it.
     *
     * @param url the offending URL
     * @param reason why it was rejected
     * @return the exception to throw
     */
    public static SQLException invalidUrl(String url, String reason) {
        return new SQLNonTransientConnectionException(
                "Invalid FalkorDB JDBC URL " + (url == null ? "null" : '"' + redact(url) + '"') + ": " + reason,
                STATE_CONNECTION_REJECTED);
    }

    private static final Pattern USERINFO_PASSWORD = Pattern.compile("(//[^/?#@]*:)([^/?#@]*)(@)");

    /**
     * Replaces the password in a URL's userinfo and in any {@code password} query parameter with
     * {@code ***}, leaving the rest of the URL readable.
     *
     * <p>Parameter names are percent-decoded before comparison, because {@code ?pass%77ord=} names
     * the same property as {@code ?password=} and must not slip past the mask.
     *
     * @param url the URL to sanitise
     * @return the URL with credentials masked
     */
    public static String redact(String url) {
        String redacted = USERINFO_PASSWORD.matcher(url).replaceAll("$1***$3");
        int start = redacted.indexOf('?');
        if (start < 0) {
            return redacted;
        }
        int end = redacted.indexOf('#', start);
        String query = end < 0 ? redacted.substring(start + 1) : redacted.substring(start + 1, end);
        StringBuilder masked = new StringBuilder(redacted.length());
        masked.append(redacted, 0, start + 1);
        String[] pairs = query.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                masked.append('&');
            }
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq >= 0 && "password".equalsIgnoreCase(decodeLoosely(pair.substring(0, eq)))) {
                masked.append(pair, 0, eq + 1).append("***");
            } else {
                masked.append(pair);
            }
        }
        if (end >= 0) {
            masked.append(redacted, end, redacted.length());
        }
        return masked.toString();
    }

    /**
     * Percent-decodes a parameter name for comparison only. Deliberately forgiving: its job is to
     * decide whether a value must be masked, so a malformed escape must never stop redaction from
     * happening. Anything it cannot decode is left alone.
     */
    private static String decodeLoosely(String name) {
        if (name.indexOf('%') < 0) {
            return name;
        }
        StringBuilder decoded = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '%' && i + 2 < name.length()) {
                int high = Character.digit(name.charAt(i + 1), 16);
                int low = Character.digit(name.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    decoded.append((char) ((high << 4) | low));
                    i += 2;
                    continue;
                }
            }
            decoded.append(c);
        }
        return decoded.toString();
    }

    /**
     * Builds the exception reported for a JDBC capability this driver deliberately does not provide.
     *
     * @param feature the unsupported operation, named as the user would recognise it
     * @return the exception to throw
     */
    public static SQLFeatureNotSupportedException unsupported(String feature) {
        return new SQLFeatureNotSupportedException(
                feature + " is not supported by the FalkorDB JDBC driver", STATE_NOT_SUPPORTED);
    }

    /**
     * Builds the exception reported when an object is used after being closed.
     *
     * @param what the kind of object, for example {@code "Connection"}
     * @return the exception to throw
     */
    public static SQLException closed(String what) {
        return new SQLException(what + " is closed", STATE_OBJECT_CLOSED);
    }

    /**
     * Builds the exception reported when a value cannot be converted to the requested Java type.
     *
     * @param value the value that could not be converted
     * @param target the Java type the caller asked for
     * @return the exception to throw
     */
    public static SQLException cannotConvert(Object value, String target) {
        String from = value == null ? "NULL" : value.getClass().getName();
        return new SQLException(
                "Cannot convert " + from + " to " + target + ": " + abbreviate(value), STATE_DATA_CONVERSION);
    }

    private static String abbreviate(Object value) {
        String text = String.valueOf(value);
        return text.length() <= 96 ? text : text.substring(0, 93) + "...";
    }
}
