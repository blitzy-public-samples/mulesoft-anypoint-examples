package com.mulesoft.examples.import_contacts_into_salesforce.exception;

/**
 * Signals that Salesforce rejected the credentials or session for an operation after the single
 * re-authentication attempt (D-020).
 *
 * <p>Raised by the Salesforce client when the OAuth2 token endpoint answers 400 or 401 (operation
 * {@code token}), or when a partner call fails with {@code INVALID_SESSION_ID} again after the one
 * re-authentication retry (operation set to the partner call name, for example {@code create}). The
 * file poller logs it at ERROR with its upstream system and operation and leaves the polled file in
 * the input directory.
 */
public class UpstreamAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that rejected the credentials or session. */
    private final String upstreamSystem;

    /** Name of the upstream operation that failed authentication. */
    private final String operation;

    /**
     * Creates the exception for one failed authentication against the upstream system.
     *
     * @param upstreamSystem name of the upstream system that rejected the credentials or session,
     *                       {@code Salesforce} in this project
     * @param operation      name of the upstream operation that failed, such as {@code token} or
     *                       {@code create}
     * @param message        detail message, in the form {@code Salesforce <operation> failed: <detail>}
     * @param cause          the vendor failure that triggered the exception, or {@code null} when none
     *                       exists
     */
    public UpstreamAuthenticationException(String upstreamSystem, String operation, String message, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
        this.operation = operation;
    }

    /**
     * Returns the name of the upstream system that rejected the credentials or session.
     *
     * @return the upstream system name, {@code Salesforce} in this project
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }

    /**
     * Returns the name of the upstream operation that failed authentication.
     *
     * @return the operation name, such as {@code token} or {@code create}
     */
    public String getOperation() {
        return operation;
    }
}
