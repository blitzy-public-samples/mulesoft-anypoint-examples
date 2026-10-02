package com.mulesoft.examples.import_contacts_into_salesforce.exception;

/**
 * Signals that Salesforce refused an operation for exceeding its request limit (D-020).
 *
 * <p>Raised by the Salesforce client for an HTTP 429 from the OAuth2 token endpoint and for a
 * Salesforce {@code ApiFault} with code {@code REQUEST_LIMIT_EXCEEDED}. The file poller catches it,
 * logs the upstream system, the operation and the {@code Retry-After} value at ERROR, leaves the
 * polled file in the input directory and ends the poll cycle; the operation is not retried.
 *
 * <p>Example: {@code new UpstreamRateLimitException("Salesforce", "token",
 * "Salesforce token failed: HTTP 429", "120", null)}.
 */
public class UpstreamRateLimitException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that refused the operation, for example {@code Salesforce}. */
    private final String upstreamSystem;

    /** Name of the refused upstream operation, for example {@code token} or {@code create}. */
    private final String operation;

    /** Raw {@code Retry-After} header text sent by the vendor, or {@code null} when none was provided. */
    private final String retryAfter;

    /**
     * Creates the exception with every value stored exactly as given.
     *
     * @param upstreamSystem name of the upstream system that refused the operation
     * @param operation name of the refused upstream operation
     * @param message detail message, in the form {@code Salesforce <operation> failed: <detail>}
     * @param retryAfter the vendor {@code Retry-After} header value, or {@code null} when none was provided
     * @param cause the vendor failure that was classified as a rate limit, or {@code null}
     */
    public UpstreamRateLimitException(
            String upstreamSystem, String operation, String message, String retryAfter, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.operation = operation;
        this.retryAfter = retryAfter;
    }

    /**
     * Returns the name of the upstream system that refused the operation.
     *
     * @return the upstream system name, for example {@code Salesforce}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the name of the refused upstream operation.
     *
     * @return the operation name, for example {@code token} or {@code create}
     */
    public String getOperation() {
        return operation;
    }

    /**
     * Returns the vendor {@code Retry-After} header value, or {@code null} when none was provided.
     *
     * @return the vendor {@code Retry-After} header value, or {@code null} when none was provided
     */
    public String getRetryAfter() {
        return retryAfter;
    }
}
