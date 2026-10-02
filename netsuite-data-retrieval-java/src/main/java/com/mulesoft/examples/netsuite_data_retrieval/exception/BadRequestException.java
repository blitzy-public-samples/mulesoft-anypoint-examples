package com.mulesoft.examples.netsuite_data_retrieval.exception;

/**
 * Raised when a request violates the RAML contract or carries a value the query builder refuses
 * (FB-NS-06, D-016); mapped to HTTP 400.
 *
 * <p>Thrown for a {@code quantity} query parameter that is not an integer, an {@code operator}
 * query parameter outside {@code LESS_THAN}, {@code GREATER_THAN}, {@code EQUAL_TO},
 * {@code LESS_THAN_OR_EQUAL_TO} and {@code GREATER_THAN_OR_EQUAL_TO}, and a {@code name} or
 * {@code title} value that contains a double quote. {@code GlobalExceptionHandler.badRequest}
 * answers it with status 400, {@code Content-Type: application/json} and the body
 * {@code { "message": "Bad request" }}; the exception message is never written to the response.
 *
 * <p>Usage: {@code throw new BadRequestException("operator is not a RAML enum value: " + value);}
 */
public class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates an exception with no detail message. */
    public BadRequestException() {
        super();
    }

    /**
     * Creates an exception with the given detail message.
     *
     * @param message the detail message; never written to the response
     */
    public BadRequestException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given detail message and cause.
     *
     * @param message the detail message; never written to the response
     * @param cause the exception that triggered the rejection, for example a
     *     {@link NumberFormatException} from parsing {@code quantity}
     */
    public BadRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
