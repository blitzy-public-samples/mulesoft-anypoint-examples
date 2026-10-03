package com.mulesoft.examples.get_customer_list_from_netsuite.exception;

/**
 * Raised when an upstream system answers with a rate limit; carries the vendor Retry-After value;
 * mapped to HTTP 429 (D-020).
 *
 * <p>The NetSuite client raises it once for a call that NetSuite answers with HTTP 429 and makes no
 * further attempt. {@code GlobalExceptionHandler} answers it with status 429 and an
 * {@code ErrorResponse} body built from {@link #getMessage()} and {@link #getUpstreamSystem()}, and
 * sets the {@code Retry-After} response header to {@link #getRetryAfter()} when that value is not
 * {@code null}; with a {@code null} value the response carries no {@code Retry-After} header.
 *
 * <p>The {@code Retry-After} value is held exactly as the vendor sent it, either delay-seconds such
 * as {@code 30} or an HTTP-date such as {@code Wed, 21 Oct 2026 07:28:00 GMT}.
 *
 * <pre>{@code
 * throw new UpstreamRateLimitException(
 *         "NetSuite rate limit exceeded", "NetSuite", response.getHeaders().getFirst("Retry-After"), cause);
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    /** Serialization version of this exception type. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that applied the limit, for example {@code NetSuite}. */
    private final String upstreamSystem;

    /** Vendor {@code Retry-After} header value exactly as received, or {@code null}. */
    private final String retryAfter;

    /**
     * Creates the exception for a rate-limited upstream call (D-020).
     *
     * @param message        text returned by {@link #getMessage()}, for example
     *                       {@code NetSuite rate limit exceeded}
     * @param upstreamSystem name of the upstream system that applied the limit, for example
     *                       {@code NetSuite}
     * @param retryAfter     the vendor {@code Retry-After} header value, stored without parsing,
     *                       trimming or reformatting, or {@code null} when the vendor response carries
     *                       no such header
     * @param cause          the vendor failure that carried the 429 status, or {@code null}
     */
    public UpstreamRateLimitException(String message, String upstreamSystem, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the name of the upstream system that applied the limit.
     *
     * @return the upstream system name, for example {@code NetSuite}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the vendor {@code Retry-After} header value exactly as received.
     *
     * @return the header value, or {@code null} when the vendor response carried none
     */
    public String getRetryAfter() {
        return retryAfter;
    }
}
