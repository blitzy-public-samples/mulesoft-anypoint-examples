package com.mulesoft.examples.mule_component_bindings.config;

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Opens the second HTTP listener of the application on the host and port of
 * {@code listener.http-listener-configuration2}, next to the primary listener on {@code server.port}
 * (D-011).
 *
 * <p>The application has two listeners on its embedded Undertow server (D-010):
 * <ul>
 *   <li>the primary listener, which Spring Boot opens on {@code server.port} and
 *       {@code server.address}, bound in {@code application.yml} to
 *       {@code listener.http-listener-configuration.port} and {@code .host} (default
 *       {@code localhost:8081}), the address of {@code HTTP_Listener_Configuration} that serves
 *       {@code /};</li>
 *   <li>the second listener, which this class adds on {@code listener.http-listener-configuration2.port}
 *       and {@code .host} (default {@code localhost:8180}), the address of
 *       {@code HTTP_Listener_Configuration2} that serves {@code /api/*}, for example
 *       {@code GET http://localhost:8180/api/stockStats?stock=AAPL&date=2012-11-28}.</li>
 * </ul>
 *
 * <p>The second listener is plain HTTP and opens on every start. Both listeners serve the same Spring
 * MVC application; this class does not restrict paths and does not change the primary listener.
 * {@code PortPathGuardFilter} keeps each path on the listener that owns it (D-011).
 *
 * <p>The address is read from configuration only. A start with
 * {@code --listener.http-listener-configuration2.port=18180} opens the second listener on
 * {@code localhost:18180}; a start with {@code --listener.http-listener-configuration2.host=0.0.0.0}
 * opens it on every local interface.
 *
 * <p>The address is checked when the web server is created, before any listener opens (D-317). The
 * start fails, and the application exits without opening a port, when:
 * <ul>
 *   <li>the port is in use: {@link PortInUseException}, reported as
 *       {@code Web server failed to start. Port 8180 was already in use.};</li>
 *   <li>the host does not resolve, the port lies outside {@code 0..65535}, or the host is not an
 *       address of this machine: {@link IllegalStateException} naming the key, the host and the
 *       port.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AdditionalPortsConfig {

    /** Logger of the added listener's address. */
    private static final Logger LOG = LoggerFactory.getLogger(AdditionalPortsConfig.class);

    /** Key prefix of the second listener's {@code port} and {@code host} properties. */
    private static final String LISTENER_KEY = "listener.http-listener-configuration2";

    /**
     * Registers an Undertow builder customizer that adds the second HTTP listener on {@code host} and
     * {@code port} (D-011).
     *
     * <p>The returned customizer calls
     * {@link UndertowServletWebServerFactory#addBuilderCustomizers} with one builder customizer. When
     * the web server is created, the builder customizer checks the address (D-317), calls
     * {@code Undertow.Builder.addHttpListener(port, host)} once and logs the address at INFO. Spring
     * Boot adds the primary listener to the same builder; the builder customizer leaves it unchanged.
     *
     * @param port the port of the second listener, {@code listener.http-listener-configuration2.port}
     *             (default 8180)
     * @param host the host of the second listener, {@code listener.http-listener-configuration2.host}
     *             (default {@code localhost})
     * @return the customizer of the embedded Undertow servlet web server factory
     */
    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> secondaryListenerCustomizer(
            @Value("${listener.http-listener-configuration2.port}") int port,
            @Value("${listener.http-listener-configuration2.host}") String host) {
        return factory -> factory.addBuilderCustomizers(builder -> {
            requireBindable(port, host);
            builder.addHttpListener(port, host);
            LOG.info("Added HTTP listener {} on {}:{}", LISTENER_KEY, host, port);
        });
    }

    /**
     * Resolves {@code host}, then binds and releases a server socket on {@code host} and {@code port}
     * with {@code SO_REUSEADDR} enabled (D-317).
     *
     * @param port the port of the second listener
     * @param host the host of the second listener
     * @throws PortInUseException    when the port is in use on that address
     * @throws IllegalStateException when the host does not resolve, the port lies outside
     *                               {@code 0..65535}, or the bind fails for another reason; the message
     *                               names {@code listener.http-listener-configuration2}, the host and the
     *                               port
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
     * Builds the exception for an address the second listener cannot listen on.
     *
     * @param port  the configured port
     * @param host  the configured host
     * @param cause the failure of the resolution or of the bind
     * @return an exception whose message names the key, the host, the port and the cause's message
     */
    private static IllegalStateException cannotListen(int port, String host, Exception cause) {
        return new IllegalStateException(LISTENER_KEY + " cannot listen on " + host + ":" + port + ": "
                + cause.getMessage(), cause);
    }
}
