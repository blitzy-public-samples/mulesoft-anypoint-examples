package com.mulesoft.examples.import_contacts_into_ms_dynamics.exception;

/**
 * Signals that the upstream system could not be reached or did not answer within the configured timeout; the
 * request is not re-sent (D-020).
 *
 * <p>Raised for a network timeout or connectivity failure of a single outbound attempt, on the Dataverse Web API
 * call or on the OAuth 2.0 token call. The cause, when present, is the original transport failure, for example a
 * {@code java.net.SocketTimeoutException} or a {@code java.net.ConnectException}. The message names the failed
 * operation and is passed in unchanged by the caller; {@link #getUpstreamSystem()} names the system that failed.
 *
 * <pre>{@code
 * throw new UpstreamUnavailableException(
 *         "createContact failed: connection timed out", UPSTREAM_SYSTEM, transportFailure);
 * }</pre>
 */
public class UpstreamUnavailableException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that could not be reached. */
    private final String upstreamSystem;

    /**
     * Creates the exception for a failed attempt with the transport failure that ended it (D-020).
     *
     * @param message        description of the failed operation, returned unchanged by {@link #getMessage()}
     * @param upstreamSystem name of the system that could not be reached, returned by {@link #getUpstreamSystem()}
     * @param cause          the original timeout or connectivity failure, returned by {@link #getCause()}
     */
    public UpstreamUnavailableException(String message, String upstreamSystem, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Creates the exception for a failed attempt without an underlying cause (D-020).
     *
     * @param message        description of the failed operation, returned unchanged by {@link #getMessage()}
     * @param upstreamSystem name of the system that could not be reached, returned by {@link #getUpstreamSystem()}
     */
    public UpstreamUnavailableException(String message, String upstreamSystem) {
        super(message);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that could not be reached (D-020).
     *
     * @return the upstream system name passed to the constructor, possibly {@code null}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
