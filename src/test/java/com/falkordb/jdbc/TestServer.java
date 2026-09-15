package com.falkordb.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.function.Supplier;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * The FalkorDB server the integration tests run against.
 *
 * <p>By default a single Testcontainers-managed {@code falkordb/falkordb} container is started for
 * the whole JVM and torn down at exit, so {@code mvn verify} needs no manual setup. Set
 * <strong>both</strong> {@code FALKORDB_HOST} and {@code FALKORDB_PORT} to reuse an already-running
 * server instead; setting only one is rejected rather than silently half-applied. {@code
 * FALKORDB_IMAGE} (system property or environment variable) overrides the pinned image, which is how
 * the suite is matrixed over FalkorDB versions.
 *
 * <p>This mirrors the arrangement JFalkorDB's own test suite uses, so both projects can be pointed at
 * the same server.
 */
final class TestServer {

    /** Pinned digest (v4.20.1), matching the image JFalkorDB's own suite runs against. */
    static final String DEFAULT_IMAGE =
            "falkordb/falkordb@sha256:9042fdc4e53f5390ca5a3993aa71506523970efb40ffb9a98e6a4b1a9a4f8862";

    private static final String HOST;
    private static final int PORT;

    static {
        String envHost = System.getenv("FALKORDB_HOST");
        String envPort = System.getenv("FALKORDB_PORT");
        boolean hasHost = envHost != null && !envHost.isBlank();
        boolean hasPort = envPort != null && !envPort.isBlank();
        if (hasHost != hasPort) {
            throw new IllegalStateException(
                    "Set BOTH FALKORDB_HOST and FALKORDB_PORT to use an external FalkorDB, or neither.");
        }
        if (hasHost) {
            HOST = envHost;
            PORT = Integer.parseInt(envPort.trim());
        } else {
            GenericContainer<?> container = new GenericContainer<>(
                            image(System.getProperty("FALKORDB_IMAGE"), () -> System.getenv("FALKORDB_IMAGE")))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());
            container.start(); // Ryuk stops it when the JVM exits
            HOST = container.getHost();
            PORT = container.getMappedPort(6379);
        }
    }

    private TestServer() {}

    /**
     * Resolves the image to run. A blank system property does not shadow a non-blank environment
     * variable, so {@code -DFALKORDB_IMAGE=} behaves as "unset".
     */
    static DockerImageName image(String property, Supplier<String> environment) {
        String override = property != null && !property.isBlank() ? property : environment.get();
        String image = override == null || override.isBlank() ? DEFAULT_IMAGE : override.trim();
        return DockerImageName.parse(image).asCompatibleSubstituteFor("falkordb/falkordb");
    }

    static String host() {
        return HOST;
    }

    static int port() {
        return PORT;
    }

    /** A JDBC URL for a graph on the test server. */
    static String url(String graph) {
        return "jdbc:falkordb://" + HOST + ":" + PORT + "/" + graph;
    }

    /** Opens a JDBC connection to a graph on the test server, through {@link DriverManager}. */
    static Connection connect(String graph) throws SQLException {
        return DriverManager.getConnection(url(graph));
    }

    /** Opens a JDBC connection with extra connection properties. */
    static Connection connect(String graph, Properties properties) throws SQLException {
        return DriverManager.getConnection(url(graph), properties);
    }
}
