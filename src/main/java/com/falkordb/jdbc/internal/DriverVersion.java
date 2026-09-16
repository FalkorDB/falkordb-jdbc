package com.falkordb.jdbc.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * The driver's own version, read once from a build-filtered resource so it always matches the
 * artifact it was compiled into.
 *
 * <p>Reading the version from a resource rather than from {@code Package.getImplementationVersion()}
 * keeps it correct when the classes are loaded from a directory, a shaded jar, or a fat application
 * jar, none of which carry this driver's manifest.
 */
public final class DriverVersion {

    private static final String RESOURCE = "/com/falkordb/jdbc/driver.properties";

    /** The full version string, for example {@code 0.1.0-SNAPSHOT}. */
    public static final String VERSION = load();

    /** The major version, as reported by {@link java.sql.Driver#getMajorVersion()}. */
    public static final int MAJOR = component(0);

    /** The minor version, as reported by {@link java.sql.Driver#getMinorVersion()}. */
    public static final int MINOR = component(1);

    /** The product name this driver reports to JDBC tooling. */
    public static final String NAME = "FalkorDB JDBC Driver";

    private DriverVersion() {}

    private static String load() {
        Properties properties = new Properties();
        try (InputStream in = DriverVersion.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            // A missing or unreadable resource must not stop the driver from loading; an unknown
            // version is merely cosmetic.
            return "unknown";
        }
        String version = properties.getProperty("driver.version", "");
        return version.isBlank() || version.startsWith("${") ? "unknown" : version;
    }

    private static int component(int index) {
        String[] parts = VERSION.split("[.\\-]");
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
