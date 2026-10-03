package com.mulesoft.examples.jms_message_rollback_and_redelivery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Map;

import jakarta.jms.ConnectionFactory;

import org.apache.activemq.artemis.api.core.BroadcastEndpointFactory;
import org.apache.activemq.artemis.api.core.DiscoveryGroupConfiguration;
import org.apache.activemq.artemis.api.core.JGroupsFileBroadcastEndpointFactory;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.core.UDPBroadcastEndpointFactory;
import org.apache.activemq.artemis.api.core.client.ServerLocator;
import org.apache.activemq.artemis.core.client.impl.ServerLocatorImpl;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.connection.CachingConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

/**
 * Tests the native-mode check of {@code spring.artemis.broker-url} by {@link JmsConfig.ArtemisBrokerUrlGuard}
 * (D-341).
 *
 * <p>Each context holds {@link JmsConfig} with Spring Boot's {@link ArtemisAutoConfiguration} and
 * {@link JmsAutoConfiguration}. A rejected value fails startup with an {@link IllegalStateException} that names
 * {@code spring.artemis.broker-url} and native Artemis mode, has no cause and does not contain the value. An
 * accepted value starts the context with Spring Boot's connection factory and the beans of {@code JmsConfig};
 * no broker is reached and no discovery group is started. Embedded mode, the mode of {@code application-test.yml},
 * starts an in-VM broker without a broker URL. No test opens a network port.
 */
class JmsConfigTest {

    /** Key checked by the guard. */
    private static final String BROKER_URL_KEY = "spring.artemis.broker-url";

    /** Fragment of every rejection message. */
    private static final String NATIVE_MODE = "native Artemis mode";

    /** Placeholder value of the committed {@code application.yml}. */
    private static final String TODO_PLACEHOLDER = "TODO";

    /** Name of a test property source mapped like the process environment. */
    private static final String TEST_SYSTEM_ENVIRONMENT = "test-systemEnvironment";

    /**
     * Packages of {@code EmbeddedJMS} and {@code EmbeddedActiveMQ}, the classes Spring Boot looks for when it
     * deduces the Artemis mode.
     */
    private static final String[] EMBEDDED_BROKER_PACKAGES = {
        "org.apache.activemq.artemis.jms.server.embedded",
        "org.apache.activemq.artemis.core.server.embedded"};

    /** Context of {@code JmsConfig} and the Artemis and JMS auto-configurations, without any profile file. */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ArtemisAutoConfiguration.class, JmsAutoConfiguration.class))
            .withUserConfiguration(JmsConfig.class)
            .withPropertyValues("jms-connector.persistent-delivery=true");

    /**
     * Returns the runner in native mode with {@code spring.artemis.broker-url} set to {@code brokerUrl} exactly,
     * whitespace included.
     *
     * @param brokerUrl the raw broker URL
     * @return the configured runner
     */
    private ApplicationContextRunner nativeMode(String brokerUrl) {
        return contextRunner
                .withPropertyValues("spring.artemis.mode=native")
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("brokerUrl", Map.of(BROKER_URL_KEY, brokerUrl))));
    }

    /**
     * Returns the runner in native mode with {@code SPRING_ARTEMIS_BROKERURL} set in a property source mapped like
     * the process environment.
     *
     * @param brokerUrl the raw broker URL
     * @return the configured runner
     */
    private ApplicationContextRunner nativeModeFromEnvironmentVariable(String brokerUrl) {
        return contextRunner
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(TEST_SYSTEM_ENVIRONMENT, Map.of(
                                "SPRING_ARTEMIS_MODE", "native",
                                "SPRING_ARTEMIS_BROKERURL", brokerUrl))));
    }

    /**
     * Asserts that startup failed with the guard's rejection and that nothing in it repeats {@code value}. A blank
     * value and the {@code TODO} placeholder, which the message names, are not searched for.
     *
     * @param context the context under test
     * @param value   the configured broker URL
     */
    private static void assertRejected(AssertableApplicationContext context, String value) {
        assertThat(context).hasFailed();
        Throwable failure = context.getStartupFailure();
        assertThat(failure)
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasNoCause()
                .hasMessageStartingWith("Property '" + BROKER_URL_KEY + "' ")
                .hasMessageContaining(NATIVE_MODE);
        assertThat(failure.getSuppressed()).isEmpty();
        String trimmed = value.trim();
        if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase(TODO_PLACEHOLDER)) {
            assertThat(failure.getMessage()).doesNotContain(trimmed);
        }
    }

    /**
     * Asserts that the context started with Spring Boot's connection factory and the beans of {@code JmsConfig}.
     *
     * @param context the context under test
     */
    private static void assertStarted(AssertableApplicationContext context) {
        assertThat(context).hasNotFailed()
                .hasSingleBean(ConnectionFactory.class)
                .hasSingleBean(JmsConfig.ArtemisBrokerUrlGuard.class)
                .hasBean("redeliveryListenerContainerFactory")
                .hasBean("topicJmsTemplate")
                .hasBean("queueJmsTemplate");
        assertThat(context.getBean("redeliveryListenerContainerFactory"))
                .isInstanceOf(DefaultJmsListenerContainerFactory.class);
    }

    /**
     * Returns the static transports of the Artemis connection factory behind Spring Boot's caching connection
     * factory.
     *
     * @param context the started context
     * @return the static transports
     */
    private static TransportConfiguration[] staticTransports(AssertableApplicationContext context) {
        CachingConnectionFactory caching = context.getBean(CachingConnectionFactory.class);
        ActiveMQConnectionFactory artemis = (ActiveMQConnectionFactory) caching.getTargetConnectionFactory();
        return artemis.getServerLocator().getStaticTransportConfigurations();
    }

    /**
     * Returns the server locator of the Artemis connection factory behind Spring Boot's caching connection factory.
     *
     * @param context the started context
     * @return the server locator
     */
    private static ServerLocator serverLocator(AssertableApplicationContext context) {
        CachingConnectionFactory caching = context.getBean(CachingConnectionFactory.class);
        return ((ActiveMQConnectionFactory) caching.getTargetConnectionFactory()).getServerLocator();
    }

    /**
     * Returns a guard outside a container whose environment sets native mode and {@code brokerUrl}.
     *
     * @param brokerUrl the broker URL
     * @return the guard
     */
    private static JmsConfig.ArtemisBrokerUrlGuard nativeModeGuard(String brokerUrl) {
        JmsConfig.ArtemisBrokerUrlGuard guard = new JmsConfig.ArtemisBrokerUrlGuard();
        guard.setEnvironment(new MockEnvironment()
                .withProperty("spring.artemis.mode", "native")
                .withProperty(BROKER_URL_KEY, brokerUrl));
        return guard;
    }

    /**
     * Intercepts the construction of {@link ActiveMQConnectionFactory} on the current thread: each constructed
     * factory returns a server locator without static transports whose discovery group has
     * {@code endpointFactory} as broadcast endpoint.
     *
     * @param endpointFactory the broadcast endpoint factory, or {@code null}
     * @return the construction control; the caller closes it
     */
    private static MockedConstruction<ActiveMQConnectionFactory> connectionFactoriesWithDiscoveryGroup(
            BroadcastEndpointFactory endpointFactory) {
        return mockConstruction(ActiveMQConnectionFactory.class, (factory, construction) -> {
            ServerLocator locator = mock(ServerLocator.class);
            when(locator.getDiscoveryGroupConfiguration())
                    .thenReturn(new DiscoveryGroupConfiguration().setBroadcastEndpointFactory(endpointFactory));
            when(factory.getServerLocator()).thenReturn(locator);
        });
    }

    /** Native mode with no {@code spring.artemis.broker-url} fails startup instead of using a default broker. */
    @Test
    void nativeModeWithoutBrokerUrlFailsNamingTheKey() {
        contextRunner.withPropertyValues("spring.artemis.mode=native").run(context -> {
            assertRejected(context, "");
            assertThat(context.getStartupFailure()).hasMessageContaining("missing or blank");
        });
    }

    /**
     * Native mode with an empty or whitespace-only broker URL fails startup naming the key.
     *
     * @param brokerUrl the blank broker URL
     */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void nativeModeWithBlankBrokerUrlFailsNamingTheKey(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure()).hasMessageContaining("missing or blank");
        });
    }

    /**
     * Native mode with the {@code TODO} placeholder, in any case and surrounded by whitespace, fails startup with
     * the instruction to replace it with the broker URL.
     *
     * @param brokerUrl the placeholder as configured
     */
    @ParameterizedTest
    @ValueSource(strings = {"TODO", "todo", " ToDo "})
    void nativeModeWithTodoPlaceholderFailsAskingForTheBrokerUrl(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("TODO placeholder")
                    .hasMessageContaining("replace it with the URL of the Artemis broker");
        });
    }

    /**
     * Native mode with a URL that {@code new ActiveMQConnectionFactory(String)} rejects fails startup without the
     * value and without the Artemis exception as cause.
     *
     * @param brokerUrl the unparseable broker URL
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "foo://bar:1",
        "tcp://",
        "failover:(tcp://localhost:61616)",
        "localhost:61616",
        "tcp://ho st:1",
        " tcp://localhost:61616"})
    void nativeModeWithUrlTheArtemisClientRejectsFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure()).hasMessageContaining("not a URL the Artemis client accepts");
        });
    }

    /**
     * Native mode with a URL that Artemis parses into a TCP transport without a host fails startup without the
     * value, its {@code password} query parameter included.
     *
     * @param brokerUrl the host-less broker URL
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "tcp://localhost:abc?password=s3cr3t",
        "tcp:///x",
        "tcp://localhost:-5",
        "tcp://localhost:61616?host="})
    void nativeModeWithTcpTransportWithoutHostFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("TCP transport without a host")
                    .hasMessageNotContaining("s3cr3t");
        });
    }

    /**
     * Native mode with a TCP transport whose port is outside 1 to 65535 or not a number fails startup without the
     * value; a second transport is checked like the first.
     *
     * @param brokerUrl the broker URL with an unusable port
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "tcp://localhost:99999",
        "tcp://localhost:65536",
        "tcp://localhost:0",
        "tcp://localhost:61616?port=abc",
        "(tcp://a:61616,tcp://b:70000)?ha=true"})
    void nativeModeWithTcpTransportWithPortOutOfRangeFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("port is not an integer from 1 to 65535");
        });
    }

    /**
     * Native mode with a usable broker URL starts the context; no broker is reached.
     *
     * @param brokerUrl the usable broker URL
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "tcp://localhost:61616",
        "tcp://localhost",
        "tcp://localhost:1",
        "tcp://localhost:65535",
        "(tcp://a:1,tcp://b:2)?ha=true",
        "tcp://a:1?ha=true#tcp://b:2",
        "tcp://[::1]:61616",
        "tcp://host:61616?user=u&password=p",
        "udp://231.7.7.7:9876",
        "udp://[ff02::1]:9876",
        "udp://231.7.7.7:9876?localBindAddress=0.0.0.0&localBindPort=-1",
        "udp://231.7.7.7:9876?localBindPort=0",
        "udp://231.7.7.7:65535?localBindPort=65535",
        "udp://231.7.7.7:1?ha=true",
        "jgroups://mychannel?file=jgroups.xml",
        "vm://0",
        "vm://1"})
    void nativeModeWithUsableBrokerUrlStartsTheContext(String brokerUrl) {
        nativeMode(brokerUrl).run(JmsConfigTest::assertStarted);
    }

    /**
     * Native mode with an in-VM URL whose server id is not an integer from 0 to 2147483647 fails startup without the
     * value.
     *
     * @param brokerUrl the in-VM broker URL with an unusable server id
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "vm://abc",
        "vm://-1",
        "vm:///",
        "vm://null",
        "vm://localhost",
        "vm://2147483648"})
    void nativeModeWithInVmTransportWithUnusableServerIdFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("in-VM transport whose server id is not an integer from 0 to 2147483647");
        });
    }

    /**
     * Native mode with a UDP discovery URL that yields no group address fails startup without the value.
     *
     * @param brokerUrl the UDP broker URL without a usable group address
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "udp://231.7.7.7:abc",
        "udp:///x",
        "udp://231.7.7.7:-5",
        "udp://null:9876",
        "udp://231.7.7.7:9876?groupAddress="})
    void nativeModeWithUdpDiscoveryGroupWithoutGroupAddressFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("UDP discovery group without a group address")
                    .hasMessageNotContaining("231.7.7.7");
        });
    }

    /**
     * Native mode with a UDP discovery URL whose group port is missing or outside 1 to 65535 fails startup without
     * the value.
     *
     * @param brokerUrl the UDP broker URL with an unusable group port
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "udp://231.7.7.7:99999",
        "udp://231.7.7.7",
        "udp://231.7.7.7:0",
        "udp://231.7.7.7:65536",
        "udp://231.7.7.7:9876?groupPort=abc"})
    void nativeModeWithUdpDiscoveryGroupWithUnusableGroupPortFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("UDP discovery group whose group port is not an integer from 1 to 65535")
                    .hasMessageNotContaining("231.7.7.7");
        });
    }

    /**
     * Native mode with a UDP discovery URL whose {@code localBindAddress} is blank or {@code null} fails startup
     * without the value.
     *
     * @param brokerUrl the UDP broker URL with an unusable local bind address
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "udp://231.7.7.7:9876?localBindAddress=",
        "udp://231.7.7.7:9876?localBindAddress=null"})
    void nativeModeWithUdpDiscoveryGroupWithUnusableLocalBindAddressFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("UDP discovery group whose local bind address is blank or null")
                    .hasMessageNotContaining("231.7.7.7");
        });
    }

    /**
     * Native mode with a UDP discovery URL whose {@code localBindPort} is neither -1 nor an integer from 0 to 65535,
     * a non-numeric value included, fails startup without the value.
     *
     * @param brokerUrl the UDP broker URL with an unusable local bind port
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "udp://231.7.7.7:9876?localBindPort=99999",
        "udp://231.7.7.7:9876?localBindPort=65536",
        "udp://231.7.7.7:9876?localBindPort=-2",
        "udp://231.7.7.7:9876?localBindPort=abc",
        "udp://231.7.7.7:9876?localBindPort="})
    void nativeModeWithUdpDiscoveryGroupWithUnusableLocalBindPortFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("UDP discovery group whose local bind port is neither -1 nor an integer "
                            + "from 0 to 65535")
                    .hasMessageNotContaining("231.7.7.7");
        });
    }

    /**
     * Native mode with a JGroups discovery URL without a channel name fails startup without the value.
     *
     * @param brokerUrl the JGroups broker URL without a usable channel name
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "jgroups:///x?file=jgroups.xml",
        "jgroups://?file=jgroups.xml",
        "jgroups://null?file=jgroups.xml"})
    void nativeModeWithJGroupsDiscoveryGroupWithoutChannelNameFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("JGroups discovery group without a channel name")
                    .hasMessageNotContaining("jgroups.xml");
        });
    }

    /**
     * Native mode with a JGroups discovery URL without a configuration file fails startup without the value.
     *
     * @param brokerUrl the JGroups broker URL without a usable {@code file} parameter
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "jgroups://mychannel",
        "jgroups://mychannel?file=",
        "jgroups://mychannel?file=null"})
    void nativeModeWithJGroupsDiscoveryGroupWithoutConfigurationFileFailsWithoutEchoingIt(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertRejected(context, brokerUrl);
            assertThat(context.getStartupFailure())
                    .hasMessageContaining("JGroups discovery group without a configuration file in its file parameter")
                    .hasMessageNotContaining("mychannel");
        });
    }

    /**
     * Native mode with a usable discovery URL starts the context with the discovery group configured and not
     * started, so no multicast group or JGroups channel is joined.
     *
     * @param brokerUrl the usable discovery broker URL
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "udp://231.7.7.7:9876",
        "udp://[ff02::1]:9876",
        "udp://231.7.7.7:9876?localBindAddress=0.0.0.0&localBindPort=-1",
        "jgroups://mychannel?file=jgroups.xml"})
    void nativeModeWithUsableDiscoveryUrlStartsTheContextWithoutStartingTheDiscoveryGroup(String brokerUrl) {
        nativeMode(brokerUrl).run(context -> {
            assertStarted(context);
            ServerLocator locator = serverLocator(context);
            assertThat(locator.getDiscoveryGroupConfiguration()).isNotNull();
            assertThat(locator).isInstanceOf(ServerLocatorImpl.class);
            assertThat(((ServerLocatorImpl) locator).getDiscoveryGroup()).isNull();
        });
    }

    /** A checked UDP or JGroups discovery URL reaches Spring Boot's connection factory unchanged. */
    @Test
    void nativeModeKeepsTheConfiguredDiscoveryGroupForSpringBootsConnectionFactory() {
        nativeMode("udp://231.7.7.7:9876?localBindAddress=0.0.0.0&localBindPort=-1").run(context -> {
            assertStarted(context);
            assertThat(serverLocator(context).getDiscoveryGroupConfiguration().getBroadcastEndpointFactory())
                    .isInstanceOfSatisfying(UDPBroadcastEndpointFactory.class, udp -> {
                        assertThat(udp.getGroupAddress()).isEqualTo("231.7.7.7");
                        assertThat(udp.getGroupPort()).isEqualTo(9876);
                        assertThat(udp.getLocalBindAddress()).isEqualTo("0.0.0.0");
                        assertThat(udp.getLocalBindPort()).isEqualTo(-1);
                    });
        });
        nativeMode("jgroups://mychannel?file=jgroups.xml").run(context -> {
            assertStarted(context);
            assertThat(serverLocator(context).getDiscoveryGroupConfiguration().getBroadcastEndpointFactory())
                    .isInstanceOfSatisfying(JGroupsFileBroadcastEndpointFactory.class, jgroups -> {
                        assertThat(jgroups.getChannelName()).isEqualTo("mychannel");
                        assertThat(jgroups.getFile()).isEqualTo("jgroups.xml");
                    });
        });
    }

    /** A checked broker URL reaches Spring Boot's connection factory unchanged. */
    @Test
    void nativeModeKeepsTheConfiguredBrokerUrlForSpringBootsConnectionFactory() {
        nativeMode("tcp://broker.example:61617").run(context -> {
            assertStarted(context);
            TransportConfiguration[] transports = staticTransports(context);
            assertThat(transports).hasSize(1);
            assertThat(transports[0].getParams())
                    .containsEntry("host", "broker.example")
                    .containsEntry("port", "61617");
        });
    }

    /** An empty {@code SPRING_ARTEMIS_BROKERURL} fails startup instead of selecting Spring Boot's default broker. */
    @Test
    void emptyEnvironmentVariableBrokerUrlFailsInsteadOfSelectingTheDefaultBroker() {
        nativeModeFromEnvironmentVariable("").run(context -> {
            assertRejected(context, "");
            assertThat(context.getStartupFailure()).hasMessageContaining("missing or blank");
        });
    }

    /** {@code SPRING_ARTEMIS_BROKERURL=TODO} fails startup as the placeholder. */
    @Test
    void todoEnvironmentVariableBrokerUrlFails() {
        nativeModeFromEnvironmentVariable("TODO").run(context -> {
            assertRejected(context, "TODO");
            assertThat(context.getStartupFailure()).hasMessageContaining("TODO placeholder");
        });
    }

    /** A usable {@code SPRING_ARTEMIS_BROKERURL} starts the context with that broker URL. */
    @Test
    void usableEnvironmentVariableBrokerUrlStartsTheContextWithThatBroker() {
        nativeModeFromEnvironmentVariable("tcp://env-broker.example:61618").run(context -> {
            assertStarted(context);
            assertThat(staticTransports(context)[0].getParams())
                    .containsEntry("host", "env-broker.example")
                    .containsEntry("port", "61618");
        });
    }

    /** The committed {@code application.yml} alone, native mode with {@code broker-url: TODO}, fails startup. */
    @Test
    void committedApplicationYamlFailsOnItsTodoPlaceholder() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ArtemisAutoConfiguration.class, JmsAutoConfiguration.class))
                .withUserConfiguration(JmsConfig.class)
                .run(context -> {
                    assertRejected(context, "TODO");
                    assertThat(context.getStartupFailure()).hasMessageContaining("TODO placeholder");
                });
    }

    /**
     * The committed {@code application.yml} with the {@code test} profile starts the embedded in-VM broker; the
     * {@code TODO} broker URL is not checked.
     */
    @Test
    void testProfileStartsTheEmbeddedBrokerWithoutBrokerUrl() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withConfiguration(AutoConfigurations.of(ArtemisAutoConfiguration.class, JmsAutoConfiguration.class))
                .withUserConfiguration(JmsConfig.class)
                .run(context -> {
                    assertStarted(context);
                    assertThat(context.getEnvironment().getProperty("spring.artemis.mode")).isEqualTo("embedded");
                    TransportConfiguration[] transports = staticTransports(context);
                    assertThat(transports).hasSize(1);
                    assertThat(transports[0].getFactoryClassName())
                            .isEqualTo("org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnectorFactory");
                });
    }

    /** Embedded mode with the settings of {@code application-test.yml} and no broker URL starts the context. */
    @Test
    void embeddedModeWithoutBrokerUrlStartsTheEmbeddedBroker() {
        contextRunner
                .withPropertyValues(
                        "spring.artemis.mode=embedded",
                        "spring.artemis.embedded.enabled=true",
                        "spring.artemis.embedded.persistent=false",
                        "spring.artemis.embedded.queues=in,ActiveMQ.DLQ",
                        "spring.artemis.embedded.topics=topic1")
                .run(JmsConfigTest::assertStarted);
    }

    /** Embedded mode does not check the broker URL, the {@code TODO} placeholder included. */
    @Test
    void embeddedModeIgnoresTodoBrokerUrl() {
        contextRunner
                .withPropertyValues("spring.artemis.mode=embedded", "spring.artemis.embedded.persistent=false",
                        BROKER_URL_KEY + "=TODO")
                .run(JmsConfigTest::assertStarted);
    }

    /** With no mode set and the embedded broker on the class path, the mode is embedded and nothing is checked. */
    @Test
    void unsetModeWithEmbeddedBrokerOnTheClassPathIsDeducedEmbedded() {
        contextRunner
                .withPropertyValues("spring.artemis.embedded.persistent=false")
                .run(JmsConfigTest::assertStarted);
    }

    /** With no mode set and {@code spring.artemis.embedded.enabled=false}, the mode is native and checked. */
    @Test
    void unsetModeWithEmbeddedBrokerDisabledIsDeducedNative() {
        contextRunner
                .withPropertyValues("spring.artemis.embedded.enabled=false")
                .run(context -> assertRejected(context, ""));
    }

    /** With no mode set and no embedded broker class, as in the packaged application, the mode is native. */
    @Test
    void unsetModeWithoutEmbeddedBrokerClassesIsDeducedNative() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(EMBEDDED_BROKER_PACKAGES))
                .run(context -> assertRejected(context, ""));
    }

    /** With no mode set and no embedded broker class, a usable broker URL starts the context. */
    @Test
    void unsetModeWithoutEmbeddedBrokerClassesAcceptsUsableBrokerUrl() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(EMBEDDED_BROKER_PACKAGES))
                .withPropertyValues(BROKER_URL_KEY + "=tcp://localhost:61616")
                .run(JmsConfigTest::assertStarted);
    }

    /** The guard fails when the container has not set its environment. */
    @Test
    void guardWithoutEnvironmentFails() {
        JmsConfig.ArtemisBrokerUrlGuard guard = new JmsConfig.ArtemisBrokerUrlGuard();
        assertThatIllegalStateException()
                .isThrownBy(() -> guard.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .withMessage("ArtemisBrokerUrlGuard has no Environment; "
                        + "setEnvironment must be called before it runs");
    }

    /**
     * Outside a container the guard rejects {@code TODO}, accepts a usable URL with the mode in upper case, and
     * leaves the bean factory without bean definitions or singletons.
     */
    @Test
    void guardOutsideContainerRejectsTodoAndLeavesBeanFactoryUnchangedOnUsableUrl() {
        JmsConfig.ArtemisBrokerUrlGuard rejecting = new JmsConfig.ArtemisBrokerUrlGuard();
        rejecting.setEnvironment(new MockEnvironment()
                .withProperty("spring.artemis.mode", "native")
                .withProperty(BROKER_URL_KEY, "TODO"));
        assertThatIllegalStateException()
                .isThrownBy(() -> rejecting.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .withMessageStartingWith("Property 'spring.artemis.broker-url' is the TODO placeholder");

        JmsConfig.ArtemisBrokerUrlGuard accepting = new JmsConfig.ArtemisBrokerUrlGuard();
        accepting.setEnvironment(new MockEnvironment()
                .withProperty("spring.artemis.mode", "NATIVE")
                .withProperty(BROKER_URL_KEY, "tcp://localhost:61616"));
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        accepting.postProcessBeanFactory(beanFactory);
        assertThat(beanFactory.getBeanDefinitionCount()).isZero();
        assertThat(beanFactory.getSingletonCount()).isZero();
    }

    /**
     * A discovery group whose broadcast endpoint is neither UDP nor a JGroups configuration file, or that has none,
     * is rejected, and the connection factory built for the check is closed.
     */
    @Test
    void guardRejectsBroadcastEndpointOfAnotherKindAndClosesTheConnectionFactory() {
        for (BroadcastEndpointFactory endpointFactory : Arrays.asList(mock(BroadcastEndpointFactory.class), null)) {
            try (MockedConstruction<ActiveMQConnectionFactory> construction =
                         connectionFactoriesWithDiscoveryGroup(endpointFactory)) {
                JmsConfig.ArtemisBrokerUrlGuard guard = nativeModeGuard("udp://231.7.7.7:9876");
                assertThatIllegalStateException()
                        .isThrownBy(() -> guard.postProcessBeanFactory(new DefaultListableBeanFactory()))
                        .withNoCause()
                        .withMessageStartingWith("Property 'spring.artemis.broker-url' names a discovery group whose "
                                + "broadcast endpoint is neither UDP nor a JGroups configuration file")
                        .withMessageContaining(NATIVE_MODE)
                        .withMessageNotContaining("231.7.7.7");
                assertThat(construction.constructed()).hasSize(1);
                verify(construction.constructed().get(0)).close();
            }
        }
    }

    /** The connection factory built for the check is closed when a discovery URL passes and when it fails. */
    @Test
    void guardClosesTheConnectionFactoryOfAcceptedAndRejectedDiscoveryUrls() {
        UDPBroadcastEndpointFactory usable = new UDPBroadcastEndpointFactory()
                .setGroupAddress("231.7.7.7")
                .setGroupPort(9876);
        try (MockedConstruction<ActiveMQConnectionFactory> construction =
                     connectionFactoriesWithDiscoveryGroup(usable)) {
            nativeModeGuard("udp://231.7.7.7:9876").postProcessBeanFactory(new DefaultListableBeanFactory());
            assertThat(construction.constructed()).hasSize(1);
            verify(construction.constructed().get(0)).close();
        }

        UDPBroadcastEndpointFactory withoutGroupPort = new UDPBroadcastEndpointFactory().setGroupAddress("231.7.7.7");
        try (MockedConstruction<ActiveMQConnectionFactory> construction =
                     connectionFactoriesWithDiscoveryGroup(withoutGroupPort)) {
            JmsConfig.ArtemisBrokerUrlGuard guard = nativeModeGuard("udp://231.7.7.7");
            assertThatIllegalStateException()
                    .isThrownBy(() -> guard.postProcessBeanFactory(new DefaultListableBeanFactory()))
                    .withNoCause()
                    .withMessageContaining("group port is not an integer from 1 to 65535");
            assertThat(construction.constructed()).hasSize(1);
            verify(construction.constructed().get(0)).close();
        }
    }

    /**
     * Outside a container the guard rejects each discovery and in-VM form that the class description names and
     * accepts the usable ones, without the value in any message.
     */
    @Test
    void guardOutsideContainerChecksDiscoveryAndInVmUrls() {
        for (String rejected : new String[] {
            "udp://231.7.7.7:99999", "udp://231.7.7.7:abc", "udp:///x", "udp://231.7.7.7",
            "udp://231.7.7.7:9876?localBindPort=99999", "jgroups://mychannel", "vm://abc", "vm://-1"}) {
            JmsConfig.ArtemisBrokerUrlGuard guard = nativeModeGuard(rejected);
            assertThatIllegalStateException()
                    .as(rejected)
                    .isThrownBy(() -> guard.postProcessBeanFactory(new DefaultListableBeanFactory()))
                    .withNoCause()
                    .withMessageStartingWith("Property '" + BROKER_URL_KEY + "' ")
                    .withMessageContaining(NATIVE_MODE)
                    .withMessageNotContaining(rejected);
        }
        for (String accepted : new String[] {
            "udp://231.7.7.7:9876", "udp://[ff02::1]:9876",
            "udp://231.7.7.7:9876?localBindAddress=0.0.0.0&localBindPort=-1",
            "jgroups://mychannel?file=jgroups.xml", "vm://0"}) {
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            nativeModeGuard(accepted).postProcessBeanFactory(beanFactory);
            assertThat(beanFactory.getBeanDefinitionCount()).as(accepted).isZero();
            assertThat(beanFactory.getSingletonCount()).as(accepted).isZero();
        }
    }
}
