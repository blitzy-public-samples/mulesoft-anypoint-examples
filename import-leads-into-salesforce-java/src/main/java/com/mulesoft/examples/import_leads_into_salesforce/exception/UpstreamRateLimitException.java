package com.mulesoft.examples.import_leads_into_salesforce.exception;

/**
 * Thrown when the upstream system reports that its request limit is exceeded: a Salesforce
 * {@code REQUEST_LIMIT_EXCEEDED} fault or an HTTP 429 from the token endpoint (D-020).
 *
 * <p>Throw sites:
 * <ul>
 *   <li>{@code SalesforceClient.execute} throws it for an {@code ApiFault} whose exception code is
 *       {@code REQUEST_LIMIT_EXCEEDED}, with upstream system {@code Salesforce}, the fault as the cause
 *       and a {@code null} {@code Retry-After} value.</li>
 *   <li>{@code SalesforceSessionProvider} throws it when the token endpoint
 *       {@code ${sfdc.login-url}/services/oauth2/token} answers 429, with upstream system
 *       {@code Salesforce} and the response's {@code Retry-After} header value, or {@code null} when
 *       the response has none.</li>
 * </ul>
 *
 * <p>{@code CreateLeadsBatchJob} catches it, fails the current record or commit block, logs the
 * upstream system, the operation and the {@code Retry-After} value at ERROR and ends the run; the call
 * is not retried, and the next scheduled poll runs normally (D-020).
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamRateLimitException(
 *         "Salesforce", "Salesforce token request failed with status 429", "30", cause);
 * }</pre>
 */
public class UpstreamRateLimitException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that reported the exceeded limit, for example {@code Salesforce}. */
    private final String upstreamSystem;

    /** {@code Retry-After} value exactly as the upstream sent it, or {@code null} when it sent none. */
    private final String retryAfter;

    /**
     * Creates the exception and stores every value exactly as given.
     *
     * @param upstreamSystem name of the upstream system that reported the exceeded limit
     * @param message        detail message returned by {@link #getMessage()}
     * @param retryAfter     the {@code Retry-After} value the upstream sent, unparsed and untrimmed, or
     *                       {@code null} when it sent none
     * @param cause          the upstream failure classified as a rate limit, or {@code null}
     */
    public UpstreamRateLimitException(String upstreamSystem, String message, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the name of the upstream system that reported the exceeded limit.
     *
     * @return the upstream system name, for example {@code Salesforce}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the {@code Retry-After} value the upstream sent, or {@code null} when it sent none.
     *
     * @return the {@code Retry-After} value as received, delay-seconds or an HTTP-date, or {@code null}
     */
    public String getRetryAfter() {
        return retryAfter;
    }
}
