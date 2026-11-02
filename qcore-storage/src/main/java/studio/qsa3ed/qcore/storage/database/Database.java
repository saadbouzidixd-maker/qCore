package studio.qsa3ed.qcore.storage.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.common.exception.StorageException;
import studio.qsa3ed.qcore.storage.config.DatabaseSettings;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the HikariCP pool and the bounded executor used for every database call.
 *
 * <p>Callers never touch a {@link Connection} on a game thread: work is handed
 * to a bounded pool and returned as a {@link CompletableFuture}. When the pool
 * or queue is saturated the call fails fast instead of blocking the caller.</p>
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    private final DatabaseSettings settings;
    private final HikariDataSource pool;
    private final ExecutorService executor;
    private volatile boolean closed;

    private Database(DatabaseSettings settings, HikariDataSource pool, ExecutorService executor) {
        this.settings = settings;
        this.pool = pool;
        this.executor = executor;
    }

    /** Opens the pool, or returns a disabled instance when persistence is off. */
    public static Database open(DatabaseSettings settings) {
        ExecutorService executor = newExecutor(settings);
        if (!settings.enabled()) {
            LOG.warn("PostgreSQL persistence is disabled; qCore central will run in memory-only mode");
            return new Database(settings, null, executor);
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("qcore-postgres");
        config.setJdbcUrl(settings.jdbcUrl());
        config.setUsername(settings.username());
        config.setPassword(settings.password());
        config.setMaximumPoolSize(settings.poolSize());
        config.setMinimumIdle(Math.min(2, settings.poolSize()));
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setValidationTimeout(Math.min(5_000L, settings.connectionTimeoutMs()));
        config.setAutoCommit(true);
        try {
            HikariDataSource dataSource = new HikariDataSource(config);
            Database database = new Database(settings, dataSource, executor);
            LOG.info("PostgreSQL pool ready: {}", settings.safeDescription());
            return database;
        } catch (RuntimeException failure) {
            executor.shutdownNow();
            throw new StorageException("Unable to open the PostgreSQL connection pool at "
                    + settings.safeDescription(), failure);
        }
    }

    private static ExecutorService newExecutor(DatabaseSettings settings) {
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "qcore-db-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        return new ThreadPoolExecutor(
                Math.max(2, settings.poolSize() / 2),
                Math.max(2, settings.poolSize()),
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.queueCapacity()),
                factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** @return {@code true} when a real database is attached. */
    public boolean isAvailable() {
        return pool != null && !closed;
    }

    /** @return the pool size, {@code 0} when disabled. */
    public int poolSize() {
        return pool == null ? 0 : pool.getMaximumPoolSize();
    }

    /**
     * Runs work with a pooled connection and closes it afterwards.
     *
     * @throws StorageException asynchronously when the database is unavailable or saturated
     */
    public <T> CompletableFuture<T> withConnection(SqlWork<T> work) {
        if (!isAvailable()) {
            return CompletableFuture.failedFuture(
                    new StorageException("PostgreSQL is not available on this node"));
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try (Connection connection = pool.getConnection()) {
                    future.complete(work.apply(connection));
                } catch (SQLException failure) {
                    future.completeExceptionally(new StorageException("Database operation failed", failure));
                } catch (RuntimeException failure) {
                    future.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException saturated) {
            future.completeExceptionally(new StorageException(
                    "Database work queue is saturated; rejecting the operation", saturated));
        }
        return future;
    }

    /** Runs work and completes with no result. */
    public CompletableFuture<Void> execute(SqlAction action) {
        return withConnection(connection -> {
            action.apply(connection);
            return null;
        });
    }

    /** Executes a lightweight connectivity probe. */
    public boolean ping() {
        if (!isAvailable()) {
            return false;
        }
        try (Connection connection = pool.getConnection()) {
            return connection.isValid(2);
        } catch (SQLException failure) {
            LOG.debug("Database ping failed", failure);
            return false;
        }
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        if (pool != null) {
            pool.close();
        }
    }

    /** Unit of work that may throw {@link SQLException}. */
    @FunctionalInterface
    public interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Unit of work without a result. */
    @FunctionalInterface
    public interface SqlAction {
        void apply(Connection connection) throws SQLException;
    }

    /** @return the settings this database was opened with. */
    public DatabaseSettings settings() {
        return settings;
    }
}
