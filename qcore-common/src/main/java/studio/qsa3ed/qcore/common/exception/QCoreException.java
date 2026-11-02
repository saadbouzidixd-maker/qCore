package studio.qsa3ed.qcore.common.exception;

import java.io.Serial;

/**
 * Base unchecked exception for every qCore failure.
 *
 * <p>All qCore exceptions are unchecked so that asynchronous pipeline code
 * (which completes futures exceptionally) does not require checked-exception
 * plumbing in every lambda.</p>
 */
public class QCoreException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public QCoreException(String message) {
        super(message);
    }

    public QCoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
