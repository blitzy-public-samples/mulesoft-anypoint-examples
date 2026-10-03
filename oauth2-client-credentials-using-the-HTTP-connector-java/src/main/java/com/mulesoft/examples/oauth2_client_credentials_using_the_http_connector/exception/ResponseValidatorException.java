package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.exception;

/**
 * Raised when an upstream HTTP response has a final status code of 400 or above; the message is
 * {@code Response code <n> mapped as failure.}, where {@code <n>} is that status code (D-207).
 *
 * <p>It corresponds to a failed {@code http:request} of the Box or FIM flow
 * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:34,41-46].
 * {@code client.BoxClient} and {@code client.FimProxyClient} raise it, and
 * {@code GlobalExceptionHandler} answers it as HTTP 500 with {@link #getMessage()} as the body.
 *
 * <p>Example:
 * <pre>{@code
 * ResponseValidatorException ex = new ResponseValidatorException(404);
 * ex.getMessage();    // "Response code 404 mapped as failure."
 * ex.getStatusCode(); // 404
 * }</pre>
 */
public class ResponseValidatorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Final upstream HTTP status code. */
    private final int statusCode;

    /** @param statusCode final upstream HTTP status code, 400 or above */
    public ResponseValidatorException(int statusCode) {
        super("Response code " + statusCode + " mapped as failure.");
        this.statusCode = statusCode;
    }

    /** @return final upstream HTTP status code passed to the constructor */
    public int getStatusCode() {
        return statusCode;
    }
}
