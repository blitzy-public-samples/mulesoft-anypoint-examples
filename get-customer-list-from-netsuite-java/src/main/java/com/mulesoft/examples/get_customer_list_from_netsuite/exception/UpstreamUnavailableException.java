package com.mulesoft.examples.get_customer_list_from_netsuite.exception;

/**
 * Raised when an upstream system cannot be reached or times out; the request is not retried; mapped to
 * HTTP 503 (D-020).
 *
 * <p>The NetSuite client throws it when a call ends in a network timeout or connectivity failure, for
 * example a {@code SocketTimeoutException}, a {@code ConnectException} or a
 * {@code WebClientRequestException}, and does not re-send the failed call. The cause carries that
 * failure. {@code GlobalExceptionHandler} answers it with status 503 and an {@code ErrorResponse} built
 * from {@link #getMessage()} and {@link #getUpstreamSystem()} (D-007, D-020).
 *
 * <pre>{@code
 * throw new UpstreamUnavailableException("NetSuite unavailable", "NetSuite", connectFailure);
 * // HTTP/1.1 503 Service Unavailable
 * // {"message":"NetSuite unavailable","upstreamSystem":"NetSuite"}
 * }</pre>
 */
public class UpstreamUnavailableException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that could not be reached, for example {@code NetSuite}. */
    private final String upstreamSystem;

    /**
     * Creates the exception for one failed attempt to reach an upstream system.
     *
     * @param message        the client-facing message, returned by {@link #getMessage()}, for example
     *                       {@code NetSuite unavailable}
     * @param upstreamSystem the name of the unreachable system, returned by {@link #getUpstreamSystem()},
     *                       for example {@code NetSuite}
     * @param cause          the timeout or connectivity failure of the failed call; may be {@code null}
     */
    public UpstreamUnavailableException(String message, String upstreamSystem, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that could not be reached.
     *
     * @return the upstream system name passed to the constructor, for example {@code NetSuite}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
