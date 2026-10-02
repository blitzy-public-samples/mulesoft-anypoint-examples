package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception;

import java.util.Optional;

/**
 * Signals a Salesforce rate-limit response; carries the {@code Retry-After} value when present (D-020).
 *
 * <p>Raised by the Salesforce client for an HTTP 429 answer from the OAuth2 token endpoint and for a
 * Salesforce {@code ApiFault} with code {@code REQUEST_LIMIT_EXCEEDED}. The poll cycle that receives it
 * ends without a retry: the trigger logs {@link #upstreamSystem()} and {@link #retryAfter()} at ERROR,
 * leaves the watermark unchanged, and the next scheduled cycle runs as usual.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamRateLimitException("Salesforce rate limit reached during query", "30", cause);
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** {@code Retry-After} value exactly as received, or {@code null} when none was received. */
    private final String retryAfter;

    /**
     * Creates the exception for a rate-limit response that carried no {@code Retry-After} value.
     *
     * @param message detail message returned by {@link #getMessage()}
     * @param cause   the vendor failure classified as a rate limit, or {@code null}
     */
    public UpstreamRateLimitException(String message, Throwable cause) {
        this(message, null, cause);
    }

    /**
     * Creates the exception for a rate-limit response and stores its {@code Retry-After} value.
     *
     * <p>The value is stored exactly as given, unparsed and untrimmed: delay-seconds, an HTTP-date, or
     * an empty or whitespace-only value. Only {@code null} is stored as absent, and
     * {@link #retryAfter()} then returns an empty {@code Optional}.
     *
     * @param message    detail message returned by {@link #getMessage()}
     * @param retryAfter the {@code Retry-After} value received with the response, or {@code null}
     * @param cause      the vendor failure classified as a rate limit, or {@code null}
     */
    public UpstreamRateLimitException(String message, String retryAfter, Throwable cause) {
        super(message, cause);
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the {@code Retry-After} value received with the response, or empty when none was received.
     *
     * @return the {@code Retry-After} value as received, or {@link Optional#empty()}
     */
    public Optional<String> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    /**
     * Returns the name of the upstream system, {@code Salesforce}.
     *
     * @return {@code "Salesforce"}
     */
    public String upstreamSystem() {
        return "Salesforce";
    }
}
