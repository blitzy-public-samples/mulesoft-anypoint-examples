package com.mulesoft.examples.authenticating_salesforce_using_oauth2.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * Adds the Salesforce OAuth callback listener on {@code sfdc.oauth.callback-port} and
 * {@code sfdc.oauth.callback-domain} beside the primary {@code server.port} listener of the embedded
 * Undertow server (D-011). The callback listener replaces the listener that
 * {@code <sfdc:oauth-callback-config domain="localhost" localPort="8081" path="oauth2callback"/>} opened
 * [authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml:4].
 *
 * <p>The application has two plain HTTP listeners:
 * <ul>
 *   <li>the primary listener, which Spring Boot opens on {@code server.port} and {@code server.address},
 *       bound in {@code application.yml} to {@code http.port} and
 *       {@code listener.http-listener-configuration.host} (defaults {@code 8082} and {@code 0.0.0.0});
 *       it serves {@code /} [salesforce-oauth.xml:6,10];</li>
 *   <li>the callback listener, which this class adds on {@code sfdc.oauth.callback-domain} and
 *       {@code sfdc.oauth.callback-port} (defaults {@code localhost} and {@code 8081}); it serves
 *       {@code /oauth2callback}.</li>
 * </ul>
 * This class leaves the primary listener unchanged and first in the listener list:
 * {@code WebServer.getPort()} and {@code @LocalServerPort} return {@code server.port}, and with the
 * committed {@code application.yml} the startup log reads
 * {@code Undertow started on ports 8082 (http), 8081 (http)}. Both listeners serve the same servlet
 * deployment; this class restricts no path. {@code PortPathGuardFilter} keeps each path on the listener
 * that owns it (D-011).
 *
 * <p>The callback address is checked before the listener is added (D-487), and each failed check stops
 * the application at startup with no listener open:
 * <ul>
 *   <li>when {@link #customize} runs: no {@code sfdc.oauth.*} key bound, a callback port outside
 *       {@code 1..65535} ({@code 0}, the value of an absent {@code sfdc.oauth.callback-port}, included),
 *       or a blank callback domain raises an {@link IllegalStateException} naming the key;</li>
 *   <li>when Spring Boot creates the web server: a callback port equal to a non-zero {@code server.port}
 *       raises an {@link IllegalStateException}; a callback port in use on the host raises Spring Boot's
 *       {@link PortInUseException}, reported as
 *       {@code Web server failed to start. Port <port> was already in use.}; a domain that does not
 *       resolve, an address that is not one of this host's, or any other bind failure raises an
 *       {@link IllegalStateException} whose message names {@code sfdc.oauth.callback}, the domain and
 *       the port.</li>
 * </ul>
 * After the checks pass, the listener is added to the Undertow builder and opens when the web server
 * starts, after the primary listener, and one INFO line {@code Added HTTP listener sfdc.oauth.callback
 * on <domain>:<port>} is logged. Only the callback domain and port are logged.
 *
 * <pre>{@code
 * // application.yml
 * sfdc:
 *   oauth:
 *     callback-port: 8081
 *     callback-domain: localhost
 * // After startup Undertow listens on 0.0.0.0:8082 (server.port) and localhost:8081 (this class).
 * }</pre>
 */
@Component
public class AdditionalPortsConfig implements WebServerFactoryCustomizer<UndertowServletWebServerFactory> {

    private static final Logger LOG = LoggerFactory.getLogger(AdditionalPortsConfig.class);

    /** Name of the callback listener in log lines and exception messages. */
    private static final String LISTENER_KEY = "sfdc.oauth.callback";

    /** Key of the callback listener's port. */
    private static final String PORT_KEY = "sfdc.oauth.callback-port";

    /** Key of the callback listener's host. */
    private static final String DOMAIN_KEY = "sfdc.oauth.callback-domain";

    /** Highest TCP port number. */
    private static final int MAX_PORT = 65535;

    /** The bound {@code sfdc.*} keys; only {@code sfdc.oauth.callback-port} and {@code -domain} are read. */
    private final SalesforceOAuthProperties properties;

    /**
     * Creates the customizer of the callback listener.
     *
     * @param properties the bound {@code sfdc.*} keys
     * @throws NullPointerException if {@code properties} is {@code null}
     */
    public AdditionalPortsConfig(SalesforceOAuthProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * Registers an Undertow builder customizer that adds one HTTP listener on
     * {@code sfdc.oauth.callback-domain} and {@code sfdc.oauth.callback-port} (D-011).
     *
     * <p>Reads {@code properties.oauth().callbackPort()} and {@code properties.oauth().callbackDomain()}
     * and checks them, then calls {@link UndertowServletWebServerFactory#addBuilderCustomizers} with one
     * builder customizer. When Spring Boot creates the web server, the builder customizer checks the
     * address against {@code server.port} and the host (D-487), calls
     * {@code builder.addHttpListener(port, host)} once and logs the address at INFO. The primary listener
     * on {@code server.port} is left to Spring Boot.
     *
     * @param factory the Undertow servlet web server factory of the application
     * @throws IllegalStateException if no {@code sfdc.oauth.*} key is bound, the callback port lies outside
     *                               {@code 1..65535}, or the callback domain is {@code null} or blank
     */
    @Override
    public void customize(UndertowServletWebServerFactory factory) {
        SalesforceOAuthProperties.OAuth oauth = properties.oauth();
        if (oauth == null) {
            throw new IllegalStateException(
                    "sfdc.oauth is not configured: " + PORT_KEY + " and " + DOMAIN_KEY + " are required");
        }
        int port = oauth.callbackPort();
        String host = oauth.callbackDomain();
        // Port 0 and a blank domain are rejected; the callback listener binds only a concrete address (D-487).
        requireConcretePort(port);
        requireDomain(host);
        factory.addBuilderCustomizers(builder -> {
            // Address checked before the listener is added; a failed check stops web server creation (D-487).
            requireDistinctFromPrimary(port, factory.getPort());
            requireBindable(port, host);
            builder.addHttpListener(port, host);
            LOG.info("Added HTTP listener {} on {}:{}", LISTENER_KEY, host, port);
        });
    }

    /**
     * Rejects a callback port outside {@code 1..65535}.
     *
     * @param port the value of {@code sfdc.oauth.callback-port}
     * @throws IllegalStateException if {@code port} is below 1 or above 65535
     */
    private static void requireConcretePort(int port) {
        if (port < 1 || port > MAX_PORT) {
            throw new IllegalStateException(
                    PORT_KEY + " must be between 1 and " + MAX_PORT + ", was " + port);
        }
    }

    /**
     * Rejects a {@code null} or blank callback domain.
     *
     * @param host the value of {@code sfdc.oauth.callback-domain}
     * @throws IllegalStateException if {@code host} is {@code null} or blank
     */
    private static void requireDomain(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(DOMAIN_KEY + " must not be blank");
        }
    }

    /**
     * Rejects a callback port equal to the primary port.
     *
     * @param port        the callback port
     * @param primaryPort the port of the primary listener, {@code server.port}; {@code 0} selects a free port
     * @throws IllegalStateException if {@code primaryPort} is positive and equals {@code port}
     */
    private static void requireDistinctFromPrimary(int port, int primaryPort) {
        if (primaryPort > 0 && primaryPort == port) {
            throw new IllegalStateException(PORT_KEY + " " + port + " equals server.port " + primaryPort);
        }
    }

    /**
     * Resolves {@code host}, then binds and releases a server socket on {@code host} and {@code port} with
     * {@code SO_REUSEADDR} enabled, as Undertow binds its listeners.
     *
     * @param port the callback port
     * @param host the callback domain
     * @throws PortInUseException    if the port is already in use on that address
     * @throws IllegalStateException if the host does not resolve or the bind fails for another reason; the
     *                               message names {@code sfdc.oauth.callback}, the host and the port
     */
    private static void requireBindable(int port, String host) {
        InetSocketAddress address;
        try {
            address = new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (UnknownHostException | IllegalArgumentException ex) {
            throw cannotListen(port, host, ex);
        }
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(address);
        } catch (IOException ex) {
            PortInUseException.throwIfPortBindingException(ex, () -> port);
            throw cannotListen(port, host, ex);
        }
    }

    /**
     * Builds the exception for a callback address that cannot be bound.
     *
     * @param port  the callback port
     * @param host  the callback domain
     * @param cause the resolution or bind failure
     * @return an {@link IllegalStateException} naming {@code sfdc.oauth.callback}, the host, the port and
     *         the cause's message
     */
    private static IllegalStateException cannotListen(int port, String host, Exception cause) {
        return new IllegalStateException(
                LISTENER_KEY + " cannot listen on " + host + ":" + port + ": " + cause.getMessage(), cause);
    }
}
