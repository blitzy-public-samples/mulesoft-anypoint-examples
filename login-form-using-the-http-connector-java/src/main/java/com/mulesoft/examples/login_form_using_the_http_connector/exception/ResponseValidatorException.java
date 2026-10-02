package com.mulesoft.examples.login_form_using_the_http_connector.exception;

/**
 * Thrown when the login requester receives a response status outside {@code 0..399}; the message
 * is {@code Response code <n> mapped as failure.}, for example
 * {@code Response code 403 mapped as failure.}.
 *
 * <p>{@code LoginRequesterClient.postLogin} throws it for the {@code POST /login} call of the
 * {@code CallLoginFlowUsingRequester} flow. {@code LoginService.callLoginFlowUsingRequester} lets
 * it propagate, and {@code GlobalExceptionHandler.unexpected} answers {@code /requesterLogin} with
 * status 500.
 *
 * <pre>{@code
 * throw new ResponseValidatorException("Response code " + status + " mapped as failure.");
 * }</pre>
 */
public class ResponseValidatorException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with the complete failure text, stored unchanged.
     *
     * @param message the failure text, {@code Response code <n> mapped as failure.}
     */
    public ResponseValidatorException(String message) {
        super(message);
    }
}
