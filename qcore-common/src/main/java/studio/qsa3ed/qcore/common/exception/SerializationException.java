package studio.qsa3ed.qcore.common.exception;

import java.io.Serial;

/** Thrown when a qCore model cannot be serialized or deserialized. */
public final class SerializationException extends QCoreException {

    @Serial
    private static final long serialVersionUID = 1L;

    public SerializationException(String message, Throwable cause) {
        super(message, cause);
    }

    public SerializationException(String message) {
        super(message);
    }
}
