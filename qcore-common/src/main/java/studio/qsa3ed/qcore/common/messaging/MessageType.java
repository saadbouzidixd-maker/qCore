package studio.qsa3ed.qcore.common.messaging;

/** The closed set of message types exchanged between qCore nodes. */
public enum MessageType {
    SERVER_REGISTRATION,
    SERVER_HEARTBEAT,
    NODE_OFFLINE,
    PLAYER_SESSION,
    PLAYER_IDENTITY_SYNC,
    ROUTING_REQUEST,
    ROUTING_DECISION,
    NETWORK_EVENT,
    COMMAND,
    ACKNOWLEDGEMENT,
    STATE_INVALIDATION
}
