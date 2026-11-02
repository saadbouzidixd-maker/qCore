package studio.qsa3ed.qcore.common.exception;

import java.io.Serial;

/** Thrown when caller-supplied data violates a documented qCore contract. */
public final class ValidationException extends QCoreException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String field;

    public ValidationException(String field, String message) {
        super(field + ": " + message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
