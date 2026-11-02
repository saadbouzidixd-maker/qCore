package studio.qsa3ed.qcore.central.rest;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixed-window rate limiter keyed by caller.
 *
 * <p>Windows are one minute wide and scale with the configured limit. Empty
 * windows are pruned lazily so a long-running central does not accumulate state
 * for callers that stopped talking to it.</p>
 */
public final class RateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;

    private final int requestsPerWindow;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimiter(int requestsPerWindow, Clock clock) {
        this.requestsPerWindow = Math.max(1, requestsPerWindow);
        this.clock = clock;
    }

    /** @return {@code true} when the caller may proceed; {@code false} when limited. */
    public boolean tryAcquire(String key) {
        long now = clock.millis();
        Window window = windows.compute(key == null ? "anonymous" : key, (ignored, existing) -> {
            if (existing == null || now - existing.startedAt >= WINDOW_MILLIS) {
                return new Window(now);
            }
            return existing;
        });
        boolean allowed = window.count.incrementAndGet() <= requestsPerWindow;
        if (windows.size() > 10_000) {
            windows.entrySet().removeIf(entry -> now - entry.getValue().startedAt > 2 * WINDOW_MILLIS);
        }
        return allowed;
    }

    private static final class Window {
        private final long startedAt;
        private final AtomicInteger count = new AtomicInteger();

        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
