package com.mulesoft.examples.import_leads_into_salesforce.exception;

/**
 * Thrown when the upstream system still rejects authentication after one re-authentication and
 * one retry of the call, or when the token endpoint answers a 4xx status other than 429 (D-020).
 *
 * <p>Throw sites in this project, all with upstream system {@code Salesforce}:
 * <ul>
 *   <li>{@code client.SalesforceClient#execute}: a partner call fails with the {@code ApiFault}
 *       code {@code INVALID_SESSION_ID}, the session is renewed once, the call is re-issued once,
 *       and the re-issued call fails with {@code INVALID_SESSION_ID} again.</li>
 *   <li>{@code client.SalesforceSessionProvider}: the OAuth2 token request to
 *       {@code ${sfdc.login-url}/services/oauth2/token} answers a 4xx status other than 429, or
 *       its response yields no usable session (no access token or instance URL, or a partner
 *       connection that cannot be created from them).</li>
 * </ul>
 *
 * <p>{@code service.CreateLeadsBatchJob} catches it for the failing record or poll cycle, logs it at
 * ERROR with {@link #getUpstreamSystem()} and {@link #getMessage()}, and does not resubmit the call.
 *
 * <p>Example:
 * <pre>{@code
 * throw new UpstreamAuthenticationException(
 *         "Salesforce", "Salesforce create rejected the session after re-authentication", fault);
 * }</pre>
 */
public class UpstreamAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Name of the upstream system that rejected authentication. */
    private final String upstreamSystem;

    /**
     * Creates the exception for one authentication failure against the upstream system (D-020).
     *
     * @param upstreamSystem name of the upstream system, {@code Salesforce} in this project
     * @param message        failure description, returned by {@link #getMessage()}
     * @param cause          underlying failure, returned by {@link #getCause()}; may be {@code null},
     *                       as for a token-endpoint 4xx answer with no underlying exception
     */
    public UpstreamAuthenticationException(String upstreamSystem, String message, Throwable cause) {
        super(message, cause);
        this.upstreamSystem = upstreamSystem;
    }

    /**
     * Returns the name of the upstream system that rejected authentication (D-020).
     *
     * @return the upstream system name passed to the constructor, {@code Salesforce} in this project
     */
    public String getUpstreamSystem() {
        return upstreamSystem;
    }
}
