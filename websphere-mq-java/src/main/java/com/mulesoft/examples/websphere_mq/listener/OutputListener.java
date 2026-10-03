package com.mulesoft.examples.websphere_mq.listener;

import java.nio.charset.StandardCharsets;

import jakarta.jms.BytesMessage;
import jakarta.jms.JMSException;
import jakarta.jms.Message;
import jakarta.jms.TextMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.support.converter.MessageConversionException;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.websphere_mq.service.WmqDequeueService;

/**
 * Inbound endpoint of the flow {@code Output} [websphere-mq/src/main/app/websphere-mq.xml:24-27].
 * Consumes the queue named by {@code wmq.out-queue} ({@code out}, :25) with automatic acknowledgement
 * through the non-transacted {@code outQueueListenerFactory} container, and hands the message text to
 * {@link WmqDequeueService#output(String)}, the {@code ajax:outbound-endpoint} of the channel
 * {@code /services/wmqExample/dequeue} (:26; D-027, D-598).
 *
 * <p>Outcome of one delivery:
 * <ul>
 *   <li>the text is bound and the service returns: {@link #output(Message)} returns and the container
 *       acknowledges the message;</li>
 *   <li>binding the text fails or the service throws, for example the {@link IllegalStateException}
 *       {@code The buffer cannot hold more than <size> objects.} of a full pre-subscription cache: the
 *       failure is logged at ERROR, {@link #output(Message)} returns normally and the container
 *       acknowledges the message; nothing is rolled back, retried or sent again (AAP 0.6.2, D-677).</li>
 * </ul>
 *
 * <p>The class holds no mutable state and contains no transformation, routing, caching or subscriber
 * handling; the subscriber set and the cache belong to {@link WmqDequeueService}.
 */
@Component
public class OutputListener {

    /** Logger of the failures of the flow {@code Output}; used at ERROR only. */
    private static final Logger LOG = LoggerFactory.getLogger(OutputListener.class);

    /** Publish side of the flow {@code Output}: the dequeue channel's subscribers and cache. */
    private final WmqDequeueService dequeueService;

    /**
     * Creates the listener.
     *
     * @param dequeueService the service that publishes each text on the dequeue channel
     */
    public OutputListener(WmqDequeueService dequeueService) {
        this.dequeueService = dequeueService;
    }

    /**
     * Processes one message from the queue {@code wmq.out-queue} on an auto-acknowledge session of the
     * {@code outQueueListenerFactory} container. Steps, in order:
     * <ol>
     *   <li>binds the text: the body of a {@link TextMessage} as it is, or the body of a
     *       {@link BytesMessage} decoded as UTF-8; any other message type fails with
     *       {@link MessageConversionException} {@code Unsupported JMS message type: <class name>}
     *       (D-677);</li>
     *   <li>calls {@link WmqDequeueService#output(String)} with that text, a {@code null} text
     *       included.</li>
     * </ol>
     * Any {@link Exception} from either step is logged at ERROR with the text
     * {@code Exception delivering message in flow Output: <exception message>} and the stack trace, and
     * the method returns normally (AAP 0.6.2, D-677).
     *
     * <p>Example: a {@link TextMessage} with the text {@code test - processed} reaches every connected
     * subscriber of {@code /services/wmqExample/dequeue}, or the cache when no page has subscribed since
     * startup.
     *
     * @param message the delivered JMS message
     */
    @JmsListener(destination = "${wmq.out-queue}", containerFactory = "outQueueListenerFactory")
    public void output(Message message) {
        try {
            String text = bindText(message);
            dequeueService.output(text);
        } catch (Exception e) {
            LOG.error("Exception delivering message in flow Output: {}", e.getMessage(), e);
        }
    }

    /**
     * Returns the text of {@code message}: {@link TextMessage#getText()} unchanged for a
     * {@link TextMessage}, and the whole body read with {@link BytesMessage#readBytes(byte[])} and
     * decoded as UTF-8 for a {@link BytesMessage} (D-677).
     *
     * @param message the delivered JMS message
     * @return the message text; {@code null} for a {@link TextMessage} without a body
     * @throws JMSException                when the JMS provider fails to read the body
     * @throws MessageConversionException when {@code message} is neither a {@link TextMessage} nor a
     *                                    {@link BytesMessage}, with the text
     *                                    {@code Unsupported JMS message type: <class name>}
     */
    private static String bindText(Message message) throws JMSException {
        if (message instanceof TextMessage textMessage) {
            return textMessage.getText();
        }
        if (message instanceof BytesMessage bytes) {
            byte[] body = new byte[(int) bytes.getBodyLength()];
            bytes.readBytes(body);
            return new String(body, StandardCharsets.UTF_8);
        }
        throw new MessageConversionException("Unsupported JMS message type: " + message.getClass().getName());
    }
}
