package com.mulesoft.examples.dataweave_with_flowreflookup.exception;

import java.util.Optional;

/**
 * Signals that Salesforce reported a rate limit: an HTTP 429 from the OAuth2 token endpoint, or an
 * {@code ApiFault} with exception code {@code REQUEST_LIMIT_EXCEEDED}. See D-020.
 *
 * <p>The exception carries the name of the upstream system and, when the vendor sent one, the raw
 * {@code Retry-After} header value. The constructor stores every value exactly as given, without
 * validation, trimming or parsing.
 *
 * <p>Example:
 * <pre>{@code
 * UpstreamRateLimitException e =
 *         new UpstreamRateLimitException("Salesforce token request rate limited", "Salesforce", "30", null);
 * e.getUpstreamSystem(); // "Salesforce"
 * e.getRetryAfter();     // Optional[30]
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    /** Serialization version of this class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that reported the rate limit. */
    private final String upstreamSystem;

    /** Raw {@code Retry-After} header value, or {@code null} when the vendor sent none. */
    private final String retryAfter;

    /**
     * Creates the exception with every value stored exactly as given.
     *
     * @param message        detail message returned by {@link #getMessage()}
     * @param upstreamSystem name of the upstream system that reported the rate limit, for example
     *                       {@code Salesforce}
     * @param retryAfter     the vendor {@code Retry-After} header value, or {@code null} when absent
     * @param cause          the vendor failure classified as a rate limit, returned by
     *                       {@link #getCause()}; may be {@code null}
     */
    public UpstreamRateLimitException(String message, String upstreamSystem, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the name of the upstream system that reported the rate limit.
     *
     * @return the upstream system name passed to the constructor
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the {@code Retry-After} value sent by the vendor.
     *
     * @return the {@code Retry-After} value, empty when none was supplied
     */
    public Optional<String> getRetryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
