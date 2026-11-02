package studio.qsa3ed.qcore.common.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 signing for node-to-central traffic.
 *
 * <p>Shared secrets never travel over the wire. Both sides compute a signature
 * over {@code nodeId + "\n" + timestamp + "\n" + body} and compare it in
 * constant time, and the central side additionally rejects timestamps outside
 * the configured skew window.</p>
 */
public final class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";
    private static final HexFormat HEX = HexFormat.of();

    private final byte[] secret;

    public HmacSigner(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("secret must not be blank");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Computes the lowercase hex signature for a canonical request. */
    public String sign(String nodeId, long timestamp, String body) {
        return HEX.formatHex(rawSignature(nodeId, timestamp, body));
    }

    /** Verifies a signature in constant time. */
    public boolean verify(String nodeId, long timestamp, String body, String providedSignature) {
        if (providedSignature == null || providedSignature.isBlank()) {
            return false;
        }
        byte[] expected = rawSignature(nodeId, timestamp, body);
        byte[] provided;
        try {
            provided = HEX.parseHex(providedSignature.trim());
        } catch (IllegalArgumentException invalidHex) {
            return false;
        }
        return MessageDigest.isEqual(expected, provided);
    }

    private byte[] rawSignature(String nodeId, long timestamp, String body) {
        String canonical = nodeId + "\n" + timestamp + "\n" + (body == null ? "" : body);
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException unsupported) {
            throw new IllegalStateException("HMAC-SHA256 is not available in this JVM", unsupported);
        }
    }
}
