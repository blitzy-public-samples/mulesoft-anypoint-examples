package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception;

/**
 * Signals that Salesforce rejected authentication, including a failed request after the single
 * re-authentication (D-020).
 *
 * <p>Covers two conditions: the OAuth2 username-password token request answered with HTTP 400 or
 * 401, and a partner call that fails with {@code INVALID_SESSION_ID} again after the session is
 * renewed and the call is re-issued once.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamAuthenticationException(
 *         "Salesforce authentication failed after re-authentication during query", fault);
 * }</pre>
 */
public class UpstreamAuthenticationException extends RuntimeException {

    /** Serialization version of this class. */
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message description of the failure, returned by {@link #getMessage()}
     * @param cause the vendor failure classified as an authentication failure, returned by
     *     {@link #getCause()}; {@code null} when there is none
     */
    public UpstreamAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns the name of the upstream system, {@code Salesforce}.
     *
     * @return the literal {@code "Salesforce"}
     */
    public String upstreamSystem() {
        return "Salesforce";
    }
}
