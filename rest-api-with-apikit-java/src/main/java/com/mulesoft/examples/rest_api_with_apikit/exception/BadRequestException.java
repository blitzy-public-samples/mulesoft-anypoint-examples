package com.mulesoft.examples.rest_api_with_apikit.exception;

/**
 * Signals a request that violates the RAML contract; answered as 400 {@code { "message": "Bad request" }}
 * by {@code GlobalExceptionHandler} (D-046 for the schema draft).
 *
 * <p>Raised by {@code config.RamlRequestValidator} for a URI parameter outside its declared length
 * ({@code teamId}, {@code homeTeamId} and {@code awayTeamId} are exactly 3 characters), a query parameter
 * that is missing when required or fails its declared type or enum, and a request body that is absent,
 * unparsable or invalid against its draft-03 request schema ({@code teams-schema-output.json},
 * {@code teamid-schema-input.json}, {@code match-schema-input.json}). The message describes the violation
 * and is not written to the response body.
 */
public class BadRequestException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with a message that describes the violated RAML constraint.
     *
     * @param message the violated constraint, such as the parameter or schema and the failed check
     */
    public BadRequestException(String message) {
        super(message);
    }
}
