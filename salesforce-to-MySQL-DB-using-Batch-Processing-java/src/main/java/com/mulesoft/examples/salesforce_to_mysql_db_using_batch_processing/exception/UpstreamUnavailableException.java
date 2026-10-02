package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception;

/**
 * Signals a timeout or connectivity failure toward Salesforce (D-020).
 *
 * <p>The Salesforce client raises it after exactly one outbound attempt when the OAuth2 token call or
 * a partner API call, such as the Contact {@code query} of {@code triggerFlow}, fails with a
 * {@code SocketTimeoutException}, a {@code ConnectException}, or a force-wsc {@code ConnectionException}
 * or Spring {@code ResourceAccessException} wrapping one of them. The cause is the original I/O failure.
 * The request is not re-sent. {@code triggerFlow} logs it at ERROR with {@link #upstreamSystem()},
 * leaves the watermark unchanged and resubmits nothing.
 *
 * <pre>{@code
 * throw new UpstreamUnavailableException("Salesforce unreachable during query", ioFailure);
 * }</pre>
 */
public class UpstreamUnavailableException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception for one failed attempt to reach Salesforce.
     *
     * @param message description of the failed operation, returned by {@link #getMessage()}
     * @param cause   the original I/O failure, returned by {@link #getCause()}, or {@code null} when none exists
     */
    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Returns the name of the upstream system, {@code Salesforce}.
     *
     * @return the literal {@code "Salesforce"}
     */
    public String upstreamSystem() {
        return "Salesforce";
    }
}
