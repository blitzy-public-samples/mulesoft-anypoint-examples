package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.exception;

/**
 * Signals a request for a resource that does not exist; answered with HTTP 404 and
 * {@code { "message": "Resource not found" }} by {@code GlobalExceptionHandler.notFound} (D-007).
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with the given detail message.
     *
     * @param message the detail message
     */
    public NotFoundException(String message) {
        super(message);
    }
}
