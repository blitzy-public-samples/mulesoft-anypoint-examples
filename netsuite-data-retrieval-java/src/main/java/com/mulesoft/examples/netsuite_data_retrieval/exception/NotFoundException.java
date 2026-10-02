package com.mulesoft.examples.netsuite_data_retrieval.exception;

/**
 * Raised when a requested API or console resource does not exist; mapped to HTTP 404 with
 * {@code Content-Type: application/json} and the body {@code { "message": "Resource not found" }}.
 *
 * <p>Counterpart of the {@code statusCode="404"} branch of
 * {@code netsuite-api-apiKitGlobalExceptionMapping}, which handles
 * {@code org.mule.module.apikit.exception.NotFoundException}.
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception without a detail message. */
    public NotFoundException() {
        super();
    }

    /**
     * Creates the exception with a detail message.
     *
     * @param message the detail message, for example the name of the missing resource
     */
    public NotFoundException(String message) {
        super(message);
    }
}
