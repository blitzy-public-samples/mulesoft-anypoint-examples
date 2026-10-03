package com.mulesoft.examples.sending_json_data_to_a_jms_queue.service;

import java.nio.charset.Charset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

/**
 * Body of the flow {@code json-to-jmsFlow}
 * [sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:5-11]: decodes the request body,
 * sends it as one text message to the configured queue and logs it.
 *
 * <p>The queue name is the {@code jms.outbound-endpoint.queue} key of {@code application.yml}. The
 * {@link JmsTemplate} is the application's point-to-point template on the embedded Artemis broker
 * (D-080). The INFO line and the failure path are D-273. This package is held to the JaCoCo line
 * coverage rule (D-049).
 *
 * <p>Usage, from the handler of {@code POST /sales}:
 * <pre>{@code
 * String responseBody = salesPublisher.jsonToJmsFlow(requestBytes, StandardCharsets.UTF_8);
 * }</pre>
 *
 * <p>The class holds no mutable state; one instance serves concurrent requests.
 */
@Service
public class SalesPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(SalesPublisher.class);

    private final JmsTemplate jmsTemplate;

    private final String queue;

    /**
     * Creates the publisher.
     *
     * @param jmsTemplate template that sends to the broker
     * @param queue name of the destination queue, from {@code jms.outbound-endpoint.queue}
     */
    public SalesPublisher(JmsTemplate jmsTemplate,
            @Value("${jms.outbound-endpoint.queue}") String queue) {
        this.jmsTemplate = jmsTemplate;
        this.queue = queue;
    }

    /**
     * Runs the steps of {@code json-to-jmsFlow} after its HTTP listener: decodes {@code body} with
     * {@code charset} (json-to-jms.xml:8), sends the text one-way as a single {@code TextMessage}
     * to the queue (json-to-jms.xml:9), then logs one INFO line with the queue name and the text
     * (json-to-jms.xml:10, D-273). The text is never trimmed, parsed, normalised or re-encoded.
     *
     * @param body raw request body bytes; an empty array for an empty request
     * @param charset charset of the request body
     * @return the decoded text, which the caller writes back as the response body
     * @throws org.springframework.jms.JmsException when the send fails; send failures propagate to
     *         the caller unchanged and no INFO line is written (D-273)
     */
    public String jsonToJmsFlow(byte[] body, Charset charset) {
        String text = new String(body, charset);
        jmsTemplate.convertAndSend(queue, text);
        LOG.info("Sent to JMS queue {}: {}", queue, text);
        return text;
    }
}
