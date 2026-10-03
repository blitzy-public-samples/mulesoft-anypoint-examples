package com.mulesoft.examples.jms_message_rollback_and_redelivery.config;

import jakarta.jms.ConnectionFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;

/**
 * JMS beans of the {@code JMSRedeliver} flow on Artemis (D-024): the transacted queue listener container
 * factory, and the topic and queue templates that join the listener session.
 *
 * <p>Source: the connection factory {@code amqFactory} and the {@code jms:activemq-connector}
 * {@code jmsConnector} [jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:13-19], the
 * {@code ALWAYS_BEGIN} transaction of the inbound endpoint [:22-24] and the {@code ALWAYS_JOIN} transaction
 * of the outbound endpoint [:35-37].
 *
 * <p>Every bean receives the auto-configured {@link ConnectionFactory} bean as a method parameter and holds
 * that same instance. Spring Boot's Artemis auto-configuration builds it from {@code spring.artemis.mode}
 * and {@code spring.artemis.broker-url}. This class sets no broker address, credential, redelivery setting
 * or broker address setting; the redelivery delays and the redelivery ceiling are applied by
 * {@code RedeliveryDelayPolicy} (D-024).
 *
 * <p>Transaction behaviour:
 * <ul>
 *   <li>A container built by {@link #redeliveryListenerContainerFactory(ConnectionFactory)} receives each
 *       message in a locally transacted session. The session commits when the listener method returns and
 *       rolls back when it throws, and the broker then redelivers the message.</li>
 *   <li>While the listener method runs, the container exposes its transacted session on the listener thread
 *       under the connection factory as key. A send through {@code topicJmsTemplate} or
 *       {@code queueJmsTemplate} on that thread, with the same connection factory instance, uses the
 *       listener session and leaves commit or rollback to the container: a message sent before the listener
 *       throws is discarded with the rollback, and a message sent by a listener that returns normally
 *       becomes visible when the container commits.</li>
 *   <li>Outside a listener thread, each template send runs in its own transacted session, which the
 *       template commits after the send.</li>
 * </ul>
 *
 * <p>Neither template has a default destination: callers pass the destination name bound from
 * {@code jms-redeliver.queue} or {@code jms-redeliver.topic}. With two {@link JmsTemplate} beans in the
 * context, Spring Boot creates no default {@code jmsTemplate} or {@code jmsMessagingTemplate} bean, and
 * consumers select a template by bean name with {@code @Qualifier}. Spring Boot's own
 * {@code jmsListenerContainerFactory} bean stays in the context and is not referenced by this application.
 *
 * <pre>
 * public RedeliveryService(&#64;Qualifier("topicJmsTemplate") JmsTemplate topicJmsTemplate) { ... }
 *
 * &#64;JmsListener(destination = "${jms-redeliver.queue}",
 *         containerFactory = "redeliveryListenerContainerFactory")
 * public void jmsRedeliver(Message message) { ... }
 * </pre>
 */
@Configuration
public class JmsConfig {

    /**
     * Creates the listener container factory of the transacted queue listener.
     *
     * <p>Each container it builds consumes from a queue ({@code pubSubDomain} {@code false}) with exactly one
     * consumer (concurrency {@code "1"}) in a locally transacted session ({@code sessionTransacted}
     * {@code true}), the equivalent of {@code jms:transaction action="ALWAYS_BEGIN"}
     * [jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:22-24]. The factory sets no
     * transaction manager, error handler, message converter or back-off.
     *
     * @param connectionFactory the auto-configured connection factory, set on the factory unchanged
     * @return the listener container factory, bean name {@code redeliveryListenerContainerFactory}
     */
    @Bean
    public DefaultJmsListenerContainerFactory redeliveryListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setSessionTransacted(true);
        factory.setPubSubDomain(false);
        factory.setConcurrency("1");
        return factory;
    }

    /**
     * Creates the template that sends to topics.
     *
     * <p>The template resolves destination names in the topic domain ({@code pubSubDomain} {@code true}),
     * sends in a transacted session ({@code sessionTransacted} {@code true}) and applies its own delivery
     * mode to every message ({@code explicitQosEnabled} {@code true}): persistent when
     * {@code jms-connector.persistent-delivery} is {@code true}, as the connector's
     * {@code persistentDelivery="true"} sets
     * [jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:19], non-persistent when it is
     * {@code false}. On the listener thread a send joins the listener session, the equivalent of
     * {@code jms:transaction action="ALWAYS_JOIN"}
     * [jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:35-37], as the class description
     * states. The template has no default destination.
     *
     * @param connectionFactory  the auto-configured connection factory, used by the template unchanged
     * @param persistentDelivery the value of {@code jms-connector.persistent-delivery}
     * @return the topic template, bean name {@code topicJmsTemplate}
     */
    @Bean
    public JmsTemplate topicJmsTemplate(
            ConnectionFactory connectionFactory,
            @Value("${jms-connector.persistent-delivery}") boolean persistentDelivery) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(true);
        template.setSessionTransacted(true);
        template.setExplicitQosEnabled(true);
        template.setDeliveryPersistent(persistentDelivery);
        return template;
    }

    /**
     * Creates the template that sends to queues.
     *
     * <p>The template resolves destination names in the queue domain ({@code pubSubDomain} {@code false})
     * and is otherwise configured as {@link #topicJmsTemplate(ConnectionFactory, boolean)}: transacted
     * session, explicit QoS, and the delivery mode of {@code jms-connector.persistent-delivery}. On the
     * listener thread a send joins the listener session, as the class description states. The template has
     * no default destination.
     *
     * @param connectionFactory  the auto-configured connection factory, used by the template unchanged
     * @param persistentDelivery the value of {@code jms-connector.persistent-delivery}
     * @return the queue template, bean name {@code queueJmsTemplate}
     */
    @Bean
    public JmsTemplate queueJmsTemplate(
            ConnectionFactory connectionFactory,
            @Value("${jms-connector.persistent-delivery}") boolean persistentDelivery) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(false);
        template.setSessionTransacted(true);
        template.setExplicitQosEnabled(true);
        template.setDeliveryPersistent(persistentDelivery);
        return template;
    }
}
