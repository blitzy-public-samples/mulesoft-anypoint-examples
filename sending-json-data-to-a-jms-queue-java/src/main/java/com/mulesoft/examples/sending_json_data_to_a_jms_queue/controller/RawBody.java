package com.mulesoft.examples.sending_json_data_to_a_jms_queue.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/**
 * Writes the status, a {@code Content-Length} header and the body bytes to a servlet response, and
 * sets no {@code Content-Type} header (D-066, D-172).
 *
 * <p>The only header this class sets is {@code Content-Length}. Headers the caller set before the
 * call are sent as set. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_OK, text.getBytes(charset));
 * }</pre>
 *
 * <p>The class holds no state, is safe for concurrent use and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response}.
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>{@code response} and {@code body} are checked for {@code null}; a {@code null} argument
     *       throws before the response is touched;</li>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length}, {@code 0} for an empty array;</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, and left open.</li>
     * </ol>
     *
     * <p>No {@code Content-Type}, character encoding or locale is set, the response writer is never
     * opened and {@code sendError} is never called: the response carries no media-type header
     * (D-066).
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes; an empty array sends {@code Content-Length: 0} and no body
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}
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
