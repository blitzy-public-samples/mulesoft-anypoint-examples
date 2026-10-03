package com.mulesoft.examples.jms_message_rollback_and_redelivery.config;

import java.util.Map;

import jakarta.jms.ConnectionFactory;

import org.apache.activemq.artemis.api.core.BroadcastEndpointFactory;
import org.apache.activemq.artemis.api.core.DiscoveryGroupConfiguration;
import org.apache.activemq.artemis.api.core.JGroupsFileBroadcastEndpointFactory;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.UDPBroadcastEndpointFactory;
import org.apache.activemq.artemis.core.remoting.impl.netty.NettyConnectorFactory;
import org.apache.activemq.artemis.core.remoting.impl.netty.TransportConstants;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.apache.activemq.artemis.uri.ConnectionFactoryParser;
import org.apache.activemq.artemis.utils.uri.URISchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisMode;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

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
 * <p>Broker URL check: the bean factory post-processor {@link ArtemisBrokerUrlGuard}, registered by
 * {@link #artemisBrokerUrlGuard()}, runs before any bean is created. In native Artemis mode it fails context
 * startup with an {@link IllegalStateException} naming {@code spring.artemis.broker-url} when that key is
 * missing, blank, the {@code TODO} placeholder or a URL the Artemis client cannot use; the message never
 * contains the configured value. In embedded Artemis mode, the mode of the {@code test} profile, it checks
 * nothing (D-341).
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

    /**
     * Registers the bean factory post-processor that checks {@code spring.artemis.broker-url} in native Artemis
     * mode (D-341).
     *
     * <p>The method is static and declares the concrete post-processor type: the container calls it without
     * instantiating {@code JmsConfig} and runs the result with the other bean factory post-processors, before it
     * creates any regular bean, Spring Boot's {@code jmsConnectionFactory} included.
     *
     * @return the broker URL guard; the container supplies its {@link Environment}
     */
    @Bean
    public static ArtemisBrokerUrlGuard artemisBrokerUrlGuard() {
        return new ArtemisBrokerUrlGuard();
    }

    /**
     * Fails context startup when Spring Boot's Artemis auto-configuration would build its native-mode connection
     * factory from a missing, placeholder or unusable {@code spring.artemis.broker-url} (D-341).
     *
     * <p>Mode: the value of {@code spring.artemis.mode} when the key is set. Otherwise the mode Spring Boot
     * deduces: embedded when {@code spring.artemis.embedded.enabled} is {@code true}, its default, and
     * {@code org.apache.activemq.artemis.jms.server.embedded.EmbeddedJMS} or
     * {@code org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ} is on the class path, native
     * otherwise. The keys are read from the {@link Environment} with Spring Boot's relaxed binding, so the
     * environment variable {@code SPRING_ARTEMIS_BROKERURL} sets {@code spring.artemis.broker-url}. In embedded
     * mode the post-processor checks nothing.
     *
     * <p>In native mode it throws an {@link IllegalStateException} whose message names
     * {@code spring.artemis.broker-url} and native Artemis mode when the value:
     * <ul>
     *   <li>is missing or blank;</li>
     *   <li>equals {@code TODO} after trimming, ignoring case;</li>
     *   <li>makes {@code new ActiveMQConnectionFactory(value)}, the constructor Spring Boot calls, throw: for
     *       example {@code foo://bar:1}, {@code tcp://} or {@code failover:(tcp://localhost:61616)};</li>
     *   <li>yields a static Netty (TCP) transport whose {@code host} parameter is missing, blank or the text
     *       {@code null}, which the Artemis client records for a URL without a host such as
     *       {@code tcp://localhost:abc}, or whose {@code port} parameter is present, not {@code -1} (the URL names
     *       no port) and not an integer from 1 to 65535, for example {@code tcp://localhost:99999};</li>
     *   <li>yields a static in-VM transport whose {@code serverId} parameter is missing or not an integer from 0 to
     *       2147483647, for example {@code vm://abc} or {@code vm://-1};</li>
     *   <li>yields a UDP discovery group whose group address is missing, blank or the text {@code null}, for
     *       example {@code udp://231.7.7.7:abc} or {@code udp:///x}; whose group port is not an integer from 1 to
     *       65535, for example {@code udp://231.7.7.7:99999} or {@code udp://231.7.7.7}; whose local bind address
     *       is set and blank or the text {@code null}; whose local bind port is neither {@code -1}, the value when
     *       the URL sets none, nor an integer from 0 to 65535; or whose {@code localBindPort} query parameter is
     *       present and not an integer, for example {@code udp://231.7.7.7:9876?localBindPort=99999} or
     *       {@code udp://231.7.7.7:9876?localBindPort=abc};</li>
     *   <li>yields a JGroups discovery group whose channel name or {@code file} parameter is missing, blank or the
     *       text {@code null}, for example {@code jgroups://mychannel} or {@code jgroups:///x?file=jgroups.xml};</li>
     *   <li>yields a discovery group whose broadcast endpoint is neither UDP nor a JGroups configuration file.</li>
     * </ul>
     * No message contains the configured value, and no exception carries an exception of the Artemis client as
     * its cause. A value that passes leaves the bean factory unchanged. The connection factory built for the check
     * is closed, no connection to a broker is opened and no discovery group is joined. Values that pass include
     * {@code tcp://localhost:61616}, {@code tcp://localhost}, {@code (tcp://a:61616,tcp://b:61616)?ha=true},
     * {@code tcp://a:61616?ha=true#tcp://b:61616}, {@code tcp://[::1]:61616}, {@code udp://231.7.7.7:9876},
     * {@code udp://[ff02::1]:9876}, {@code udp://231.7.7.7:9876?localBindAddress=0.0.0.0&localBindPort=-1},
     * {@code jgroups://mychannel?file=jgroups.xml} and {@code vm://0}.
     *
     * <p>Usage outside a container:
     * <pre>{@code
     * ArtemisBrokerUrlGuard guard = new ArtemisBrokerUrlGuard();
     * guard.setEnvironment(new MockEnvironment()
     *         .withProperty("spring.artemis.mode", "native")
     *         .withProperty("spring.artemis.broker-url", "TODO"));
     * guard.postProcessBeanFactory(new DefaultListableBeanFactory());
     * // throws IllegalStateException: Property 'spring.artemis.broker-url' is the TODO placeholder; ...
     * }</pre>
     */
    public static final class ArtemisBrokerUrlGuard implements BeanFactoryPostProcessor, EnvironmentAware {

        /** Key of the Artemis broker URL. */
        private static final String BROKER_URL_KEY = "spring.artemis.broker-url";

        /** Key of the Artemis mode. */
        private static final String MODE_KEY = "spring.artemis.mode";

        /** Key of the switch of the embedded Artemis broker. */
        private static final String EMBEDDED_ENABLED_KEY = "spring.artemis.embedded.enabled";

        /** Classes whose presence on the class path lets Spring Boot deduce embedded mode. */
        private static final String[] EMBEDDED_BROKER_CLASSES = {
                "org.apache.activemq.artemis.jms.server.embedded.EmbeddedJMS",
                "org.apache.activemq.artemis.core.server.embedded.EmbeddedActiveMQ"};

        /** Placeholder value of {@code spring.artemis.broker-url} in the committed {@code application.yml}, D-012. */
        private static final String TODO_PLACEHOLDER = "TODO";

        /** Value the Artemis client records as the {@code host} transport parameter when the URL names no host. */
        private static final String NO_HOST = "null";

        /** Value the Artemis client records as the {@code port} transport parameter when the URL names no port. */
        private static final String NO_PORT = "-1";

        /** Highest TCP port number. */
        private static final int MAX_PORT = 65535;

        /** Connector factory of the static transport that the Artemis client creates for a {@code vm://} URL. */
        private static final String IN_VM_CONNECTOR_FACTORY =
                "org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnectorFactory";

        /** Transport parameter holding the server id of an in-VM transport. */
        private static final String SERVER_ID = "serverId";

        /** Query parameter of a {@code udp://} URL that sets the local bind port of the discovery group. */
        private static final String LOCAL_BIND_PORT = "localBindPort";

        /** Local bind port of a UDP discovery group whose URL sets none. */
        private static final int DEFAULT_LOCAL_BIND_PORT = -1;

        /** Text rejected as a UDP group address or local bind address and as a JGroups channel name or file name. */
        private static final String NULL_TEXT = "null";

        /** Environment holding the {@code spring.artemis.*} keys; set by the container. */
        private Environment environment;

        /**
         * Stores the environment from which {@link #postProcessBeanFactory} reads the {@code spring.artemis.*} keys.
         *
         * @param environment the application environment
         */
        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        /**
         * Determines the Artemis mode and, in native mode, checks {@code spring.artemis.broker-url} as the class
         * description states. The bean factory itself is left unchanged.
         *
         * @param beanFactory the bean factory being post-processed
         * @throws IllegalStateException when no environment was set, or in native mode when the broker URL is
         *                               missing, blank, {@code TODO} or not usable by the Artemis client
         */
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            if (environment == null) {
                throw new IllegalStateException(getClass().getSimpleName()
                        + " has no Environment; setEnvironment must be called before it runs");
            }
            Binder binder = Binder.get(environment);
            ArtemisMode mode = binder.bind(MODE_KEY, ArtemisMode.class).orElseGet(() -> deduceMode(binder));
            if (mode == ArtemisMode.NATIVE) {
                checkBrokerUrl(binder.bind(BROKER_URL_KEY, String.class).orElse(null));
            }
        }

        /**
         * Returns the mode Spring Boot deduces when {@code spring.artemis.mode} is not set.
         *
         * @param binder the binder over the application environment
         * @return {@link ArtemisMode#EMBEDDED} when {@code spring.artemis.embedded.enabled} is {@code true} or
         *         unset and an embedded broker class is present, {@link ArtemisMode#NATIVE} otherwise
         */
        private static ArtemisMode deduceMode(Binder binder) {
            boolean embeddedEnabled = binder.bind(EMBEDDED_ENABLED_KEY, Boolean.class).orElse(Boolean.TRUE);
            if (embeddedEnabled && isEmbeddedBrokerClassPresent()) {
                return ArtemisMode.EMBEDDED;
            }
            return ArtemisMode.NATIVE;
        }

        /**
         * Tells whether one of {@link #EMBEDDED_BROKER_CLASSES} can be loaded by Spring's default class loader.
         *
         * @return {@code true} when at least one of the classes is present
         */
        private static boolean isEmbeddedBrokerClassPresent() {
            for (String className : EMBEDDED_BROKER_CLASSES) {
                if (ClassUtils.isPresent(className, null)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Checks a native-mode broker URL: present and not blank, not {@code TODO}, accepted by the Artemis client,
         * every static Netty transport with a host and a usable port, every static in-VM transport with a usable
         * server id, and a discovery group, when the URL yields one, with a usable UDP or JGroups broadcast endpoint.
         * The connection factory built from the URL is closed before the method returns or throws.
         *
         * @param brokerUrl the bound value of {@code spring.artemis.broker-url}, or {@code null} when unset
         * @throws IllegalStateException when a check fails; the message does not contain {@code brokerUrl}
         */
        private static void checkBrokerUrl(String brokerUrl) {
            if (!StringUtils.hasText(brokerUrl)) {
                throw invalid("is missing or blank; native Artemis mode requires the URL of the Artemis broker");
            }
            if (TODO_PLACEHOLDER.equalsIgnoreCase(brokerUrl.trim())) {
                throw invalid("is the TODO placeholder; replace it with the URL of the Artemis broker, "
                        + "which native Artemis mode requires");
            }
            ActiveMQConnectionFactory connectionFactory = parse(brokerUrl);
            try {
                TransportConfiguration[] transports =
                        connectionFactory.getServerLocator().getStaticTransportConfigurations();
                if (transports != null) {
                    for (TransportConfiguration transport : transports) {
                        checkTransport(transport);
                    }
                }
                DiscoveryGroupConfiguration discoveryGroup =
                        connectionFactory.getServerLocator().getDiscoveryGroupConfiguration();
                if (discoveryGroup != null) {
                    checkDiscoveryGroup(discoveryGroup.getBroadcastEndpointFactory(), brokerUrl);
                }
            } finally {
                connectionFactory.close();
            }
        }

        /**
         * Builds an Artemis connection factory from the broker URL with the constructor Spring Boot calls in native
         * mode. Building it opens no connection.
         *
         * @param brokerUrl the broker URL, unchanged
         * @return the connection factory; the caller closes it
         * @throws IllegalStateException when the Artemis client rejects the URL; the exception has no cause and its
         *                               message does not contain {@code brokerUrl}
         */
        private static ActiveMQConnectionFactory parse(String brokerUrl) {
            try {
                return new ActiveMQConnectionFactory(brokerUrl);
            } catch (RuntimeException ex) {
                // Thrown without the Artemis client's exception as cause (D-341).
                throw invalid("is not a URL the Artemis client accepts; native Artemis mode requires the URL of "
                        + "the Artemis broker");
            }
        }

        /**
         * Checks a static transport: the host and port parameters of a transport that uses the Netty connector, and
         * the server id of a transport that uses the in-VM connector. A transport of any other connector is not
         * checked.
         *
         * @param transport a static transport of the parsed broker URL
         * @throws IllegalStateException when the Netty host is missing, blank or {@value #NO_HOST}, the Netty port is
         *                               present, not {@value #NO_PORT} and not an integer from 1 to 65535, or the
         *                               in-VM server id is missing or not an integer from 0 to 2147483647
         */
        private static void checkTransport(TransportConfiguration transport) {
            if (IN_VM_CONNECTOR_FACTORY.equals(transport.getFactoryClassName())) {
                checkInVmTransport(transport.getParams());
                return;
            }
            if (!NettyConnectorFactory.class.getName().equals(transport.getFactoryClassName())) {
                return;
            }
            Map<String, Object> params = transport.getParams();
            Object host = params == null ? null : params.get(TransportConstants.HOST_PROP_NAME);
            if (host == null || !StringUtils.hasText(host.toString()) || NO_HOST.equals(host.toString())) {
                throw invalid("names a TCP transport without a host; native Artemis mode requires the URL of the "
                        + "Artemis broker");
            }
            Object port = params.get(TransportConstants.PORT_PROP_NAME);
            if (port != null && !NO_PORT.equals(port.toString()) && !isIntegerBetween(port.toString(), 1, MAX_PORT)) {
                throw invalid("names a TCP transport whose port is not an integer from 1 to " + MAX_PORT
                        + "; native Artemis mode requires the URL of the Artemis broker");
            }
        }

        /**
         * Checks the {@value #SERVER_ID} parameter of a static in-VM transport, the URI host of a {@code vm://} URL.
         *
         * @param params the transport parameters, or {@code null}
         * @throws IllegalStateException when the server id is missing or not an integer from 0 to 2147483647
         */
        private static void checkInVmTransport(Map<String, Object> params) {
            Object serverId = params == null ? null : params.get(SERVER_ID);
            if (serverId == null || !isIntegerBetween(serverId.toString(), 0, Integer.MAX_VALUE)) {
                throw invalid("names an in-VM transport whose server id is not an integer from 0 to "
                        + Integer.MAX_VALUE + "; native Artemis mode requires the URL of the Artemis broker");
            }
        }

        /**
         * Checks the broadcast endpoint of the discovery group that the Artemis client creates for a {@code udp://}
         * or {@code jgroups://} URL.
         *
         * @param endpointFactory the broadcast endpoint factory of the discovery group, or {@code null}
         * @param brokerUrl       the broker URL, whose {@value #LOCAL_BIND_PORT} query parameter is read
         * @throws IllegalStateException when a UDP or JGroups check fails, or the endpoint is of any other kind
         */
        private static void checkDiscoveryGroup(BroadcastEndpointFactory endpointFactory, String brokerUrl) {
            if (endpointFactory instanceof UDPBroadcastEndpointFactory udp) {
                checkUdpDiscoveryGroup(udp, brokerUrl);
            } else if (endpointFactory instanceof JGroupsFileBroadcastEndpointFactory jgroups) {
                checkJGroupsDiscoveryGroup(jgroups);
            } else {
                throw invalid("names a discovery group whose broadcast endpoint is neither UDP nor a JGroups "
                        + "configuration file; native Artemis mode requires the URL of the Artemis broker");
            }
        }

        /**
         * Checks the group address, group port, local bind address and local bind port of a UDP discovery group.
         *
         * @param udp       the UDP broadcast endpoint factory built from the broker URL
         * @param brokerUrl the broker URL, whose {@value #LOCAL_BIND_PORT} query parameter is read
         * @throws IllegalStateException when the group address is missing, blank or {@value #NULL_TEXT}; the group
         *                               port is not an integer from 1 to 65535; the local bind address is set and
         *                               blank or {@value #NULL_TEXT}; the local bind port is neither
         *                               {@value #DEFAULT_LOCAL_BIND_PORT} nor an integer from 0 to 65535; or the
         *                               {@value #LOCAL_BIND_PORT} query parameter is present and not an integer
         */
        private static void checkUdpDiscoveryGroup(UDPBroadcastEndpointFactory udp, String brokerUrl) {
            if (isMissing(udp.getGroupAddress())) {
                throw invalid("names a UDP discovery group without a group address; native Artemis mode requires "
                        + "the URL of the Artemis broker");
            }
            int groupPort = udp.getGroupPort();
            if (groupPort < 1 || groupPort > MAX_PORT) {
                throw invalid("names a UDP discovery group whose group port is not an integer from 1 to " + MAX_PORT
                        + "; native Artemis mode requires the URL of the Artemis broker");
            }
            String localBindAddress = udp.getLocalBindAddress();
            if (localBindAddress != null && isMissing(localBindAddress)) {
                throw invalid("names a UDP discovery group whose local bind address is blank or " + NULL_TEXT
                        + "; native Artemis mode requires the URL of the Artemis broker");
            }
            int localBindPort = udp.getLocalBindPort();
            String localBindPortText = queryParameters(brokerUrl).get(LOCAL_BIND_PORT);
            if (localBindPort < DEFAULT_LOCAL_BIND_PORT || localBindPort > MAX_PORT
                    || (localBindPortText != null
                            && !isIntegerBetween(localBindPortText.trim(), Integer.MIN_VALUE, Integer.MAX_VALUE))) {
                throw invalid("names a UDP discovery group whose local bind port is neither "
                        + DEFAULT_LOCAL_BIND_PORT + " nor an integer from 0 to " + MAX_PORT
                        + "; native Artemis mode requires the URL of the Artemis broker");
            }
        }

        /**
         * Checks the channel name and the configuration file name of a JGroups discovery group.
         *
         * @param jgroups the JGroups broadcast endpoint factory built from the broker URL
         * @throws IllegalStateException when the channel name or the file name is missing, blank or
         *                               {@value #NULL_TEXT}
         */
        private static void checkJGroupsDiscoveryGroup(JGroupsFileBroadcastEndpointFactory jgroups) {
            if (isMissing(jgroups.getChannelName())) {
                throw invalid("names a JGroups discovery group without a channel name; native Artemis mode requires "
                        + "the URL of the Artemis broker");
            }
            if (isMissing(jgroups.getFile())) {
                throw invalid("names a JGroups discovery group without a configuration file in its file parameter; "
                        + "native Artemis mode requires the URL of the Artemis broker");
            }
        }

        /**
         * Returns the query parameters of a broker URL as the Artemis client reads them when it builds the
         * connection factory.
         *
         * @param brokerUrl a broker URL that {@link #parse(String)} accepted
         * @return the decoded query parameters by name; empty when the URL has no query
         * @throws IllegalStateException when the Artemis client cannot expand the URL; the exception has no cause
         *                               and its message does not contain {@code brokerUrl}
         */
        private static Map<String, String> queryParameters(String brokerUrl) {
            try {
                return URISchema.parseQuery(new ConnectionFactoryParser().expandURI(brokerUrl).getQuery(), null);
            } catch (Exception ex) {
                // Thrown without the Artemis client's exception as cause (D-341).
                throw invalid("is not a URL the Artemis client accepts; native Artemis mode requires the URL of "
                        + "the Artemis broker");
            }
        }

        /**
         * Tells whether a value recorded by the Artemis client is missing: {@code null}, blank or
         * {@value #NULL_TEXT}.
         *
         * @param value the recorded value
         * @return {@code true} when the value is missing
         */
        private static boolean isMissing(String value) {
            return !StringUtils.hasText(value) || NULL_TEXT.equals(value);
        }

        /**
         * Tells whether a text is a decimal integer from {@code min} to {@code max}, both included.
         *
         * @param text the text to parse
         * @param min  the lowest accepted number
         * @param max  the highest accepted number
         * @return {@code true} when the text parses as an integer in that range
         */
        private static boolean isIntegerBetween(String text, int min, int max) {
            try {
                int number = Integer.parseInt(text);
                return number >= min && number <= max;
            } catch (NumberFormatException ex) {
                return false;
            }
        }

        /**
         * Creates the exception for a broker URL that fails a check.
         *
         * @param problem what is wrong with the value, without the value itself
         * @return an exception whose message is {@code Property 'spring.artemis.broker-url' } followed by
         *         {@code problem}
         */
        private static IllegalStateException invalid(String problem) {
            return new IllegalStateException("Property '" + BROKER_URL_KEY + "' " + problem);
        }
    }
}
