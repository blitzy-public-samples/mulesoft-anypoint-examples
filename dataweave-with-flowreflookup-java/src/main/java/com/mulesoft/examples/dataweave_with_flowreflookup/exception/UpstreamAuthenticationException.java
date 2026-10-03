package com.mulesoft.examples.dataweave_with_flowreflookup.exception;

/**
 * Signals that Salesforce rejected authentication for the account import. See D-020.
 *
 * <p>Represents one of two conditions:
 * <ul>
 *   <li>Salesforce authentication failed at the OAuth2 token endpoint: the username-password grant
 *       was answered with HTTP 400 or 401;</li>
 *   <li>the Salesforce session remained invalid ({@code INVALID_SESSION_ID}) after one
 *       re-authentication and the single re-issue of the partner call.</li>
 * </ul>
 */
public class UpstreamAuthenticationException extends RuntimeException {

    /** Serialization version of this class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that rejected authentication. */
    private final String upstreamSystem;

    /**
     * Creates the exception with a detail message, the rejecting system and the underlying failure.
     *
     * @param message        detail message returned by {@link #getMessage()}
     * @param upstreamSystem name of the upstream system that rejected authentication,
     *                       {@code Salesforce} in this project
     * @param cause          the vendor failure classified as an authentication failure, returned by
     *                       {@link #getCause()}; {@code null} when none exists
     */
    public UpstreamAuthenticationException(String message, String upstreamSystem, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that rejected authentication.
     *
     * @return the upstream system name passed to the constructor, {@code Salesforce} in this project
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
