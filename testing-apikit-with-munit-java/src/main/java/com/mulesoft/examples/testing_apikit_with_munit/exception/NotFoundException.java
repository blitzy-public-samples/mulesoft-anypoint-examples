package com.mulesoft.examples.testing_apikit_with_munit.exception;

/** Raised when a request targets a resource the API does not declare; rendered as the APIkit 404 body by GlobalExceptionHandler. */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with the given detail message. */
    public NotFoundException(String message) {
        super(message);
    }
}
