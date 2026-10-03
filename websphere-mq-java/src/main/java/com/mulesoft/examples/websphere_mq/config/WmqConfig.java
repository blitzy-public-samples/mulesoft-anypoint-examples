package com.mulesoft.examples.websphere_mq.config;

import com.ibm.mq.jakarta.jms.MQConnectionFactory;
import com.ibm.msg.client.jakarta.wmq.WMQConstants;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.JMSException;
import jakarta.jms.Session;

import org.springframework.boot.autoconfigure.jms.DefaultJmsListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.UserCredentialsConnectionFactoryAdapter;

/**
 * IBM MQ connection and JMS listener-container configuration of the {@code wmq:connector}
 * {@code wmqConnector} [websphere-mq/src/main/app/websphere-mq.xml:4] and of the transactions of its
 * three endpoints (D-040).
 *
 * <p>Every connection setting comes from {@link WmqProperties}, bound from the {@code wmq.*} keys of
 * {@code application.yml} (D-012).
 *
 * <p>Beans:
 * <ul>
 *   <li>{@code mqConnectionFactory}: client-mode {@link MQConnectionFactory} for the configured host,
 *       port, server-connection channel and queue manager. Creating it opens no connection; the
 *       connector's {@code validateConnections="true"} has no counterpart (D-596).</li>
 *   <li>{@code connectionFactory}: the primary {@link ConnectionFactory}, a
 *       {@link UserCredentialsConnectionFactoryAdapter} over {@code mqConnectionFactory} that presents
 *       the configured user name and password on every connection. Boot's {@code JmsTemplate} and both
 *       listener container factories use this one instance (D-597).</li>
 *   <li>{@code inQueueListenerFactory}: container factory for queue {@code in}, flow
 *       {@code MessageProcessor} [websphere-mq.xml:16-18, {@code ALWAYS_BEGIN}]: transacted sessions,
 *       four consumers (D-595). A {@code JmsTemplate} send on a listener thread of this factory, the
 *       outbound endpoint on queue {@code out} [websphere-mq.xml:20-22, {@code JOIN_IF_POSSIBLE}], goes
 *       through the listener's session and commits or rolls back with the received message
 *       (D-597).</li>
 *   <li>{@code outQueueListenerFactory}: container factory for queue {@code out}, flow {@code Output}
 *       [websphere-mq.xml:25, no transaction]: non-transacted, auto-acknowledge sessions, four
 *       consumers (D-595).</li>
 * </ul>
 *
 * <p>Usage on a listener method:
 * <pre>
 * &#64;JmsListener(destination = "${wmq.in-queue}", containerFactory = "inQueueListenerFactory")
 * public void messageProcessor(Message message) throws JMSException { ... }
 * </pre>
 *
 * <p>When the queue manager is unreachable, startup completes; each listener container logs the failed
 * connection and retries with Spring's default recovery interval.
 */
@Configuration
public class WmqConfig {

    /**
     * Client-mode IBM MQ connection factory for the configured queue manager (D-040).
     *
     * <p>Maps the connector attributes [websphere-mq.xml:4] {@code hostName} to {@link WmqProperties#host()},
     * {@code port} to {@link WmqProperties#port()}, {@code queueManager} to {@link WmqProperties#queue()},
     * {@code channel} to {@link WmqProperties#channel()} and {@code transportType="CLIENT_MQ_TCPIP"} to
     * {@link WMQConstants#WMQ_CM_CLIENT}. No connection is opened here (D-596).
     *
     * @param properties the bound {@code wmq.*} settings
     * @return the unconnected factory
     * @throws JMSException when the IBM MQ client rejects a setting
     */
    @Bean
    MQConnectionFactory mqConnectionFactory(WmqProperties properties) throws JMSException {
        MQConnectionFactory factory = new MQConnectionFactory();
        factory.setHostName(properties.host());
        factory.setPort(properties.port());
        factory.setQueueManager(properties.queue());
        factory.setChannel(properties.channel());
        factory.setTransportType(WMQConstants.WMQ_CM_CLIENT);
        return factory;
    }

    /**
     * Connection factory shared by the JMS template and both listener containers; supplies the configured
     * user name and password, the connector's {@code username} and {@code password}
     * [websphere-mq.xml:4], on every connection it opens through {@code mqConnectionFactory} (D-597).
     *
     * @param mqConnectionFactory the IBM MQ factory the adapter delegates to
     * @param properties          the bound {@code wmq.*} settings
     * @return the primary connection factory of the application context
     */
    @Bean
    @Primary
    ConnectionFactory connectionFactory(MQConnectionFactory mqConnectionFactory, WmqProperties properties) {
        UserCredentialsConnectionFactoryAdapter adapter = new UserCredentialsConnectionFactoryAdapter();
        adapter.setTargetConnectionFactory(mqConnectionFactory);
        adapter.setUsername(properties.username());
        adapter.setPassword(properties.password());
        return adapter;
    }

    /**
     * Container factory for the {@code in} queue: transacted sessions, four consumers
     * [websphere-mq.xml:17, {@code ALWAYS_BEGIN}] (D-595, D-597).
     *
     * <p>Boot's {@code spring.jms.listener.*} settings, {@code auto-startup} included, are applied first;
     * the session mode and consumer range set here replace theirs.
     *
     * @param configurer        Boot's listener-container configurer
     * @param connectionFactory the primary connection factory
     * @return the factory named by {@code containerFactory = "inQueueListenerFactory"}
     */
    @Bean
    DefaultJmsListenerContainerFactory inQueueListenerFactory(
            DefaultJmsListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setSessionTransacted(true);
        factory.setConcurrency("4-4");
        return factory;
    }

    /**
     * Container factory for the {@code out} queue: non-transacted, auto-acknowledge, four consumers
     * [websphere-mq.xml:25, no transaction] (D-595).
     *
     * <p>Boot's {@code spring.jms.listener.*} settings, {@code auto-startup} included, are applied first;
     * the session mode, acknowledge mode and consumer range set here replace theirs.
     *
     * @param configurer        Boot's listener-container configurer
     * @param connectionFactory the primary connection factory
     * @return the factory named by {@code containerFactory = "outQueueListenerFactory"}
     */
    @Bean
    DefaultJmsListenerContainerFactory outQueueListenerFactory(
            DefaultJmsListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setSessionTransacted(false);
        factory.setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE);
        factory.setConcurrency("4-4");
        return factory;
    }
}
