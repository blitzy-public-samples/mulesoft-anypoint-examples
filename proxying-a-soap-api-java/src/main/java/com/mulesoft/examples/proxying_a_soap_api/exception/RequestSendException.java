package com.mulesoft.examples.proxying_a_soap_api.exception;

/**
 * Signals that the {@code http:request} of flow {@code main}
 * [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:10] could not be sent: an I/O error, a refused
 * connection or a timeout. The message is always {@code Error sending HTTP request.} and the cause is the
 * transport exception (D-023).
 *
 * <p>{@code client.ShopServiceProxyClient} raises it when the upstream ShopService cannot be reached, and
 * {@code GlobalExceptionHandler.unexpected} answers it with status 500 and a {@code soap:Server} fault whose
 * {@code faultstring} is the message. Nothing is retried.
 *
 * <pre>{@code
 * RequestSendException ex = new RequestSendException(new java.net.ConnectException("Connection refused"));
 * ex.getMessage(); // "Error sending HTTP request."
 * ex.getCause();   // the ConnectException
 * }</pre>
 */
public class RequestSendException extends RuntimeException {

    /** Serialization version of this class. */
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with the message {@code Error sending HTTP request.} and the given cause.
     *
     * @param cause the transport exception raised while sending the request
     */
    public RequestSendException(Throwable cause) {
        super("Error sending HTTP request.", cause);
    }
}
