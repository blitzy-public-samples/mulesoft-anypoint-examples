package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception;

/**
 * Thrown on an upstream timeout or connectivity failure; mapped to 503; never retried (D-020).
 *
 * <p>{@code WorkdayRevenueClient.execute} throws it with {@code upstreamSystem} {@code "Workday"} when a call
 * ends in a {@code SocketTimeoutException}, a {@code ConnectException}, or a Spring WS
 * {@code WebServiceIOException} wrapping either. Exactly one outbound attempt precedes it: the
 * {@code Put_Customer} write is not re-sent. {@code GlobalExceptionHandler.upstreamUnavailable} answers it with
 * HTTP 503 and an {@code application/json} {@code ErrorResponse} built from {@link #getMessage()} and
 * {@link #getUpstreamSystem()}.
 *
 * <pre>{@code
 * throw new UpstreamUnavailableException(
 *         "Workday", "Workday unavailable during Put_Customer: Read timed out", ioFailure);
 * }</pre>
 */
public class UpstreamUnavailableException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that could not be reached, for example {@code "Workday"}. */
    private final String upstreamSystem;

    /**
     * Creates the exception for one failed attempt to reach an upstream system.
     *
     * @param upstreamSystem name of the unreachable system, for example {@code "Workday"}
     * @param message        description of the failed operation, returned by {@link #getMessage()}
     * @param cause          the timeout or connectivity failure that ended the attempt, or {@code null}
     *                       when none is available
     */
    public UpstreamUnavailableException(String upstreamSystem, String message, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that could not be reached (D-020).
     *
     * @return the upstream system name passed to the constructor, for example {@code "Workday"}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
