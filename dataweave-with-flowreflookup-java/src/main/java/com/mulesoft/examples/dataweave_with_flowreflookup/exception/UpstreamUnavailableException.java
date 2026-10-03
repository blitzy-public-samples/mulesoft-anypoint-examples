package com.mulesoft.examples.dataweave_with_flowreflookup.exception;

/**
 * Thrown when a Salesforce call or the OAuth2 token request fails with a network timeout or a connectivity
 * failure; the call is not retried. See D-020.
 *
 * <p>Raised for a {@code SocketTimeoutException}, a {@code ConnectException} or another {@code IOException}
 * found in the cause chain of a partner API call, including one wrapped in a force-wsc
 * {@code ConnectionException}, and for a {@code ResourceAccessException} from the token request. Exactly one
 * outbound attempt precedes it, and an Account {@code create} is never re-sent. The file poller logs it at
 * ERROR with the file name and {@link #getUpstreamSystem()}, and the file stays in the input directory.
 *
 * <pre>{@code
 * throw new UpstreamUnavailableException(
 *         "Salesforce create failed: Read timed out", "Salesforce", socketTimeout);
 * }</pre>
 */
public class UpstreamUnavailableException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the external system that could not be reached. */
    private final String upstreamSystem;

    /**
     * Creates the exception for one failed attempt to reach an external system.
     *
     * @param message        description of the failed call, returned by {@link #getMessage()}
     * @param upstreamSystem name of the external system that could not be reached, for example
     *                       {@code "Salesforce"}
     * @param cause          the timeout or connection failure, returned by {@link #getCause()}, or {@code null}
     *                       when none is available
     */
    public UpstreamUnavailableException(String message, String upstreamSystem, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the external system that could not be reached.
     *
     * @return the upstream system name passed to the constructor
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
