package studio.qsa3ed.qcore.common.exception;

import java.io.Serial;

/** Thrown when a PostgreSQL or Redis operation cannot be completed. */
public final class StorageException extends QCoreException {

    @Serial
    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }

    public StorageException(String message) {
        super(message);
    }
}
