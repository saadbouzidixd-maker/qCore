package studio.qsa3ed.qcore.central.security;

import java.io.Serial;

/** Signals that a request could not be authenticated or is not authorized. */
public final class AuthenticationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean forbidden;

    public AuthenticationException(String message, boolean forbidden) {
        super(message);
        this.forbidden = forbidden;
    }

    /** @return {@code true} when the caller was identified but lacks the required role. */
    public boolean forbidden() {
        return forbidden;
    }
}
