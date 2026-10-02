package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception;

/**
 * Thrown when Salesforce rejects authentication after one re-authentication attempt, or when the
 * OAuth2 token endpoint rejects the credentials (D-020).
 *
 * <p>The Salesforce client raises it when a partner call answered with {@code INVALID_SESSION_ID}
 * fails again after the session is renewed and the call is re-issued once, and the session provider
 * raises it when the token endpoint answers HTTP 400 or 401. The poll trigger logs it at ERROR with
 * {@link #upstreamSystem()} and {@link #getMessage()} and leaves the watermark unchanged.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamAuthenticationException(
 *         "Salesforce session invalid after re-authentication", "Salesforce", fault);
 * }</pre>
 */
public class UpstreamAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Name of the external system that rejected authentication. */
    private final String upstreamSystem;

    /**
     * Creates the exception with a detail message, the rejecting system and the underlying failure.
     *
     * @param message detail message returned by {@link #getMessage()}
     * @param upstreamSystem name of the external system that rejected authentication
     * @param cause vendor failure classified as an authentication failure, or {@code null} when none exists
     */
    public UpstreamAuthenticationException(String message, String upstreamSystem, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the external system that rejected authentication.
     *
     * @return the upstream system name passed to the constructor
     */
    public String upstreamSystem() {
        return upstreamSystem;
    }
}
