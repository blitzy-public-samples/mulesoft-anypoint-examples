package com.mulesoft.examples.filtering_a_message.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.boot.web.embedded.undertow.UndertowServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds the embedded Undertow listener to the host configured in
 * {@code listener.http-listener-configuration.host}.
 *
 * <p>The address is set on the {@link UndertowServletWebServerFactory}; {@code server.address} is not
 * set. The listener port is {@code server.port}, which this class neither reads nor sets.
 */
@Configuration
public class HttpListenerConfig {

    /**
     * Binds the embedded Undertow listener to the host configured in
     * {@code listener.http-listener-configuration.host}.
     *
     * <p>When Spring Boot creates the web server, the returned customizer resolves
     * {@link HttpListenerProperties#host()} with {@link InetAddress#getByName(String)} and sets the
     * result as the factory's address. The customizer has no order, and Spring Boot applies it after
     * its own {@code server.*} customizers. Examples:
     *
     * <pre>{@code
     * listener.http-listener-configuration.host=0.0.0.0     -> listens on every interface (default)
     * listener.http-listener-configuration.host=127.0.0.1   -> listens on the loopback interface only
     * }</pre>
     *
     * <p>A {@code null} or empty host resolves to the loopback address, as
     * {@link InetAddress#getByName(String)} defines. A host that does not resolve stops the start of
     * the web server with an {@link IllegalStateException} whose cause is the
     * {@link UnknownHostException}.
     *
     * @param properties the bound {@code listener.http-listener-configuration} properties
     * @return the customizer that sets the listener address on the Undertow factory
     */
    @Bean
    public WebServerFactoryCustomizer<UndertowServletWebServerFactory> listenerAddressCustomizer(
            HttpListenerProperties properties) {
        return factory -> {
            try {
                factory.setAddress(InetAddress.getByName(properties.host()));
            } catch (UnknownHostException e) {
                throw new IllegalStateException(e);
            }
        };
    }
}
