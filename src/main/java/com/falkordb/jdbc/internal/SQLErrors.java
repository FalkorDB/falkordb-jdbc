package com.falkordb.jdbc.internal;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
                "Invalid FalkorDB JDBC URL " + (url == null ? "null" : '"' + redact(url) + '"') + ": "
                        + (url == null ? reason : maskQuotedSecrets(reason, url)),
                STATE_CONNECTION_REJECTED);
    }

    /**
     * Masks the password inside a URL's userinfo.
     *
     * <p>The password runs from the first {@code :} of the authority to its <em>last</em> {@code @}
     * — the same delimiter {@code ConnectionSettings} splits on — so a password like {@code p@ss}
     * is masked whole rather than leaving its tail behind.
     *
     * <p>A password holding an unescaped {@code /}, {@code ?} or {@code #} pushes that {@code @}
     * out of the authority, and such a URL is always rejected. Redaction cannot rely on a URL being
     * well formed, so this is the case it has to get right: when the authority has a {@code :} whose
     * right-hand side is not a port, the delimiter is looked for in the rest of the URL instead. A
     * {@code :} that is a port separator means there is no userinfo, which is what keeps an {@code
     * @} in a query parameter from being mistaken for one.
     *
     * <p>When there is no {@code @} anywhere, the text after that {@code :} is either a password
     * whose delimiter was left out or simply a malformed port. Nothing in the URL tells the two
     * apart, and only one of them is safe to print, so it is masked — to the end of the URL, not
     * the end of the authority, because the same unescaped delimiter that would hide the tail of
     * such a password is also what moves that boundary. The reason still reports that the port was
     * not a number; it just does not quote it.
     */
    private static int[] passwordSpan(String url) {
        int authority = url.indexOf("//");
        if (authority < 0) {
            return null;
        }
        authority += 2;
        int end = authority;
        while (end < url.length() && "/?#".indexOf(url.charAt(end)) < 0) {
            end++;
        }
        String candidate = url.substring(authority, end);
        // A bracketed IPv6 host is full of colons that are not a port separator, so start looking
        // after the closing bracket.
        int host = candidate.startsWith("[") ? candidate.indexOf(']') : -1;
        int colon = candidate.indexOf(':', host + 1);
        if (colon < 0) {
            return null;
        }
        int at = candidate.lastIndexOf('@');
        if (at < 0) {
            if (isPort(candidate.substring(candidate.lastIndexOf(':') + 1))) {
                return null;
            }
            at = url.lastIndexOf('@');
            // With no "@" anywhere there is nothing to say where the password stopped: an
            // unescaped delimiter inside it is also what moved the authority boundary, so stopping
            // at that boundary would leave the tail behind. Mask the rest of the URL.
            return new int[] {authority + colon + 1, at < authority ? url.length() : at};
        }
        if (colon > at) {
            // The only colon precedes the port, so the userinfo is a bare user name.
            return null;
        }
        return new int[] {authority + colon + 1, authority + at};
    }

    private static String maskUserInfo(String url) {
        int[] password = passwordSpan(url);
        return password == null ? url : url.substring(0, password[0]) + "***" + url.substring(password[1]);
    }

    /**
     * Masks any quoted fragment of {@code reason} that carries part of a credential.
     *
     * <p>A reason routinely quotes the text it objected to, and that text comes from the URL. When
     * an unescaped delimiter inside a password makes the parser read part of it as something else —
     * a query parameter name, say — the quoted slice carries the password with it, and redacting
     * the URL alone would not stop it from reaching a log.
     *
     * <p>Two tests are needed because a quoted fragment reaches the reason by two routes. A slice
     * cut straight from the URL is caught by comparing positions: it is masked only if it really
     * does overlap the userinfo password. A fragment the parser <em>decoded</em> first is not a
     * substring of the raw URL at all — {@code ?password%3Dsecret} is reported as {@code
     * password=secret} — so it is compared against the credential values themselves instead.
     * Anything else is left alone, so ordinary reasons keep saying what went wrong.
     */
    private static String maskQuotedSecrets(String reason, String url) {
        if (reason == null) {
            return null;
        }
        int[] password = passwordSpan(url);
        List<String> secrets = passwordValues(url);
        if (password == null && secrets.isEmpty()) {
            return reason;
        }
        StringBuilder masked = new StringBuilder(reason.length());
        int from = 0;
        while (true) {
            int open = reason.indexOf('"', from);
            int close = open < 0 ? -1 : reason.indexOf('"', open + 1);
            if (close < 0) {
                return masked.append(reason, from, reason.length()).toString();
            }
            String quoted = reason.substring(open + 1, close);
            masked.append(reason, from, open + 1)
                    .append(carriesSecret(quoted, url, password, secrets) ? "***" : quoted)
                    .append('"');
            from = close + 1;
        }
    }

    private static boolean carriesSecret(String quoted, String url, int[] password, List<String> secrets) {
        if (quoted.isEmpty()) {
            return false;
        }
        for (String secret : secrets) {
            if (!secret.isEmpty() && quoted.contains(secret)) {
                return true;
            }
        }
        if (password == null) {
            return false;
        }
        for (int at = url.indexOf(quoted); at >= 0; at = url.indexOf(quoted, at + 1)) {
            if (at < password[1] && at + quoted.length() > password[0]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects the values of any {@code password} query parameter, raw and decoded, so a reason that
     * quotes either spelling can be recognised.
     */
    private static List<String> passwordValues(String url) {
        int start = url.indexOf('?');
        if (start < 0) {
            return List.of();
        }
        int end = url.indexOf('#', start);
        String query = end < 0 ? url.substring(start + 1) : url.substring(start + 1, end);
        List<String> values = new ArrayList<>();
        for (String pair : query.split("&", -1)) {
            String value = passwordValue(pair);
            if (value != null) {
                values.add(value);
                String decoded = decodeLoosely(value);
                if (!decoded.equals(value)) {
                    values.add(decoded);
                }
            }
        }
        return values;
    }

    /**
     * Returns the raw value of {@code pair} when it names the password, or {@code null}.
     *
     * <p>The {@code =} itself may be percent-encoded, in which case the raw pair has no separator at
     * all and only the decoded form shows that it is a password being set.
     */
    private static String passwordValue(String pair) {
        int eq = pair.indexOf('=');
        if (eq >= 0) {
            return "password".equalsIgnoreCase(decodeLoosely(pair.substring(0, eq))) ? pair.substring(eq + 1) : null;
        }
        String decoded = decodeLoosely(pair);
        int decodedEq = decoded.indexOf('=');
        if (decodedEq < 0 || !"password".equalsIgnoreCase(decoded.substring(0, decodedEq))) {
            return null;
        }
        return decoded.substring(decodedEq + 1);
    }

    private static boolean isPort(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

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
        String redacted = maskUserInfo(url);
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
            if (passwordValue(pair) == null) {
                masked.append(pair);
            } else if (eq >= 0) {
                masked.append(pair, 0, eq + 1).append("***");
            } else {
                // The "=" is percent-encoded, so there is no separator to keep the name on the
                // readable side of; the whole pair goes.
                masked.append("***");
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
