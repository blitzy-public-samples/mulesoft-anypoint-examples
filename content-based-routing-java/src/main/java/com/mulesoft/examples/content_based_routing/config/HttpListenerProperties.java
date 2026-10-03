package com.mulesoft.examples.content_based_routing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the host and port of the listener configuration {@code HTTP_Listener_Configuration}
 * ({@code content-based-routing.xml}, line 3) from the keys under
 * {@code listener.http-listener-configuration}.
 *
 * <p>{@code listener.http-listener-configuration.host} binds to {@link #host()} and
 * {@code listener.http-listener-configuration.port} binds to {@link #port()}. In
 * {@code application.yml} the host defaults to {@code 0.0.0.0} and the port to the value of
 * {@code http.port}, {@code 8081} by default (D-065). The record is registered by
 * {@code @ConfigurationPropertiesScan} on {@code ContentBasedRoutingApplication}.
 *
 * <pre>{@code
 * HttpListenerProperties listener = context.getBean(HttpListenerProperties.class);
 * listener.host(); // "0.0.0.0"
 * listener.port(); // 8081
 * }</pre>
 *
 * @param host the address the HTTP listener binds to, for example {@code 0.0.0.0}
 * @param port the port the HTTP listener binds to, for example {@code 8081}
 */
@ConfigurationProperties(prefix = "listener.http-listener-configuration")
public record HttpListenerProperties(String host, int port) {
}
