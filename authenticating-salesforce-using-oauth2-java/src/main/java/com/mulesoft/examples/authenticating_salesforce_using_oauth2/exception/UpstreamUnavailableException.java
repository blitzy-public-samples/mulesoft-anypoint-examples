package com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception;

/**
 * The upstream system could not be reached or did not answer in time (D-020).
 *
 * <p>Raised by the Salesforce clients for a network timeout or connectivity failure, such as a
 * {@code SocketTimeoutException}, a {@code ConnectException}, a {@code RestClient}
 * {@code ResourceAccessException}, or a force-wsc {@code ConnectionException} wrapping one of them.
 * Exactly one outbound attempt precedes it and the request is not re-sent.
 * {@code GlobalExceptionHandler} answers it with HTTP 503 and an {@code ErrorResponse} built from
 * {@link #getMessage()} and {@link #getUpstreamSystem()}.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamUnavailableException(
 *         "Salesforce query timed out or could not connect", "Salesforce", ioFailure);
 * }</pre>
 */
public class UpstreamUnavailableException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that could not be reached. */
    private final String upstreamSystem;

    /**
     * Creates the exception without an underlying cause.
     *
     * @param message description of the failed operation, returned by {@link #getMessage()}
     * @param upstreamSystem name of the unreachable system, for example {@code "Salesforce"}
     */
    public UpstreamUnavailableException(String message, String upstreamSystem) {
        super(message);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Creates the exception with the I/O or connection failure that caused it.
     *
     * @param message description of the failed operation, returned by {@link #getMessage()}
     * @param upstreamSystem name of the unreachable system, for example {@code "Salesforce"}
     * @param cause the original timeout or connectivity failure, returned by {@link #getCause()},
     *     or {@code null} when none is available
     */
    public UpstreamUnavailableException(String message, String upstreamSystem, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that could not be reached.
     *
     * @return the upstream system name passed to the constructor
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
