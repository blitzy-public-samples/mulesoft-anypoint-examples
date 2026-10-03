package com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception;

/**
 * The upstream system rejected the call with a rate limit; {@code retryAfter} holds its
 * {@code Retry-After} value or {@code null} (D-020).
 *
 * <p>Raised by the Salesforce clients for an HTTP 429 answer from the OAuth2 token endpoint or a
 * partner-API {@code ApiFault} with code {@code REQUEST_LIMIT_EXCEEDED}. The call is not re-sent.
 * {@code GlobalExceptionHandler} answers it with status 429 and an {@code ErrorResponse} built from
 * {@link #getMessage()} and {@link #getUpstreamSystem()}, and adds a {@code Retry-After} response
 * header carrying {@link #getRetryAfter()} only when that value is not {@code null}.
 *
 * <pre>{@code
 * throw new UpstreamRateLimitException(
 *         "Salesforce token endpoint rate limit exceeded", "Salesforce", "30", responseException);
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that applied the limit, for example {@code "Salesforce"}. */
    private final String upstreamSystem;

    /** Upstream {@code Retry-After} header value exactly as received, or {@code null}. */
    private final String retryAfter;

    /**
     * Creates the exception without a cause (D-020).
     *
     * @param message        description of the rate-limited operation, returned by {@link #getMessage()}
     * @param upstreamSystem name of the upstream system that applied the limit
     * @param retryAfter     the upstream {@code Retry-After} header value as received, or {@code null}
     *                       when the upstream system supplied none
     */
    public UpstreamRateLimitException(String message, String upstreamSystem, String retryAfter) {
        super(message);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = retryAfter;
    }

    /**
     * Creates the exception with the vendor failure that signalled the limit (D-020).
     *
     * @param message        description of the rate-limited operation, returned by {@link #getMessage()}
     * @param upstreamSystem name of the upstream system that applied the limit
     * @param retryAfter     the upstream {@code Retry-After} header value as received, or {@code null}
     *                       when the upstream system supplied none
     * @param cause          the vendor failure that signalled the limit, returned by {@link #getCause()}
     */
    public UpstreamRateLimitException(String message, String upstreamSystem, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the name of the upstream system that applied the limit (D-020).
     *
     * @return the upstream system name passed to the constructor
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the upstream {@code Retry-After} header value exactly as passed to the constructor,
     * without parsing or defaulting (D-020).
     *
     * @return the raw {@code Retry-After} value, or {@code null} when the upstream system supplied none
     */
    public String getRetryAfter() {
        return retryAfter;
    }
}
