package com.mulesoft.examples.import_contacts_into_salesforce.exception;

/**
 * Signals that Salesforce could not be reached or did not answer in time for an operation; the operation is not re-sent (D-020).
 *
 * <p>Raised for a socket timeout, a refused or failed connection, or a force-wsc {@code ConnectionException}
 * wrapping either. The file poller logs it at ERROR with {@link #getUpstreamSystem()} and {@link #getOperation()}
 * and leaves the polled file in the input directory.
 *
 * <pre>{@code
 * throw new UpstreamUnavailableException("Salesforce", "create",
 *         "Salesforce create failed: Read timed out", cause);
 * }</pre>
 */
public class UpstreamUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String upstreamSystem;

    private final String operation;

    /**
     * Creates the exception for one failed outbound operation.
     *
     * @param upstreamSystem name of the unreachable system, {@code Salesforce}
     * @param operation      Salesforce operation that failed, for example {@code token} or {@code create}
     * @param message        detail message, {@code Salesforce <operation> failed: <detail>}
     * @param cause          underlying I/O or connection failure, or {@code null}
     */
    public UpstreamUnavailableException(String upstreamSystem, String operation, String message, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.operation = operation;
    }

    /**
     * Returns the name of the unreachable system.
     *
     * @return the upstream system name, {@code Salesforce}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the Salesforce operation that failed.
     *
     * @return the operation name, for example {@code token} or {@code create}
     */
    public String getOperation() {
        return operation;
    }
}
