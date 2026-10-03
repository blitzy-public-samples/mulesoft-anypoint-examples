package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import jakarta.jms.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.jta.JtaTransactionManager;
import org.springframework.util.StringUtils;

/**
 * JMS wiring of the in-VM Artemis broker that takes the place of the {@code Active_MQ} XA connector
 * [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:4] (D-026, D-080, D-691).
 *
 * <p>Spring Boot's Artemis auto-configuration runs the embedded, non-persistent broker
 * ({@code spring.artemis.mode: embedded}) with the queues {@code queues.inhouse-order} and {@code queues.audit},
 * reachable only through its in-VM acceptor (D-080). With the Narayana starter's {@code XAConnectionFactoryWrapper}
 * bean present, Boot registers two connection factories; this class declares none of its own:
 * <ul>
 *   <li>{@code jmsConnectionFactory}, alias {@value #XA_CONNECTION_FACTORY}: the {@code @Primary} Artemis XA factory
 *       wrapped by Narayana. A session it opens while a JTA transaction is active enlists its XA resource in that
 *       transaction;</li>
 *   <li>{@value #NON_XA_CONNECTION_FACTORY}: the unwrapped Artemis factory. Its sessions never join a JTA
 *       transaction.</li>
 * </ul>
 *
 * <p>Beans:
 * <ul>
 *   <li>{@link #jmsListenerContainerFactory}: the container factory of every {@code @JmsListener} that names no
 *       other one. Each delivery to the {@code inhouseOrder} listener [same file:98-100] and to the {@code audit}
 *       listener [same file:130-132] runs in one JTA transaction of the Narayana {@link JtaTransactionManager}, the
 *       counterpart of {@code <xa-transaction action="ALWAYS_BEGIN"/>}. The Derby XA data source of
 *       {@link DerbyConfig} joins the same transaction: a listener exception rolls back the receive together with the
 *       {@code orders} or {@code order_audits} insert, and the broker delivers the message again (D-026).</li>
 *   <li>{@link #inhouseOrderJmsTemplate}: the request-response dispatch of one order item to
 *       {@code queues.inhouse-order} [same file:39]. {@code sendAndReceive} sends on the non-XA factory outside any
 *       transaction, waits on a temporary reply queue for at most {@code inhouse-order.response-timeout}
 *       milliseconds and returns {@code null} when no reply arrives in that time.</li>
 *   <li>{@link #auditJmsTemplate}: the one-way dispatch to {@code queues.audit} [same file:44], sent on the non-XA
 *       factory outside any transaction; the in-VM queue takes the place of the transacted VM endpoint
 *       {@code audit} (D-026).</li>
 * </ul>
 *
 * <p>Both templates keep Spring's default {@code SimpleMessageConverter}: a {@code Serializable} payload such as
 * {@code OrderItem} or {@code PurchaseReceipt} travels as an {@code ObjectMessage}, a {@code String} payload as a
 * {@code TextMessage} (the original {@code mimeType="text/plain"}). No deserialization allow list is set; the broker
 * accepts {@code ObjectMessage} bodies of any {@code Serializable} type.
 *
 * <p>Injection: neither template is {@code @Primary}, and with both present Boot's auto-configured
 * {@code jmsTemplate} is not created. Consumers select a template by bean name:
 * <pre>{@code
 * public OrderOrchestrationService(@Qualifier("inhouseOrderJmsTemplate") JmsTemplate inhouseOrderJmsTemplate,
 *                                  @Qualifier("auditJmsTemplate") JmsTemplate auditJmsTemplate) {
 *     this.inhouseOrderJmsTemplate = inhouseOrderJmsTemplate;
 *     this.auditJmsTemplate = auditJmsTemplate;
 * }
 * }</pre>
 *
 * <p>Redelivery: no {@code ArtemisConfigurationCustomizer} is declared. The address settings of both queues are the
 * Artemis defaults, a redelivery delay of 0 ms and {@code max-delivery-attempts} 10. Ten delivery attempts exceed
 * {@code inhouse-order.max-redelivery-attempts} + 1, the deliveries the {@code inhouseOrder} listener processes
 * before its exceeded handler runs [same file:123-128]; the listener's ceiling decides the outcome of a failing
 * message (D-024, D-692).
 *
 * <p>Startup fails with an {@link IllegalStateException} naming the key when {@code queues.inhouse-order} or
 * {@code queues.audit} is blank or {@code inhouse-order.response-timeout} is not greater than zero, and with a
 * placeholder or conversion exception when a key is missing or not a number.
 */
@Configuration(proxyBeanMethods = false)
public class JmsConfig {

    /** Key of the queue the in-house order items are dispatched to. */
    static final String INHOUSE_ORDER_QUEUE_KEY = "queues.inhouse-order";

    /** Key of the queue the order audit messages are dispatched to. */
    static final String AUDIT_QUEUE_KEY = "queues.audit";

    /** Key of the time, in milliseconds, a request-response dispatch to the in-house queue waits for its reply. */
    static final String INHOUSE_ORDER_RESPONSE_TIMEOUT_KEY = "inhouse-order.response-timeout";

    /** Name of Boot's Narayana-wrapped Artemis XA connection factory, an alias of {@code jmsConnectionFactory}. */
    static final String XA_CONNECTION_FACTORY = "xaJmsConnectionFactory";

    /** Name of Boot's unwrapped Artemis connection factory. */
    static final String NON_XA_CONNECTION_FACTORY = "nonXaJmsConnectionFactory";

    /** Logger of the configured JMS listener container factory and templates. */
    private static final Logger LOGGER = LoggerFactory.getLogger(JmsConfig.class);

    /**
     * Builds the default {@code @JmsListener} container factory: Boot's listener settings on the Narayana-wrapped
     * XA connection factory, with every delivery received and processed inside one JTA transaction (D-026, D-691).
     *
     * <p>The bean name {@code jmsListenerContainerFactory} is the default container factory name of
     * {@code @JmsListener}; Boot's own factory of that name is not created. A container built from this factory
     * begins a transaction of {@code transactionManager} before each receive, enlists the JMS session in it and
     * commits it after the listener method returns. Every XA resource the listener method uses in between, such as a
     * connection of the Derby XA data source, commits or rolls back with the receive.
     *
     * @param configurer          Boot's configurer, which applies the {@code spring.jms.listener.*} settings and the
     *                            context's message converter
     * @param xaConnectionFactory Boot's Artemis XA connection factory wrapped by the Narayana
     *                            {@code XAConnectionFactoryWrapper}
     * @param transactionManager  the Narayana JTA transaction manager, the only transaction manager of the context
     * @return the transacted listener container factory
     */
    @Bean
    public DefaultJmsListenerContainerFactory jmsListenerContainerFactory(
            DefaultJmsListenerContainerFactoryConfigurer configurer,
            @Qualifier(XA_CONNECTION_FACTORY) ConnectionFactory xaConnectionFactory,
            JtaTransactionManager transactionManager) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, xaConnectionFactory);
        factory.setTransactionManager(transactionManager);
        factory.setSessionTransacted(true);
        LOGGER.info("JMS listener container factory: connection factory {}, JTA transaction manager {}",
                xaConnectionFactory.getClass().getName(), transactionManager.getClass().getName());
        return factory;
    }

    /**
     * Builds the template of the request-response dispatch to the in-house order queue [same file:39] (D-691).
     *
     * <p>The template sends to {@code queues.inhouse-order} on the non-XA connection factory in a non-transacted
     * session; the message is on the queue as soon as {@code send} or {@code sendAndReceive} returns from the send.
     * {@code sendAndReceive} creates a temporary queue, sets it as {@code JMSReplyTo}, and returns the first message
     * received on it within {@code inhouse-order.response-timeout} milliseconds, or {@code null} when none arrives in
     * that time.
     *
     * <pre>{@code
     * Message reply = inhouseOrderJmsTemplate.sendAndReceive(
     *         session -> inhouseOrderJmsTemplate.getMessageConverter().toMessage(orderItem, session));
     * PurchaseReceipt receipt = reply == null
     *         ? null
     *         : (PurchaseReceipt) inhouseOrderJmsTemplate.getMessageConverter().fromMessage(reply);
     * }</pre>
     *
     * @param nonXaConnectionFactory Boot's unwrapped Artemis connection factory
     * @param queue                  the value of {@code queues.inhouse-order}
     * @param responseTimeout        the value of {@code inhouse-order.response-timeout}, in milliseconds
     * @return the non-transacted point-to-point template with the in-house queue as default destination
     * @throws IllegalStateException when the queue name is blank or the timeout is not greater than zero
     */
    @Bean
    public JmsTemplate inhouseOrderJmsTemplate(
            @Qualifier(NON_XA_CONNECTION_FACTORY) ConnectionFactory nonXaConnectionFactory,
            @Value("${queues.inhouse-order}") String queue,
            @Value("${inhouse-order.response-timeout}") long responseTimeout) {
        JmsTemplate template = new JmsTemplate(nonXaConnectionFactory);
        template.setDefaultDestinationName(requireText(queue, INHOUSE_ORDER_QUEUE_KEY));
        template.setReceiveTimeout(requirePositive(responseTimeout, INHOUSE_ORDER_RESPONSE_TIMEOUT_KEY));
        template.setPubSubDomain(false);
        template.setSessionTransacted(false);
        LOGGER.info("JMS template inhouseOrderJmsTemplate: queue {}, reply timeout {} ms", queue, responseTimeout);
        return template;
    }

    /**
     * Builds the template of the one-way dispatch to the audit queue [same file:44] (D-026, D-691).
     *
     * <p>The template sends to {@code queues.audit} on the non-XA connection factory in a non-transacted session;
     * the message is on the queue as soon as {@code send} returns. The sending service chooses the body; a
     * {@code String} converted by the default converter becomes a {@code TextMessage}.
     *
     * @param nonXaConnectionFactory Boot's unwrapped Artemis connection factory
     * @param queue                  the value of {@code queues.audit}
     * @return the non-transacted point-to-point template with the audit queue as default destination
     * @throws IllegalStateException when the queue name is blank
     */
    @Bean
    public JmsTemplate auditJmsTemplate(
            @Qualifier(NON_XA_CONNECTION_FACTORY) ConnectionFactory nonXaConnectionFactory,
            @Value("${queues.audit}") String queue) {
        JmsTemplate template = new JmsTemplate(nonXaConnectionFactory);
        template.setDefaultDestinationName(requireText(queue, AUDIT_QUEUE_KEY));
        template.setPubSubDomain(false);
        template.setSessionTransacted(false);
        LOGGER.info("JMS template auditJmsTemplate: queue {}", queue);
        return template;
    }

    /**
     * Returns {@code value} when it holds a non-whitespace character.
     *
     * @param value the configured value
     * @param key   the key the value was read from
     * @return {@code value}
     * @throws IllegalStateException when {@code value} is {@code null}, empty or whitespace only
     */
    static String requireText(String value, String key) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Configuration key '" + key + "' must name a queue, but is blank");
        }
        return value;
    }

    /**
     * Returns {@code value} when it is greater than zero.
     *
     * @param value the configured value
     * @param key   the key the value was read from
     * @return {@code value}
     * @throws IllegalStateException when {@code value} is zero or negative
     */
    static long requirePositive(long value, String key) {
        if (value <= 0) {
            throw new IllegalStateException(
                    "Configuration key '" + key + "' must be greater than zero, but is " + value);
        }
        return value;
    }
}
