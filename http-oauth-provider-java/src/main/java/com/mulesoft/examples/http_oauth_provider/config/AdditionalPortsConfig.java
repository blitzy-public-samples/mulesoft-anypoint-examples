package com.mulesoft.examples.http_oauth_provider.config;

import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Adds the provider listener on {@code http.provider.port} and registers the port path guard
 * (D-011).
 *
 * <p>Source: the provider module opens its own port, {@code oauth2-provider:config
 * port="${http.provider.port}"}, and names no host
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:26]; {@code HTTP_Listener_Configuration}
 * listens on {@code localhost:${http.listener.port}} [:43] and serves {@code /resources} [:45] and
 * {@code /redirect} [:54]. The defaults are 8081 and 8082
 * [http-oauth-provider/src/main/app/mule-app.properties].
 *
 * <p>The embedded Undertow server (D-010) has two plain-HTTP listeners:
 * <ul>
 *   <li>the primary listener, which Spring Boot opens on {@code server.port} and
 *       {@code server.address}, bound in {@code application.yml} to {@code http.listener.port} and
 *       {@code http.listener.host} (default {@code localhost:8082}, D-100); this class leaves it
 *       unchanged;</li>
 *   <li>the provider listener, which {@link #providerPortCustomizer(int)} adds on every local
 *       interface, {@code 0.0.0.0:${http.provider.port}} (default {@code 0.0.0.0:8081}, D-594).</li>
 * </ul>
 * Both listeners serve the same Spring MVC application and the same servlet filters.
 * {@link PortPathGuardFilter}, registered by {@link #portPathGuardFilterRegistration(int, int)},
 * passes {@code /authorize} and {@code /token} only on the provider port and {@code /resources} and
 * {@code /redirect} only on the listener port, and answers every other port and path pair with an
 * empty 404 (D-011, D-440).
 *
 * <p>The ports are read from configuration only. A start with
 * {@code --http.provider.port=18081 --http.listener.port=18082} opens {@code 0.0.0.0:18081} and
 * {@code localhost:18082}.
 *
 * <p>The provider address is checked before the provider listener is added (D-594). Startup fails,
 * with no listener open, when:
 * <ul>
 *   <li>{@code http.provider.port} lies outside {@code 1..65535}: {@link IllegalStateException}
 *       {@code http.provider.port must be between 1 and 65535, was <port>}, raised when the
 *       customizer bean is created;</li>
 *   <li>{@code http.provider.port} equals a non-zero {@code server.port}:
 *       {@link IllegalStateException}
 *       {@code http.provider.port and server.port must differ, both are <port>};</li>
 *   <li>the provider port is in use on {@code 0.0.0.0}: {@link PortInUseException}, reported as
 *       {@code Web server failed to start. Port <port> was already in use.};</li>
 *   <li>any other bind failure on {@code 0.0.0.0:<port>}: {@link IllegalStateException}
 *       {@code http.provider.port cannot listen on 0.0.0.0:<port>: <cause message>}.</li>
 * </ul>
 * The last three checks run when Spring Boot creates the web server, before any listener opens.
 *
 * <p>The class holds no mutable state.
 */
@Configuration(proxyBeanMethods = false)
public class AdditionalPortsConfig {

    /** Logger of the added provider listener's address. */
    private static final Logger LOG = LoggerFactory.getLogger(AdditionalPortsConfig.class);

    /** Configuration key of the provider listener port. */
    private static final String PROVIDER_PORT_KEY = "http.provider.port";

    /** Name of the provider listener in the INFO line that reports the added listener. */
    private static final String PROVIDER_LISTENER_NAME = "http.provider";

    /** Host of the provider listener: every local interface (D-594). */
    private static final String PROVIDER_HOST = "0.0.0.0";

    /** Lowest accepted provider port. */
    private static final int MIN_PORT = 1;

    /** Highest accepted provider port. */
    private static final int MAX_PORT = 65535;

    /** Servlet filter name of the {@link PortPathGuardFilter} registration. */
    private static final String GUARD_FILTER_NAME = "portPathGuardFilter";

    /** URL pattern of the {@link PortPathGuardFilter} registration: every request path. */
    private static final String ALL_PATHS = "/*";

    /**
     * Registers an Undertow builder customizer that adds the provider listener on
     * {@code 0.0.0.0:<providerPort>} (D-011, D-594).
     *
     * <p>The returned customizer calls {@link UndertowServletWebServerFactory#addBuilderCustomizers}
     * with one builder customizer. When the web server is created, the builder customizer rejects a
     * provider port equal to the factory's non-zero {@code server.port}, binds and releases a probe
     * server socket on {@code 0.0.0.0:<providerPort>}, calls
     * {@code Undertow.Builder.addHttpListener(providerPort, "0.0.0.0")} once and logs
     * {@code Added HTTP listener http.provider on 0.0.0.0:<providerPort>} at INFO. Spring Boot adds
     * the primary listener to the same builder; the builder customizer leaves it unchanged.
     *
     * @param providerPort the provider listener port, {@code http.provider.port} (default 8081)
     * @return the customizer of the embedded Undertow servlet web server factory
     * @throws IllegalStateException
     *     {@code http.provider.port must be between 1 and 65535, was <port>} when
     *     {@code providerPort} lies outside {@code 1..65535}
     */
    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> providerPortCustomizer(
            @Value("${http.provider.port}") int providerPort) {
        requirePortInRange(providerPort);
        return factory -> factory.addBuilderCustomizers(builder -> {
            requireDistinctFromServerPort(providerPort, factory.getPort());
            requireBindable(providerPort);
            builder.addHttpListener(providerPort, PROVIDER_HOST);
            LOG.info("Added HTTP listener {} on {}:{}", PROVIDER_LISTENER_NAME, PROVIDER_HOST, providerPort);
        });
    }

    /**
     * Registers {@link PortPathGuardFilter} for the provider and listener ports (D-011, D-440).
     *
     * <p>The registration maps the filter to {@code /*}, applies it to {@code REQUEST} dispatches
     * only, orders it at {@link Ordered#HIGHEST_PRECEDENCE} ({@code Integer.MIN_VALUE}), ahead of
     * every other servlet filter, and names it {@code portPathGuardFilter}. An {@code ERROR}
     * dispatch does not pass through the filter.
     *
     * @param providerPort the provider listener port, {@code http.provider.port} (default 8081),
     *                     owner of {@code /authorize} and {@code /token}
     * @param listenerPort the primary listener port, {@code http.listener.port} (default 8082),
     *                     owner of {@code /resources} and {@code /redirect}
     * @return the servlet filter registration of a new {@link PortPathGuardFilter}
     */
    @Bean
    public FilterRegistrationBean<PortPathGuardFilter> portPathGuardFilterRegistration(
            @Value("${http.provider.port}") int providerPort,
            @Value("${http.listener.port}") int listenerPort) {
        FilterRegistrationBean<PortPathGuardFilter> registration =
                new FilterRegistrationBean<>(new PortPathGuardFilter(providerPort, listenerPort));
        registration.addUrlPatterns(ALL_PATHS);
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName(GUARD_FILTER_NAME);
        return registration;
    }

    /**
     * Checks that the provider port lies in {@code 1..65535} (D-594).
     *
     * @param providerPort the value of {@code http.provider.port}
     * @throws IllegalStateException
     *     {@code http.provider.port must be between 1 and 65535, was <port>} when the port lies
     *     outside {@code 1..65535}
     */
    private static void requirePortInRange(int providerPort) {
        if (providerPort < MIN_PORT || providerPort > MAX_PORT) {
            throw new IllegalStateException(PROVIDER_PORT_KEY + " must be between " + MIN_PORT + " and "
                    + MAX_PORT + ", was " + providerPort);
        }
    }

    /**
     * Checks that the provider port differs from a non-zero primary listener port (D-594).
     *
     * @param providerPort the value of {@code http.provider.port}
     * @param serverPort   the factory's {@code server.port}; {@code 0} is never rejected
     * @throws IllegalStateException
     *     {@code http.provider.port and server.port must differ, both are <port>} when the two ports
     *     are equal and non-zero
     */
    private static void requireDistinctFromServerPort(int providerPort, int serverPort) {
        if (serverPort != 0 && providerPort == serverPort) {
            throw new IllegalStateException(PROVIDER_PORT_KEY + " and server.port must differ, both are "
                    + providerPort);
        }
    }

    /**
     * Resolves {@code 0.0.0.0}, then binds and releases a server socket on
     * {@code 0.0.0.0:<providerPort>} with {@code SO_REUSEADDR} enabled (D-594).
     *
     * @param providerPort the value of {@code http.provider.port}
     * @throws PortInUseException    when the port is in use on {@code 0.0.0.0}
     * @throws IllegalStateException {@code http.provider.port cannot listen on 0.0.0.0:<port>: <cause
     *                               message>} when the address does not resolve or the bind fails
     *                               for another reason
     */
    private static void requireBindable(int providerPort) {
        InetSocketAddress address;
        try {
            address = new InetSocketAddress(InetAddress.getByName(PROVIDER_HOST), providerPort);
        } catch (UnknownHostException | IllegalArgumentException ex) {
            throw cannotListen(providerPort, ex);
        }
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(address);
        } catch (IOException ex) {
            PortInUseException.throwIfPortBindingException(ex, () -> providerPort);
            throw cannotListen(providerPort, ex);
        }
    }

    /**
     * Builds the exception for a provider address the listener cannot listen on.
     *
     * @param providerPort the value of {@code http.provider.port}
     * @param cause        the failure of the resolution or of the bind
     * @return an exception whose message names {@code http.provider.port}, the host, the port and
     *     the cause's message
     */
    private static IllegalStateException cannotListen(int providerPort, Exception cause) {
        return new IllegalStateException(PROVIDER_PORT_KEY + " cannot listen on " + PROVIDER_HOST + ":"
                + providerPort + ": " + cause.getMessage(), cause);
    }
}
