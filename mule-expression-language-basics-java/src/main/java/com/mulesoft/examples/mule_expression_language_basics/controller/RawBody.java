package com.mulesoft.examples.mule_expression_language_basics.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/**
 * Writes an HTTP status, a {@code Content-Length} header and raw body bytes to a servlet response;
 * sets no {@code Content-Type} (D-066).
 *
 * <p>{@code Content-Length} is the only header this class sets (D-152). Headers the caller set
 * before the call are sent as set. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_OK, "Hello Mule".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The reply above carries {@code Content-Length: 10} and no {@code Content-Type}. The class
 * holds no state and is not instantiable.
 */
public final class RawBody {

    /** Not instantiable; the class has only static members. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and flushes the response output
     * stream; sets no {@code Content-Type} (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>{@code response} and {@code body} are checked for {@code null}; a {@code null} argument
     *       throws before the response is touched;</li>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length} (D-152);</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the status and
     *       headers; the stream is left open for the container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * and the response is neither reset nor sent through {@code sendError}.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code, for example {@code 200}, {@code 404} or {@code 500}
     * @param body     the body bytes, written unchanged; an empty array sends
     *                 {@code Content-Length: 0} and no body
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}; the response
     *                              is left untouched
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(body, "body");
        response.setStatus(status);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
