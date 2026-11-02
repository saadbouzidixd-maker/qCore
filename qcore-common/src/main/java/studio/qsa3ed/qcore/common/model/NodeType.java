package studio.qsa3ed.qcore.common.model;

/** The role a qCore node plays inside the network. */
public enum NodeType {
    /** A gameplay server (Paper, Purpur or Folia). */
    SERVER,
    /** A Velocity proxy. */
    PROXY,
    /** The central coordinator. */
    CENTRAL
}
