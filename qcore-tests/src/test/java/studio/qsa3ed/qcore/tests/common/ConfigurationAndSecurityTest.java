package studio.qsa3ed.qcore.tests.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.common.config.YamlConfig;
import studio.qsa3ed.qcore.common.exception.ConfigurationException;
import studio.qsa3ed.qcore.common.security.HmacSigner;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationAndSecurityTest {

    @Test
    @DisplayName("durations accept seconds, milliseconds and minutes")
    void durationParsing() {
        YamlConfig config = YamlConfig.fromString("""
                heartbeat:
                  timeout: 30s
                  sweep: 500ms
                  window: 2m
                """, "test.yml");

        assertEquals(Duration.ofSeconds(30), config.getDuration("heartbeat.timeout", Duration.ZERO));
        assertEquals(Duration.ofMillis(500), config.getDuration("heartbeat.sweep", Duration.ZERO));
        assertEquals(Duration.ofMinutes(2), config.getDuration("heartbeat.window", Duration.ZERO));
    }

    @Test
    @DisplayName("defaults apply when keys are absent")
    void defaults() {
        YamlConfig config = YamlConfig.fromString("rest:\n  port: 9090\n", "test.yml");

        assertEquals(9090, config.getInt("rest.port", 8080));
        assertEquals(8080, config.getInt("rest.missing", 8080));
        assertEquals("fallback", config.getString("rest.name", "fallback"));
    }

    @Test
    @DisplayName("a malformed numeric value names the offending key")
    void invalidNumber() {
        YamlConfig config = YamlConfig.fromString("rest:\n  port: abc\n", "test.yml");

        ConfigurationException failure = assertThrows(ConfigurationException.class,
                () -> config.getInt("rest.port", 8080));
        assertTrue(failure.getMessage().contains("rest.port"));
    }

    @Test
    @DisplayName("routing thresholds outside their range are rejected at construction")
    void routingValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new CentralConfiguration.Routing(25.0, 0.9, false, Set.of(), 10));
        assertThrows(IllegalArgumentException.class,
                () -> new CentralConfiguration.Routing(15.0, 1.5, false, Set.of(), 10));
        assertEquals(0.95, CentralConfiguration.Routing.defaults().maxCapacityUtilisation());
    }

    @Test
    @DisplayName("HMAC signatures verify and reject tampering")
    void hmac() {
        HmacSigner signer = new HmacSigner("super-secret-value");
        String body = "{\"node\":\"lobby-1\"}";
        long timestamp = 1_700_000_000_000L;

        String signature = signer.sign("lobby-1", timestamp, body);

        assertTrue(signer.verify("lobby-1", timestamp, body, signature));
        assertFalse(signer.verify("lobby-1", timestamp, body + " ", signature));
        assertFalse(signer.verify("lobby-2", timestamp, body, signature));
        assertFalse(signer.verify("lobby-1", timestamp + 1, body, signature));
        assertFalse(signer.verify("lobby-1", timestamp, body, "not-hex"));
        assertFalse(signer.verify("lobby-1", timestamp, body, null));
    }

    @Test
    @DisplayName("a different secret cannot forge a signature")
    void hmacForgery() {
        String body = "{}";
        String signature = new HmacSigner("secret-a").sign("node", 1L, body);

        assertFalse(new HmacSigner("secret-b").verify("node", 1L, body, signature));
    }
}
