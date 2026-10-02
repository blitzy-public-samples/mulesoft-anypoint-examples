package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception;

/**
 * Thrown when a Salesforce call or the OAuth2 token request times out or cannot connect; the call is not
 * repeated (D-020).
 *
 * <p>Raised for a {@code SocketTimeoutException} or {@code ConnectException} found in the cause chain of a
 * partner API call, including one wrapped in a force-wsc {@code ConnectionException}, and for a
 * {@code ResourceAccessException} from the token request. Exactly one outbound attempt precedes it. The poll
 * trigger logs it at ERROR with {@link #upstreamSystem()} and {@link #getMessage()}, resubmits nothing and
 * leaves the watermark unchanged.
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
     * @param upstreamSystem name of the external system that could not be reached
     * @param cause          the timeout or connection failure, returned by {@link #getCause()}
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
    public String upstreamSystem() {
        return upstreamSystem;
    }
}
