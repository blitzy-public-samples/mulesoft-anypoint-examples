package com.mulesoft.examples.filtering_a_message.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code listener.http-listener-configuration} properties: the host address the HTTP listener binds to.
 *
 * @param host the address the embedded HTTP listener binds to, for example {@code 0.0.0.0}
 */
@ConfigurationProperties("listener.http-listener-configuration")
public record HttpListenerProperties(String host) {
}
