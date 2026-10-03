package com.mulesoft.examples.querying_a_mysql_database.exception;

import io.undertow.servlet.handlers.ServletRequestContext;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;

/**
 * Sets the reason phrase written on the HTTP/1.1 status line of the current Undertow exchange (D-010).
 *
 * <p>The phrase is stored with {@code HttpServerExchange.setReasonPhrase} on the exchange of the Undertow servlet
 * request handled on the calling thread. When the response is committed, Undertow writes the stored phrase after the
 * status code the response carries at that point. A response for which no phrase is set carries Undertow's standard
 * phrase for its status code, for example {@code HTTP/1.1 200 OK}.
 *
 * <p>The caller sets the final status first and calls {@code set} before the response is committed. Both methods work
 * only on the embedded Undertow server: on a thread without an Undertow servlet request, for example in a MockMvc
 * request or a plain unit test, they throw {@link IllegalStateException} (D-257).
 *
 * <p>{@link #set(HttpServletResponse, String)} is the form {@code GlobalExceptionHandler#noListener} calls for a path
 * that matches no listener:
 *
 * <pre>{@code
 * response.setStatus(404);
 * ReasonPhrase.set(response, "Not Found");
 * RawBody.write(response, 404, body);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 404 Not Found}. {@link #set(String)} is the one-argument form of the
 * same operation. The class holds no state and is not instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only its static {@code set} methods. */
    private ReasonPhrase() { }

    /**
     * Sets the reason phrase on the status line of {@code response}, the response of the Undertow servlet request
     * handled on the calling thread (D-010, D-257).
     *
     * <p>A {@code null} response is rejected with {@link NullPointerException} and a committed response with
     * {@link IllegalStateException}; otherwise the phrase is stored exactly as {@link #set(String)} stores it. The
     * status code, headers and body are left unchanged. Call after the final status is set.
     *
     * @param response the response whose status line is being completed; not {@code null} and not yet committed
     * @param phrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@code "Not Found"}; {@code null} leaves Undertow's standard phrase for the status code
     * @throws NullPointerException if {@code response} is {@code null}
     * @throws IllegalStateException if {@code response} is already committed, or if no Undertow servlet request is
     *     active on the calling thread
     */
    public static void set(HttpServletResponse response, String phrase) {
        Objects.requireNonNull(response, "response");
        if (response.isCommitted()) {
            throw new IllegalStateException("Response already committed; reason phrase not applied: " + phrase);
        }
        set(phrase);
    }

    /**
     * Sets the reason phrase of the response to the Undertow servlet request handled on the calling thread (D-010).
     *
     * <p>The phrase is passed unchanged to {@code HttpServerExchange.setReasonPhrase} on the exchange of
     * {@link ServletRequestContext#requireCurrent()}. The status code, headers and body are left unchanged. Call after
     * the final status is set and before the response is committed; a phrase set after commit does not reach the
     * status line.
     *
     * @param phrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@code "Not Found"}; {@code null} leaves Undertow's standard phrase for the status code
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
