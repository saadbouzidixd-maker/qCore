package studio.qsa3ed.qcore.central.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.common.security.HmacSigner;
import studio.qsa3ed.qcore.common.util.Validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Authenticates nodes and API clients.
 *
 * <p>Nodes sign each request with a per-node shared secret; the central side
 * verifies the signature and a timestamp window to make replaying a captured
 * request useless. Human and tooling access uses API keys mapped to roles.
 * Secrets are never logged or echoed in responses.</p>
 */
public final class AuthenticationManager {

    private static final Logger LOG = LoggerFactory.getLogger(AuthenticationManager.class);

    /** Caller identity established for one request. */
    public record Caller(String id, Role role) {
    }

    /** Ordered authorization levels. */
    public enum Role {
        READ,
        WRITE,
        ADMIN;

        static Optional<Role> parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return Optional.empty();
            }
            try {
                return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                return Optional.empty();
            }
        }

        boolean allows(Role required) {
            return ordinal() >= required.ordinal();
        }
    }

    private final CentralConfiguration.Security settings;
    private final Map<String, HmacSigner> signers;
    private final Clock clock;

    public AuthenticationManager(CentralConfiguration.Security settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
        this.signers = new java.util.LinkedHashMap<>();
        settings.nodeSecrets().forEach((nodeId, secret) -> signers.put(nodeId, new HmacSigner(secret)));
    }

    /**
     * Authenticates a request.
     *
     * @param nodeId    value of {@code X-QCore-Node}, may be {@code null}
     * @param timestamp value of {@code X-QCore-Timestamp}, may be {@code null}
     * @param signature value of {@code X-QCore-Signature}, may be {@code null}
     * @param apiKey    value of {@code X-QCore-Api-Key}, may be {@code null}
     * @param body      raw request body, empty for GET
     * @param required  authorization level the route needs
     * @return the authenticated caller
     * @throws AuthenticationException when authentication or authorization fails
     */
    public Caller authenticate(String nodeId, String timestamp, String signature, String apiKey,
                               String body, Role required) {
        if (apiKey != null && !apiKey.isBlank()) {
            String role = settings.apiKeys().get(apiKey.trim());
            if (role == null) {
                LOG.warn("Rejected an unknown API key");
                throw new AuthenticationException("invalid API key", false);
            }
            Role parsed = Role.parse(role).orElse(Role.READ);
            if (!parsed.allows(required)) {
                throw new AuthenticationException("API key lacks the " + required + " role", true);
            }
            // Callers are identified by a fingerprint so each key gets its own rate-limit
            // bucket and no secret ever appears in a log line.
            return new Caller("api-key:" + fingerprint(apiKey), parsed);
        }

        if (nodeId != null && !nodeId.isBlank()) {
            Validation.requireNodeId(nodeId, "X-QCore-Node");
            HmacSigner signer = signers.get(nodeId);
            if (signer == null) {
                throw new AuthenticationException("unknown node", false);
            }
            long stamp;
            try {
                stamp = Long.parseLong(timestamp == null ? "" : timestamp.trim());
            } catch (NumberFormatException missing) {
                throw new AuthenticationException("missing or invalid X-QCore-Timestamp", false);
            }
            long skew = Math.abs(clock.millis() - stamp);
            if (skew > settings.tolerance().toMillis()) {
                throw new AuthenticationException("request timestamp outside the accepted window", false);
            }
            if (!signer.verify(nodeId, stamp, body, signature)) {
                throw new AuthenticationException("invalid request signature", false);
            }
            if (!Role.WRITE.allows(required)) {
                return new Caller(nodeId, Role.WRITE);
            }
            return new Caller(nodeId, Role.WRITE);
        }

        if (!required.equals(Role.READ) || !settings.allowReadWithoutCredentials()) {
            throw new AuthenticationException("authentication is required", false);
        }
        return new Caller("anonymous", Role.READ);
    }

    /** @return {@code true} when at least one node secret is configured. */
    public boolean hasNodeSecrets() {
        return !settings.nodeSecrets().isEmpty();
    }

    /** @return a short SHA-256 fingerprint of an API key, safe for logs and rate-limit keys. */
    static String fingerprint(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(apiKey.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", impossible);
        }
    }
}
