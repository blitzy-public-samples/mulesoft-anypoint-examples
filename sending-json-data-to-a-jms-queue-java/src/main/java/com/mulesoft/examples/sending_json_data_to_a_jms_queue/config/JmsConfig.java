package com.mulesoft.examples.sending_json_data_to_a_jms_queue.config;

import jakarta.jms.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.core.JmsTemplate;

/**
 * Provides the {@link JmsTemplate} that sends point-to-point, non-transacted messages through the
 * auto-configured Artemis connection factory (D-080).
 *
 * <p>The broker and its {@code sales} queue are defined by the {@code spring.artemis.*} keys of
 * {@code application.yml}; this class declares no connection settings.
 */
@Configuration
public class JmsConfig {

    /**
     * Returns a {@link JmsTemplate} over {@code connectionFactory} that resolves destination names
     * to queues and sends each message outside a local session transaction.
     *
     * @param connectionFactory the connection factory that Spring Boot auto-configures for Artemis
     * @return the template used to send messages to the outbound queue
     */
    @Bean
    public JmsTemplate jmsTemplate(ConnectionFactory connectionFactory) {
        JmsTemplate template = new JmsTemplate(connectionFactory);
        template.setPubSubDomain(false);
        template.setSessionTransacted(false);
        return template;
    }
}
