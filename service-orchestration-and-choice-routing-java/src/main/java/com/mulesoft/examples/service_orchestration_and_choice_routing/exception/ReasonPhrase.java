package com.mulesoft.examples.service_orchestration_and_choice_routing.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes the given reason phrase on the HTTP/1.1 status line of the current Undertow response; no
 * effect outside an Undertow request (D-010).
 *
 * <p>Responses for which no phrase is set carry Undertow's standard phrase for their status code,
 * for example {@code 200 OK}. Each project carries its own copy of this class (D-004).
 *
 * <p>Usage, after the status is set and before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}. The class holds no state
 * and is not instantiable.
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Writes the given reason phrase on the HTTP/1.1 status line of the current Undertow response;
     * no effect outside an Undertow request (D-010), for example in a plain unit test, a MockMvc
     * request or a non-Undertow container (D-168). Leaves the status code unchanged.
     *
     * <p>Precondition: the caller sets the status code first and calls this method before the
     * response is committed. A phrase set after the response is committed does not reach the
     * status line.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Invalid input data"}
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context == null) {
            return;
        }
        context.getExchange().setReasonPhrase(phrase);
    }
}
