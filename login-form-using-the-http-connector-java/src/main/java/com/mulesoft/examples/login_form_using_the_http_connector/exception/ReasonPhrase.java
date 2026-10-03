package com.mulesoft.examples.login_form_using_the_http_connector.exception;

import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.servlet.spec.HttpServletResponseImpl;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Sets the reason phrase of an HTTP/1.1 status line on Undertow (D-010).
 *
 * <p>Both {@code set} methods store the phrase on an Undertow {@link HttpServerExchange}, and
 * Undertow writes it after the status code when the response is committed. They differ only in how
 * the exchange is located (D-239):
 *
 * <ul>
 *   <li>{@link #set(HttpServletResponse, String)} unwraps every {@link ServletResponseWrapper}
 *       around the given response down to Undertow's {@link HttpServletResponseImpl} and takes its
 *       exchange. When the innermost response is not Undertow's, it takes the exchange of the
 *       {@link ServletRequestContext} bound to the calling thread.
 *   <li>{@link #set(String)} takes the exchange of the {@link ServletRequestContext} bound to the
 *       calling thread.
 * </ul>
 *
 * <p>Neither method changes the status code, a header or the body. Each returns without effect,
 * with no exception and no log entry, when the phrase is {@code null}, when the response is already
 * committed, or when no Undertow exchange is found, for example for a MockMvc
 * {@code MockHttpServletResponse} or in a plain unit test. A response for which no phrase is set
 * carries Undertow's standard phrase for its status code, for example {@code HTTP/1.1 200 OK}.
 *
 * <p>Usage, for the 403 answer of {@code DoLoginFlow}:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_FORBIDDEN);
 * ReasonPhrase.set(response, "Forbidden");
 * response.getOutputStream().write(failurePage);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 403 Forbidden}. The class holds no state and is not
 * instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only its static {@code set} methods. */
    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the status line of {@code response} (D-010). Call after the final
     * status is set and before the body is written.
     *
     * <p>The exchange is located in this order (D-239):
     *
     * <ol>
     *   <li>starting from {@code response}, each {@link ServletResponseWrapper} is replaced by the
     *       response it wraps; when the response reached is Undertow's
     *       {@link HttpServletResponseImpl}, its {@link HttpServletResponseImpl#getExchange()
     *       exchange} is used;
     *   <li>otherwise the exchange of {@link ServletRequestContext#current()} is used, when the
     *       calling thread has a context and the context has an exchange;
     *   <li>otherwise nothing is set.
     * </ol>
     *
     * <p>The phrase is then stored unchanged with {@link HttpServerExchange#setReasonPhrase(String)}.
     * The status code, headers and body are left unchanged. The call returns without effect, and
     * throws nothing, when {@code response} or {@code phrase} is {@code null}, when
     * {@code response.isCommitted()} is {@code true}, or when no exchange is located.
     *
     * @param response the response whose status line carries the phrase
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Forbidden"}; {@code null} leaves the response unchanged
     */
    public static void set(HttpServletResponse response, String phrase) {
        if (phrase == null || response == null || response.isCommitted()) {
            return;
        }
        apply(exchangeOf(response), phrase);
    }

    /**
     * Sets the reason phrase of the status line of the response handled on the calling thread
     * (D-010). Call after the final status is set and before the body is written.
     *
     * <p>The exchange is the one of {@link ServletRequestContext#current()} (D-239). The phrase is
     * stored unchanged with {@link HttpServerExchange#setReasonPhrase(String)}; the status code,
     * headers and body are left unchanged. The call returns without effect, and throws nothing, when
     * {@code phrase} is {@code null}, when the calling thread has no Undertow request context, or
     * when the response is already committed.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Forbidden"}; {@code null} leaves the response unchanged
     */
    public static void set(String phrase) {
        if (phrase == null) {
            return;
        }
        apply(currentExchange(), phrase);
    }

    /**
     * Returns the exchange of the innermost wrapped response when it is Undertow's, otherwise the
     * exchange of the calling thread's request context, or {@code null} when there is neither.
     *
     * @param response the response to unwrap
     * @return the located exchange, or {@code null}
     */
    private static HttpServerExchange exchangeOf(HttpServletResponse response) {
        ServletResponse current = response;
        while (current instanceof ServletResponseWrapper wrapper) {
            current = wrapper.getResponse();
        }
        if (current instanceof HttpServletResponseImpl undertowResponse) {
            return undertowResponse.getExchange();
        }
        return currentExchange();
    }

    /**
     * Returns the exchange of the calling thread's Undertow request context, or {@code null} when the
     * thread has no context or the context has no exchange.
     *
     * @return the exchange of the current context, or {@code null}
     */
    private static HttpServerExchange currentExchange() {
        ServletRequestContext context = ServletRequestContext.current();
        return context == null ? null : context.getExchange();
    }

    /**
     * Stores {@code phrase} on {@code exchange}; does nothing when {@code exchange} is {@code null}
     * or its response has started.
     *
     * @param exchange the exchange that receives the phrase, or {@code null}
     * @param phrase the phrase to store, not {@code null}
     */
    private static void apply(HttpServerExchange exchange, String phrase) {
        if (exchange == null || exchange.isResponseStarted()) {
            return;
        }
        exchange.setReasonPhrase(phrase);
    }
}
