package com.mulesoft.examples.scatter_gather_flow_control.exception;

import io.undertow.servlet.handlers.ServletRequestContext;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Writes a custom HTTP/1.1 reason phrase on the current Undertow exchange (D-010).
 *
 * <p>Both {@code set} methods store the phrase on the {@code HttpServerExchange} of the
 * {@link ServletRequestContext} bound to the calling thread, and Undertow writes it after the status
 * code when the response is committed. {@link #set(HttpServletResponse, String)} checks the commit
 * state of the response it is given; {@link #set(String)} checks the commit state of Undertow's own
 * response for the current request (D-250).
 *
 * <p>Neither method changes the status code, a header or the body. Each returns without effect, with
 * no exception and no log entry, when the calling thread has no Undertow request context (for
 * example a MockMvc request or a plain unit test) or when the response is already committed. A
 * response with no phrase set carries Undertow's standard phrase for its status code, for example
 * {@code HTTP/1.1 500 Internal Server Error}.
 *
 * <p>Usage, for the answer to a path with no handler:
 *
 * <pre>{@code
 * response.setStatus(404);
 * ReasonPhrase.set(response, "Not Found");
 * response.getOutputStream().write(body);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 404 Not Found}. The class holds no state and is not
 * instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only its static {@code set} methods. */
    private ReasonPhrase() {
    }

    /**
     * Sets {@code phrase} as the reason phrase of the current Undertow exchange when one exists and
     * {@code response} is not committed; does nothing otherwise (D-010). Callers set the status
     * first, then the reason phrase, then write the body.
     *
     * <p>The phrase is stored unchanged with {@code HttpServerExchange.setReasonPhrase}; a {@code null}
     * phrase clears a phrase set earlier, and the status line then carries Undertow's standard phrase.
     * The status code, headers and body of {@code response} are left unchanged.
     *
     * @param response the response of the current request, not {@code null}; its
     *     {@link HttpServletResponse#isCommitted()} state is checked before the phrase is stored
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Not Found"}
     */
    public static void set(HttpServletResponse response, String phrase) {
        ServletRequestContext ctx = ServletRequestContext.current();
        if (ctx != null && !response.isCommitted()) {
            ctx.getExchange().setReasonPhrase(phrase);
        }
    }

    /**
     * Sets {@code phrase} as the reason phrase of the current Undertow exchange when one exists and
     * Undertow's response for the current request is not committed; does nothing otherwise (D-010,
     * D-250). Callers set the status first, then the reason phrase, then write the body.
     *
     * <p>Equivalent to {@link #set(HttpServletResponse, String)} called with
     * {@link ServletRequestContext#getOriginalResponse()} of the current context.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Not Found"}
     */
    public static void set(String phrase) {
        ServletRequestContext ctx = ServletRequestContext.current();
        if (ctx != null) {
            set(ctx.getOriginalResponse(), phrase);
        }
    }
}
