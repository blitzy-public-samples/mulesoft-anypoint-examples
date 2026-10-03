package com.mulesoft.examples.jms_message_rollback_and_redelivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.DeliveryMode;
import jakarta.jms.MessageListener;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.SimpleJmsListenerEndpoint;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.config.JmsConfig;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;

/**
 * Mockito unit tests of {@link RedeliveryService}, the body of the flow {@code JMSRedeliver}
 * [jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:21-48], of {@link MyException} and of the
 * beans of {@link JmsConfig}. Decisions: D-024, D-049, D-465.
 *
 * <p>No Spring context and no broker start. Both {@link JmsTemplate}s are Mockito mocks, the service is built with
 * the topic {@code topic1} and the connector redelivery limit 5, and the payload is {@code Message123}. The INFO
 * texts are read from a {@link ListAppender} attached to the {@code RedeliveryService} logger, whose level is INFO
 * during each test and restored afterwards.
 *
 * <p>Checked: the delivery-count choice [:26-33] and the publish to {@code topic1} on delivery 5 [:35-37]; the
 * rollback branch for an exception caused by {@link MyException} and the catch branch for any other exception
 * [:39-47] (D-024); the connector redelivery check above the limit [:19] and the send to {@code ActiveMQ.DLQ}
 * (D-465); the cause-chain test {@code causedBy} [:40, :44]; the error texts of {@link MyException}; and the
 * template and listener container settings of {@link JmsConfig} [:19-24, :35-37].
 */
public class RedeliveryServiceTest {

    /** INFO text of the {@code when} branch [jms-redelivery.xml:28]. */
    private static final String TRANSACTION_WITHOUT_ERRORS = "Transaction without errors";

    /** INFO text of the catch branch [jms-redelivery.xml:41]. */
    private static final String COMMIT_TEXT = "Entered catch exception strategy. The transaction is commited.";

    /** INFO text of the rollback branch [jms-redelivery.xml:45]. */
    private static final String ROLLBACK_TEXT =
            "Entered rollback exception strategy. The message rolls back to its original state for reprocessing.";

    /** Outbound topic, the value of {@code jms-redeliver.topic}. */
    private static final String TOPIC = "topic1";

    /** Inbound queue, the value of {@code jms-redeliver.queue}. */
    private static final String QUEUE = "in";

    /** Connector redelivery limit, the value of {@code jms-connector.max-redelivery}. */
    private static final int MAX_REDELIVERY = 5;

    /** Message body of the original integration test. */
    private static final String PAYLOAD = "Message123";

    /** Mock passed to the service as {@code topicJmsTemplate}. */
    private final JmsTemplate topicJmsTemplate = mock(JmsTemplate.class);

    /** Mock passed to the service as {@code queueJmsTemplate}. */
    private final JmsTemplate queueJmsTemplate = mock(JmsTemplate.class);

    /** Service under test. */
    private RedeliveryService service;

    /** Appender recording the events of the {@code RedeliveryService} logger. */
    private ListAppender<ILoggingEvent> appender;

    /** Logback logger of {@code RedeliveryService}. */
    private Logger serviceLogger;

    /** Level of {@link #serviceLogger} before the test. */
    private Level previousLevel;

    /**
     * Builds the service on the two template mocks with topic {@code topic1} and redelivery limit 5, sets the
     * service logger to INFO and attaches a started list appender to it.
     */
    @BeforeEach
    void setUp() {
        service = new RedeliveryService(topicJmsTemplate, queueJmsTemplate, TOPIC, MAX_REDELIVERY);

        serviceLogger = (Logger) LoggerFactory.getLogger(RedeliveryService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);

        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    /** Detaches and stops the list appender and restores the logger's previous level. */
    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    /**
     * Asserts that every event recorded on the {@code RedeliveryService} logger has level INFO and returns their
     * formatted messages.
     *
     * @return the formatted messages in logging order; empty when nothing was logged
     */
    private List<String> infoMessages() {
        assertThat(appender.list).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /**
     * Tells whether {@code t} or an exception of its cause chain is an instance of {@code type}. The walk stops at
     * the end of the chain or at the first exception already visited.
     *
     * @param t    the exception to inspect, or {@code null}
     * @param type the exception type to look for
     * @return {@code true} when an exception of the chain is an instance of {@code type}; {@code false} otherwise
     *         and when {@code t} is {@code null}
     */
    private static boolean chainContains(Throwable t, Class<? extends Throwable> type) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable element = t; element != null && visited.add(element); element = element.getCause()) {
            if (type.isInstance(element)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Delivery 5 logs only {@code Transaction without errors} and sends the payload unchanged once to
     * {@code topic1} through the topic template; the queue template is not used [:27-28, :35-37].
     *
     * @throws MyException never on this delivery
     */
    @Test
    @DisplayName("delivery 5 logs the success text and publishes the payload to topic1")
    public void deliveryFivePublishesToTopic() throws MyException {
        service.jmsRedeliver(PAYLOAD, 5);

        verify(topicJmsTemplate).convertAndSend(eq(TOPIC), same(PAYLOAD));
        verifyNoMoreInteractions(topicJmsTemplate);
        verifyNoInteractions(queueJmsTemplate);
        assertThat(infoMessages()).containsExactly(TRANSACTION_WITHOUT_ERRORS);
    }

    /**
     * Deliveries 1 to 4 throw a {@link MyException} with error text {@code test} and no cause, log only the
     * rollback text and use neither template [:30-32, :44-46] (D-024).
     *
     * @param deliveryCount the {@code JMSXDeliveryCount} of the delivery
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("deliveries 1 to 4 throw MyException, log the rollback text and publish nothing")
    public void deliveriesOneToFourRollBack(int deliveryCount) {
        Throwable thrown = catchThrowable(() -> service.jmsRedeliver(PAYLOAD, deliveryCount));

        assertThat(thrown).isExactlyInstanceOf(MyException.class).hasNoCause();
        assertThat(((MyException) thrown).getError()).isEqualTo("test");
        assertThat(infoMessages()).containsExactly(ROLLBACK_TEXT);
        verifyNoInteractions(topicJmsTemplate, queueJmsTemplate);
    }

    /**
     * Delivery 7, a redelivery count of 6 above the limit 5, returns normally, logs only the commit text and uses
     * neither template [:19, :40-42] (D-465).
     */
    @Test
    @DisplayName("delivery 7 above the redelivery limit 5 logs the commit text and publishes nothing")
    public void deliveryAboveCeilingCommits() {
        assertThatCode(() -> service.jmsRedeliver(PAYLOAD, 7)).doesNotThrowAnyException();

        assertThat(infoMessages()).containsExactly(COMMIT_TEXT);
        verifyNoInteractions(topicJmsTemplate, queueJmsTemplate);
    }

    /**
     * A {@link RuntimeException} {@code publish failed} from the topic send on delivery 5 returns normally after one
     * send attempt and logs {@code Transaction without errors}, then the commit text, and no rollback text
     * [:40-42].
     */
    @Test
    @DisplayName("a publish failure on delivery 5 logs the commit text and returns normally")
    public void publishFailureCommits() {
        doThrow(new RuntimeException("publish failed")).when(topicJmsTemplate).convertAndSend(TOPIC, PAYLOAD);

        assertThatCode(() -> service.jmsRedeliver(PAYLOAD, 5)).doesNotThrowAnyException();

        verify(topicJmsTemplate).convertAndSend(TOPIC, PAYLOAD);
        verifyNoMoreInteractions(topicJmsTemplate);
        verifyNoInteractions(queueJmsTemplate);
        assertThat(infoMessages()).containsExactly(TRANSACTION_WITHOUT_ERRORS, COMMIT_TEXT);
    }

    /**
     * A {@link RuntimeException} caused by a {@link MyException} from the topic send on delivery 5 throws that
     * {@link MyException} itself after one send attempt and logs {@code Transaction without errors}, then the
     * rollback text, and no commit text [:44-46].
     */
    @Test
    @DisplayName("a publish failure caused by MyException logs the rollback text and throws the MyException")
    public void publishFailureCausedByMyExceptionRollsBack() {
        MyException cause = new MyException();
        doThrow(new RuntimeException(cause)).when(topicJmsTemplate).convertAndSend(TOPIC, PAYLOAD);

        Throwable thrown = catchThrowable(() -> service.jmsRedeliver(PAYLOAD, 5));

        assertThat(chainContains(thrown, MyException.class)).isTrue();
        assertThat(thrown).isSameAs(cause);
        verify(topicJmsTemplate).convertAndSend(TOPIC, PAYLOAD);
        verifyNoMoreInteractions(topicJmsTemplate);
        verifyNoInteractions(queueJmsTemplate);
        assertThat(infoMessages()).containsExactly(TRANSACTION_WITHOUT_ERRORS, ROLLBACK_TEXT);
    }

    /**
     * {@code causedBy} is {@code false} for {@code null} and for a chain without {@link MyException}, and
     * {@code true} for a {@link MyException} as direct cause and as a cause two levels down [:40, :44].
     */
    @Test
    @DisplayName("causedBy finds MyException at any depth of the cause chain")
    public void causedByChecksCauseChain() {
        assertThat(RedeliveryService.causedBy(null, MyException.class)).isFalse();
        assertThat(RedeliveryService.causedBy(
                new RuntimeException(new IllegalStateException()), MyException.class)).isFalse();
        assertThat(RedeliveryService.causedBy(
                new RuntimeException(new MyException()), MyException.class)).isTrue();
        assertThat(RedeliveryService.causedBy(
                new RuntimeException(new IllegalStateException(new MyException())), MyException.class)).isTrue();
    }

    /**
     * {@code DEAD_LETTER_QUEUE} is {@code ActiveMQ.DLQ}, and {@code moveToDeadLetterQueue} sends the same payload
     * instance once to it through the queue template, uses no topic template and logs nothing (D-465).
     */
    @Test
    @DisplayName("moveToDeadLetterQueue sends the payload unchanged to queue ActiveMQ.DLQ")
    public void moveToDeadLetterQueueSendsToDlq() {
        assertThat(RedeliveryService.DEAD_LETTER_QUEUE).isEqualTo("ActiveMQ.DLQ");

        service.moveToDeadLetterQueue(PAYLOAD);

        verify(queueJmsTemplate).convertAndSend(eq("ActiveMQ.DLQ"), same(PAYLOAD));
        verifyNoMoreInteractions(queueJmsTemplate);
        verifyNoInteractions(topicJmsTemplate);
        assertThat(appender.list).isEmpty();
    }

    /**
     * {@code new MyException()} has error text {@code test} and no detail message; {@code new MyException("custom")}
     * has {@code custom} as both error text and detail message.
     */
    @Test
    @DisplayName("MyException holds the error text test by default and the given text otherwise")
    public void myExceptionErrorTexts() {
        MyException standard = new MyException();
        assertThat(standard.getError()).isEqualTo("test");
        assertThat(standard.getMessage()).isNull();

        MyException custom = new MyException("custom");
        assertThat(custom.getError()).isEqualTo("custom");
        assertThat(custom.getMessage()).isEqualTo("custom");
    }

    /**
     * With a mocked connection factory and persistent delivery on, both templates hold that factory, send in a
     * transacted session with explicit QoS and the persistent delivery mode; the topic template is in the topic
     * domain and the queue template in the queue domain. The listener container factory builds, for queue
     * {@code in}, a {@link DefaultMessageListenerContainer} on that factory with a transacted session, one
     * consumer, the queue domain, the endpoint's listener and no transaction manager. No container is initialized
     * or started and the connection factory is not used (D-024). The static guard method returns a
     * {@link JmsConfig.ArtemisBrokerUrlGuard}.
     */
    @Test
    @DisplayName("JmsConfig builds transacted persistent templates and a transacted single-consumer queue container")
    public void jmsConfigBeans() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        JmsConfig config = new JmsConfig();

        JmsTemplate topicTemplate = config.topicJmsTemplate(connectionFactory, true);
        JmsTemplate queueTemplate = config.queueJmsTemplate(connectionFactory, true);
        for (JmsTemplate template : List.of(topicTemplate, queueTemplate)) {
            assertThat(template.getConnectionFactory()).isSameAs(connectionFactory);
            assertThat(template.isSessionTransacted()).isTrue();
            assertThat(template.isExplicitQosEnabled()).isTrue();
            assertThat(template.getDeliveryMode()).isEqualTo(DeliveryMode.PERSISTENT);
        }
        assertThat(topicTemplate.isPubSubDomain()).isTrue();
        assertThat(queueTemplate.isPubSubDomain()).isFalse();

        DefaultJmsListenerContainerFactory factory = config.redeliveryListenerContainerFactory(connectionFactory);
        MessageListener listener = mock(MessageListener.class);
        SimpleJmsListenerEndpoint endpoint = new SimpleJmsListenerEndpoint();
        endpoint.setId("redeliveryListener");
        endpoint.setDestination(QUEUE);
        endpoint.setMessageListener(listener);

        DefaultMessageListenerContainer container = factory.createListenerContainer(endpoint);

        assertThat(container.getConnectionFactory()).isSameAs(connectionFactory);
        assertThat(container.isSessionTransacted()).isTrue();
        assertThat(container.getConcurrentConsumers()).isEqualTo(1);
        assertThat(container.getMaxConcurrentConsumers()).isEqualTo(1);
        assertThat(container.isPubSubDomain()).isFalse();
        assertThat(container.getDestinationName()).isEqualTo(QUEUE);
        assertThat(container.getMessageListener()).isSameAs(listener);
        assertThat(ReflectionTestUtils.getField(container, "transactionManager")).isNull();
        verifyNoInteractions(connectionFactory, listener);

        assertThat(JmsConfig.artemisBrokerUrlGuard()).isExactlyInstanceOf(JmsConfig.ArtemisBrokerUrlGuard.class);
    }
}
