package com.falkordb.jdbc.internal;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Properties;

/**
 * The fully resolved configuration behind a single JDBC connection.
 *
 * <p>Settings are parsed from a JDBC URL of the form
 *
 * <pre>{@code
 * jdbc:falkordb://[user[:password]@]host[:port]/<graphName>[?key=value&...]
 * jdbc:falkordb+ssl://...
 * }</pre>
 *
 * <p>The path segment names the FalkorDB graph, which this driver models as the JDBC catalog. The
 * port defaults to {@value #DEFAULT_PORT} and the host to {@value #DEFAULT_HOST}.
 *
 * <p>When a value is supplied in more than one place the highest-priority source wins:
 *
 * <ol>
 *   <li>the {@link Properties} handed to {@link java.sql.Driver#connect(String, Properties)} — which
 *       is where {@code DriverManager.getConnection(url, user, password)} puts its arguments;
 *   <li>the URL query string;
 *   <li>the URL user-info component ({@code user:password@}), for credentials only.
 * </ol>
 */
public record ConnectionSettings(
        String host,
        int port,
        String graphName,
        Optional<String> user,
        Optional<String> password,
        boolean ssl,
        Optional<Duration> connectionTimeout,
        Optional<Duration> socketTimeout,
        OptionalLong queryTimeoutMillis,
        OptionalInt poolMaxTotal,
        OptionalInt poolMaxIdle,
        Optional<Duration> poolMaxWait,
        boolean readOnly) {

    /** The JDBC sub-protocol prefix every FalkorDB URL starts with. */
    public static final String URL_PREFIX = "jdbc:falkordb:";

    /** Host used when the URL authority omits one. */
    public static final String DEFAULT_HOST = "localhost";

    /** Port used when the URL authority omits one. */
    public static final int DEFAULT_PORT = 6379;

    private static final String JDBC_PREFIX = "jdbc:";
    private static final String SCHEME_PLAIN = "falkordb";
    private static final List<String> SCHEMES_TLS = List.of("falkordb+ssl", "falkordb+s", "falkordbs");

    /** Property name carrying the user name. */
    public static final String PROP_USER = "user";

    /** Property name carrying the password. */
    public static final String PROP_PASSWORD = "password";

    /** Property name toggling TLS. */
    public static final String PROP_SSL = "ssl";

    /** Property name overriding the graph taken from the URL path. */
    public static final String PROP_GRAPH = "graph";

    /** Property name carrying the TCP connect timeout, in milliseconds. */
    public static final String PROP_CONNECTION_TIMEOUT = "connectionTimeout";

    /** Property name carrying the socket read timeout, in milliseconds. */
    public static final String PROP_SOCKET_TIMEOUT = "socketTimeout";

    /** Property name carrying the default server-side query timeout, in milliseconds. */
    public static final String PROP_QUERY_TIMEOUT = "queryTimeout";

    /** Property name carrying the maximum connection-pool size. */
    public static final String PROP_POOL_MAX_TOTAL = "poolMaxTotal";

    /** Property name carrying the maximum number of idle pooled connections. */
    public static final String PROP_POOL_MAX_IDLE = "poolMaxIdle";

    /** Property name carrying the maximum pool borrow wait, in milliseconds. */
    public static final String PROP_POOL_MAX_WAIT = "poolMaxWait";

    /** Property name putting the connection into read-only mode from the outset. */
    public static final String PROP_READ_ONLY = "readOnly";

    /**
     * Describes one recognised connection property, for {@link
     * java.sql.Driver#getPropertyInfo(String, Properties)}.
     *
     * @param name the property name
     * @param description a human-readable explanation
     * @param choices the legal values, or an empty array when unconstrained
     */
    public record Known(String name, String description, String[] choices) {}

    private static final String[] BOOLEANS = {"true", "false"};

    /** Every property this driver understands, in the order it is advertised. */
    public static final List<Known> KNOWN_PROPERTIES = List.of(
            new Known(PROP_USER, "User name used to authenticate against FalkorDB.", new String[0]),
            new Known(PROP_PASSWORD, "Password used to authenticate against FalkorDB.", new String[0]),
            new Known(PROP_GRAPH, "Graph (catalog) to connect to; overrides the URL path segment.", new String[0]),
            new Known(PROP_SSL, "Whether to connect over TLS.", BOOLEANS),
            new Known(PROP_CONNECTION_TIMEOUT, "TCP connect timeout in milliseconds.", new String[0]),
            new Known(PROP_SOCKET_TIMEOUT, "Socket read timeout in milliseconds; 0 means no deadline.", new String[0]),
            new Known(
                    PROP_QUERY_TIMEOUT,
                    "Default server-side query timeout in milliseconds applied to new statements.",
                    new String[0]),
            new Known(PROP_POOL_MAX_TOTAL, "Maximum number of pooled connections.", new String[0]),
            new Known(PROP_POOL_MAX_IDLE, "Maximum number of idle pooled connections.", new String[0]),
            new Known(
                    PROP_POOL_MAX_WAIT,
                    "Maximum time in milliseconds to wait for a pooled connection; negative waits forever.",
                    new String[0]),
            new Known(
                    PROP_READ_ONLY,
                    "Start the connection read-only, routing queries through FalkorDB's read-only path.",
                    BOOLEANS));

    /**
     * Reports whether a URL is one this driver is willing to handle. Only the prefix is inspected, as
     * {@link java.sql.Driver#acceptsURL(String)} requires: a syntactically broken but
     * correctly-prefixed URL is accepted here and rejected by {@link #parse}.
     *
     * @param url the JDBC URL, possibly {@code null}
     * @return {@code true} if the URL targets FalkorDB
     */
    public static boolean acceptsUrl(String url) {
        return schemeOf(url) != null;
    }

    /**
     * Extracts the FalkorDB sub-protocol from a URL — {@code falkordb} or one of its TLS spellings —
     * or {@code null} if the URL belongs to another driver.
     */
    private static String schemeOf(String url) {
        if (url == null || !url.regionMatches(true, 0, JDBC_PREFIX, 0, JDBC_PREFIX.length())) {
            return null;
        }
        int end = url.indexOf(':', JDBC_PREFIX.length());
        if (end < 0) {
            return null;
        }
        String scheme = url.substring(JDBC_PREFIX.length(), end).toLowerCase(Locale.ROOT);
        return SCHEME_PLAIN.equals(scheme) || SCHEMES_TLS.contains(scheme) ? scheme : null;
    }

    /**
     * Parses a JDBC URL and merges it with the supplied connection properties.
     *
     * <p>An unrecognised <em>query parameter</em> is rejected, because a URL is typed by a person and
     * a silently ignored typo such as {@code passwrod=} would connect with no password at all. An
     * unrecognised <em>property</em> is ignored, because connection pools, BI tools and application
     * servers routinely add keys of their own to the {@link Properties} they pass down, and rejecting
     * those would break them. The two are deliberately different; see the README.
     *
     * @param url the JDBC URL
     * @param properties connection properties; may be {@code null}. Keys this driver does not know
     *     are ignored
     * @return the resolved settings
     * @throws SQLException if the URL is not a FalkorDB URL, is malformed, names no graph, carries an
     *     unknown query parameter, or carries an unparseable value for a known property
     */
    public static ConnectionSettings parse(String url, Properties properties) throws SQLException {
        String scheme = schemeOf(url);
        if (scheme == null) {
            String detail = url != null && url.regionMatches(true, 0, JDBC_PREFIX + SCHEME_PLAIN, 0, 13)
                    ? "unsupported sub-protocol; expected one of " + supportedSchemes()
                    : "expected a URL starting with \"" + URL_PREFIX + "\"";
            throw SQLErrors.invalidUrl(url, detail);
        }
        URI uri = toUri(url);

        boolean tlsScheme = SCHEMES_TLS.contains(scheme);
        Map<String, String> query = parseQuery(uri.getRawQuery(), url);
        Map<String, String> overrides = toMap(properties);

        String host = blankToNull(uri.getHost());
        Integer declaredPort = uri.getPort() == -1 ? null : uri.getPort();
        if (host == null) {
            // A host such as "falkordb_server" with an underscore makes URI fall back to a
            // registry-based authority, which leaves both getHost() and getPort() unset even though
            // the raw authority still says exactly what was meant. A negative port lands here too.
            String[] authority = splitAuthority(uri.getRawAuthority());
            host = authority[0];
            if (authority[1] != null) {
                declaredPort = parsePort(authority[1], url);
            }
        }
        int port = declaredPort == null ? DEFAULT_PORT : declaredPort;
        if (port < 1 || port > 65535) {
            throw SQLErrors.invalidUrl(url, "port out of range: " + port);
        }

        Optional<String> declaredGraph = pick(PROP_GRAPH, overrides, query);
        String graph = declaredGraph.isPresent() ? declaredGraph.get() : pathToGraph(uri.getRawPath(), url);
        if (graph == null || graph.isBlank()) {
            throw SQLErrors.invalidUrl(url, "no graph name; expected \"" + URL_PREFIX + "//host:port/graphName\"");
        }

        String[] userInfo = splitUserInfo(uri.getRawUserInfo(), url);
        Optional<String> user = pick(PROP_USER, overrides, query).or(() -> Optional.ofNullable(userInfo[0]));
        Optional<String> password = pick(PROP_PASSWORD, overrides, query).or(() -> Optional.ofNullable(userInfo[1]));

        boolean ssl = tlsScheme
                || parseBoolean(pick(PROP_SSL, overrides, query), PROP_SSL, url).orElse(false);

        return new ConnectionSettings(
                host == null ? DEFAULT_HOST : host,
                port,
                graph,
                user,
                password,
                ssl,
                parseMillis(pick(PROP_CONNECTION_TIMEOUT, overrides, query), PROP_CONNECTION_TIMEOUT, url),
                parseMillis(pick(PROP_SOCKET_TIMEOUT, overrides, query), PROP_SOCKET_TIMEOUT, url),
                parseLong(pick(PROP_QUERY_TIMEOUT, overrides, query), PROP_QUERY_TIMEOUT, url),
                parseInt(pick(PROP_POOL_MAX_TOTAL, overrides, query), PROP_POOL_MAX_TOTAL, url),
                parseInt(pick(PROP_POOL_MAX_IDLE, overrides, query), PROP_POOL_MAX_IDLE, url),
                parseSignedMillis(pick(PROP_POOL_MAX_WAIT, overrides, query), PROP_POOL_MAX_WAIT, url),
                parseBoolean(pick(PROP_READ_ONLY, overrides, query), PROP_READ_ONLY, url)
                        .orElse(false));
    }

    private static URI toUri(String url) throws SQLException {
        // Everything after "jdbc:" is itself a URI, whose scheme ("falkordb", "falkordb+ssl", ...)
        // selects plaintext or TLS.
        String withoutJdbc = url.substring("jdbc:".length());
        try {
            return new URI(withoutJdbc);
        } catch (URISyntaxException e) {
            throw SQLErrors.invalidUrl(url, e.getReason() == null ? e.getMessage() : e.getReason());
        }
    }

    /**
     * Splits a raw authority into host and port, for the cases {@link URI#getHost()} declines to
     * parse. User-info is discarded here; {@link URI} still reports it even for a registry-based
     * authority.
     *
     * @return a two-element array of host and port text, either of which may be null
     */
    private static String[] splitAuthority(String rawAuthority) {
        if (rawAuthority == null || rawAuthority.isEmpty()) {
            return new String[] {null, null};
        }
        String authority = rawAuthority;
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.lastIndexOf(':');
        if (colon < 0 || authority.indexOf(']') > colon) {
            return new String[] {blankToNull(authority), null};
        }
        return new String[] {blankToNull(authority.substring(0, colon)), authority.substring(colon + 1)};
    }

    private static int parsePort(String text, String url) throws SQLException {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw SQLErrors.invalidUrl(url, "port is not a number: \"" + text + "\"");
        }
    }

    private static String supportedSchemes() {
        List<String> all = new java.util.ArrayList<>();
        all.add(SCHEME_PLAIN);
        all.addAll(SCHEMES_TLS);
        return String.join(", ", all);
    }

    private static String pathToGraph(String rawPath, String url) throws SQLException {
        if (rawPath == null) {
            return null;
        }
        String path = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        // A trailing slash is a URL convention, not part of the graph name.
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() ? null : decode(path, url);
    }

    /**
     * Splits {@code user:password} user-info into its two halves. A missing colon means the whole
     * component is the user name; both halves are percent-decoded so credentials may contain
     * {@code @}, {@code :} and other reserved characters.
     */
    private static String[] splitUserInfo(String rawUserInfo, String url) throws SQLException {
        if (rawUserInfo == null || rawUserInfo.isEmpty()) {
            return new String[] {null, null};
        }
        int colon = rawUserInfo.indexOf(':');
        if (colon < 0) {
            return new String[] {blankToNull(decode(rawUserInfo, url)), null};
        }
        return new String[] {
            blankToNull(decode(rawUserInfo.substring(0, colon), url)), decode(rawUserInfo.substring(colon + 1), url)
        };
    }

    private static Map<String, String> parseQuery(String rawQuery, String url) throws SQLException {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isEmpty()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq < 0) {
                throw SQLErrors.invalidUrl(url, "query parameter \"" + decode(pair, url) + "\" has no value");
            }
            String name = decode(pair.substring(0, eq), url);
            if (KNOWN_PROPERTIES.stream().noneMatch(known -> known.name().equals(name))) {
                throw SQLErrors.invalidUrl(
                        url,
                        "unknown query parameter \"" + name + "\"; supported parameters are "
                                + KNOWN_PROPERTIES.stream()
                                        .map(Known::name)
                                        .collect(java.util.stream.Collectors.joining(", ")));
            }
            result.put(name, decode(pair.substring(eq + 1), url));
        }
        return result;
    }

    private static Map<String, String> toMap(Properties properties) {
        Map<String, String> result = new LinkedHashMap<>();
        if (properties == null) {
            return result;
        }
        for (String name : properties.stringPropertyNames()) {
            result.put(name, properties.getProperty(name));
        }
        return result;
    }

    /** Applies the documented precedence: explicit properties first, then the URL query string. */
    private static Optional<String> pick(String name, Map<String, String> overrides, Map<String, String> query) {
        String value = overrides.get(name);
        if (value == null) {
            value = query.get(name);
        }
        return Optional.ofNullable(blankToNull(value));
    }

    private static Optional<Boolean> parseBoolean(Optional<String> raw, String name, String url) throws SQLException {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        String value = raw.get().trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "yes", "on", "1" -> Optional.of(Boolean.TRUE);
            case "false", "no", "off", "0" -> Optional.of(Boolean.FALSE);
            default -> throw SQLErrors.invalidUrl(url, name + " must be true or false, but was \"" + raw.get() + "\"");
        };
    }

    private static OptionalInt parseInt(Optional<String> raw, String name, String url) throws SQLException {
        if (raw.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(raw.get().trim()));
        } catch (NumberFormatException e) {
            throw SQLErrors.invalidUrl(url, name + " must be an integer, but was \"" + raw.get() + "\"");
        }
    }

    private static OptionalLong parseLong(Optional<String> raw, String name, String url) throws SQLException {
        if (raw.isEmpty()) {
            return OptionalLong.empty();
        }
        try {
            long value = Long.parseLong(raw.get().trim());
            if (value < 0) {
                throw SQLErrors.invalidUrl(url, name + " must not be negative, but was " + value);
            }
            return OptionalLong.of(value);
        } catch (NumberFormatException e) {
            throw SQLErrors.invalidUrl(url, name + " must be an integer, but was \"" + raw.get() + "\"");
        }
    }

    private static Optional<Duration> parseMillis(Optional<String> raw, String name, String url) throws SQLException {
        OptionalLong millis = parseLong(raw, name, url);
        return millis.isPresent() ? Optional.of(Duration.ofMillis(millis.getAsLong())) : Optional.empty();
    }

    /** Like {@link #parseMillis} but allows a negative value, which JFalkorDB reads as "wait forever". */
    private static Optional<Duration> parseSignedMillis(Optional<String> raw, String name, String url)
            throws SQLException {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Duration.ofMillis(Long.parseLong(raw.get().trim())));
        } catch (NumberFormatException e) {
            throw SQLErrors.invalidUrl(url, name + " must be an integer, but was \"" + raw.get() + "\"");
        }
    }

    /**
     * Percent-decodes one URL component.
     *
     * <p>This is deliberately not {@link java.net.URLDecoder}, which implements HTML form decoding
     * and would turn a literal {@code +} in a password or graph name into a space. Only {@code %XX}
     * escapes are decoded; every other character is already a literal.
     *
     * @param value the raw component
     * @param url the connection URL, for the error message
     * @return the decoded component
     * @throws SQLException if the component contains a malformed escape
     */
    private static String decode(String value, String url) throws SQLException {
        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder decoded = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            if (value.charAt(i) != '%') {
                decoded.append(value.charAt(i++));
                continue;
            }
            // Decode a run of escapes together: one UTF-8 character may span several of them.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4);
            while (i < value.length() && value.charAt(i) == '%') {
                if (i + 2 >= value.length()) {
                    throw SQLErrors.invalidUrl(url, "URL contains a truncated percent-escape");
                }
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high < 0 || low < 0) {
                    throw SQLErrors.invalidUrl(url, "URL contains a malformed percent-escape");
                }
                bytes.write((high << 4) | low);
                i += 3;
            }
            decoded.append(bytes.toString(StandardCharsets.UTF_8));
        }
        return decoded.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * Renders the settings back as a JDBC URL, with any password elided. Used for diagnostics and by
     * {@link java.sql.DatabaseMetaData#getURL()}.
     *
     * @return a redacted JDBC URL
     */
    public String toRedactedUrl() {
        StringBuilder sb = new StringBuilder("jdbc:").append(ssl ? "falkordb+ssl" : SCHEME_PLAIN);
        sb.append("://");
        user.ifPresent(
                u -> sb.append(u).append(password.isPresent() ? ":*****" : "").append('@'));
        sb.append(host).append(':').append(port).append('/').append(graphName);
        List<String> params = new ArrayList<>();
        if (readOnly) {
            params.add(PROP_READ_ONLY + "=true");
        }
        queryTimeoutMillis.ifPresent(t -> params.add(PROP_QUERY_TIMEOUT + "=" + t));
        if (!params.isEmpty()) {
            sb.append('?').append(String.join("&", params));
        }
        return sb.toString();
    }
}
