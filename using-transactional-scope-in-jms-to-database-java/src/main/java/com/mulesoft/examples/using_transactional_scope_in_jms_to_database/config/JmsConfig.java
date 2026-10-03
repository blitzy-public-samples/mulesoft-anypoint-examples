package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import jakarta.jms.ConnectionFactory;

import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisConfigurationCustomizer;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;

/**
 * JMS configuration of the {@code Active_MQ} connector on Boot's embedded, non-persistent Artemis broker
 * (D-024, D-080, D-343).
 *
 * <p>Source: the global element
 * {@code <jms:activemq-connector name="Active_MQ" maxRedelivery="2" validateConnections="true"/>}
 * [using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:3], used by the inbound
 * endpoint on queue {@code in} (:7) and the outbound endpoint on queue {@code out} (:18-20) of
 * {@code transactionsFlow1}.
 *
 * <p>Beans:
 * <ul>
 *   <li>{@code transactedListenerFactory}: session-transacted listener container factory with one consumer
 *       on the auto-configured {@link ConnectionFactory}; a listener exception rolls the received message
 *       back and Artemis redelivers it.</li>
 *   <li>{@code jmsTemplate}: session-transacted {@link JmsTemplate} on the same {@link ConnectionFactory};
 *       on a listener thread of {@code transactedListenerFactory} it sends through the listener's session,
 *       and the send commits or rolls back with the received message.</li>
 *   <li>{@code artemisRedeliveryCustomizer}: a redelivery delay of {@value #REDELIVERY_DELAY_MS} ms on the
 *       inbound address.</li>
 * </ul>
 *
 * <p>Usage on a listener method:
 * <pre>
 * &#64;JmsListener(destination = "${active-mq.inbound-queue}", containerFactory = "transactedListenerFactory")
 * public void transactionsFlow1(Message message) { ... }
 * </pre>
 */
@Configuration
@EnableConfigurationProperties(JmsConfig.ActiveMqProperties.class)
public class JmsConfig {

    private static final Logger log = LoggerFactory.getLogger(JmsConfig.class);

    /** Name of the original connector, {@code Active_MQ} [transactions.xml:3]. */
    public static final String CONNECTOR_NAME = "Active_MQ";

    /** Broker-side delay in milliseconds before Artemis redelivers a rolled-back message on the inbound address. */
    public static final long REDELIVERY_DELAY_MS = 1000L;

    /**
     * Binds the {@code active-mq.*} keys of the {@code Active_MQ} connector [transactions.xml:3]; each default
     * is the original literal.
     *
     * @param maxRedelivery redelivery ceiling, {@code maxRedelivery="2"} [transactions.xml:3]; key
     *                      {@code active-mq.max-redelivery}
     * @param inboundQueue  queue the flow consumes, {@code queue="in"} [transactions.xml:7]; key
     *                      {@code active-mq.inbound-queue}
     * @param outboundQueue queue the flow publishes to, {@code queue="out"} [transactions.xml:18]; key
     *                      {@code active-mq.outbound-queue}
     */
    @ConfigurationProperties("active-mq")
    public record ActiveMqProperties(
            @DefaultValue("2") int maxRedelivery,
            @DefaultValue("in") String inboundQueue,
            @DefaultValue("out") String outboundQueue) { }

    /**
     * Session-transacted listener factory for queue {@code in}, concurrency 1 (D-024, D-080, D-343).
     *
     * <p>Source: {@code processingStrategy="synchronous"} [transactions.xml:6] and the inbound endpoint on
     * connector {@code Active_MQ} [transactions.xml:7].
     *
     * <p>Boot's configurer applies the {@code spring.jms.*} settings first. No transaction manager is set on
     * the factory: the container commits the received message when the listener returns and rolls it back when
     * the listener throws, before the error handler runs. The error handler writes one DEBUG entry naming
     * {@link #CONNECTOR_NAME}, followed by the exception class names and stack frames of the failure and of its
     * causes, without any exception message (D-338).
     *
     * @param configurer        Boot's listener container factory configurer
     * @param connectionFactory the auto-configured connection factory of the embedded broker
     * @return the factory referenced by name from {@code @JmsListener(containerFactory = "transactedListenerFactory")}
     */
    @Bean
    DefaultJmsListenerContainerFactory transactedListenerFactory(
            DefaultJmsListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setSessionTransacted(true);
        factory.setConcurrency("1");
        factory.setErrorHandler(t -> {
            if (log.isDebugEnabled()) {
                log.debug("JMS listener invocation on connector {} failed; the received message was rolled back: {}",
                        CONNECTOR_NAME, messageFreeTrace(t));
            }
        });
        return factory;
    }

    /**
     * Renders the class name and stack frames of {@code throwable} and of each of its causes, outermost first,
     * each cause introduced by {@code Caused by: }, without any exception message; a cause already rendered ends
     * the chain (D-338).
     *
     * @param throwable the failure of the listener invocation
     * @return one line per class name and per stack frame, separated by the platform line separator
     */
    private static String messageFreeTrace(Throwable throwable) {
        StringBuilder trace = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = throwable; current != null && seen.add(current); current = current.getCause()) {
            if (current != throwable) {
                trace.append(System.lineSeparator()).append("Caused by: ");
            }
            trace.append(current.getClass().getName());
            for (StackTraceElement frame : current.getStackTrace()) {
                trace.append(System.lineSeparator()).append("\tat ").append(frame);
            }
        }
        return trace.toString();
    }

    /**
     * Session-transacted {@link JmsTemplate} on the auto-configured {@link ConnectionFactory}; it replaces
     * Boot's auto-configured template (D-343).
     *
     * <p>Source: {@code <jms:transaction action="JOIN_IF_POSSIBLE"/>} on the outbound endpoint
     * [transactions.xml:18-20].
     *
     * <p>On a listener thread of {@link #transactedListenerFactory} the template sends through the listener's
     * session and leaves the commit to the container; elsewhere it commits each send itself. No default
     * destination is set and the domain is point-to-point; callers name the queue on each send.
     *
     * @param connectionFactory the auto-configured connection factory of the embedded broker
     * @return the transacted template
     */
    @Bean
    JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setSessionTransacted(true);
        return template;
    }

    /**
     * Registers {@code redelivery-delay} {@value #REDELIVERY_DELAY_MS} ms for the address matching
     * {@link ActiveMqProperties#inboundQueue()} on the embedded broker (D-024, D-343).
     *
     * <p>Only the redelivery delay is set. Every other address setting of the inbound address, including Boot's
     * {@code #} dead-letter and expiry addresses and the default {@code max-delivery-attempts} of 10, is
     * inherited from the broker's wildcard and default settings.
     *
     * @param properties the {@code active-mq.*} keys
     * @return the customizer Boot applies to the embedded broker's configuration before the broker starts
     */
    @Bean
    ArtemisConfigurationCustomizer artemisRedeliveryCustomizer(ActiveMqProperties properties) {
        return configuration -> {
            configuration.addAddressSetting(properties.inboundQueue(),
                    new AddressSettings().setRedeliveryDelay(REDELIVERY_DELAY_MS));
            log.info("Embedded broker of connector {}: redelivery-delay {} ms on address {}",
                    CONNECTOR_NAME, REDELIVERY_DELAY_MS, properties.inboundQueue());
        };
    }
}
