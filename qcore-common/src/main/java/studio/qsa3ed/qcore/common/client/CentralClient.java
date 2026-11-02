package studio.qsa3ed.qcore.common.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import studio.qsa3ed.qcore.common.events.NetworkEvent;
import studio.qsa3ed.qcore.common.exception.MessagingException;
import studio.qsa3ed.qcore.common.json.Json;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.NetworkSnapshot;
import studio.qsa3ed.qcore.common.model.PlayerSession;
import studio.qsa3ed.qcore.common.model.RoutingDecision;
import studio.qsa3ed.qcore.common.model.RoutingRequest;
import studio.qsa3ed.qcore.common.model.ServerMetrics;
import studio.qsa3ed.qcore.common.security.HmacSigner;
import studio.qsa3ed.qcore.common.util.Ids;
import studio.qsa3ed.qcore.common.util.Validation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Asynchronous HTTP client used by gameplay servers and proxies to talk to
 * qCore Central.
 *
 * <p>Every request is signed with the node's shared secret, so a node never
 * sends a password. The client never blocks a game thread: all requests are
 * dispatched on the JDK HTTP client's executor and returned as futures.</p>
 */
public final class CentralClient implements AutoCloseable {

    /**
     * Client settings.
     *
     * @param baseUrl      central REST base url, for example {@code http://127.0.0.1:8080}
     * @param nodeId       this node's id
     * @param sharedSecret node secret used for HMAC signing
     * @param timeout      request timeout
     */
    public record Settings(String baseUrl, String nodeId, String sharedSecret, Duration timeout) {

        public Settings {
            Validation.requireNonBlank(baseUrl, "baseUrl");
            Validation.requireNodeId(nodeId, "nodeId");
            Validation.requireNonBlank(sharedSecret, "sharedSecret");
            timeout = timeout == null || timeout.isZero() || timeout.isNegative()
                    ? Duration.ofSeconds(5) : timeout;
            baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
    }

    private final Settings settings;
    private final HttpClient http;
    private final HmacSigner signer;

    public CentralClient(Settings settings) {
        this.settings = settings;
        this.signer = new HmacSigner(settings.sharedSecret());
        this.http = HttpClient.newBuilder()
                .connectTimeout(settings.timeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    /** @return the node id this client authenticates as. */
    public String nodeId() {
        return settings.nodeId();
    }

    /** Registers or refreshes this node. */
    public CompletableFuture<Void> register(NetworkServer server) {
        return send("POST", "/api/v1/servers/register", server).thenApply(ignored -> null);
    }

    /** Reports telemetry for this node. */
    public CompletableFuture<Void> heartbeat(String serverId, ServerMetrics metrics) {
        return send("POST", "/api/v1/servers/" + encode(serverId) + "/heartbeat", metrics)
                .thenApply(ignored -> null);
    }

    /** Reports a player session change. */
    public CompletableFuture<Void> reportSession(PlayerSession session) {
        return send("POST", "/api/v1/players/session", session).thenApply(ignored -> null);
    }

    /** Asks central to place a player. */
    public CompletableFuture<RoutingDecision> evaluateRouting(RoutingRequest request) {
        return send("POST", "/api/v1/routing/evaluate", request)
                .thenApply(node -> Json.mapper().convertValue(node, RoutingDecision.class));
    }

    /** Publishes a network event. */
    public CompletableFuture<Void> publishEvent(NetworkEvent event) {
        return send("POST", "/api/v1/events", event).thenApply(ignored -> null);
    }

    /** @return every registered server. */
    public CompletableFuture<List<NetworkServer>> servers() {
        return send("GET", "/api/v1/servers", null)
                .thenApply(node -> Json.mapper().convertValue(node, new TypeReference<List<NetworkServer>>() {
                }));
    }

    /** @return the current network snapshot. */
    public CompletableFuture<NetworkSnapshot> snapshot() {
        return send("GET", "/api/v1/network", null)
                .thenApply(node -> Json.mapper().convertValue(node, NetworkSnapshot.class));
    }

    /** Performs a signed request and unwraps the response body. */
    private CompletableFuture<JsonNode> send(String method, String path, Object body) {
        String json = body == null ? "" : write(body);
        long timestamp = Ids.now();
        String signature = signer.sign(settings.nodeId(), timestamp, json);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(settings.baseUrl() + path))
                .timeout(settings.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("X-QCore-Node", settings.nodeId())
                .header("X-QCore-Timestamp", Long.toString(timestamp))
                .header("X-QCore-Signature", signature);

        HttpRequest request = "GET".equals(method)
                ? builder.GET().build()
                : builder.method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();

        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        String responseBody = response.body();
                        if (responseBody == null || responseBody.isBlank()) {
                            return Json.mapper().createObjectNode();
                        }
                        try {
                            return Json.mapper().readTree(responseBody);
                        } catch (IOException failure) {
                            throw new MessagingException("Malformed response from central", failure);
                        }
                    }
                    throw new MessagingException("Central rejected " + method + " " + path
                            + " with HTTP " + response.statusCode() + ": " + summarize(response.body()));
                });
    }

    private static String summarize(String body) {
        if (body == null || body.isBlank()) {
            return "<empty body>";
        }
        String compact = body.replaceAll("\\s+", " ").trim();
        return compact.length() <= 300 ? compact : compact.substring(0, 300) + "...";
    }

    private static String write(Object body) {
        try {
            return Json.mapper().writeValueAsString(body);
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            throw new CompletionException(new MessagingException("Unable to serialize request body", failure));
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        // The JDK HttpClient holds no resources that require explicit release on
        // current Java versions; the method exists so callers can close uniformly.
    }
}
