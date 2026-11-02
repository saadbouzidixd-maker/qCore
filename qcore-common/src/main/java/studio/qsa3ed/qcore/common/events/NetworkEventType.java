package studio.qsa3ed.qcore.common.events;

/** Canonical network events emitted by qCore subsystems. */
public enum NetworkEventType {
    SERVER_REGISTERED,
    SERVER_HEARTBEAT_LOST,
    SERVER_STATUS_CHANGED,
    SERVER_OVERLOADED,
    PLAYER_JOINED_NETWORK,
    PLAYER_LEFT_NETWORK,
    PLAYER_SERVER_CHANGED,
    ROUTING_DECISION,
    ROUTING_FAILED,
    RULE_TRIGGERED,
    NODE_REGISTERED,
    MESSAGING_FAILURE,
    STORAGE_UNAVAILABLE
}
