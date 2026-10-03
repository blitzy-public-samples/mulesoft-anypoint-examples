package com.mulesoft.examples.import_contacts_into_ms_dynamics.exception;

/**
 * Signals that the upstream system rejected the request with a rate limit (HTTP 429); carries the
 * upstream {@code Retry-After} header value when one was sent (D-020).
 *
 * <p>The Dataverse client raises it after exactly one attempt of the rate-limited call. The file
 * poller catches it, logs {@link #getUpstreamSystem()} and {@link #getRetryAfter()}, ends the poll
 * cycle and leaves the polled file in the input directory for the next poll.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamRateLimitException(
 *         "Dataverse createContact failed: rate limited", upstreamSystem, "30", cause);
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    /** Serialization version of this exception type. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that applied the rate limit. */
    private final String upstreamSystem;

    /** Upstream {@code Retry-After} header value exactly as received, or {@code null}. */
    private final String retryAfter;

    /**
     * Creates the exception with the upstream {@code Retry-After} value and the vendor failure.
     *
     * @param message        detail message naming the rate-limited operation
     * @param upstreamSystem name of the upstream system that applied the rate limit
     * @param retryAfter     the upstream {@code Retry-After} header value exactly as received
     *                       (delay-seconds or an HTTP-date), stored unparsed, or {@code null} when
     *                       the response carried none
     * @param cause          the vendor failure that carried the 429 status, or {@code null}
     */
    public UpstreamRateLimitException(String message, String upstreamSystem, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = retryAfter;
    }

    /**
     * Creates the exception with the vendor failure and no {@code Retry-After} value.
     *
     * @param message        detail message naming the rate-limited operation
     * @param upstreamSystem name of the upstream system that applied the rate limit
     * @param cause          the vendor failure that carried the 429 status, or {@code null}
     */
    public UpstreamRateLimitException(String message, String upstreamSystem, Throwable cause) {
        this(message, upstreamSystem, null, cause);
    }

    /**
     * Creates the exception with no {@code Retry-After} value and no cause.
     *
     * @param message        detail message naming the rate-limited operation
     * @param upstreamSystem name of the upstream system that applied the rate limit
     */
    public UpstreamRateLimitException(String message, String upstreamSystem) {
        super(message);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = null;
    }

    /**
     * Returns the name of the upstream system that applied the rate limit.
     *
     * @return the upstream system name, as supplied by the caller
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the raw upstream {@code Retry-After} header value.
     *
     * @return the header value exactly as received, or {@code null} when absent
     */
    public String getRetryAfter() {
        return retryAfter;
    }
}
