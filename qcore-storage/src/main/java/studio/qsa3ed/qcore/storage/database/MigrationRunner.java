package studio.qsa3ed.qcore.storage.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.common.exception.StorageException;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Applies ordered SQL migrations from the classpath folder {@code /migrations}.
 *
 * <p>Migrations are discovered from the module jar (or target/classes during
 * development), sorted by file name and applied inside a single transaction per
 * file. Applied migrations are recorded with a checksum, so an edited migration
 * is reported as a startup error instead of silently drifting from production.</p>
 */
public final class MigrationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationRunner.class);
    private static final String LOCATION = "migrations";
    private static final String HISTORY_TABLE = """
            CREATE TABLE IF NOT EXISTS qcore_schema_history (
                version     TEXT PRIMARY KEY,
                checksum    TEXT NOT NULL,
                applied_at  BIGINT NOT NULL
            )
            """;

    private final Database database;

    public MigrationRunner(Database database) {
        this.database = database;
    }

    /** @return the number of migrations applied by this call. */
    public int migrate() {
        if (!database.isAvailable()) {
            LOG.warn("Skipping migrations: PostgreSQL is disabled");
            return 0;
        }
        List<PathResource> migrations = discover();
        if (migrations.isEmpty()) {
            throw new StorageException("No migrations were found on the classpath");
        }
        try {
            return database.withConnection(connection -> {
                ensureHistoryTable(connection);
                Set<String> applied = loadApplied(connection);
                int count = 0;
                for (PathResource migration : migrations) {
                    String checksum = checksum(migration.content());
                    String existing = applied.contains(migration.version()) ? checksumFor(connection, migration.version()) : null;
                    if (existing != null) {
                        if (!existing.equals(checksum)) {
                            throw new StorageException("Migration " + migration.version()
                                    + " was modified after it was applied; create a new migration instead");
                        }
                        continue;
                    }
                    apply(connection, migration, checksum);
                    count++;
                }
                return count;
            }).join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            if (cause instanceof StorageException storage) {
                throw storage;
            }
            throw new StorageException("Migration failed", cause);
        }
    }

    private void apply(Connection connection, PathResource migration, String checksum) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            statement.execute(migration.content());
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO " + HISTORY_TABLE_NAME + " (version, checksum, applied_at) VALUES (?, ?, ?)")) {
                insert.setString(1, migration.version());
                insert.setString(2, checksum);
                insert.setLong(3, System.currentTimeMillis());
                insert.executeUpdate();
            }
            connection.commit();
            LOG.info("Applied database migration {}", migration.version());
        } catch (SQLException failure) {
            connection.rollback();
            throw new StorageException("Migration " + migration.version() + " failed: " + failure.getMessage(), failure);
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private static final String HISTORY_TABLE_NAME = "qcore_schema_history";

    private void ensureHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(HISTORY_TABLE);
        }
    }

    private Set<String> loadApplied(Connection connection) throws SQLException {
        Set<String> versions = new LinkedHashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version FROM " + HISTORY_TABLE_NAME)) {
            while (rows.next()) {
                versions.add(rows.getString(1));
            }
        }
        return versions;
    }

    private String checksumFor(Connection connection, String version) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT checksum FROM " + HISTORY_TABLE_NAME + " WHERE version = ?")) {
            query.setString(1, version);
            try (ResultSet rows = query.executeQuery()) {
                return rows.next() ? rows.getString(1) : null;
            }
        }
    }

    private List<PathResource> discover() {
        List<PathResource> resources = new ArrayList<>();
        ClassLoader loader = MigrationRunner.class.getClassLoader();
        try {
            var urls = loader.getResources(LOCATION);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if ("file".equals(url.getProtocol())) {
                    Path directory = Path.of(url.toURI());
                    try (var files = Files.list(directory)) {
                        files.filter(path -> path.getFileName().toString().endsWith(".sql"))
                                .forEach(path -> resources.add(new PathResource(
                                        path.getFileName().toString().replace(".sql", ""),
                                        readString(path))));
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    JarURLConnection connection = (JarURLConnection) url.openConnection();
                    try (JarFile jar = connection.getJarFile()) {
                        jar.stream()
                                .filter(entry -> !entry.isDirectory())
                                .filter(entry -> entry.getName().startsWith(LOCATION + "/")
                                        && entry.getName().endsWith(".sql"))
                                .forEach(entry -> resources.add(new PathResource(
                                        entry.getName().substring(LOCATION.length() + 1).replace(".sql", ""),
                                        readJarEntry(jar, entry))));
                    }
                }
            }
        } catch (IOException | URISyntaxException failure) {
            throw new StorageException("Unable to discover database migrations", failure);
        }
        resources.sort(Comparator.comparing(PathResource::version));
        return resources;
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StorageException("Unable to read migration " + path, failure);
        }
    }

    private static String readJarEntry(JarFile jar, JarEntry entry) {
        try (InputStream in = jar.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new StorageException("Unable to read migration " + entry.getName(), failure);
        }
    }

    private static String checksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new StorageException("SHA-256 is not available", impossible);
        }
    }

    private record PathResource(String version, String content) {
    }
}
