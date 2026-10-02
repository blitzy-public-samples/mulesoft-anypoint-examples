package com.mulesoft.examples.rest_api_with_apikit.exception;

/**
 * Signals an unknown team or match; answered as 404 {@code { "message": "Resource not found" }}
 * by {@code GlobalExceptionHandler}.
 *
 * <p>The message names the missing team or match, in the form
 * {@code "Team <teamId> does not exist"} or
 * {@code "There is no match between team <homeTeamId> and team <awayTeamId>"}. The response body
 * never carries the message.
 */
public class NotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message that names the missing team or match.
     *
     * @param message the detail message returned by {@link #getMessage()}
     */
    public NotFoundException(String message) {
        super(message);
    }
}
