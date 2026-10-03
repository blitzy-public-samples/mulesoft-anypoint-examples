package com.mulesoft.examples.netsuite_data_retrieval.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the reason phrase of the current Undertow HTTP/1.1 response (D-010).
 *
 * <p>{@link #set(String)} stores the phrase on the Undertow exchange of the servlet request bound
 * to the calling thread. When the response is committed, Undertow writes the stored phrase after the
 * status code the response carries at that point, for example {@code HTTP/1.1 400 Invalid input data}.
 * A response for which no phrase is set carries Undertow's standard phrase for its status code, for
 * example {@code HTTP/1.1 404 Not Found}.
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class ReasonPhrase {

    private ReasonPhrase() { }

    /**
     * Sets {@code phrase} as the reason phrase of the current Undertow HTTP/1.1 response (D-010).
     *
     * <p>The call takes effect when made on the request thread before the response is committed.
     * The value is passed to {@code HttpServerExchange.setReasonPhrase} unchanged; {@code null}
     * restores Undertow's standard phrase for the status code.
     *
     * @param phrase the text written after the status code on the status line
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
