package studio.qsa3ed.qcore.central.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import studio.qsa3ed.qcore.api.ConfigurationService;
import studio.qsa3ed.qcore.api.EventService;
import studio.qsa3ed.qcore.api.MetricsService;
import studio.qsa3ed.qcore.api.PlayerIdentityService;
import studio.qsa3ed.qcore.api.RoutingService;
import studio.qsa3ed.qcore.api.ServerRegistry;
import studio.qsa3ed.qcore.central.config.CentralConfiguration;
import studio.qsa3ed.qcore.central.intelligence.IntelligenceEngine;
import studio.qsa3ed.qcore.central.metrics.Counters;
import studio.qsa3ed.qcore.central.security.AuthenticationException;
import studio.qsa3ed.qcore.central.security.AuthenticationManager;
import studio.qsa3ed.qcore.common.events.NetworkEvent;
import studio.qsa3ed.qcore.common.exception.ValidationException;
import studio.qsa3ed.qcore.common.json.Json;
import studio.qsa3ed.qcore.common.model.NetworkServer;
import studio.qsa3ed.qcore.common.model.PlayerSession;
import studio.qsa3ed.qcore.common.model.RoutingDecision;
import studio.qsa3ed.qcore.common.model.RoutingRequest;
import studio.qsa3ed.qcore.common.model.ServerMetrics;
import studio.qsa3ed.qcore.common.util.Ids;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The qCore REST API.
 *
 * <p>Uses the JDK HTTP server on virtual threads, so no extra dependency is
 * required and every request is handled on its own cheap thread. Every route
 * authenticates, authorizes, validates input, rate limits by caller and returns
 * a structured error body. Responses are marked {@code no-store} because they
 * may contain network topology.</p>
 */
public final class HttpApiServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(HttpApiServer.class);
    private static final String PREFIX = "/api/v1/";
    private static final ObjectMapper MAPPER = Json.mapper();

    /** Services the API exposes. */
    public record Api(ServerRegistry servers, PlayerIdentityService players, RoutingService routing,
                      EventService events, MetricsService metrics, ConfigurationService configuration,
                      IntelligenceEngine intelligence) {
    }

    private final CentralConfiguration configuration;
    private final AuthenticationManager authentication;
    private final RateLimiter rateLimiter;
    private final Api api;
    private final Supplier<HealthReport> health;
    private final Counters counters;

    private HttpServer server;

    public HttpApiServer(CentralConfiguration configuration, AuthenticationManager authentication,
                         Api api, Supplier<HealthReport> health, Counters counters) {
        this.configuration = configuration;
        this.authentication = authentication;
        this.api = api;
        this.health = health;
        this.counters = counters;
        this.rateLimiter = new RateLimiter(configuration.rest().requestsPerMinute(),
                java.time.Clock.systemUTC());
    }

    /** Binds and starts the listener. */
    public void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(
                    configuration.rest().bindAddress(), configuration.rest().port()), 0);
            server.createContext(PREFIX, this::handle);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.start();
            LOG.info("qCore REST API listening on http://{}:{}{}",
                    configuration.rest().bindAddress(), configuration.rest().port(), PREFIX);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to bind the REST API to "
                    + configuration.rest().bindAddress() + ":" + configuration.rest().port(), failure);
        }
    }

    /** @return the bound port, {@code -1} before start. */
    public int port() {
        return server == null ? -1 : server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) {
        String requestId = Ids.newId();
        long startedAt = System.nanoTime();
        try {
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            String path = exchange.getRequestURI().getPath();
            String body = readBody(exchange);
            AuthenticationManager.Caller caller = authenticate(exchange, method, body);

            String rateKey = caller.id() + "|" + exchange.getRemoteAddress().getHostString();
            if (!rateLimiter.tryAcquire(rateKey)) {
                throw ApiException.rateLimited("too many requests; slow down");
            }

            Object result = route(method, path, exchange, caller, body);
            counters.increment("api.requests." + method.toLowerCase(Locale.ROOT));
            respond(exchange, 200, result);
            LOG.debug("{} {} -> 200 in {}ms", method, path, (System.nanoTime() - startedAt) / 1_000_000);
        } catch (AuthenticationException failure) {
            respondError(exchange, failure.forbidden() ? 403 : 401,
                    failure.forbidden() ? "forbidden" : "unauthorized", failure.getMessage(), requestId);
        } catch (ApiException failure) {
            respondError(exchange, failure.status(), failure.code(), failure.getMessage(), requestId);
        } catch (ValidationException | IllegalArgumentException failure) {
            respondError(exchange, 400, "bad_request", failure.getMessage(), requestId);
        } catch (CompletionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            LOG.warn("Request {} failed while calling an asynchronous service", requestId, cause);
            respondError(exchange, 503, "unavailable", "the requested service is unavailable", requestId);
        } catch (Exception failure) {
            LOG.error("Unhandled error while serving {} {}", exchange.getRequestMethod(),
                    exchange.getRequestURI(), failure);
            respondError(exchange, 500, "internal_error", "the request could not be completed", requestId);
        }
    }

    private AuthenticationManager.Caller authenticate(HttpExchange exchange, String method, String body) {
        AuthenticationManager.Role required = switch (method) {
            case "GET" -> AuthenticationManager.Role.READ;
            case "POST", "PUT", "DELETE" -> AuthenticationManager.Role.WRITE;
            default -> throw ApiException.badRequest("unsupported method " + method);
        };
        AuthenticationManager.Caller caller = authentication.authenticate(
                header(exchange, "X-QCore-Node"),
                header(exchange, "X-QCore-Timestamp"),
                header(exchange, "X-QCore-Signature"),
                header(exchange, "X-QCore-Api-Key"),
                body,
                required);
        counters.increment("api.auth.ok");
        return caller;
    }

    private Object route(String method, String path, HttpExchange exchange,
                         AuthenticationManager.Caller caller, String body) {
        if (!path.startsWith(PREFIX)) {
            throw ApiException.notFound("unknown endpoint");
        }
        String[] segments = path.substring(PREFIX.length()).split("/");
        if (segments.length == 0 || segments[0].isBlank()) {
            throw ApiException.notFound("unknown endpoint");
        }
        return "GET".equals(method)
                ? routeGet(segments, exchange, caller)
                : routePost(segments, caller, body);
    }

    private Object routeGet(String[] segments, HttpExchange exchange, AuthenticationManager.Caller caller) {
        return switch (segments[0]) {
            case "health" -> health.get();
            case "network" -> api.metrics().currentSnapshot();
            case "servers" -> {
                if (segments.length == 1) {
                    yield api.servers().all().join();
                }
                yield api.servers().find(segments[1]).join()
                        .orElseThrow(() -> ApiException.notFound("unknown server " + segments[1]));
            }
            case "players" -> {
                if (segments.length < 2) {
                    throw ApiException.badRequest("player uuid is required");
                }
                UUID uuid = parseUuid(segments[1]);
                yield api.players().profile(uuid).join()
                        .orElseThrow(() -> ApiException.notFound("unknown player " + uuid));
            }
            case "metrics" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("snapshot", api.metrics().currentSnapshot());
                payload.put("counters", api.metrics().counters());
                yield payload;
            }
            case "events" -> api.events().recent(parseLimit(exchange)).join();
            case "rules" -> {
                requireRole(caller, AuthenticationManager.Role.ADMIN, "listing automation rules");
                yield api.intelligence().rules();
            }
            default -> throw ApiException.notFound("unknown endpoint");
        };
    }

    private Object routePost(String[] segments, AuthenticationManager.Caller caller, String body) {
        String path = String.join("/", segments);
        switch (path) {
            case "servers/register" -> {
                NetworkServer server = read(body, NetworkServer.class);
                requireNodeIdentity(caller, server.id());
                api.servers().register(server).join();
                return Map.of("ok", true, "nodeId", server.id());
            }
            case "players/session" -> {
                PlayerSession session = read(body, PlayerSession.class);
                requireNodeIdentity(caller, session.serverId());
                api.players().updateSession(session).join();
                return Map.of("ok", true);
            }
            case "routing/evaluate" -> {
                RoutingRequest request = read(body, RoutingRequest.class);
                RoutingDecision decision = api.routing().evaluate(request).join();
                return decision;
            }
            case "events" -> {
                NetworkEvent event = read(body, NetworkEvent.class);
                return api.events().publish(event).join();
            }
            case "intelligence/evaluate" -> {
                requireRole(caller, AuthenticationManager.Role.ADMIN, "triggering automation");
                return Map.of("appliedActions", api.intelligence().tick());
            }
            default -> {
                // POST /servers/{id}/heartbeat
                if (segments.length == 3 && "servers".equals(segments[0]) && "heartbeat".equals(segments[2])) {
                    String serverId = segments[1];
                    requireNodeIdentity(caller, serverId);
                    ServerMetrics metrics = read(body, ServerMetrics.class);
                    api.servers().recordHeartbeat(serverId, metrics).join();
                    counters.increment("heartbeats.received");
                    return Map.of("ok", true);
                }
                throw ApiException.notFound("unknown endpoint");
            }
        }
    }

    /** A signed node may only speak for itself; API keys may manage any node. */
    private void requireNodeIdentity(AuthenticationManager.Caller caller, String nodeId) {
        if (caller.id().startsWith("api-key") || "anonymous".equals(caller.id())) {
            requireRole(caller, AuthenticationManager.Role.WRITE, "managing node " + nodeId);
            return;
        }
        if (!caller.id().equals(nodeId)) {
            throw new AuthenticationException("a node may only report for itself", true);
        }
    }

    private void requireRole(AuthenticationManager.Caller caller, AuthenticationManager.Role required,
                             String action) {
        if (caller.role().ordinal() < required.ordinal()) {
            throw new AuthenticationException("the " + required + " role is required for " + action, true);
        }
    }

    private String readBody(HttpExchange exchange) {
        int limit = configuration.rest().maxRequestBytes();
        try (InputStream in = exchange.getRequestBody()) {
            byte[] buffer = in.readNBytes(limit + 1);
            if (buffer.length > limit) {
                throw ApiException.payloadTooLarge("request body exceeds " + limit + " bytes");
            }
            return new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw ApiException.badRequest("unable to read the request body");
        }
    }

    private <T> T read(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            throw ApiException.badRequest("a JSON body is required");
        }
        try {
            return MAPPER.readValue(body, type);
        } catch (IOException failure) {
            throw ApiException.badRequest("invalid JSON body: " + failure.getMessage());
        }
    }

    private static String header(HttpExchange exchange, String name) {
        List<String> values = exchange.getRequestHeaders().get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException failure) {
            throw ApiException.badRequest("invalid uuid '" + raw + "'");
        }
    }

    private static int parseLimit(HttpExchange exchange) {
        String query = exchange.getRequestURI().getQuery();
        if (query == null || query.isBlank()) {
            return 50;
        }
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && "limit".equals(pair[0])) {
                try {
                    return Math.max(1, Math.min(500, Integer.parseInt(pair[1])));
                } catch (NumberFormatException invalid) {
                    throw ApiException.badRequest("limit must be an integer");
                }
            }
        }
        return 50;
    }

    private void respond(HttpExchange exchange, int status, Object payload) throws IOException {
        byte[] bytes;
        try {
            JsonNode node = MAPPER.valueToTree(payload);
            bytes = MAPPER.writeValueAsBytes(node);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Unable to serialize the response", failure);
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().add("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private void respondError(HttpExchange exchange, int status, String code, String message, String requestId) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message == null ? "request failed" : message);
        error.put("requestId", requestId);
        List<Map<String, Object>> details = new ArrayList<>();
        details.add(error);
        try {
            respond(exchange, status, Map.of("error", error, "errors", details));
        } catch (IOException failure) {
            LOG.debug("Unable to write the error response", failure);
        }
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
            LOG.info("REST API stopped");
        }
    }
}
