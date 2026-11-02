package studio.qsa3ed.qcore.central.rest;

import java.io.Serial;

/** A request failure that maps directly onto an HTTP status and error code. */
public final class ApiException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "bad_request", message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(401, "unauthorized", message);
    }

    public static ApiException forbidden(String message) {
        return new ApiException(403, "forbidden", message);
    }

    public static ApiException notFound(String message) {
        return new ApiException(404, "not_found", message);
    }

    public static ApiException payloadTooLarge(String message) {
        return new ApiException(413, "payload_too_large", message);
    }

    public static ApiException rateLimited(String message) {
        return new ApiException(429, "rate_limited", message);
    }

    public static ApiException unavailable(String message) {
        return new ApiException(503, "unavailable", message);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
