package com.mulesoft.examples.import_leads_into_salesforce.exception;

/**
 * Thrown when the upstream system cannot be reached or does not answer in time, or when the token endpoint
 * answers a 5xx status (D-020).
 *
 * <p>Throw sites:
 * <ul>
 *   <li>{@code client.SalesforceClient.execute}: the cause chain of the failed vendor call contains a
 *       {@code java.net.SocketTimeoutException}, a {@code java.net.ConnectException} or any other
 *       {@code java.io.IOException}, including one wrapped in a {@code com.sforce.ws.ConnectionException}.
 *       {@link #getUpstreamSystem()} is {@code "Salesforce"}, and exactly one outbound attempt precedes the
 *       exception.</li>
 *   <li>{@code client.SalesforceSessionProvider}: the OAuth2 token endpoint
 *       {@code ${sfdc.login-url}/services/oauth2/token} answers a 5xx status, or the token request cannot reach
 *       it or receives no answer in time.</li>
 * </ul>
 *
 * <p>{@code service.CreateLeadsBatchJob} logs the exception at ERROR with {@link #getUpstreamSystem()} and fails
 * the affected record or commit block. The failed call is made once only and nothing is resubmitted: a
 * Salesforce {@code create} write is never re-sent (D-020).
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamUnavailableException(
 *         "Salesforce", "Salesforce create failed to reach the service", connectionException);
 * }</pre>
 */
public class UpstreamUnavailableException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that could not be reached, for example {@code "Salesforce"}. */
    private final String upstreamSystem;

    /**
     * Creates the exception for one failed attempt to reach an upstream system (D-020).
     *
     * @param upstreamSystem name of the upstream system that could not be reached, for example
     *                       {@code "Salesforce"}; returned unchanged by {@link #getUpstreamSystem()}
     * @param message        description of the failed operation, for example
     *                       {@code "Salesforce query failed to reach the service"}; returned by
     *                       {@link #getMessage()}
     * @param cause          the underlying failure, such as the I/O exception or the
     *                       {@code ConnectionException} wrapping it; returned by {@link #getCause()}, and
     *                       {@code null} when the failure has no underlying exception
     */
    public UpstreamUnavailableException(String upstreamSystem, String message, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that could not be reached (D-020).
     *
     * @return the upstream system name passed to the constructor, for example {@code "Salesforce"}
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
