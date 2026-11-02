package studio.qsa3ed.qcore.api;

/**
 * Entry point for developer integrations.
 *
 * <p>Plugins and external tools obtain a {@code QCoreAPI} instance from the
 * central application or from the platform plugin. Every accessor returns a
 * stable public interface; implementation classes are never exposed.</p>
 */
public interface QCoreAPI {

    /** @return the network name configured for this deployment. */
    String networkName();

    ServerRegistry servers();

    PlayerIdentityService players();

    RoutingService routing();

    EventService events();

    MetricsService metrics();

    NetworkMessagingService messaging();

    ConfigurationService configuration();

    /** Released the API's resources; idempotent. */
    void close();
}
