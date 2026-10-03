package com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception;

/**
 * Authentication with the upstream system failed: the token request was rejected, or the call
 * still failed after one re-authentication attempt (D-020).
 *
 * <p>Raised by the Salesforce clients with {@code upstreamSystem} {@code "Salesforce"} when:
 * <ul>
 *   <li>the token endpoint answers HTTP 401 to the authorization-code exchange;</li>
 *   <li>a token refresh is rejected, or no refresh token or access token is held;</li>
 *   <li>a partner API call answered with {@code INVALID_SESSION_ID} fails with the same fault
 *       again after the token is refreshed and the call is re-issued once.</li>
 * </ul>
 *
 * <p>{@code GlobalExceptionHandler} answers it with HTTP 502 and an {@code ErrorResponse} built
 * from {@link #getMessage()} and {@link #getUpstreamSystem()}.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamAuthenticationException(
 *         "Salesforce rejected the session after re-authentication during query", "Salesforce", fault);
 * }</pre>
 */
public class UpstreamAuthenticationException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that rejected the authentication, for example {@code "Salesforce"}. */
    private final String upstreamSystem;

    /**
     * Creates the exception without a cause.
     *
     * @param message        description of the failure, returned by {@link #getMessage()}
     * @param upstreamSystem name of the upstream system that rejected the authentication, returned
     *                       by {@link #getUpstreamSystem()}
     */
    public UpstreamAuthenticationException(String message, String upstreamSystem) {
        super(message);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Creates the exception with the vendor failure that was classified as an authentication failure.
     *
     * @param message        description of the failure, returned by {@link #getMessage()}
     * @param upstreamSystem name of the upstream system that rejected the authentication, returned
     *                       by {@link #getUpstreamSystem()}
     * @param cause          the vendor failure, returned by {@link #getCause()}; may be {@code null}
     */
    public UpstreamAuthenticationException(String message, String upstreamSystem, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that rejected the authentication (D-020).
     *
     * @return the upstream system name passed to the constructor, for example {@code "Salesforce"}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
