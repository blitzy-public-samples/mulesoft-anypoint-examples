/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception;

/**
 * Raised when a person lookup finds no stored person for the requested id; answered with 404
 * {@code Not Found} by {@code GlobalExceptionHandler}.
 *
 * <p>Signals the rejection by the {@code message-filter} with {@code throwOnUnaccepted="true"}
 * around the {@code personDataStore.containsKey(personId)} expression filter
 * [http-restful-resource.xml:33-35]. {@code PersonService.retrievePersonFlow} throws it before the
 * lookup; {@code GlobalExceptionHandler.notFound} answers it with status 404, reason phrase
 * {@code Not Found} and an empty body [http-restful-resource.xml:38-41].
 */
public class FilterUnacceptedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with the given detail message.
     *
     * @param message the detail message returned by {@link #getMessage()}
     */
    public FilterUnacceptedException(String message) {
        super(message);
    }
}
