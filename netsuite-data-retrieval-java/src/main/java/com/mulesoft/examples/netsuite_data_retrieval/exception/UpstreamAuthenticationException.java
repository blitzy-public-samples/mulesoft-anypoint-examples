package com.mulesoft.examples.netsuite_data_retrieval.exception;

/**
 * Raised when NetSuite rejects authentication after one re-authentication and retry (D-020);
 * mapped to HTTP 502 with an {@code ErrorResponse} whose {@code upstreamSystem} is
 * {@code NetSuite}.
 *
 * <p>The NetSuite client throws it from its {@code execute(operation, ...)} wrapper when a
 * request answered with HTTP 401 is re-issued once with a newly obtained OAuth 2.0 M2M access
 * token and NetSuite answers HTTP 401 again. The message has the form
 * {@code NetSuite authentication failed: <operation>}, for example
 * {@code NetSuite authentication failed: queryIds customer}, and the cause is the error of the
 * second attempt.
 *
 * <pre>{@code
 * throw new UpstreamAuthenticationException(
 *         "NetSuite authentication failed: " + operation, secondAttemptFailure);
 * }</pre>
 */
public class UpstreamAuthenticationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with the client's failure message and the error of the retried
     * request.
     *
     * @param message the failure message, {@code NetSuite authentication failed: <operation>}
     * @param cause the failure of the retried request; may be {@code null}
     */
    public UpstreamAuthenticationException(String message, Throwable cause) {
        super(message, cause);
    }
}
