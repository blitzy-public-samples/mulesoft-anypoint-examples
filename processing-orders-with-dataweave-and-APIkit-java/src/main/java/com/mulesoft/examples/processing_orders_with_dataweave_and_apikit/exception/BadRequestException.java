package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.exception;

/**
 * Signals a request that violates the RAML contract; answered with HTTP 400 by
 * {@code GlobalExceptionHandler.badRequest}.
 */
public class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with the given detail message.
     *
     * @param message the violation description returned by {@link #getMessage()}
     */
    public BadRequestException(String message) {
        super(message);
    }
}
