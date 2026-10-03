package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the reason phrase of the current HTTP/1.1 response status line on the embedded Undertow
 * server. See D-010.
 *
 * <p>The class holds no state and is not instantiable. Usage from an exception handler method:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_NOT_FOUND);
 * ReasonPhrase.set("Not Found");
 * RawBody.write(response, HttpServletResponse.SC_NOT_FOUND, body);
 * }</pre>
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Writes {@code phrase} as the reason phrase of the current request's status line.
     *
     * <p>Call after the status code is set and before the first body byte is written. The phrase
     * is held by the Undertow exchange of the servlet request handled on the calling thread, and a
     * later status-code change on the same response leaves it in place.
     *
     * @param phrase the reason phrase sent after the status code, for example {@code Not Found}
     * @throws IllegalStateException when no Undertow servlet request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
