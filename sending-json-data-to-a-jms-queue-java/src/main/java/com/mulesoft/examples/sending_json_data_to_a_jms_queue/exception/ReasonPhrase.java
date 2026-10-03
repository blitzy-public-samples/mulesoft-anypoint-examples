package com.mulesoft.examples.sending_json_data_to_a_jms_queue.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the reason phrase written on the HTTP/1.1 status line of the current Undertow response (D-010).
 */
public final class ReasonPhrase {

    private ReasonPhrase() { }

    /**
     * Writes {@code phrase} as the reason phrase of the response to the Undertow servlet request
     * being handled on the calling thread.
     *
     * @param phrase the reason phrase to send after the status code
     * @throws IllegalStateException if no Undertow servlet request is current on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
