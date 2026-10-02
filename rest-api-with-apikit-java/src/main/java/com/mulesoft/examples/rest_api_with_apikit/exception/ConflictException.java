/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.exception;

/**
 * Signals a request that conflicts with the league state; answered as a bodyless 409 (D-008).
 *
 * <p>{@code LeagueService} throws it when a team with the requested id already exists and when a
 * score is set for a match that has not been played yet. {@code GlobalExceptionHandler.conflict}
 * answers it with status 409, no body and no {@code Content-Type}; the message never reaches the
 * response.
 */
public class ConflictException extends RuntimeException {

    private static final long serialVersionUID = 3387516993124229969L;

    /**
     * Creates the exception with the conflict description.
     *
     * @param message the conflict description, for example {@code "There is already a team with id BAR"}
     */
    public ConflictException(String message) {
        super(message);
    }
}
