package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception;

/**
 * Thrown when the upstream system rejects authentication after one re-authentication retry;
 * mapped to HTTP 502 (D-020).
 *
 * <p>{@code client.WorkdayRevenueClient.execute} throws it with upstream system {@code Workday}
 * when a {@code Put_Customer} or {@code Get_Customers} call fails authentication, is re-issued
 * once, and fails authentication again. {@code GlobalExceptionHandler.upstreamAuthentication}
 * answers it with status 502 and an {@code application/json} {@code ErrorResponse} built from
 * {@link #getMessage()} and {@link #getUpstreamSystem()}.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamAuthenticationException(
 *         "Workday", "Workday authentication failed for Put_Customer", secondAttemptFailure);
 * }</pre>
 */
public class UpstreamAuthenticationException extends RuntimeException {

    /** Serialization version of this exception type. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that rejected the authentication. */
    private final String upstreamSystem;

    /**
     * Creates the exception.
     *
     * @param upstreamSystem name of the upstream system that rejected the authentication,
     *     {@code Workday} in this project
     * @param message        description of the failure, returned by {@link #getMessage()}
     * @param cause          the failure of the retried request, or {@code null} when there is none
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
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
