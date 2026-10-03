package com.mulesoft.examples.import_contacts_into_ms_dynamics.exception;

/**
 * Signals that the upstream system rejected the credentials or the token request, after at most one
 * re-authentication attempt (D-020).
 *
 * <p>The Dataverse client throws it from its {@code execute(operation, ...)} wrapper in two cases:
 * the token endpoint rejects the client-credentials token request with an OAuth 2.0 error
 * response, or a Dataverse request answered with HTTP 401 is re-issued once with a newly obtained
 * access token and answered with HTTP 401 again. A token or Dataverse request that times out or
 * cannot connect raises {@link UpstreamUnavailableException}, and a token or Dataverse request
 * answered with HTTP 429 raises {@link UpstreamRateLimitException}, instead of this exception.
 * The caller supplies the complete message, naming the failed operation, and passes the original
 * failure as the cause. The file poller logs the exception at ERROR with
 * {@link #getUpstreamSystem()} and leaves the polled file in the input directory.
 */
public class UpstreamAuthenticationException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that rejected the credentials or the token request. */
    private final String upstreamSystem;

    /**
     * Creates the exception with a detail message, the upstream system name and the failure that
     * triggered it.
     *
     * @param message        the complete detail message, naming the failed operation
     * @param upstreamSystem the name of the upstream system that rejected the credentials or the
     *                       token request
     * @param cause          the original failure, such as the token-endpoint rejection or the
     *                       second HTTP 401 response; may be {@code null}
     */
    public UpstreamAuthenticationException(String message, String upstreamSystem, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Creates the exception with a detail message and the upstream system name, without a cause.
     *
     * @param message        the complete detail message, naming the failed operation
     * @param upstreamSystem the name of the upstream system that rejected the credentials or the
     *                       token request
     */
    public UpstreamAuthenticationException(String message, String upstreamSystem) {
        super(message);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that rejected the credentials or the token request.
     *
     * @return the upstream system name passed to the constructor; may be {@code null}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
