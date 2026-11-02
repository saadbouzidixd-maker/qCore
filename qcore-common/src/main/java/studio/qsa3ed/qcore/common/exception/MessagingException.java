package studio.qsa3ed.qcore.common.exception;

import java.io.Serial;

/** Thrown when a network message cannot be published, delivered or acknowledged. */
public final class MessagingException extends QCoreException {

    @Serial
    private static final long serialVersionUID = 1L;

    public MessagingException(String message, Throwable cause) {
        super(message, cause);
    }

    public MessagingException(String message) {
        super(message);
    }
}
