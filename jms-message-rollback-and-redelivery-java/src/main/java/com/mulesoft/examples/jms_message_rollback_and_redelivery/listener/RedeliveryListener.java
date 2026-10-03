package com.mulesoft.examples.jms_message_rollback_and_redelivery.listener;

import jakarta.jms.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.jms_message_rollback_and_redelivery.config.RedeliveryDelayPolicy;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.exception.MyException;
import com.mulesoft.examples.jms_message_rollback_and_redelivery.service.RedeliveryService;

/**
 * Inbound endpoint of the flow {@code JMSRedeliver}
 * [jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:21-24]. Receives each delivery of a message
 * from the queue named by {@code jms-redeliver.queue} ({@code in}) in a locally transacted session of the
 * {@code redeliveryListenerContainerFactory} container, the {@code jms:transaction action="ALWAYS_BEGIN"} of the
 * inbound endpoint. It waits the listener-side redelivery delay of {@link RedeliveryDelayPolicy}, delegates the flow
 * body to {@link RedeliveryService#jmsRedeliver(Object, int)}, and hands a message that reached the redelivery
 * ceiling to the dead-letter queue {@value RedeliveryService#DEAD_LETTER_QUEUE}. See D-024 and D-465.
 *
 * <p>Outcome of one delivery:
 * <ul>
 *   <li>{@link #jmsRedeliver(Message)} returns normally: the container commits the session, and the receipt and
 *       every message the service sent on the listener thread become visible together;</li>
 *   <li>{@link #jmsRedeliver(Message)} throws: the container rolls the session back, the sends are discarded and
 *       the broker redelivers the message with {@code JMSXDeliveryCount} incremented.</li>
 * </ul>
 *
 * <p>With the {@code redelivery-policy.*} values of {@code application.yml}:
 * <ul>
 *   <li>deliveries 1 to 4: the service throws {@link MyException}, the ceiling is not reached, the listener
 *       rethrows it and the session rolls back;</li>
 *   <li>delivery 5: the service publishes the payload to the topic {@code jms-redeliver.topic} ({@code topic1})
 *       and returns, and the session commits;</li>
 *   <li>a message for which the service throws {@link MyException} on every delivery: delivery 6 reaches the
 *       ceiling, the payload is sent to {@value RedeliveryService#DEAD_LETTER_QUEUE} and the session commits;</li>
 *   <li>a failure other than {@link MyException} inside the flow body: the service logs the catch text and
 *       returns, and the session commits after that delivery.</li>
 * </ul>
 * Delivery 1 is processed at once, delivery 2 after 5000 ms and deliveries 3 to 6 after 2000 ms each.
 *
 * <p>The class contains no routing, transformation or JMS send of its own. It logs only at DEBUG, once per
 * dead-letter hand-off; the INFO texts of the flow come from {@link RedeliveryService}. It holds no mutable state.
 */
@Component
public class RedeliveryListener {

    /** Logger of the dead-letter hand-off; used at DEBUG only. */
    private static final Logger LOGGER = LoggerFactory.getLogger(RedeliveryListener.class);

    /** JMS-defined message property holding the delivery count, 1 for the first delivery. */
    private static final String DELIVERY_COUNT_PROPERTY = "JMSXDeliveryCount";

    /** Body of the flow {@code JMSRedeliver} and the dead-letter send. */
    private final RedeliveryService service;

    /** Listener-side redelivery delays and ceiling, bound from {@code redelivery-policy.*} (D-024). */
    private final RedeliveryDelayPolicy policy;

    /**
     * Creates the listener.
     *
     * @param service the service that runs the flow body and the dead-letter send
     * @param policy  the listener-side redelivery policy, the bean of the {@code redelivery-policy.*} record
     */
    public RedeliveryListener(RedeliveryService service, RedeliveryDelayPolicy policy) {
        this.service = service;
        this.policy = policy;
    }

    /**
     * Processes one delivery of a message from the queue {@code jms-redeliver.queue} inside the listener
     * container's transacted session. Steps, in order:
     * <ol>
     *   <li>reads the delivery count from {@code JMSXDeliveryCount}: 1 on the first delivery, incremented by the
     *       broker on every redelivery after a rollback;</li>
     *   <li>reads the body with {@link Message#getBody(Class)} as {@link Object}: a {@code String} for a
     *       {@code TextMessage}, the matching Java type for bytes, map and object messages;</li>
     *   <li>waits {@link RedeliveryDelayPolicy#delayBeforeDelivery(int)} milliseconds when that value is greater
     *       than 0 (D-024);</li>
     *   <li>calls {@link RedeliveryService#jmsRedeliver(Object, int)} with the body and the delivery count;</li>
     *   <li>on {@link MyException} from the service: when {@link RedeliveryDelayPolicy#ceilingReached(int)} is
     *       {@code true}, sends the body through {@link RedeliveryService#moveToDeadLetterQueue(Object)}, logs the
     *       hand-off at DEBUG and returns normally (D-465); otherwise rethrows the exception.</li>
     * </ol>
     * Any other exception propagates to the container unchanged.
     *
     * <p>Example with the defaults of {@code application.yml}: the first delivery of {@code Message123} calls the
     * service at once and rethrows its {@link MyException}; the fifth delivery, 5000 ms plus three times 2000 ms
     * after the first rollback, returns after the service has published {@code Message123} to {@code topic1}.
     *
     * @param message the delivered JMS message
     * @throws MyException          when the service throws it and the redelivery ceiling is not reached; the
     *                              container rolls the session back
     * @throws InterruptedException when the thread is interrupted during the redelivery delay; the interrupt
     *                              status is set again and the container rolls the session back
     * @throws Exception            when reading the delivery count or the body fails, or the service or the
     *                              dead-letter send fails with another exception; the container rolls the
     *                              session back
     */
    @JmsListener(destination = "${jms-redeliver.queue}", containerFactory = "redeliveryListenerContainerFactory")
    public void jmsRedeliver(Message message) throws Exception {
        int count = message.getIntProperty(DELIVERY_COUNT_PROPERTY);
        Object payload = message.getBody(Object.class);
        long delay = policy.delayBeforeDelivery(count);
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
        try {
            service.jmsRedeliver(payload, count);
        } catch (MyException e) {
            if (!policy.ceilingReached(count)) {
                throw e;
            }
            service.moveToDeadLetterQueue(payload);
            LOGGER.debug("Delivery {} reached the redelivery ceiling; message sent to {}", count,
                    RedeliveryService.DEAD_LETTER_QUEUE);
        }
    }
}
