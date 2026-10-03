package com.mulesoft.examples.websphere_mq.service;

import com.mulesoft.examples.websphere_mq.config.WmqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

/**
 * Body of the Mule flow {@code Input} [websphere-mq/src/main/app/websphere-mq.xml:8-14]: each text
 * posted to the AJAX channel {@code /services/wmqExample/enqueue} (:9) is sent as one JMS text
 * message to the WMQ queue {@code in} (:13).
 *
 * <p>Message contents:
 * <ul>
 *   <li>The body is the text, unchanged.</li>
 *   <li>No reply-to destination is set, the counterpart of the {@code MULE_REPLYTO} deletion
 *       (:10-12).</li>
 *   <li>No message property and no correlation id is set (D-081).</li>
 * </ul>
 *
 * <p>The send runs outside any transaction (:13) and is attempted once. The {@link JmsTemplate}
 * is Boot's template over the primary connection factory of {@code config.WmqConfig}; a send from
 * a request thread opens its own non-transacted session (D-040, D-597). The queue name is
 * {@link WmqProperties#inQueue()}, bound from {@code wmq.in-queue} ({@code in} in
 * {@code application.yml}).
 *
 * <p>Usage, from the handler of {@code POST /services/wmqExample/enqueue}:
 * <pre>{@code
 * enqueueService.input("hello"); // one TextMessage "hello" on queue "in"
 * }</pre>
 *
 * <p>The class holds no mutable state; one instance serves concurrent requests.
 */
@Service
public class WmqEnqueueService {

    /** Writes the DEBUG line of each send. */
    private static final Logger LOG = LoggerFactory.getLogger(WmqEnqueueService.class);

    /** Template that sends to the queue manager. */
    private final JmsTemplate jmsTemplate;

    /** The bound {@code wmq.*} keys; {@link WmqProperties#inQueue()} names the destination. */
    private final WmqProperties properties;

    /**
     * Creates the service.
     *
     * @param jmsTemplate template that sends to the queue manager
     * @param properties  the bound {@code wmq.*} keys; {@code properties.inQueue()} is the
     *                    destination queue
     */
    public WmqEnqueueService(JmsTemplate jmsTemplate, WmqProperties properties) {
        this.jmsTemplate = jmsTemplate;
        this.properties = properties;
    }

    /**
     * Runs the steps of {@code Input} after its AJAX inbound endpoint: sends {@code text} as a JMS
     * text message to the {@code in} queue without a reply-to destination, message properties or
     * correlation id (websphere-mq.xml:10-13, D-081). Writes one DEBUG line naming the queue
     * before the send; nothing is logged above DEBUG (D-695).
     *
     * @param text the posted channel text, passed to {@code Session.createTextMessage} unchanged;
     *             the controller passes {@code ""} for an absent body
     * @throws org.springframework.jms.JmsException when the session cannot be opened or the send
     *         fails; it propagates to the caller unchanged and the send is not retried
     */
    public void input(String text) {
        String destination = properties.inQueue();
        LOG.debug("Sending a text message to queue {}", destination);
        jmsTemplate.send(destination, session -> session.createTextMessage(text));
    }
}
