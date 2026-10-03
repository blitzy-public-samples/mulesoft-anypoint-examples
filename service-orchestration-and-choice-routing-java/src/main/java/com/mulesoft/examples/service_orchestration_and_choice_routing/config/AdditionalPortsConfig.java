package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

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
import org.springframework.core.env.Environment;

/**
 * Opens the four extra HTTP listeners of the embedded Undertow server beside the primary listener
 * (D-011).
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:24-27}, the four
 *       {@code http:listener-config} elements: {@code HTTP_Listener_Configuration} ({@code 0.0.0.0:8091},
 *       {@code GET populate}), {@code HTTP_Listener_Configuration1} ({@code 0.0.0.0:9090},
 *       {@code POST samsung/orders}), {@code HTTP_Listener_Configuration2} ({@code 0.0.0.0:9999},
 *       {@code POST api}) and {@code HTTP_Listener_Configuration3} ({@code 0.0.0.0:1080},
 *       {@code POST orders});</li>
 *   <li>{@code service-orchestration-and-choice-routing/src/main/app/mule-config.xml:3}, the
 *       {@code ajax:connector} at {@code http://0.0.0.0:8090/orders}, which is the primary listener
 *       ({@code server.port} = {@code ${ajax-server.port}}, {@code server.address} =
 *       {@code ${ajax-server.host}}).</li>
 * </ul>
 *
 * <p>Spring Boot opens the primary listener itself when it creates the {@code Undertow.Builder}.
 * {@link #additionalListenersCustomizer(ListenerPorts, Environment)} adds one plain-HTTP listener per
 * original {@code http:listener-config}, in the order {@code listener.http-listener-configuration3}
 * (1080), {@code listener.http-listener-configuration1} (9090), {@code listener.http-listener-configuration2}
 * (9999) and {@code listener.http-listener-configuration} (8091), each on the port and host of its own
 * {@code application.yml} keys. Every listener serves the same Spring MVC application;
 * {@code PortPathGuardFilter} restricts each path to the port that owns it (D-011).
 *
 * <p>Startup fails with an {@link IllegalStateException} that names the offending key when a listener
 * port lies outside {@code 1..65535}, when two listener ports are equal, when a listener port equals
 * {@code server.port}, or when a listener host is blank (D-011).
 *
 * <p>Each listener's address is checked when the web server is created, before any listener opens
 * (D-349). Web server creation fails, and startup stops with no listener open, when:
 * <ul>
 *   <li>a listener port is in use: {@link PortInUseException}, reported as
 *       {@code Web server failed to start. Port 9090 was already in use.};</li>
 *   <li>a listener host does not resolve or is not an address of this machine, or the bind fails for
 *       another reason: {@link IllegalStateException} naming the listener's {@code .port} key, the
 *       host and the port.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class AdditionalPortsConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AdditionalPortsConfig.class);

    /** Key of the primary listener port, the AJAX connector's port. */
    private static final String PRIMARY_PORT_KEY = "server.port";

    /** Port key of {@code HTTP_Listener_Configuration3}: SOAP {@code IProcessOrder} on {@code POST /orders}. */
    private static final String ORDERS_SOAP_PORT_KEY = "listener.http-listener-configuration3.port";

    /** Host key of {@code HTTP_Listener_Configuration3}. */
    private static final String ORDERS_SOAP_HOST_KEY = "listener.http-listener-configuration3.host";

    /** Port key of {@code HTTP_Listener_Configuration1}: SOAP {@code SamsungService} on {@code POST /samsung/orders}. */
    private static final String SAMSUNG_SOAP_PORT_KEY = "listener.http-listener-configuration1.port";

    /** Host key of {@code HTTP_Listener_Configuration1}. */
    private static final String SAMSUNG_SOAP_HOST_KEY = "listener.http-listener-configuration1.host";

    /** Port key of {@code HTTP_Listener_Configuration2}: price service on {@code POST /api}. */
    private static final String API_PORT_KEY = "listener.http-listener-configuration2.port";

    /** Host key of {@code HTTP_Listener_Configuration2}. */
    private static final String API_HOST_KEY = "listener.http-listener-configuration2.host";

    /** Port key of {@code HTTP_Listener_Configuration}: database initialisation on {@code GET /populate}. */
    private static final String POPULATE_PORT_KEY = "listener.http-listener-configuration.port";

    /** Host key of {@code HTTP_Listener_Configuration}. */
    private static final String POPULATE_HOST_KEY = "listener.http-listener-configuration.host";

    /** Lowest accepted listener port. */
    private static final int MIN_PORT = 1;

    /** Highest accepted listener port. */
    private static final int MAX_PORT = 65535;

    /**
     * Ports of the four extra listeners, bound from their {@code listener.<config>.port} keys (D-011).
     *
     * <p>The record holds no primary (AJAX) port. {@link #isAjaxPort(int)} reports every local port that
     * is none of the four as the AJAX port, a random {@code server.port} of {@code 0} included (D-011).
     *
     * <p>The compact constructor accepts only ports in {@code 1..65535} that are pairwise distinct and
     * otherwise throws an {@link IllegalStateException} naming the offending key or keys (D-011).
     *
     * <pre>{@code
     * ListenerPorts ports = new ListenerPorts(1080, 9090, 9999, 8091);
     * ports.isAjaxPort(8090);  // true
     * ports.isAjaxPort(1080);  // false
     * new ListenerPorts(0, 9090, 9999, 8091);
     * // IllegalStateException: listener.http-listener-configuration3.port must be between 1 and 65535, was 0
     * new ListenerPorts(1080, 1080, 9999, 8091);
     * // IllegalStateException: listener.http-listener-configuration3.port and
     * //     listener.http-listener-configuration1.port must differ, both are 1080
     * }</pre>
     *
     * @param ordersSoapPort  port of {@code listener.http-listener-configuration3} (default 1080), SOAP
     *                        {@code IProcessOrder} on {@code POST /orders}
     * @param samsungSoapPort port of {@code listener.http-listener-configuration1} (default 9090), SOAP
     *                        {@code SamsungService} on {@code POST /samsung/orders}
     * @param apiPort         port of {@code listener.http-listener-configuration2} (default 9999),
     *                        {@code POST /api}
     * @param populatePort    port of {@code listener.http-listener-configuration} (default 8091),
     *                        {@code GET /populate}
     */
    public record ListenerPorts(int ordersSoapPort, int samsungSoapPort, int apiPort, int populatePort) {

        /**
         * Validates the four ports: each lies in {@code 1..65535} and no two are equal.
         *
         * @throws IllegalStateException naming the key of a port outside {@code 1..65535}, or the two
         *                               keys of two equal ports
         */
        public ListenerPorts {
            String[] keys = {ORDERS_SOAP_PORT_KEY, SAMSUNG_SOAP_PORT_KEY, API_PORT_KEY, POPULATE_PORT_KEY};
            int[] ports = {ordersSoapPort, samsungSoapPort, apiPort, populatePort};
            for (int i = 0; i < ports.length; i++) {
                if (ports[i] < MIN_PORT || ports[i] > MAX_PORT) {
                    throw new IllegalStateException(keys[i] + " must be between " + MIN_PORT + " and "
                            + MAX_PORT + ", was " + ports[i]);
                }
            }
            for (int i = 0; i < ports.length; i++) {
                for (int j = i + 1; j < ports.length; j++) {
                    if (ports[i] == ports[j]) {
                        throw new IllegalStateException(keys[i] + " and " + keys[j]
                                + " must differ, both are " + ports[i]);
                    }
                }
            }
        }

        /**
         * Tells whether a request's local port is the primary (AJAX) listener's port.
         *
         * @param localPort the local port of a request, for example
         *                  {@code HttpServletRequest.getLocalPort()}
         * @return {@code true} when {@code localPort} equals none of the four listener ports
         */
        public boolean isAjaxPort(int localPort) {
            return localPort != ordersSoapPort
                    && localPort != samsungSoapPort
                    && localPort != apiPort
                    && localPort != populatePort;
        }
    }

    /**
     * Binds the four listener ports from {@code application.yml} (D-011).
     *
     * @param ordersSoapPort  {@code listener.http-listener-configuration3.port}
     * @param samsungSoapPort {@code listener.http-listener-configuration1.port}
     * @param apiPort         {@code listener.http-listener-configuration2.port}
     * @param populatePort    {@code listener.http-listener-configuration.port}
     * @return the validated ports
     * @throws IllegalStateException when the ports fail the {@link ListenerPorts} validation
     */
    @Bean
    public ListenerPorts listenerPorts(
            @Value("${" + ORDERS_SOAP_PORT_KEY + "}") int ordersSoapPort,
            @Value("${" + SAMSUNG_SOAP_PORT_KEY + "}") int samsungSoapPort,
            @Value("${" + API_PORT_KEY + "}") int apiPort,
            @Value("${" + POPULATE_PORT_KEY + "}") int populatePort) {
        return new ListenerPorts(ordersSoapPort, samsungSoapPort, apiPort, populatePort);
    }

    /**
     * Adds the four extra plain-HTTP listeners to the Undertow builder (D-011).
     *
     * <p>The customizer registers one Undertow builder customizer per listener, in the order
     * {@code listener.http-listener-configuration3}, {@code listener.http-listener-configuration1},
     * {@code listener.http-listener-configuration2} and {@code listener.http-listener-configuration}.
     * Each checks the listener's address (D-349), then calls
     * {@code Undertow.Builder.addHttpListener(port, host)} with the listener's port from
     * {@code listenerPorts} and its host from the {@code listener.<config>.host} key (D-011). The builder
     * customizers run after Spring Boot has added the primary listener on {@code server.port} and
     * {@code server.address}; the primary listener is not added again.
     *
     * @param listenerPorts the validated ports of the four listeners
     * @param environment   the source of the four {@code listener.<config>.host} keys
     * @return the customizer of the Undertow servlet web server factory
     * @throws IllegalStateException when a {@code listener.<config>.host} key is missing or blank; web
     *                               server creation fails, before any listener opens, with an
     *                               {@link IllegalStateException} naming both keys when a listener port
     *                               equals {@code server.port}, with a {@link PortInUseException} when a
     *                               listener port is in use, and with an {@link IllegalStateException}
     *                               naming the listener's {@code .port} key, host and port when its host
     *                               does not resolve or another bind failure occurs (D-349)
     */
    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> additionalListenersCustomizer(
            ListenerPorts listenerPorts, Environment environment) {
        String ordersSoapHost = requiredHost(environment, ORDERS_SOAP_HOST_KEY);
        String samsungSoapHost = requiredHost(environment, SAMSUNG_SOAP_HOST_KEY);
        String apiHost = requiredHost(environment, API_HOST_KEY);
        String populateHost = requiredHost(environment, POPULATE_HOST_KEY);
        return factory -> {
            addListener(factory, ORDERS_SOAP_PORT_KEY, listenerPorts.ordersSoapPort(), ordersSoapHost);
            addListener(factory, SAMSUNG_SOAP_PORT_KEY, listenerPorts.samsungSoapPort(), samsungSoapHost);
            addListener(factory, API_PORT_KEY, listenerPorts.apiPort(), apiHost);
            addListener(factory, POPULATE_PORT_KEY, listenerPorts.populatePort(), populateHost);
        };
    }

    /**
     * Reads a listener host key.
     *
     * @param environment the property source
     * @param hostKey     the {@code listener.<config>.host} key
     * @return the key's value
     * @throws IllegalStateException when the key is missing or its value is blank
     */
    private static String requiredHost(Environment environment, String hostKey) {
        String host = environment.getRequiredProperty(hostKey);
        if (host.isBlank()) {
            throw new IllegalStateException(hostKey + " must not be blank");
        }
        return host;
    }

    /**
     * Registers the builder customizer that adds one plain-HTTP listener.
     *
     * <p>When the builder customizer runs, the factory's port is the primary listener port that Spring
     * Boot has just bound to the builder. A listener port equal to it raises an
     * {@link IllegalStateException} naming {@code server.port} and {@code portKey}, and no listener is
     * added. Otherwise the builder customizer checks the address with a probe bind (D-349), then calls
     * {@code Undertow.Builder.addHttpListener(port, host)} (D-011) and logs the address at INFO. A failed
     * check raises a {@link PortInUseException} or an {@link IllegalStateException} naming
     * {@code portKey}, the host and the port, and no listener is added.
     *
     * @param factory the Undertow servlet web server factory
     * @param portKey the {@code listener.<config>.port} key of the listener
     * @param port    the listener port
     * @param host    the listener host
     */
    private static void addListener(UndertowServletWebServerFactory factory, String portKey, int port, String host) {
        factory.addBuilderCustomizers(builder -> {
            int primaryPort = factory.getPort();
            if (primaryPort == port) {
                throw new IllegalStateException(PRIMARY_PORT_KEY + " and " + portKey
                        + " must differ, both are " + port);
            }
            requireBindable(portKey, port, host);
            builder.addHttpListener(port, host);
            LOG.info("Added HTTP listener {} on {}:{}", portKey, host, port);
        });
    }

    /**
     * Resolves {@code host}, then binds and releases a server socket on {@code host} and {@code port}
     * with {@code SO_REUSEADDR} enabled (D-349).
     *
     * @param portKey the {@code listener.<config>.port} key of the listener
     * @param port    the listener port
     * @param host    the listener host
     * @throws PortInUseException    when the port is in use on that address
     * @throws IllegalStateException when the host does not resolve, the port lies outside
     *                               {@code 0..65535}, or the bind fails for another reason; the message
     *                               names {@code portKey}, the host and the port
     */
    private static void requireBindable(String portKey, int port, String host) {
        InetSocketAddress address;
        try {
            address = new InetSocketAddress(InetAddress.getByName(host), port);
        } catch (UnknownHostException | IllegalArgumentException ex) {
            throw cannotListen(portKey, port, host, ex);
        }
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(address);
        } catch (IOException ex) {
            PortInUseException.throwIfPortBindingException(ex, () -> port);
            throw cannotListen(portKey, port, host, ex);
        }
    }

    /**
     * Builds the exception for an address a listener cannot listen on.
     *
     * @param portKey the {@code listener.<config>.port} key of the listener
     * @param port    the configured port
     * @param host    the configured host
     * @param cause   the failure of the resolution or of the bind
     * @return an exception whose message names {@code portKey}, the host, the port and the cause's
     *         message
     */
    private static IllegalStateException cannotListen(String portKey, int port, String host, Exception cause) {
        return new IllegalStateException(portKey + " cannot listen on " + host + ":" + port + ": "
                + cause.getMessage(), cause);
    }
}
