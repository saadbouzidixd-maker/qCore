package studio.qsa3ed.qcore.central;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.api.ConfigurationService;
import studio.qsa3ed.qcore.api.EventService;
import studio.qsa3ed.qcore.api.NetworkMessagingService;
import studio.qsa3ed.qcore.api.PlayerIdentityService;
import studio.qsa3ed.qcore.api.QCoreAPI;
import studio.qsa3ed.qcore.api.RoutingService;
import studio.qsa3ed.qcore.api.ServerRegistry;
import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.central.events.EventCoordinator;
import studio.qsa3ed.qcore.central.intelligence.IntelligenceEngine;
import studio.qsa3ed.qcore.central.intelligence.RuleEngine;
import studio.qsa3ed.qcore.central.messaging.NodeMessagingService;
import studio.qsa3ed.qcore.central.metrics.Counters;
import studio.qsa3ed.qcore.central.network.HeartbeatMonitor;
import studio.qsa3ed.qcore.central.network.NetworkStateManager;
import studio.qsa3ed.qcore.central.network.ServerRegistryManager;
import studio.qsa3ed.qcore.central.players.PlayerDataCoordinator;
import studio.qsa3ed.qcore.central.rest.HealthReport;
import studio.qsa3ed.qcore.central.rest.HttpApiServer;
import studio.qsa3ed.qcore.central.routing.RoutingCoordinator;
import studio.qsa3ed.qcore.central.security.AuthenticationManager;
import studio.qsa3ed.qcore.common.messaging.MessageSerializer;
import studio.qsa3ed.qcore.storage.database.Database;
import studio.qsa3ed.qcore.storage.redis.RedisConnectionManager;
import studio.qsa3ed.qcore.storage.redis.RedisMessageBus;
import studio.qsa3ed.qcore.storage.redis.RedisStreamQueue;
import studio.qsa3ed.qcore.storage.repository.EventRepository;
import studio.qsa3ed.qcore.storage.repository.PlayerRepository;
import studio.qsa3ed.qcore.storage.repository.ServerRepository;
import studio.qsa3ed.qcore.storage.repository.jdbc.JdbcEventRepository;
import studio.qsa3ed.qcore.storage.repository.jdbc.JdbcPlayerRepository;
import studio.qsa3ed.qcore.storage.repository.jdbc.JdbcServerRepository;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Composition root of the central node.
 *
 * <p>Builds every subsystem in dependency order, starts the background sweeps
 * (heartbeat expiry, network intelligence) and exposes the aggregated
 * {@link QCoreAPI} for integrations. Closing is idempotent and ordered so the
 * API stops accepting requests before storage is released.</p>
 */
public final class NetworkCoordinator implements QCoreAPI, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkCoordinator.class);

    private final CentralConfiguration configuration;
    private final ConfigurationService configurationService;
    private final Database database;
    private final RedisConnectionManager redis;
    private final Clock clock;
    private final long startedAt;

    private final Counters counters = new Counters();
    private final ServerRegistryManager registry;
    private final NetworkStateManager state;
    private final EventCoordinator events;
    private final PlayerDataCoordinator players;
    private final RoutingCoordinator routing;
    private final IntelligenceEngine intelligence;
    private final HeartbeatMonitor heartbeatMonitor;
    private final AuthenticationManager authentication;
    private final HttpApiServer apiServer;
    private final RedisMessageBus messageBus;
    private final RedisStreamQueue streamQueue;
    private final NodeMessagingService messaging;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "qcore-central-scheduler");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean closed = new AtomicBoolean();

    public NetworkCoordinator(CentralConfiguration configuration, ConfigurationService configurationService,
                              Database database, RedisConnectionManager redis, Clock clock) {
        this.configuration = configuration;
        this.configurationService = configurationService;
        this.database = database;
        this.redis = redis;
        this.clock = clock;
        this.startedAt = clock.millis();

        ServerRepository serverRepository = new JdbcServerRepository(database);
        PlayerRepository playerRepository = new JdbcPlayerRepository(database);
        EventRepository eventRepository = new JdbcEventRepository(database);

        this.messageBus = new RedisMessageBus(redis);
        this.streamQueue = new RedisStreamQueue(redis, NodeMessagingService.DURABLE_STREAM,
                NodeMessagingService.GROUP, "central", 5);
        this.messaging = new NodeMessagingService(messageBus, streamQueue, new MessageSerializer());

        this.events = new EventCoordinator(eventRepository, messaging, counters, "central");
        this.registry = new ServerRegistryManager(serverRepository, events, clock);
        this.state = new NetworkStateManager(registry, counters, clock);
        this.players = new PlayerDataCoordinator(playerRepository, events, counters, clock);
        this.routing = new RoutingCoordinator(registry, state, configuration.routing(), events, clock);
        this.intelligence = new IntelligenceEngine(
                new RuleEngine(configuration.intelligence().rules(), clock),
                registry, events, state, eventRepository, configuration.intelligence(), clock);
        this.heartbeatMonitor = new HeartbeatMonitor(registry, configuration.heartbeat().timeout(), clock);
        this.authentication = new AuthenticationManager(configuration.security(), clock);
        this.apiServer = new HttpApiServer(configuration, authentication,
                new HttpApiServer.Api(registry, players, routing, events, state, configurationService, intelligence),
                this::health, counters);
    }

    /** Starts background work and the REST listener. */
    public void start() {
        registry.warmUp().join();
        heartbeatMonitor.sweep();
        long sweepMillis = Math.max(1_000L, configuration.heartbeat().sweepInterval().toMillis());
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                heartbeatMonitor.sweep();
                counters.increment("heartbeat.sweeps");
            } catch (RuntimeException failure) {
                LOG.warn("Heartbeat sweep failed", failure);
            }
        }, sweepMillis, sweepMillis, TimeUnit.MILLISECONDS);

        if (configuration.intelligence().enabled()) {
            long interval = Math.max(1_000L, configuration.intelligence().interval().toMillis());
            scheduler.scheduleWithFixedDelay(() -> {
                try {
                    intelligence.tick();
                } catch (RuntimeException failure) {
                    LOG.warn("Network intelligence tick failed", failure);
                }
            }, interval, interval, TimeUnit.MILLISECONDS);
            LOG.info("Network intelligence enabled with {} rule(s){}",
                    configuration.intelligence().rules().size(),
                    configuration.intelligence().dryRun() ? " in dry-run mode" : "");
        } else {
            LOG.info("Network intelligence is disabled by configuration");
        }

        streamQueue.ensureGroup().exceptionally(failure -> {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            LOG.warn("Durable message consumer group is unavailable: {}", cause.getMessage());
            LOG.debug("Consumer group creation failed", cause);
            return null;
        });

        apiServer.start();
    }

    /** @return an observable health report. */
    public HealthReport health() {
        var snapshot = state.currentSnapshot();
        return new HealthReport(
                configuration.networkName(),
                CentralApplication.VERSION,
                clock.millis() - startedAt,
                database.ping(),
                redis.ping(),
                messaging.isAvailable(),
                registry.cachedByType(studio.qsa3ed.qcore.common.model.NodeType.SERVER).size(),
                snapshot.onlineServers(),
                snapshot.totalPlayers(),
                counters.snapshot());
    }

    @Override
    public String networkName() {
        return configuration.networkName();
    }

    @Override
    public ServerRegistry servers() {
        return registry;
    }

    @Override
    public PlayerIdentityService players() {
        return players;
    }

    @Override
    public RoutingService routing() {
        return routing;
    }

    @Override
    public EventService events() {
        return events;
    }

    @Override
    public studio.qsa3ed.qcore.api.MetricsService metrics() {
        return state;
    }

    @Override
    public NetworkMessagingService messaging() {
        return messaging;
    }

    @Override
    public ConfigurationService configuration() {
        return configurationService;
    }

    /** @return the central configuration. */
    public CentralConfiguration centralConfiguration() {
        return configuration;
    }

    /** @return the REST port in use, or {@code -1}. */
    public int restPort() {
        return apiServer.port();
    }

    /** @return the operational counters. */
    public Map<String, Long> counters() {
        return counters.snapshot();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        LOG.info("Shutting down qCore central");
        apiServer.close();
        scheduler.shutdownNow();
        messageBus.close();
        streamQueue.close();
        redis.close();
        database.close();
        LOG.info("qCore central stopped");
    }
}
