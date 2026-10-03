package com.mulesoft.examples.jms_message_rollback_and_redelivery.service;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;

/**
 * Body of the flow {@code JMSRedeliver} [jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:21-48].
 *
 * <p>{@code listener.RedeliveryListener} receives each delivery of a message from queue {@code in} in a
 * transacted session and calls {@link #jmsRedeliver(Object, int)} with the message body and its
 * {@code JMSXDeliveryCount}. The delivery counts this class sees follow the listener-side redelivery policy
 * {@code config.RedeliveryDelayPolicy}: 5000 ms before delivery 2, 2000 ms before each later delivery and at most
 * 5 redeliveries (D-024). The listener, not this class, reads the delivery count, waits the redelivery delay and
 * decides the dead-letter hand-off to {@link #moveToDeadLetterQueue(Object)} (D-465).
 *
 * <p>{@link #jmsRedeliver(Object, int)} runs these steps in order:
 * <ol>
 *   <li>Connector redelivery check, the {@code maxRedelivery="5"} of {@code jms:activemq-connector} [:19]: when
 *       {@code jms-connector.max-redelivery} is 0 or more and the redelivery count {@code deliveryCount - 1} is
 *       greater than it, an {@link IllegalStateException} is raised (D-465).</li>
 *   <li>{@code choice} [:26-33]: delivery 5 logs {@code Transaction without errors} at INFO [:27-28]; every
 *       other delivery raises {@link MyException} [:30-32].</li>
 *   <li>Outbound endpoint [:35-37]: the payload is published unchanged to the topic named by
 *       {@code jms-redeliver.topic} ({@code topic1}) through the {@code topicJmsTemplate} bean. On the listener
 *       thread the send joins the listener's transacted session ({@code ALWAYS_JOIN}).</li>
 *   <li>{@code choice-exception-strategy} [:39-47]: an exception whose cause chain holds a {@link MyException}
 *       takes the rollback branch [:44-46]: it logs
 *       {@code Entered rollback exception strategy. The message rolls back to its original state for reprocessing.}
 *       at INFO and rethrows that {@link MyException}; the listener container then rolls the session back and the
 *       broker redelivers the message. Any other exception takes the catch branch [:40-42]: it logs
 *       {@code Entered catch exception strategy. The transaction is commited.} at INFO and the method returns
 *       normally; the listener container then commits the session.</li>
 * </ol>
 *
 * <p>The class logger emits exactly these three INFO messages, the texts of the original's loggers at
 * [:28], [:41] and [:45], and nothing else at INFO or above. The class holds no mutable state; one instance
 * serves every listener thread. This package is held to the JaCoCo line coverage rule (D-049).
 *
 * <p>Usage, from the transacted listener method:
 * <pre>{@code
 * redeliveryService.jmsRedeliver(message.getBody(Object.class), message.getIntProperty("JMSXDeliveryCount"));
 * }</pre>
 */
@Service
public class RedeliveryService {

    /** Name of the queue that receives a message whose redelivery ceiling is reached (D-465). */
    public static final String DEAD_LETTER_QUEUE = "ActiveMQ.DLQ";

    /** Logger of the three INFO texts of the flow; it emits nothing else at INFO or above. */
    private static final Logger LOG = LoggerFactory.getLogger(RedeliveryService.class);

    /** Delivery count on which the flow publishes, the {@code JMSXDeliveryCount==5} of [:27]. */
    private static final int PUBLISH_ON_DELIVERY = 5;

    /** INFO text of the {@code when} branch [jms-redelivery.xml:28]. */
    private static final String TRANSACTION_WITHOUT_ERRORS = "Transaction without errors";

    /** INFO text of the catch branch [jms-redelivery.xml:41]. */
    private static final String COMMIT_TEXT = "Entered catch exception strategy. The transaction is commited.";

    /** INFO text of the rollback branch [jms-redelivery.xml:45]. */
    private static final String ROLLBACK_TEXT =
            "Entered rollback exception strategy. The message rolls back to its original state for reprocessing.";

    /** Template that publishes to the outbound topic. */
    private final JmsTemplate topicJmsTemplate;

    /** Template that sends to {@value #DEAD_LETTER_QUEUE}. */
    private final JmsTemplate queueJmsTemplate;

    /** Name of the outbound topic, {@code jms-redeliver.topic}. */
    private final String topic;

    /** Limit of the connector redelivery check, {@code jms-connector.max-redelivery}; negative turns it off. */
    private final int maxRedelivery;

    /**
     * Creates the service.
     *
     * @param topicJmsTemplate the template that sends to topics, bean {@code topicJmsTemplate} of
     *                         {@code config.JmsConfig}
     * @param queueJmsTemplate the template that sends to queues, bean {@code queueJmsTemplate} of
     *                         {@code config.JmsConfig}
     * @param topic            name of the outbound topic, from {@code jms-redeliver.topic}
     * @param maxRedelivery    the connector's redelivery limit, from {@code jms-connector.max-redelivery}; a
     *                         negative value turns the connector redelivery check off
     * @throws NullPointerException when {@code topicJmsTemplate}, {@code queueJmsTemplate} or {@code topic} is
     *                              {@code null}
     */
    public RedeliveryService(@Qualifier("topicJmsTemplate") JmsTemplate topicJmsTemplate,
                             @Qualifier("queueJmsTemplate") JmsTemplate queueJmsTemplate,
                             @Value("${jms-redeliver.topic}") String topic,
                             @Value("${jms-connector.max-redelivery}") int maxRedelivery) {
        this.topicJmsTemplate = Objects.requireNonNull(topicJmsTemplate, "topicJmsTemplate");
        this.queueJmsTemplate = Objects.requireNonNull(queueJmsTemplate, "queueJmsTemplate");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.maxRedelivery = maxRedelivery;
    }

    /**
     * Runs the body of the flow {@code JMSRedeliver} for one delivery of a message.
     *
     * <ul>
     *   <li>{@code deliveryCount - 1} greater than a non-negative {@code jms-connector.max-redelivery}: the
     *       {@link IllegalStateException} of the connector redelivery check takes the catch branch; the method logs
     *       the commit text and returns normally without sending (D-465).</li>
     *   <li>{@code deliveryCount} equal to 5: logs {@code Transaction without errors} and sends {@code payload}
     *       unchanged to the topic {@code jms-redeliver.topic}; a {@code String} payload is sent as a
     *       {@code TextMessage}. A failure of that send takes the catch branch: the method logs the commit text and
     *       returns normally.</li>
     *   <li>Any other {@code deliveryCount}: raises {@link MyException}, which takes the rollback branch; the
     *       method logs the rollback text and throws that exception without sending.</li>
     * </ul>
     *
     * @param payload       the message body, passed to the topic template unchanged
     * @param deliveryCount the {@code JMSXDeliveryCount} of the delivery, 1 for the first delivery
     * @throws MyException on the rollback branch: the {@link MyException} found in the cause chain of the
     *                     exception the flow body raised, never a wrapper
     */
    public void jmsRedeliver(Object payload, int deliveryCount) throws MyException {
        try {
            // Connector redelivery check [jms-redelivery.xml:19]; IllegalStateException stands in for the
            // connector's redelivery-exceeded exception (D-465).
            if (maxRedelivery >= 0 && deliveryCount - 1 > maxRedelivery) {
                throw new IllegalStateException("Delivery count " + deliveryCount
                        + " exceeds maxRedelivery " + maxRedelivery + " of the JMS connector");
            }
            // choice [jms-redelivery.xml:26-33]
            if (deliveryCount == PUBLISH_ON_DELIVERY) {
                LOG.info(TRANSACTION_WITHOUT_ERRORS);
            } else {
                throw new MyException();
            }
            // Outbound endpoint, topic, ALWAYS_JOIN [jms-redelivery.xml:35-37]
            topicJmsTemplate.convertAndSend(topic, payload);
        } catch (Exception e) {
            // rollback-exception-strategy when causedBy(MyException) [jms-redelivery.xml:44-46]
            if (causedBy(e, MyException.class)) {
                LOG.info(ROLLBACK_TEXT);
                throw firstInCauseChain(e, MyException.class);
            }
            // catch-exception-strategy when !causedBy(MyException) [jms-redelivery.xml:40-42]
            LOG.info(COMMIT_TEXT);
        }
    }

    /**
     * Sends {@code payload} unchanged to the queue {@value #DEAD_LETTER_QUEUE} through the {@code queueJmsTemplate}
     * bean. On the listener thread the send joins the listener's transacted session and becomes visible when the
     * listener container commits it (D-465). Logs nothing.
     *
     * @param payload the message body, passed to the queue template unchanged
     * @throws org.springframework.jms.JmsException when the send fails; the exception propagates unchanged
     */
    public void moveToDeadLetterQueue(Object payload) {
        queueJmsTemplate.convertAndSend(DEAD_LETTER_QUEUE, payload);
    }

    /**
     * Tells whether {@code t} or any exception in its cause chain is an instance of {@code type}, the MEL
     * {@code exception.causedBy(...)} of [jms-redelivery.xml:40] and [:44]. The walk visits {@code t},
     * {@code t.getCause()} and onwards, and stops at the end of the chain or at the first exception it has already
     * visited. The method has no side effects.
     *
     * @param t    the exception to inspect, or {@code null}
     * @param type the exception type to look for
     * @return {@code true} as soon as an exception of the chain is an instance of {@code type}; {@code false} when
     *         none is, and when {@code t} is {@code null}
     */
    static boolean causedBy(Throwable t, Class<? extends Throwable> type) {
        return firstInCauseChain(t, type) != null;
    }

    /**
     * Returns the first exception of the cause chain of {@code t} that is an instance of {@code type}. The walk
     * visits {@code t}, {@code t.getCause()} and onwards, and stops at the end of the chain or at the first
     * exception it has already visited, compared by identity.
     *
     * @param t    the exception to inspect, or {@code null}
     * @param type the exception type to look for
     * @param <T>  the exception type to look for
     * @return the first matching exception, {@code t} itself when it matches; {@code null} when none matches and
     *         when {@code t} is {@code null}
     */
    private static <T extends Throwable> T firstInCauseChain(Throwable t, Class<T> type) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable element = t; element != null && visited.add(element); element = element.getCause()) {
            if (type.isInstance(element)) {
                return type.cast(element);
            }
        }
        return null;
    }
}
