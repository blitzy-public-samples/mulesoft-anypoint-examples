package com.mulesoft.examples.proxying_a_soap_api.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom HTTP/1.1 reason phrase on the current Undertow exchange (D-010).
 *
 * <p>The phrase applies when it is set after the status and before the response is committed, that is
 * before the first body byte is written or the response is flushed. A response for which no phrase is
 * set carries Undertow's standard phrase for its status code, for example {@code HTTP/1.1 200 OK}.
 * HTTP/2 responses carry no reason phrase.
 *
 * <p>Usage from an exception handler on the request thread:
 *
 * <pre>{@code
 * response.setStatus(404);
 * ReasonPhrase.set("Not Found");
 * RawBody.write(response, 404, body);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 404 Not Found}. The class holds no state and is not
 * instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() { }

    /**
     * Sets {@code phrase} as the reason phrase of the Undertow exchange of the servlet request handled
     * on the calling thread. Undertow writes it after the status code on the HTTP/1.1 status line when
     * the response is committed (D-010, D-253).
     *
     * <p>The phrase is passed unchanged to {@code HttpServerExchange.setReasonPhrase}; {@code null}
     * keeps Undertow's standard phrase for the status code. A phrase set after the response is
     * committed does not reach the status line.
     *
     * @param phrase the reason phrase written on the status line, for example {@code "Not Found"}
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread,
     *     for example under MockMvc or in a plain unit test
     */
    public static void set(String phrase) {
        // The exchange comes from the Undertow request context bound to the calling thread; the
        // signature is set(String), with no HttpServletRequest parameter (D-253).
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
