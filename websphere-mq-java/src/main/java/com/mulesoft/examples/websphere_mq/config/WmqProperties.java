package com.mulesoft.examples.websphere_mq.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code wmq.*} keys of the IBM MQ connection and queue names that replace the
 * {@code wmq:connector} {@code wmqConnector} [websphere-mq/src/main/app/websphere-mq.xml:4] and the
 * queues of the flows {@code Input}, {@code MessageProcessor} and {@code Output}
 * [websphere-mq/src/main/app/websphere-mq.xml:8-27] (D-040). {@link #queue()} is the queue-manager
 * name, and {@link Ajax#cacheSize()} bounds the pre-subscription cache of the dequeue channel (D-027).
 *
 * <p>The record declares no default value; {@code application.yml} holds every value (D-012). The
 * generated {@code toString()} prints every component, {@code password} included (D-373).
 *
 * @param host     {@code wmq.host}: host name of the queue manager's listener, the connector's
 *                 {@code hostName}
 * @param port     {@code wmq.port}: TCP port of the queue manager's listener, the connector's
 *                 {@code port}
 * @param username {@code wmq.username}: user name presented when a connection opens, the connector's
 *                 {@code username}
 * @param password {@code wmq.password}: password presented when a connection opens, the connector's
 *                 {@code password}
 * @param channel  {@code wmq.channel}: name of the server-connection channel, the connector's
 *                 {@code channel}
 * @param queue    {@code wmq.queue}: name of the queue manager, the connector's {@code queueManager}
 * @param inQueue  {@code wmq.in-queue}: the queue {@code Input} writes to and {@code MessageProcessor}
 *                 reads from [websphere-mq/src/main/app/websphere-mq.xml:13,16]
 * @param outQueue {@code wmq.out-queue}: the queue {@code MessageProcessor} writes to and
 *                 {@code Output} reads from [websphere-mq/src/main/app/websphere-mq.xml:20,25]
 * @param ajax     {@code wmq.ajax.*}: settings of the dequeue channel's pre-subscription cache
 */
@ConfigurationProperties(prefix = "wmq")
public record WmqProperties(String host, int port, String username, String password,
                            String channel, String queue, String inQueue, String outQueue,
                            Ajax ajax) {

    /**
     * Binds the {@code wmq.ajax.*} keys of the dequeue channel's pre-subscription cache (D-027).
     *
     * @param cacheSize {@code wmq.ajax.cache-size}: the most messages the dequeue channel holds, in
     *                  arrival order, before its first subscription
     */
    public record Ajax(int cacheSize) {
    }
}
