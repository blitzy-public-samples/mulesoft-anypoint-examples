package com.mulesoft.examples.proxying_a_rest_api.exception;

/**
 * Signals an upstream response status outside {@code 0..399}; the message is
 * {@code Response code <n> mapped as failure.}, for example
 * {@code Response code 404 mapped as failure.}.
 *
 * <p>{@code RestProxyService.restApiProxy} throws it when the upstream response status lies outside
 * {@code 0..399}, and {@code GlobalExceptionHandler.unexpected} answers it with status 500 and the
 * message as the body.
 *
 * <pre>{@code
 * throw new UpstreamStatusException(404); // getMessage(): "Response code 404 mapped as failure."
 * }</pre>
 *
 * <p>See DECISIONS.md row "proxying-a-rest-api-java — upstream status mapping".
 */
public class UpstreamStatusException extends RuntimeException {

    /** Serialization version of this class. */
    private static final long serialVersionUID = 1L;

    /** The upstream response status code. */
    private final int status;

    /**
     * Creates the exception for one upstream response status, with the message
     * {@code Response code <status> mapped as failure.}.
     *
     * @param status the upstream response status code
     */
    public UpstreamStatusException(int status) {
        super("Response code " + status + " mapped as failure.");
        this.status = status;
    }

    /**
     * Returns the upstream response status code.
     *
     * @return the status code passed to the constructor
     */
    public int status() {
        return status;
    }
}
