package com.mulesoft.examples.netsuite_data_retrieval.exception;

/**
 * Raised when NetSuite answers a call with HTTP 429. Carries the vendor {@code Retry-After} header
 * value unchanged, or {@code null} when the response has no such header (D-020).
 *
 * <p>The NetSuite client raises it once per rate-limited call and makes no further attempt.
 * {@code GlobalExceptionHandler} answers it with status 429, sets the {@code Retry-After} response
 * header to {@link #getRetryAfter()} when that value is not {@code null}, and writes the message in
 * an {@code ErrorResponse} body.
 *
 * <pre>{@code
 * throw new UpstreamRateLimitException(
 *         "NetSuite rate limit exceeded: queryIds customer", "120", cause);
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String retryAfter;

    /**
     * Creates the exception.
     *
     * @param message    the failure text, {@code NetSuite rate limit exceeded: <operation>}
     * @param retryAfter the vendor {@code Retry-After} header value as received, or {@code null}
     *                   when the response carries none; stored without parsing, trimming or defaulting
     * @param cause      the vendor failure that carried the 429 status
     */
    public UpstreamRateLimitException(String message, String retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
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
