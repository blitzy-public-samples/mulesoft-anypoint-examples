package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.PortInUseException;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mulesoft.examples.foreach_processing_and_choice_routing.config.ListenerProperties.Listener;

/**
 * Adds one Undertow HTTP listener per listener configuration 2 to 7 of the loan broker (D-011).
 *
 * <p>Sources: {@code foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:7-12}, the
 * {@code http:listener-config} elements {@code HTTP_Listener_Configuration_2} (flow
 * {@code TheCreditAgencyService}) and {@code HTTP_Listener_Configuration_3} …
 * {@code HTTP_Listener_Configuration_7} (flows {@code Bank1Flow} … {@code Bank5Flow}).
 *
 * <p>Listeners opened on the embedded Undertow server:
 * <ul>
 *   <li>listener 1, {@code HTTP_Listener_Configuration_1}: Spring Boot's own listener on
 *       {@code server.port} and {@code server.address}, which {@code application.yml} binds to
 *       {@code listener.http-listener-configuration-1.port} and {@code .host}. This class does not add
 *       or change it;</li>
 *   <li>listeners 2 … 7: one plain HTTP listener each, added by this class on the {@code port} and
 *       {@code host} of {@code listener.http-listener-configuration-2} … {@code -7}, in that order, as
 *       {@link ListenerProperties#additional()} returns them.</li>
 * </ul>
 *
 * <p>All seven listeners serve one servlet deployment. This class opens ports only and maps no path:
 * {@code PortPathGuardFilter} keeps each path on the listener that owns it, and {@code WsConfig}
 * dispatches the SOAP requests of listeners 2 … 7. {@code HttpServletRequest.getLocalPort()} reports
 * the listener that accepted a request, which {@link ListenerProperties#additionalOnPort(int)} resolves.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>when Spring Boot customizes the {@link UndertowServletWebServerFactory}, the customizer reads
 *       {@link ListenerProperties#additional()} and registers one Undertow builder customizer per entry;
 *       a missing entry among 2 … 7 raises the {@link IllegalStateException} of
 *       {@link ListenerProperties#listener(int)} and stops the start;</li>
 *   <li>when Spring Boot creates the web server, each builder customizer, in listener order, checks its
 *       address (D-521), calls {@code Undertow.Builder.addHttpListener(port, host)} once and logs
 *       {@code Added HTTP listener listener.http-listener-configuration-<n> on <host>:<port> for
 *       <base-path>} at INFO;</li>
 *   <li>when the web server starts, Undertow binds listener 1, then listeners 2 … 7.</li>
 * </ul>
 *
 * <p>Address check (D-521). Web server creation fails, no listener opens and the application exits when
 * the port of a listener among 2 … 7:
 * <ul>
 *   <li>equals a positive {@code server.port}: {@link IllegalStateException}
 *       {@code listener.http-listener-configuration-<n>.port <port> equals server.port <port>};</li>
 *   <li>equals the port of an earlier listener among 2 … 7: {@link IllegalStateException}
 *       {@code listener.http-listener-configuration-<n>.port <port> equals
 *       listener.http-listener-configuration-<m>.port};</li>
 *   <li>is in use on the host: Spring Boot's {@link PortInUseException}, reported as
 *       {@code Web server failed to start. Port <port> was already in use.};</li>
 *   <li>cannot be bound on its host for any other reason, an unresolvable host or an address that is not
 *       one of this machine's included: {@link IllegalStateException}
 *       {@code listener.http-listener-configuration-<n> cannot listen on <host>:<port>: <cause>}.</li>
 * </ul>
 * A {@code server.port} of {@code 0} selects a free port when the web server starts and is not compared.
 *
 * <p>Usage, opening listener 2 on another port:
 * <pre>{@code
 * java -jar foreach-processing-and-choice-routing-java-1.0.0.jar \
 *     --listener.http-listener-configuration-2.port=<port>
 * // GET http://localhost:<port>/mule/TheCreditAgencyService?wsdl is answered on the added listener.
 * }</pre>
 */
@Configuration(proxyBeanMethods = false)
public class AdditionalPortsConfig {

    /** Logger of the added listeners' addresses. */
    private static final Logger LOG = LoggerFactory.getLogger(AdditionalPortsConfig.class);

    /** Property path of a listener entry; the listener number follows it. */
    private static final String KEY_PREFIX = "listener.http-listener-configuration-";

    /** Listener number of the first entry of {@link ListenerProperties#additional()}. */
    private static final int FIRST_ADDITIONAL = 2;

    /**
     * Returns the customizer that adds listeners 2 … 7 to the embedded Undertow server (D-011).
     *
     * <p>The customizer calls {@link UndertowServletWebServerFactory#addBuilderCustomizers} once per
     * entry of {@link ListenerProperties#additional()}, in list order. Each builder customizer checks the
     * entry's address as the class description states (D-521), then calls
     * {@code Undertow.Builder.addHttpListener(port, host)} with that entry's {@code port} and
     * {@code host} and logs the address. Listener 1 is not added.
     *
     * @param listeners the bound listener addresses of {@code listener.http-listener-configuration-1}
     *                  … {@code -7}
     * @return the customizer of the embedded Undertow servlet web server factory
     * @throws NullPointerException when {@code listeners} is {@code null}
     */
    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> additionalPortsCustomizer(
            ListenerProperties listeners) {
        Objects.requireNonNull(listeners, "listeners");
        return factory -> {
            List<Listener> additional = listeners.additional();
            for (int index = 0; index < additional.size(); index++) {
                addListener(factory, additional, index);
            }
        };
    }

    /**
     * Registers the builder customizer of entry {@code index} of {@code additional}.
     *
     * @param factory    the Undertow servlet web server factory of the application
     * @param additional listeners 2 … 7, in order
     * @param index      position of the entry in {@code additional}; listener number {@code index + 2}
     */
    private static void addListener(UndertowServletWebServerFactory factory, List<Listener> additional,
                                    int index) {
        Listener entry = additional.get(index);
        String key = key(index);
        factory.addBuilderCustomizers(builder -> {
            // Address checked before the listener is added; a failed check stops web server creation (D-521).
            requireDistinctPort(factory.getPort(), additional, index);
            requireBindable(key, entry);
            builder.addHttpListener(entry.port(), entry.host());
            LOG.info("Added HTTP listener {} on {}:{} for {}",
                    key, entry.host(), entry.port(), entry.basePath());
        });
    }

    /**
     * Rejects a port of entry {@code index} equal to a positive {@code server.port} or to the port of an
     * earlier entry of {@code additional}.
     *
     * @param primaryPort the port of listener 1, {@code server.port}; {@code 0} or less is not compared
     * @param additional  listeners 2 … 7, in order
     * @param index       position of the checked entry in {@code additional}
     * @throws IllegalStateException with the message
     *         {@code listener.http-listener-configuration-<n>.port <port> equals server.port <port>} or
     *         {@code listener.http-listener-configuration-<n>.port <port> equals
     *         listener.http-listener-configuration-<m>.port}
     */
    private static void requireDistinctPort(int primaryPort, List<Listener> additional, int index) {
        int port = additional.get(index).port();
        if (primaryPort > 0 && primaryPort == port) {
            throw new IllegalStateException(
                    key(index) + ".port " + port + " equals server.port " + primaryPort);
        }
        for (int earlier = 0; earlier < index; earlier++) {
            if (additional.get(earlier).port() == port) {
                throw new IllegalStateException(
                        key(index) + ".port " + port + " equals " + key(earlier) + ".port");
            }
        }
    }

    /**
     * Resolves the entry's host, then binds and releases a probe server socket with
     * {@code SO_REUSEADDR} on that host and the entry's port.
     *
     * @param key   property path of the entry, {@code listener.http-listener-configuration-<n>}
     * @param entry the checked listener address
     * @throws PortInUseException    when the port is in use on that address
     * @throws IllegalStateException when the host does not resolve or the bind fails for another reason;
     *                               the message names the key, the host and the port
     */
    private static void requireBindable(String key, Listener entry) {
        InetSocketAddress address;
        try {
            address = new InetSocketAddress(InetAddress.getByName(entry.host()), entry.port());
        } catch (UnknownHostException ex) {
            throw cannotListen(key, entry, ex);
        }
        try (ServerSocket probe = new ServerSocket()) {
            probe.setReuseAddress(true);
            probe.bind(address);
        } catch (IOException ex) {
            PortInUseException.throwIfPortBindingException(ex, entry::port);
            throw cannotListen(key, entry, ex);
        }
    }

    /**
     * Builds the exception for a listener address that cannot be bound.
     *
     * @param key   property path of the entry
     * @param entry the listener address
     * @param cause the resolution or bind failure
     * @return an exception whose message names the key, the host, the port and the cause's message
     */
    private static IllegalStateException cannotListen(String key, Listener entry, Exception cause) {
        return new IllegalStateException(key + " cannot listen on " + entry.host() + ":" + entry.port()
                + ": " + cause.getMessage(), cause);
    }

    /**
     * Returns the property path of entry {@code index} of {@link ListenerProperties#additional()}.
     *
     * @param index position in the list
     * @return {@code listener.http-listener-configuration-<index + 2>}
     */
    private static String key(int index) {
        return KEY_PREFIX + (FIRST_ADDITIONAL + index);
    }
}
