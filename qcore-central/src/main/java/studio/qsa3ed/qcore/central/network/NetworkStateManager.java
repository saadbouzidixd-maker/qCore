package studio.qsa3ed.qcore.central.network;

import studio.qsa3ed.qcore.api.MetricsService;
import studio.qsa3ed.qcore.api.ServerRegistry;
import studio.qsa3ed.qcore.central.metrics.Counters;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NetworkSnapshot;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Combines registry state into network snapshots and exposes operational counters.
 *
 * <p>Snapshots are cheap: they are built from the registry cache and never
 * perform I/O, which keeps routing and the REST health path fast under load.</p>
 */
public final class NetworkStateManager implements MetricsService {

    private final ServerRegistry registry;
    private final Counters counters;
    private final Clock clock;

    public NetworkStateManager(ServerRegistry registry, Counters counters, Clock clock) {
        this.registry = registry;
        this.counters = counters;
        this.clock = clock;
    }

    @Override
    public NetworkSnapshot currentSnapshot() {
        List<NetworkServer> servers = registry.cachedAll();
        return NetworkSnapshot.of(servers, clock.millis());
    }

    @Override
    public CompletableFuture<NetworkSnapshot> snapshot() {
        return CompletableFuture.completedFuture(currentSnapshot());
    }

    @Override
    public Map<String, Long> counters() {
        return counters.snapshot();
    }

    @Override
    public void increment(String counter) {
        counters.increment(counter);
    }
}
