package com.mulesoft.examples.websphere_mq.service;

import java.util.Objects;

import com.mulesoft.examples.websphere_mq.config.WmqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

/**
 * Body of the Mule flow {@code MessageProcessor} [websphere-mq/src/main/app/websphere-mq.xml:15-23]: the
 * {@code test:component} (:19) and the outbound endpoint to the queue {@code out} (:20-22).
 *
 * <p>{@code listener.MessageProcessorListener} receives each text message from the queue {@code in} (:16-18)
 * in a transacted session of {@code config.WmqConfig.inQueueListenerFactory} and calls
 * {@link #messageProcessor(String)} with the message text on the listener thread. The listener rethrows every
 * exception this class raises, and the container then rolls the session back.
 *
 * <p>{@link #messageProcessor(String)} runs these steps in order:
 * <ol>
 *   <li>Logs one INFO line holding the full payload,
 *       {@code Message received in flow MessageProcessor. Content is: <payload>} ({@code logMessageDetails="true"},
 *       :19).</li>
 *   <li>Waits {@code test-component.wait-time} ms, 15000 in {@code application.yml} ({@code waitTime="15000"},
 *       :19). A value of 0 or less does not wait. An interrupt during the wait sets the thread's interrupt flag
 *       again and raises {@link IllegalStateException} with the text
 *       {@code Interrupted while waiting <wait-time> ms}; nothing is sent.</li>
 *   <li>Appends {@code test-component.append-string}, {@code " - processed"} in {@code application.yml}
 *       ({@code appendString}, :19). A {@code null} payload is appended as the text {@code null}.</li>
 *   <li>Sends the result as a text message to the queue named by {@code wmq.out-queue}, {@code out}
 *       ({@code JOIN_IF_POSSIBLE}, :20-22), through Boot's auto-configured {@link JmsTemplate} over the IBM MQ
 *       client (D-040). The message carries no reply-to destination and no message property. On the listener
 *       thread the send uses the listener's transacted session: the message reaches {@code out} when the
 *       container commits that session and is discarded when the container rolls it back (D-597).</li>
 *   <li>Returns the result.</li>
 * </ol>
 *
 * <p>A {@link org.springframework.jms.JmsException} raised by the send propagates unchanged; there is no retry.
 * The only exception this class catches is the {@link InterruptedException} of the wait. The class holds no
 * mutable state; one instance serves every consumer thread of the {@code in} container (D-595). This package is
 * held to the JaCoCo line coverage rule (D-049).
 *
 * <pre>{@code
 * MessageProcessingService service =
 *         new MessageProcessingService(jmsTemplate, properties, " - processed", 15000L);
 * String sent = service.messageProcessor("test"); // "test - processed", sent to out after 15000 ms
 * }</pre>
 */
@Service
public class MessageProcessingService {

    /** Writes the INFO line of each received payload. */
    private static final Logger LOG = LoggerFactory.getLogger(MessageProcessingService.class);

    /** Template that sends each result to the queue named by {@code wmq.out-queue}. */
    private final JmsTemplate jmsTemplate;

    /** Bound {@code wmq.*} keys; {@code outQueue()} names the destination queue. */
    private final WmqProperties properties;

    /** Text appended to each payload, {@code test-component.append-string}. */
    private final String appendString;

    /** Wait in milliseconds before the append, {@code test-component.wait-time}; 0 or less does not wait. */
    private final long waitTime;

    /**
     * Creates the service.
     *
     * @param jmsTemplate  the template that sends each result
     * @param properties   the bound {@code wmq.*} keys; {@code properties.outQueue()} is the destination queue
     * @param appendString the text appended to each payload, {@code test-component.append-string}
     * @param waitTime     the wait in milliseconds before the append, {@code test-component.wait-time}; 0 or
     *                     less does not wait
     * @throws NullPointerException when {@code jmsTemplate}, {@code properties} or {@code appendString} is
     *                              {@code null}
     */
    public MessageProcessingService(JmsTemplate jmsTemplate, WmqProperties properties,
            @Value("${test-component.append-string}") String appendString,
            @Value("${test-component.wait-time}") long waitTime) {
        this.jmsTemplate = Objects.requireNonNull(jmsTemplate, "jmsTemplate");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.appendString = Objects.requireNonNull(appendString, "appendString");
        this.waitTime = waitTime;
    }

    /**
     * Logs the payload, waits {@code waitTime} ms, appends {@code appendString} and sends the result to the
     * queue {@code out} within the caller's JMS session.
     *
     * @param payload the text of the message received from the queue {@code in}
     * @return the payload followed by {@code appendString}, the text sent to the queue {@code out}
     * @throws IllegalStateException when the thread is interrupted during the wait, with the text
     *                               {@code Interrupted while waiting <waitTime> ms} and the
     *                               {@link InterruptedException} as cause; the thread's interrupt flag is set
     *                               again and nothing is sent
     * @throws org.springframework.jms.JmsException when the send fails
     */
    public String messageProcessor(String payload) {
        LOG.info("Message received in flow MessageProcessor. Content is: {}", payload);
        if (waitTime > 0) {
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting " + waitTime + " ms", e);
            }
        }
        String result = payload + appendString;
        jmsTemplate.send(properties.outQueue(), session -> session.createTextMessage(result));
        return result;
    }
}
