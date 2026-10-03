package com.mulesoft.examples.websphere_mq.service;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.mulesoft.examples.websphere_mq.config.WmqProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Publish side of the Mule flow {@code Output} [websphere-mq/src/main/app/websphere-mq.xml:24-27]:
 * each text read from the queue {@code out} (:25) is published to the AJAX channel
 * {@code /services/wmqExample/dequeue}, declared with {@code cacheMessages="true"} (:26), whose
 * subscribers are Server-Sent Event streams opened by {@code GET /services/wmqExample/dequeue}
 * (D-027).
 *
 * <p>Channel behaviour:
 * <ul>
 *   <li>{@link #subscribe(SseEmitter)} registers a stream and creates the channel. The channel then
 *       exists for the life of the process, with or without subscribers.</li>
 *   <li>Before the channel exists, {@link #output(String)} caches each message in arrival order.
 *       The cache holds at most {@code wmq.ajax.cache-size} entries (500 in {@code application.yml});
 *       a message arriving at a full cache raises {@link IllegalStateException} with the text
 *       {@code The buffer cannot hold more than <size> objects.} and is not cached.</li>
 *   <li>Once the channel exists, a message arriving with no subscriber is dropped.</li>
 *   <li>A message arriving with at least one subscriber first drains the cache, one cached message
 *       per subscriber per turn in registration order, and is then sent to every subscriber.</li>
 *   <li>A {@code null} text is neither sent nor cached.</li>
 *   <li>A subscriber whose send fails is removed without being completed.</li>
 *   <li>{@link #unsubscribe(SseEmitter)} removes a stream and completes it once; the completion,
 *       timeout and error callbacks of a registered stream call it.</li>
 * </ul>
 *
 * <p>Every public method is {@code synchronized} on the bean. Every walk over the subscribers runs
 * over a copy of the list taken when the walk starts, and skips a subscriber removed during the
 * walk.
 *
 * <pre>{@code
 * WmqDequeueService service = new WmqDequeueService(properties); // wmq.ajax.cache-size: 500
 * service.output("a");                  // cached
 * service.output("b");                  // cached
 * SseEmitter first = new SseEmitter();
 * service.subscribe(first);             // the channel now exists
 * service.output("c");                  // first receives a, b, then c
 * service.unsubscribe(first);           // first is completed
 * service.output("d");                  // dropped: the channel has no subscriber
 * }</pre>
 */
@Service
public class WmqDequeueService {

    /** Writes the DEBUG and TRACE lines of the channel. */
    private static final Logger LOG = LoggerFactory.getLogger(WmqDequeueService.class);

    /** Registered streams, in registration order; each stream appears at most once. */
    private final List<SseEmitter> subscribers = new ArrayList<>();

    /** Messages received before the first subscription, oldest first. */
    private final ArrayDeque<String> cache = new ArrayDeque<>();

    /** The most messages {@link #cache} holds, from {@code wmq.ajax.cache-size}. */
    private final int cacheSize;

    /** {@code true} from the first {@link #subscribe(SseEmitter)} on; never reset. */
    private boolean channelExists = false;

    /**
     * Creates the channel state with an empty cache bounded by {@code wmq.ajax.cache-size}.
     *
     * @param properties the bound {@code wmq.*} keys; {@code properties.ajax().cacheSize()} is the
     *                   cache bound
     * @throws IllegalArgumentException when the cache bound is lower than 1, with the text
     *                                  {@code The size must be greater than 0}
     */
    public WmqDequeueService(WmqProperties properties) {
        this.cacheSize = properties.ajax().cacheSize();
        if (this.cacheSize < 1) {
            throw new IllegalArgumentException("The size must be greater than 0");
        }
    }

    /**
     * Registers {@code emitter} as a subscriber of the channel and creates the channel (D-027).
     * The emitter's completion, timeout and error callbacks call {@link #unsubscribe(SseEmitter)}.
     * Registering an emitter that is already registered changes nothing.
     *
     * @param emitter the stream that receives the channel's messages
     * @throws NullPointerException when {@code emitter} is {@code null}
     */
    public synchronized void subscribe(SseEmitter emitter) {
        Objects.requireNonNull(emitter, "emitter");
        if (subscribers.contains(emitter)) {
            LOG.debug("Subscriber already registered on the dequeue channel");
            return;
        }
        subscribers.add(emitter);
        channelExists = true;
        emitter.onCompletion(() -> unsubscribe(emitter));
        emitter.onTimeout(() -> unsubscribe(emitter));
        emitter.onError(error -> unsubscribe(emitter));
        LOG.debug("Subscriber registered on the dequeue channel; {} subscriber(s)", subscribers.size());
    }

    /**
     * Removes {@code emitter} from the channel's subscribers and completes it (D-027). An emitter
     * that is not registered is left untouched, so a repeated call changes nothing. A
     * {@link RuntimeException} raised by {@link SseEmitter#complete()} is logged at DEBUG.
     *
     * @param emitter the stream to remove
     */
    public synchronized void unsubscribe(SseEmitter emitter) {
        if (!subscribers.remove(emitter)) {
            return;
        }
        LOG.debug("Subscriber removed from the dequeue channel; {} subscriber(s)", subscribers.size());
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            LOG.debug("Completing a removed dequeue subscriber failed", e);
        }
    }

    /**
     * Returns the number of streams registered on the channel (D-027).
     *
     * @return the current subscriber count
     */
    public synchronized int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Publishes {@code text} on the channel, the {@code ajax:outbound-endpoint} of the flow
     * {@code Output} [websphere-mq/src/main/app/websphere-mq.xml:26]:
     * <ol>
     *   <li>a {@code null} text is ignored;</li>
     *   <li>before the first subscription the text is cached, or, with the cache full, an
     *       {@link IllegalStateException} is raised and nothing is cached;</li>
     *   <li>once the channel exists with no subscriber, the text is dropped and the cache is left as
     *       it is;</li>
     *   <li>otherwise the cache is drained to the subscribers, one cached message per subscriber per
     *       turn, and the text is then sent to every subscriber.</li>
     * </ol>
     *
     * @param text the message read from the queue {@code out}
     * @throws IllegalStateException when the channel does not exist yet and the cache already holds
     *                               {@code wmq.ajax.cache-size} messages, with the text
     *                               {@code The buffer cannot hold more than <size> objects.}
     */
    public synchronized void output(String text) {
        if (text == null) {
            LOG.debug("Null message ignored by the dequeue channel");
            return;
        }
        if (!channelExists) {
            if (cache.size() >= cacheSize) {
                throw new IllegalStateException("The buffer cannot hold more than " + cacheSize + " objects.");
            }
            cache.addLast(text);
            LOG.trace("No subscriber has joined the dequeue channel; message cached ({} cached)", cache.size());
            return;
        }
        if (subscribers.isEmpty()) {
            LOG.debug("Message dropped: the dequeue channel has no subscriber");
            return;
        }
        drainCache();
        broadcast(text);
    }

    /**
     * Sends the cached messages, oldest first, one per subscriber per turn in registration order,
     * until the cache is empty. Each polled message is consumed whether or not its send succeeds.
     * The drain stops with the remaining messages still cached once no subscriber is registered.
     */
    private void drainCache() {
        while (!cache.isEmpty()) {
            List<SseEmitter> turn = new ArrayList<>(subscribers);
            if (turn.isEmpty()) {
                LOG.debug("Drain of the dequeue cache stopped: no subscriber left; {} cached", cache.size());
                return;
            }
            for (SseEmitter subscriber : turn) {
                if (cache.isEmpty()) {
                    return;
                }
                if (subscribers.contains(subscriber)) {
                    deliver(subscriber, cache.pollFirst());
                }
            }
        }
    }

    /**
     * Sends {@code text} to every subscriber registered when the walk starts and still registered
     * when its turn comes.
     *
     * @param text the message to send
     */
    private void broadcast(String text) {
        for (SseEmitter subscriber : new ArrayList<>(subscribers)) {
            if (subscribers.contains(subscriber)) {
                deliver(subscriber, text);
            }
        }
    }

    /**
     * Sends {@code text} to {@code emitter} as one Server-Sent Event with no event name. A failed
     * send removes the emitter from the subscribers without completing it and is logged at DEBUG.
     *
     * @param emitter the receiving stream
     * @param text    the event data
     */
    private void deliver(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().data(text));
        } catch (IOException | RuntimeException e) {
            subscribers.remove(emitter);
            LOG.debug("Send to a dequeue subscriber failed; subscriber removed ({} left)", subscribers.size(), e);
        }
    }
}
