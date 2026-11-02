package studio.qsa3ed.qcore.central.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Named, thread-safe counters for qCore's own behaviour.
 *
 * <p>Counters are intentionally cheap and unbounded in name, but the number of
 * names is fixed by the code that increments them, so memory stays constant.</p>
 */
public final class Counters {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** Increments a counter by one. */
    public void increment(String name) {
        if (name == null || name.isBlank()) {
            return;
        }
        counters.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
    }

    /** Increments a counter by a delta. */
    public void add(String name, long delta) {
        if (name == null || name.isBlank()) {
            return;
        }
        counters.computeIfAbsent(name, key -> new AtomicLong()).addAndGet(delta);
    }

    /** @return an immutable snapshot of every counter. */
    public Map<String, Long> snapshot() {
        Map<String, Long> result = new ConcurrentHashMap<>();
        counters.forEach((name, value) -> result.put(name, value.get()));
        return Map.copyOf(result);
    }
}
