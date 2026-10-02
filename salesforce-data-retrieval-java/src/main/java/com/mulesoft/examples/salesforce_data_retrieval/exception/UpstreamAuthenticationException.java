package com.mulesoft.examples.salesforce_data_retrieval.exception;

/**
 * Authentication with the upstream system failed after one re-authentication attempt (D-020).
 *
 * <p>Raised by the Salesforce client when the OAuth2 token request is rejected, or when a call
 * answered with {@code INVALID_SESSION_ID} fails again after the session is renewed and the call is
 * re-issued once. {@code GlobalExceptionHandler} answers it with HTTP 502 and an
 * {@code ErrorResponse} built from {@link #getMessage()} and {@link #upstreamSystem()}.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamAuthenticationException("Salesforce", "Salesforce session is invalid", fault);
 * }</pre>
 */
public class UpstreamAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String upstreamSystem;

    /**
     * Creates the exception.
     *
     * @param upstreamSystem name of the upstream system that rejected the authentication, for
     *     example {@code "Salesforce"}
     * @param message description of the failure, returned by {@link #getMessage()}
     * @param cause the vendor failure that was classified as an authentication failure, or
     *     {@code null} when there is none
     */
    public UpstreamAuthenticationException(String upstreamSystem, String message, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that rejected the authentication.
     *
     * @return the upstream system name passed to the constructor
     */
    public String upstreamSystem() {
        return upstreamSystem;
    }
}
