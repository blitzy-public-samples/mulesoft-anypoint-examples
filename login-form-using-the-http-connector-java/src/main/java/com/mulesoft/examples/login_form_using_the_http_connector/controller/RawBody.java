package com.mulesoft.examples.login_form_using_the_http_connector.controller;

import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;
import io.undertow.servlet.spec.HttpServletResponseImpl;
import io.undertow.util.Headers;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.ServletResponseWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import com.mulesoft.examples.login_form_using_the_http_connector.exception.ReasonPhrase;

/**
 * Writes an HTTP status and raw body bytes to a servlet response through its output stream, with
 * a {@code Content-Type} header only when the caller names one, and then exactly as named (D-066).
 *
 * <p>The three {@code write} overloads differ only in what they add to the status and the body:
 *
 * <ul>
 *   <li>{@link #write(HttpServletResponse, int, byte[])} sends no {@code Content-Type}, the answer
 *       for a {@code parse-template} or {@code set-payload} payload without a MIME type, for example
 *       the login page of {@code GetLoginPageFlow} [login-form-using-the-http-connector.xml:11];
 *   <li>{@link #write(HttpServletResponse, int, String, byte[])} sends the given
 *       {@code Content-Type} value byte for byte, for example {@code text/html; charset=UTF-8} of
 *       {@code DoLoginFlow} [login-form-using-the-http-connector.xml:25], or none for {@code null};
 *   <li>{@link #write(HttpServletResponse, int, String, String, byte[])} also writes a custom
 *       reason phrase on the status line through {@link ReasonPhrase} (D-010), for example
 *       {@code HTTP/1.1 403 Forbidden} for the catch strategy of {@code DoLoginFlow}
 *       [login-form-using-the-http-connector.xml:27-30].
 * </ul>
 *
 * <p>Every overload sets {@code Content-Length} to the body length, writes the bytes unchanged,
 * flushes the output stream and leaves it open for the container (D-458). A {@code null} body is
 * written as zero bytes, sent as {@code Content-Length: 0} with no body. No character encoding or
 * locale is set, the response writer is never opened, and no header other than
 * {@code Content-Length} and the named {@code Content-Type} is added.
 *
 * <p>On Undertow the {@code Content-Type} value is put directly into the response headers of the
 * {@link HttpServerExchange}; the servlet API's content-type parsing is not involved, and the value
 * is sent unchanged. Without an Undertow exchange, for example for a MockMvc
 * {@code MockHttpServletResponse}, the value is set with
 * {@link HttpServletResponse#setHeader(String, String)}.
 *
 * <p>Usage from handler methods that return {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 200, loginPage);                                   // no Content-Type
 * RawBody.write(response, 200, "text/html; charset=UTF-8", successPage);     // exact header
 * RawBody.write(response, 403, "Forbidden", null, failurePage);              // 403 Forbidden
 * }</pre>
 *
 * <p>The class holds no state, is safe for concurrent use on distinct responses and is not
 * instantiable.
 */
public final class RawBody {

    /** Not instantiable; the class exposes only its static {@code write} methods. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} without a {@code Content-Type}
     * header (D-066).
     *
     * <p>The steps, in order:
     *
     * <ol>
     *   <li>the response status is set to {@code status};
     *   <li>{@code Content-Length} is set to the body length (D-458);
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()}, and the stream is flushed, also for an
     *       empty body.
     * </ol>
     *
     * @param response the servlet response to write to, not yet committed
     * @param status the HTTP status code, for example {@code 200}
     * @param body the body bytes, sent unchanged; {@code null} or an empty array sends
     *     {@code Content-Length: 0} and no body
     * @throws NullPointerException when {@code response} is {@code null}; the response is then left
     *     untouched
     * @throws IOException when the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body)
            throws IOException {
        response.setStatus(status);
        body(response, body);
    }

    /**
     * Writes {@code status}, the exact {@code Content-Type} value {@code contentType} and
     * {@code body} to {@code response} (D-066).
     *
     * <p>The steps, in order:
     *
     * <ol>
     *   <li>the response status is set to {@code status};
     *   <li>when {@code contentType} is not {@code null}, the {@code Content-Type} header is set to
     *       exactly that value, for example {@code text/html; charset=UTF-8}; {@code null} sends no
     *       {@code Content-Type};
     *   <li>{@code Content-Length} is set to the body length (D-458);
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()}, and the stream is flushed.
     * </ol>
     *
     * @param response the servlet response to write to, not yet committed
     * @param status the HTTP status code, for example {@code 200}
     * @param contentType the {@code Content-Type} header value, sent byte for byte, or {@code null}
     *     for no {@code Content-Type}
     * @param body the body bytes, sent unchanged; {@code null} or an empty array sends
     *     {@code Content-Length: 0} and no body
     * @throws NullPointerException when {@code response} is {@code null}; the response is then left
     *     untouched
     * @throws IOException when the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, String contentType,
            byte[] body) throws IOException {
        response.setStatus(status);
        if (contentType != null) {
            contentType(response, contentType);
        }
        body(response, body);
    }

    /**
     * Writes {@code status}, the reason phrase {@code reasonPhrase}, the exact {@code Content-Type}
     * value {@code contentType} and {@code body} to {@code response} (D-066, D-010).
     *
     * <p>The steps, in this order, with the status set once and never again after the phrase:
     *
     * <ol>
     *   <li>the response status is set to {@code status};
     *   <li>{@link ReasonPhrase#set(HttpServletResponse, String)} writes {@code reasonPhrase} to the
     *       status line, for example {@code HTTP/1.1 403 Forbidden}; {@code null} keeps Undertow's
     *       standard phrase, and without an Undertow exchange the call has no effect (D-010);
     *   <li>when {@code contentType} is not {@code null}, the {@code Content-Type} header is set to
     *       exactly that value; {@code null} sends no {@code Content-Type};
     *   <li>{@code Content-Length} is set to the body length (D-458);
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()}, and the stream is flushed.
     * </ol>
     *
     * @param response the servlet response to write to, not yet committed
     * @param status the HTTP status code, for example {@code 403}
     * @param reasonPhrase the text written after the status code on the status line, for example
     *     {@code Forbidden}, or {@code null} for the standard phrase of {@code status}
     * @param contentType the {@code Content-Type} header value, sent byte for byte, or {@code null}
     *     for no {@code Content-Type}
     * @param body the body bytes, sent unchanged; {@code null} or an empty array sends
     *     {@code Content-Length: 0} and no body
     * @throws NullPointerException when {@code response} is {@code null}; the response is then left
     *     untouched
     * @throws IOException when the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, String reasonPhrase,
            String contentType, byte[] body) throws IOException {
        response.setStatus(status);
        ReasonPhrase.set(response, reasonPhrase);
        if (contentType != null) {
            contentType(response, contentType);
        }
        body(response, body);
    }

    /**
     * Sets the {@code Content-Type} header of {@code response} to exactly {@code contentType}.
     * With an Undertow exchange the value is put into the exchange's response headers, replacing
     * any earlier value; otherwise it is set with
     * {@link HttpServletResponse#setHeader(String, String)}.
     *
     * @param response the response that receives the header
     * @param contentType the header value, not {@code null}
     */
    private static void contentType(HttpServletResponse response, String contentType) {
        HttpServerExchange exchange = exchange(response);
        if (exchange != null) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, contentType);
        } else {
            response.setHeader("Content-Type", contentType);
        }
    }

    /**
     * Returns the Undertow exchange behind {@code response}: the exchange of the innermost wrapped
     * response when it is Undertow's {@link HttpServletResponseImpl}, otherwise the exchange of the
     * calling thread's {@link ServletRequestContext}, or {@code null} when there is neither.
     *
     * @param response the response to unwrap
     * @return the located exchange, or {@code null}
     */
    private static HttpServerExchange exchange(HttpServletResponse response) {
        ServletResponse current = response;
        while (current instanceof ServletResponseWrapper wrapper) {
            current = wrapper.getResponse();
        }
        if (current instanceof HttpServletResponseImpl undertowResponse) {
            return undertowResponse.getExchange();
        }
        ServletRequestContext context = ServletRequestContext.current();
        return context == null ? null : context.getExchange();
    }

    /**
     * Sets {@code Content-Length} to the body length, writes the body bytes unchanged through the
     * servlet output stream and flushes the stream, leaving it open (D-458). A {@code null} body is
     * written as zero bytes.
     *
     * @param response the response to write to
     * @param body the body bytes, or {@code null} for an empty body
     * @throws IOException when the output stream cannot be obtained, written or flushed
     */
    private static void body(HttpServletResponse response, byte[] body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body;
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}
