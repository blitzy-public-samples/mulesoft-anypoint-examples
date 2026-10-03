package com.mulesoft.examples.document_integration_using_the_cmis_connector.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes the given reason phrase onto the HTTP/1.1 status line of the current Undertow exchange
 * (D-010). Call it after {@code response.setStatus(code)} and before the body is written; the
 * phrase stays on the exchange when the status is set again and is sent with the status code the
 * response carries when it is committed.
 */
public final class ReasonPhrase {

    /** Not instantiable. */
    private ReasonPhrase() {
    }

    /**
     * Sets {@code phrase} as the reason phrase of the Undertow exchange serving the calling thread,
     * leaving the status code unchanged. Throws {@link IllegalStateException} when the calling thread
     * serves no Undertow servlet request (D-228).
     *
     * @param phrase the text written after the status code, for example {@code "Not Found"}
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
