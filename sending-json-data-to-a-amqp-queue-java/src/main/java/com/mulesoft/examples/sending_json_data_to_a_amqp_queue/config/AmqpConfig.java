package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.Connection;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AMQP connector settings for the outbound request-response send.
 *
 * <p>The class carries the {@code amqp:connector} {@code AMQP_Connector} with {@code validateConnections="true"}
 * [sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:3]. It declares no connection factory,
 * template, admin, queue, exchange or binding. Spring Boot's {@code RabbitAutoConfiguration} builds the
 * {@code CachingConnectionFactory} and the {@code RabbitTemplate} from the {@code spring.rabbitmq.*} keys of
 * {@code application.yml} (host, port, virtual host, username and password), and this class adds two beans:
 * <ul>
 *   <li>{@link #rabbitTemplateReplyCustomizer(AmqpOutboundProperties)} sets the reply behaviour of the
 *       {@code request-response} {@code amqp:outbound-endpoint}
 *       [sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:9] on the auto-configured
 *       {@code RabbitTemplate};</li>
 *   <li>{@link #amqpStartupConnectionCheck(ConnectionFactory, boolean)} connects to the broker once at
 *       startup.</li>
 * </ul>
 *
 * <p>The AMQP client is {@code spring-boot-starter-amqp} (Spring AMQP 3.1.8, RabbitMQ Java client 5.19.0).
 * See DECISIONS.md D-063.
 */
@Configuration
public class AmqpConfig {

    private static final Logger LOG = LoggerFactory.getLogger(AmqpConfig.class);

    /**
     * Uses a temporary reply queue for each request-response send and waits
     * {@code amqp.outbound.response-timeout} ms for the reply.
     *
     * <p>Boot applies the returned customizer to the auto-configured {@code RabbitTemplate} after its
     * {@code RabbitTemplateConfigurer}. The customizer calls, in this order,
     * {@code setUseTemporaryReplyQueues(true)} and {@code setReplyTimeout(props.responseTimeout())}; the timeout
     * replaces any {@code spring.rabbitmq.template.reply-timeout}. Each {@code sendAndReceive} then declares a
     * server-named, exclusive, auto-delete reply queue, publishes the request with that queue as its
     * {@code reply_to}, and returns {@code null} when no reply arrives within the timeout. No direct reply-to,
     * reply listener container or fixed reply queue is configured. See DECISIONS.md D-640.
     *
     * @param props the outbound endpoint settings; only {@link AmqpOutboundProperties#responseTimeout()} is read
     * @return the customizer that sets the temporary reply queue and the reply timeout
     */
    @Bean
    public RabbitTemplateCustomizer rabbitTemplateReplyCustomizer(AmqpOutboundProperties props) {
        return (RabbitTemplate template) -> {
            template.setUseTemporaryReplyQueues(true);
            template.setReplyTimeout(props.responseTimeout());
        };
    }

    /**
     * Opens one broker connection after singleton creation and closes the returned handle when
     * {@code amqp.connector.connect-on-startup} is true; a failure stops startup.
     *
     * <p>The check mirrors {@code validateConnections="true"} of the {@code amqp:connector}
     * [sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:3]. The returned callback runs in
     * {@code afterSingletonsInstantiated()}, before the web server starts. When the key is true it calls
     * {@link ConnectionFactory#createConnection()} once and closes the connection in a try-with-resources
     * block. Any {@code AmqpException} it raises, such as {@code AmqpConnectException} for an unreachable broker
     * or {@code AmqpAuthenticationException} for rejected credentials, propagates unchanged and fails the
     * context refresh; the HTTP port then never opens. With Boot's {@code CachingConnectionFactory} in its
     * default {@code CHANNEL} cache mode, closing the handle leaves the shared broker connection open for the
     * later sends. When the key is false the callback calls nothing on the connection factory. See DECISIONS.md
     * D-469.
     *
     * @param connectionFactory the Spring AMQP connection factory that Boot auto-configures
     * @param connectOnStartup  {@code amqp.connector.connect-on-startup}, bound by {@code @Value}; {@code true}
     *                          when the key is absent
     * @return the callback that performs the startup connection check
     */
    @Bean
    public SmartInitializingSingleton amqpStartupConnectionCheck(
            ConnectionFactory connectionFactory,
            @Value("${amqp.connector.connect-on-startup:true}") boolean connectOnStartup) {
        return () -> {
            if (!connectOnStartup) {
                return;
            }
            try (Connection connection = connectionFactory.createConnection()) {
                LOG.debug("AMQP connection check succeeded; connection open: {}", connection.isOpen());
            }
        };
    }
}
