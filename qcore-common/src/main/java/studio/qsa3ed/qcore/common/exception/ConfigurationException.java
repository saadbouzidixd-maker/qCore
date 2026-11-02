package studio.qsa3ed.qcore.common.exception;

import java.io.Serial;

/** Thrown when a qCore configuration file is missing, malformed or unsafe. */
public final class ConfigurationException extends QCoreException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConfigurationException(String message) {
        super(message);
    }

    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
