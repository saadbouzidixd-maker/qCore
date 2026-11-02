package studio.qsa3ed.qcore.common.util;

import java.util.UUID;

/** Identifier helpers. qCore uses UUIDv4 for messages and derives node ids from configuration. */
public final class Ids {

    private Ids() {
    }

    /** @return a new random message or correlation id. */
    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** @return the current epoch millis, isolated for testability. */
    public static long now() {
        return System.currentTimeMillis();
    }
}
