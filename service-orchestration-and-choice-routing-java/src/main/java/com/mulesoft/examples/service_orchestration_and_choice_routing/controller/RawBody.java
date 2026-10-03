package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Writes an HTTP status and raw body bytes to a servlet response and sets no {@code Content-Type}
 * header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length} (D-193). Headers the caller set
 * before the call are sent as set. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_OK, "db populated".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes the status and body; sets no {@code Content-Type} header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>a {@code null} body is taken as an empty array (D-193);</li>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to the body length (D-193);</li>
     *   <li>every byte of the body is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, and is left open for the
     *       container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * and the response is not reset. An empty body sends {@code Content-Length: 0} and no bytes.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code, for example {@link HttpServletResponse#SC_OK}
     * @param body     the body bytes; {@code null} or an empty array sends no body
     * @throws NullPointerException  if {@code response} is {@code null}
     * @throws IllegalStateException if the response writer was opened before the call
     * @throws UncheckedIOException  if the output stream cannot be obtained, written or flushed; its
     *                               cause is the {@link IOException} the container raised
     */
    public static void write(HttpServletResponse response, int status, byte[] body) {
        byte[] bytes = body == null ? new byte[0] : body;
        response.setStatus(status);
        response.setContentLength(bytes.length);
        try {
            ServletOutputStream out = response.getOutputStream();
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
