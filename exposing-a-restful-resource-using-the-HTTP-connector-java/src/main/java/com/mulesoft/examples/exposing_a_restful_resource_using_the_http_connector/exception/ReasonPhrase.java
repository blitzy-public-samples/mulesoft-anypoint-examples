/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.exception;

import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the response served by embedded
 * Undertow (D-010).
 *
 * <p>Replaces the {@code reasonPhrase="#[errorReasonPhrase]"} attribute of the
 * {@code http:error-response-builder} [http-restful-resource.xml:29].
 * {@code GlobalExceptionHandler.notFound} calls {@link #set(String)} with {@code Not Found}, the
 * value the rollback branch assigns to {@code errorReasonPhrase} [http-restful-resource.xml:40], and
 * the status line reads {@code HTTP/1.1 404 Not Found}.
 *
 * <p>Usage on the request thread, before the response is committed:
 *
 * <pre>{@code
 * ReasonPhrase.set("Not Found");
 * return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() { }

    /**
     * Sets the reason phrase of the current HTTP/1.1 response status line on the Undertow exchange,
     * and re-applies it when the response is committed (D-010).
     *
     * <p>Call it once per response, on the request thread, before the response body is written. When
     * it is called more than once for the same response, the status line carries the phrase of the
     * first call (D-213).
     *
     * @param phrase the reason phrase written after the status code on the HTTP/1.1 status line,
     *     for example {@code "Not Found"}
     */
    public static void set(String phrase) {
        HttpServerExchange exchange = ServletRequestContext.requireCurrent().getExchange();
        exchange.setReasonPhrase(phrase);
        exchange.addResponseCommitListener(ex -> ex.setReasonPhrase(phrase));
    }
}
