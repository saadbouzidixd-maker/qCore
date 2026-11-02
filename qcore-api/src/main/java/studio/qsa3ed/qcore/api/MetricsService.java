package studio.qsa3ed.qcore.api;

import studio.qsa3ed.qcore.common.model.NetworkSnapshot;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Exposes network health and qCore's own operational counters. */
public interface MetricsService {

    /** @return an immediately available in-memory snapshot, never blocking. */
    NetworkSnapshot currentSnapshot();

    /** @return a freshly gathered snapshot. */
    CompletableFuture<NetworkSnapshot> snapshot();

    /** @return operational counters such as heartbeats, routing decisions and rule triggers. */
    Map<String, Long> counters();

    /** Increments a counter by one. */
    void increment(String counter);
}
