package com.mulesoft.examples.soap_webservice_security.config;

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
import org.springframework.stereotype.Component;

/**
 * Opens the client HTTP listener {@code HTTP_Listener_Configuration} on
 * {@code listener.http-listener-configuration.port} and {@code listener.http-listener-configuration.host}
 * (defaults {@code 63080} and {@code 0.0.0.0}) beside the primary listener of the embedded Undertow server (D-011).
 *
 * <p>The primary listener is Spring Boot's own: {@code server.port} and {@code server.address}, bound in
 * {@code application.yml} to {@code listener.http-listener-configuration1.port} and {@code .host} (defaults
 * {@code 63081} and {@code 0.0.0.0}), serves the six Greeter SOAP services under {@code /services}. This class leaves
 * that listener unchanged and adds exactly one plain HTTP listener, which serves {@code /client}. Both listeners
 * share one servlet deployment; {@code PortPathGuardFilter} keeps each path on the port that owns it (D-011).
 *
 * <p>When Spring Boot creates the web server, before any listener opens, the client address is checked (D-386):
 * <ul>
 *   <li>a port equal to a non-zero {@code server.port} raises an {@link IllegalStateException};</li>
 *   <li>a port in use on the host raises Spring Boot's {@link PortInUseException}, reported as
 *       {@code Web server failed to start. Port <port> was already in use.};</li>
 *   <li>an unresolvable host, a port outside {@code 0..65535} or any other bind failure raises an
 *       {@link IllegalStateException} whose message names the key, the host and the port.</li>
 * </ul>
 * In each case web server creation fails and the application stops. Otherwise the listener is added to the Undertow
 * builder and opens when the web server starts, after the primary listener. The port is bound as configured,
 * {@code 0} included; tests set a concrete free port.
 *
 * <pre>{@code
 * // application.yml
 * listener:
 *   http-listener-configuration:
 *     port: 63080
 *     host: 0.0.0.0
 * // After startup Undertow listens on 63081 (server.port) and 63080 (this class).
 * }</pre>
 */
@Component
public class AdditionalPortsConfig implements WebServerFactoryCustomizer<UndertowServletWebServerFactory> {

    private static final Logger LOG = LoggerFactory.getLogger(AdditionalPortsConfig.class);

    /** Property prefix of the client listener, used in log lines and exception messages. */
    private static final String KEY = "listener.http-listener-configuration";

    /** Port of the client listener, {@code listener.http-listener-configuration.port}. */
    private final int port;

    /** Bind address of the client listener, {@code listener.http-listener-configuration.host}. */
    private final String host;

    /**
     * Creates the customizer for the client listener.
     *
     * @param port the value of {@code listener.http-listener-configuration.port}
     * @param host the value of {@code listener.http-listener-configuration.host}
     */
    public AdditionalPortsConfig(@Value("${listener.http-listener-configuration.port}") int port,
                                 @Value("${listener.http-listener-configuration.host}") String host) {
        this.port = port;
        this.host = host;
    }

    /**
     * Registers an Undertow builder customizer that adds one HTTP listener on the configured port and host (D-011).
     * The customizer runs when Spring Boot creates the web server: it checks the address as the class description
     * states, then calls {@code builder.addHttpListener(port, host)}. Undertow opens the listener when the web server
     * starts, after the primary listener on {@code server.port}.
     *
     * @param factory the Undertow servlet web server factory of the application
     */
    @Override
    public void customize(UndertowServletWebServerFactory factory) {
        factory.addBuilderCustomizers(builder -> {
            // Address checked before the listener is added; a failed check stops web server creation (D-386).
            requireDistinctFromPrimary(factory.getPort());
            requireBindable();
            builder.addHttpListener(port, host);
            LOG.info("Added HTTP listener {} on {}:{}", KEY, host, port);
        });
    }

    /**
     * Rejects a client port equal to the primary port.
     *
     * @param primaryPort the port of the primary listener, {@code server.port}; {@code 0} selects a free port
     * @throws IllegalStateException if {@code primaryPort} is positive and equals the client port
     */
    private void requireDistinctFromPrimary(int primaryPort) {
        if (primaryPort > 0 && primaryPort == port) {
            throw new IllegalStateException(
                    KEY + ".port " + port + " equals server.port " + primaryPort);
        }
    }

    /**
     * Binds and releases a probe socket on the client address, with {@code SO_REUSEADDR} as Undertow binds it.
     *
     * @throws PortInUseException if the port is already in use on the host
     * @throws IllegalStateException if the host does not resolve, the port is out of range or the bind fails
     */
    private void requireBindable() {
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException ex) {
            throw cannotListen(ex);
        }
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(new InetSocketAddress(address, port));
        } catch (IOException ex) {
            PortInUseException.throwIfPortBindingException(ex, () -> port);
            throw cannotListen(ex);
        } catch (IllegalArgumentException ex) {
            throw cannotListen(ex);
        }
    }

    /**
     * Builds the exception for a client address that cannot be bound.
     *
     * @param cause the resolution or bind failure
     * @return an {@link IllegalStateException} naming the key, the host, the port and the cause message
     */
    private IllegalStateException cannotListen(Exception cause) {
        return new IllegalStateException(
                KEY + " cannot listen on " + host + ":" + port + ": " + cause.getMessage(), cause);
    }
}
