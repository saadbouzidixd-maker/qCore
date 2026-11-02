package studio.qsa3ed.qcore.central;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.central.config.ConfigurationLoader;
import studio.qsa3ed.qcore.common.exception.ConfigurationException;
import studio.qsa3ed.qcore.common.exception.StorageException;
import studio.qsa3ed.qcore.storage.database.Database;
import studio.qsa3ed.qcore.storage.database.MigrationRunner;
import studio.qsa3ed.qcore.storage.redis.RedisConnectionManager;

import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The qCore central process.
 *
 * <p>Startup order matters: configuration is validated, PostgreSQL is opened and
 * migrated, Redis is attached or degraded, then the coordinator binds the REST
 * API. Any failure before the API starts aborts the process with a clear message
 * instead of half-running infrastructure.</p>
 */
public final class CentralApplication {

    /** qCore version reported by the health endpoint. */
    public static final String VERSION = "0.1.0-SNAPSHOT";

    private static final Logger LOG = LoggerFactory.getLogger(CentralApplication.class);

    private final CountDownLatch stopped = new CountDownLatch(1);
    private final Path configDirectory;
    private NetworkCoordinator coordinator;

    public CentralApplication(Path configDirectory) {
        this.configDirectory = configDirectory;
    }

    /** Starts the application and blocks until it is shut down. */
    public void run() {
        ConfigurationLoader loader = new ConfigurationLoader(configDirectory);
        CentralConfiguration configuration;
        try {
            configuration = loader.load();
        } catch (ConfigurationException failure) {
            LOG.error("Invalid configuration: {}", failure.getMessage());
            throw failure;
        }

        Database database = Database.open(configuration.database());
        RedisConnectionManager redis = RedisConnectionManager.open(configuration.redis());
        try {
            if (database.isAvailable()) {
                int applied = new MigrationRunner(database).migrate();
                LOG.info("Database schema is up to date ({} migration(s) applied)", applied);
            }
            coordinator = new NetworkCoordinator(configuration, loader.configurationService(),
                    database, redis, Clock.systemUTC());
            coordinator.start();
        } catch (RuntimeException failure) {
            LOG.error("qCore central failed to start", failure);
            redis.close();
            database.close();
            throw failure instanceof StorageException || failure instanceof ConfigurationException
                    ? failure : new IllegalStateException("qCore central could not start", failure);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "qcore-shutdown"));
        LOG.info("qCore {} started — network '{}' — {} server(s) registered",
                VERSION, configuration.networkName(), coordinator.health().registeredServers());

        try {
            stopped.await(365, TimeUnit.DAYS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            shutdown();
        }
    }

    /** Stops the coordinator once; safe to call from a shutdown hook. */
    public synchronized void shutdown() {
        if (coordinator != null) {
            coordinator.close();
            coordinator = null;
        }
        stopped.countDown();
    }

    /** @return the running coordinator, {@code null} before start or after shutdown. */
    public NetworkCoordinator coordinator() {
        return coordinator;
    }

    /** Entry point: {@code java -jar qcore-central.jar [--config-dir DIR]}. */
    public static void main(String[] args) {
        Path directory = Path.of("config");
        for (int index = 0; index < args.length; index++) {
            if ("--config-dir".equals(args[index]) && index + 1 < args.length) {
                directory = Path.of(args[index + 1]);
                index++;
            } else if ("--help".equals(args[index]) || "-h".equals(args[index])) {
                System.out.println("""
                        qCore network intelligence engine — central node

                        Usage: java -jar qcore-central.jar [options]

                          --config-dir DIR   directory holding central.yml, database.yml,
                                             redis.yml, security.yml and rules.yml
                                             (default: ./config)
                          --version          print the version and exit
                          --help             print this help
                        """);
                return;
            } else if ("--version".equals(args[index])) {
                System.out.println("qCore " + VERSION);
                return;
            } else {
                System.err.println("Unknown argument: " + args[index]);
                System.exit(2);
            }
        }
        try {
            new CentralApplication(directory).run();
        } catch (RuntimeException failure) {
            System.err.println("qCore central failed to start: " + failure.getMessage());
            System.exit(1);
        }
    }
}
