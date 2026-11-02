package studio.qsa3ed.qcore.common.model;

/** Health status of a registered node. */
public enum NodeStatus {
    /** Healthy and receiving players. */
    ONLINE(false),
    /** Reachable but unhealthy or refusing new players. */
    DEGRADED(true),
    /** Health rules marked the node as capacity-exhausted. */
    OVERLOADED(true),
    /** No heartbeat inside the configured window. */
    OFFLINE(false),
    /** Registered but never seen a heartbeat yet. */
    UNKNOWN(true);

    private final boolean degraded;

    NodeStatus(boolean degraded) {
        this.degraded = degraded;
    }

    /** @return {@code true} when the status signals a health problem rather than normal operation. */
    public boolean isDegraded() {
        return degraded;
    }

    /** @return {@code true} when routing may consider this node as a destination. */
    public boolean isRoutable() {
        return this == ONLINE || this == DEGRADED || this == UNKNOWN;
    }
}
