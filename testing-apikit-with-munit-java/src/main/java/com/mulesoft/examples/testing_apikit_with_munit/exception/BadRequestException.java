package com.mulesoft.examples.testing_apikit_with_munit.exception;

/** A request that violates the RAML contract, rendered by GlobalExceptionHandler as the APIkit 400 body. */
public class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BadRequestException(String message) {
        super(message);
    }
}
