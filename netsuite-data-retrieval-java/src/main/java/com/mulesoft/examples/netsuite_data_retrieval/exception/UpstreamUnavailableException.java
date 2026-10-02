package com.mulesoft.examples.netsuite_data_retrieval.exception;

/**
 * Raised when NetSuite cannot be reached or does not answer in time: a connection failure, a socket or
 * response timeout, or a {@code WebClientRequestException} during a NetSuite REST call.
 *
 * <p>The NetSuite client makes exactly one outbound attempt per operation and never retries it (D-020).
 * The message names the failed operation, for example {@code NetSuite unavailable: queryIds customer},
 * and the cause carries the underlying I/O or timeout failure.
 *
 * <p>{@code GlobalExceptionHandler.upstreamUnavailable} maps it to HTTP 503 with an {@code ErrorResponse}
 * body whose {@code upstreamSystem} is {@code NetSuite} (D-007, D-020).
 */
public class UpstreamUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for one failed NetSuite operation.
     *
     * @param message the client-facing message, {@code NetSuite unavailable: <operation>}
     * @param cause   the connection or timeout failure that ended the single attempt
     */
    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
