package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Exchange, queue, routing key and reply timeout of the outbound AMQP request-response send; bound from
 * {@code amqp.outbound.*}.
 *
 * <p>The record carries the attributes of the {@code amqp:outbound-endpoint} of flow
 * {@code json-to-rabbitmqFlow} [sending-json-data-to-a-amqp-queue/src/main/app/json-to-rabbitmq.xml:9]. Its
 * defaults are the endpoint's literals: exchange {@code sales_exchange}, queue {@code sales_queue} and reply
 * timeout {@code 10000} ms. The routing key is the empty string unless {@code amqp.outbound.routing-key} binds
 * another value (D-469).
 *
 * <p>Each key binds to one component by relaxed binding:
 * <ul>
 *   <li>{@code amqp.outbound.exchange-name} to {@link #exchangeName()};</li>
 *   <li>{@code amqp.outbound.queue-name} to {@link #queueName()};</li>
 *   <li>{@code amqp.outbound.routing-key} to {@link #routingKey()};</li>
 *   <li>{@code amqp.outbound.response-timeout} to {@link #responseTimeout()}.</li>
 * </ul>
 * The environment variables {@code AMQP_OUTBOUND_EXCHANGE_NAME}, {@code AMQP_OUTBOUND_QUEUE_NAME},
 * {@code AMQP_OUTBOUND_ROUTING_KEY} and {@code AMQP_OUTBOUND_RESPONSE_TIMEOUT} bind the same keys. Every
 * component binds by constructor binding, and an absent key binds its default, also when no
 * {@code amqp.outbound} key is present at all. A {@code null} routing key becomes the empty string; no other
 * value is validated or normalised. Instances are immutable.
 *
 * <p>The record is registered as a bean by the main class {@code SendingJsonDataToAAmqpQueueApplication}.
 * {@code config.AmqpConfig} reads the reply timeout, and {@code service.SalesAmqpService} reads the exchange,
 * queue and routing key.
 *
 * <pre>{@code
 * amqp:
 *   outbound:
 *     exchange-name: sales_exchange
 *     queue-name: sales_queue
 *     routing-key: ""
 *     response-timeout: 10000
 * }</pre>
 * binds {@code exchangeName()} {@code "sales_exchange"}, {@code queueName()} {@code "sales_queue"},
 * {@code routingKey()} {@code ""} and {@code responseTimeout()} {@code 10000}; the same values bind when the
 * block is absent.
 *
 * @param exchangeName    {@code amqp.outbound.exchange-name}: the exchange each request is published to;
 *                        default {@code sales_exchange}
 * @param queueName       {@code amqp.outbound.queue-name}: the queue bound to the exchange; default
 *                        {@code sales_queue}
 * @param routingKey      {@code amqp.outbound.routing-key}: the routing key of each publish and of the queue
 *                        binding; the empty string when absent or {@code null}
 * @param responseTimeout {@code amqp.outbound.response-timeout}: the time in milliseconds a send waits for its
 *                        reply; default {@code 10000}
 */
@ConfigurationProperties("amqp.outbound")
public record AmqpOutboundProperties(
        @DefaultValue("sales_exchange") String exchangeName,
        @DefaultValue("sales_queue") String queueName,
        String routingKey,
        @DefaultValue("10000") long responseTimeout) {

    /**
     * Stores the components as given, with a {@code null} routing key replaced by the empty string.
     *
     * @param exchangeName    the exchange each request is published to, stored as given
     * @param queueName       the queue bound to the exchange, stored as given
     * @param routingKey      the routing key; {@code null} is stored as the empty string
     * @param responseTimeout the reply wait in milliseconds, stored as given
     */
    public AmqpOutboundProperties {
        if (routingKey == null) {
            routingKey = "";
        }
    }
}
