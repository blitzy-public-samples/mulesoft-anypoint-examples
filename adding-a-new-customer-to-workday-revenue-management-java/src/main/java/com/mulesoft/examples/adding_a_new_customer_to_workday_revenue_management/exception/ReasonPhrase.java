package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the reason phrase of the current Undertow response; CR and LF are removed; no effect outside
 * an Undertow request (D-010, D-178).
 *
 * <p>{@link #set(String)} stores the phrase on the Undertow exchange of the request handled on the
 * calling thread. Undertow writes it after the status code on the HTTP/1.1 status line when the
 * response is committed. A response for which no phrase is set carries Undertow's standard phrase
 * for its status code, for example {@code HTTP/1.1 200 OK}.
 *
 * <p>Call order in a handler: the status first, then the phrase, then the body, all before the
 * response is committed.
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
 * ReasonPhrase.set("Method not allowed for endpoint: /");
 * RawBody.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, body);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 405 Method not allowed for endpoint: /}. A phrase
 * set after the response is committed does not reach the status line.
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the current Undertow response; CR and LF are removed; no effect
     * outside an Undertow request. Call after the status is set and before the body is written
     * (D-010, D-178).
     *
     * <p>Every CR ({@code \r}) and LF ({@code \n}) character of {@code phrase} is removed and the
     * remaining text is stored unchanged on the {@code HttpServerExchange} of the request handled on
     * the calling thread. The status code is left unchanged. The call returns without effect, and
     * throws nothing, when {@code phrase} is {@code null} or when the calling thread handles no
     * Undertow servlet request, for example under {@code MockMvc} or in a plain unit test.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "No listener for endpoint: /x"}; {@code null} leaves the response unchanged
     */
    public static void set(String phrase) {
        if (phrase == null) {
            return;
        }
        String stripped = phrase.replace("\r", "").replace("\n", "");
        ServletRequestContext context = ServletRequestContext.current();
        if (context == null) {
            return;
        }
        context.getExchange().setReasonPhrase(stripped);
    }
}
