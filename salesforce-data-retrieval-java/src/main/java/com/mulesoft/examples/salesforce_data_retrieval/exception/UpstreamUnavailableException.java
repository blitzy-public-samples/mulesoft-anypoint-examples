package com.mulesoft.examples.salesforce_data_retrieval.exception;

/**
 * The upstream system could not be reached or did not answer in time; the request is not re-sent (D-020).
 *
 * <p>Raised for a network timeout or connectivity failure, such as a {@code SocketTimeoutException}, a
 * {@code ConnectException}, a {@code RestClient} {@code ResourceAccessException}, or a force-wsc
 * {@code ConnectionException} wrapping one of them. Exactly one outbound attempt precedes it. An HTTP entry
 * point answers it with 503 and an {@code ErrorResponse} built from {@link #getMessage()} and
 * {@link #upstreamSystem()}.
 *
 * <pre>{@code
 * throw new UpstreamUnavailableException(
 *         "Salesforce", "Salesforce unreachable during queryAll: Read timed out", ioFailure);
 * }</pre>
 */
public class UpstreamUnavailableException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that could not be reached. */
    private final String upstreamSystem;

    /**
     * Creates the exception for one failed attempt to reach an upstream system (D-020).
     *
     * @param upstreamSystem name of the unreachable system, for example {@code "Salesforce"}
     * @param message        description of the failed operation, returned by {@link #getMessage()}
     * @param cause          the original I/O failure, or {@code null} when none is available
     */
    public UpstreamUnavailableException(String upstreamSystem, String message, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that could not be reached (D-020).
     *
     * @return the upstream system name passed to the constructor
     */
    public String upstreamSystem() {
        return upstreamSystem;
    }
}
