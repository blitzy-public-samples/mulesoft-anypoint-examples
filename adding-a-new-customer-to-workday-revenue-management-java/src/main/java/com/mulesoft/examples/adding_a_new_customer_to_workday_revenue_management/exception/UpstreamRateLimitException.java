package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception;

/**
 * Thrown when the upstream system answers a call with HTTP 429. Carries the vendor
 * {@code Retry-After} header value exactly as received, or {@code null} when the response has no
 * such header; mapped to status 429 (D-020).
 *
 * <p>{@code WorkdayRevenueClient.execute} raises it with upstream system {@code Workday} once per
 * rate-limited call and makes no further attempt. {@code GlobalExceptionHandler} answers it with
 * status 429, an {@code application/json} {@code ErrorResponse} body holding {@link #getMessage()}
 * and {@link #getUpstreamSystem()}, and a {@code Retry-After} response header set to
 * {@link #getRetryAfter()} when that value is not {@code null}.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamRateLimitException(
 *         "Workday", "Workday rate limit exceeded for Put_Customer", "30", cause);
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    /** Serialization version of this exception type. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that applied the limit, for example {@code Workday}. */
    private final String upstreamSystem;

    /** Vendor {@code Retry-After} header value exactly as received, or {@code null}. */
    private final String retryAfter;

    /**
     * Creates the exception with every value stored exactly as given (D-020).
     *
     * @param upstreamSystem name of the upstream system that applied the limit, for example
     *                       {@code Workday}
     * @param message        text returned by {@link #getMessage()}, for example
     *                       {@code Workday rate limit exceeded for Put_Customer}
     * @param retryAfter     the vendor {@code Retry-After} header value as received (delay-seconds or
     *                       an HTTP-date), or {@code null} when the response carries none; stored
     *                       without parsing, trimming or defaulting
     * @param cause          the vendor failure that carried the 429 status, or {@code null}
     */
    public UpstreamRateLimitException(String upstreamSystem, String message, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the name of the upstream system that applied the limit (D-020).
     *
     * @return the upstream system name as given to the constructor, for example {@code Workday}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the vendor {@code Retry-After} header value exactly as received (D-020).
     *
     * @return the header value as given to the constructor, or {@code null} when the vendor response
     *         carried none
     */
    public String getRetryAfter() {
        return retryAfter;
    }
}
