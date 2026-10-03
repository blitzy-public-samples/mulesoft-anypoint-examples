package com.mulesoft.examples.get_customer_list_from_netsuite.exception;

/**
 * Raised when an upstream system rejects authentication after one re-authenticated retry, or
 * rejects the access token request itself; mapped to HTTP 502 (D-020).
 *
 * <p>{@code GlobalExceptionHandler} answers it with an {@code ErrorResponse} built from
 * {@link #getMessage()} and {@link #getUpstreamSystem()}, for example
 * {@code {"message":"NetSuite authentication failed","upstreamSystem":"NetSuite"}}.
 *
 * <pre>{@code
 * throw new UpstreamAuthenticationException("NetSuite authentication failed", "NetSuite", cause);
 * }</pre>
 */
public class UpstreamAuthenticationException extends RuntimeException {

    /** Serialization version of this class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that rejected the authentication. */
    private final String upstreamSystem;

    /**
     * Creates the exception.
     *
     * @param message        the failure message, returned by {@link #getMessage()}
     * @param upstreamSystem the name of the upstream system that rejected the authentication,
     *                       for example {@code "NetSuite"}
     * @param cause          the failure of the last attempt; may be {@code null}
     */
    public UpstreamAuthenticationException(String message, String upstreamSystem, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that rejected the authentication.
     *
     * @return the upstream system name passed to the constructor
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
