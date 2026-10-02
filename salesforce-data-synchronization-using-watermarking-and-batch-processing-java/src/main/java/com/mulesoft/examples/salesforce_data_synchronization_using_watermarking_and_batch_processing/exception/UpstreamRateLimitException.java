package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception;

import java.util.Optional;

/**
 * Thrown when Salesforce reports a rate limit ({@code REQUEST_LIMIT_EXCEEDED}) or its token
 * endpoint answers 429; carries the vendor {@code Retry-After} value when one was sent (D-020).
 *
 * <p>{@link #upstreamSystem()} names the system that applied the limit, as passed by the caller.
 * {@link #retryAfter()} holds the {@code Retry-After} header text exactly as received, either
 * delta-seconds or an HTTP-date, and is empty when the vendor sent none. The constructor stores
 * every value as given, with no validation, trimming or parsing.
 *
 * <p>The poll-triggered {@code triggerFlow} cycle that receives this exception ends, logs the
 * upstream system, the message and the {@code Retry-After} value, and leaves the watermark
 * unchanged; the call is not re-issued and the next scheduled cycle runs normally (D-020).
 *
 * <p>Example:
 * <pre>{@code
 * UpstreamRateLimitException e =
 *         new UpstreamRateLimitException("Salesforce token request rate limited", "Salesforce", "120", cause);
 * e.upstreamSystem(); // "Salesforce"
 * e.retryAfter();     // Optional[120]
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that applied the rate limit. */
    private final String upstreamSystem;

    /** Raw {@code Retry-After} header value, or {@code null} when the vendor sent none. */
    private final String retryAfter;

    /**
     * Creates the exception with every value stored exactly as given (D-020).
     *
     * @param message        detail message returned by {@link #getMessage()}
     * @param upstreamSystem name of the upstream system that applied the rate limit
     * @param retryAfter     raw {@code Retry-After} header value, or null when the vendor sent none
     * @param cause          the vendor failure classified as a rate limit, returned by
     *                       {@link #getCause()}; may be {@code null}
     */
    public UpstreamRateLimitException(String message, String upstreamSystem, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the name of the upstream system that applied the rate limit (D-020).
     *
     * @return the upstream system name passed to the constructor
     */
    public String upstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the {@code Retry-After} value sent by the vendor (D-020).
     *
     * @return the {@code Retry-After} value, empty when none was sent
     */
    public Optional<String> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
