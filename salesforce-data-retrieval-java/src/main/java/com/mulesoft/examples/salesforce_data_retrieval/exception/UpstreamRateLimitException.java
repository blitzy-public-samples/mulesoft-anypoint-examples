package com.mulesoft.examples.salesforce_data_retrieval.exception;

/**
 * The upstream system rejected the request with a rate limit: an HTTP 429 answer from the
 * OAuth2 token endpoint or a Salesforce {@code ApiFault} with code {@code REQUEST_LIMIT_EXCEEDED}.
 * The request is not re-sent (D-020).
 *
 * <p>{@link #upstreamSystem()} names the system that applied the limit, and {@link #retryAfter()}
 * holds the value of its {@code Retry-After} header exactly as received (delay-seconds or an
 * HTTP-date), or {@code null} when the upstream system supplied none. The HTTP entry points answer
 * this exception with status 429, an {@code ErrorResponse} body and, when {@link #retryAfter()} is
 * not {@code null}, a {@code Retry-After} header carrying the same value.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamRateLimitException("Salesforce", "Salesforce rate limit exceeded", "30", cause);
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that applied the limit. */
    private final String upstreamSystem;

    /** Upstream {@code Retry-After} header value exactly as received, or {@code null}. */
    private final String retryAfter;

    /**
     * Creates the exception for a rate-limited upstream call (D-020).
     *
     * @param upstreamSystem name of the upstream system that applied the limit, for example
     *                       {@code "Salesforce"}
     * @param message        text returned as {@link #getMessage()}
     * @param retryAfter     the upstream {@code Retry-After} header value, unparsed, or {@code null}
     *                       when none was supplied
     * @param cause          the vendor failure that signalled the limit, or {@code null}
     */
    public UpstreamRateLimitException(String upstreamSystem, String message, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the name of the upstream system that applied the limit (D-020).
     *
     * @return the upstream system name, for example {@code "Salesforce"}
     */
    public String upstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the upstream {@code Retry-After} header value exactly as received, or {@code null}
     * when the upstream system supplied none (D-020).
     *
     * @return the raw {@code Retry-After} value, or {@code null}
     */
    public String retryAfter() {
        return retryAfter;
    }
}
