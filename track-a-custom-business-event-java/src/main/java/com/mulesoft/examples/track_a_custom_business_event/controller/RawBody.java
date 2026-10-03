package com.mulesoft.examples.track_a_custom_business_event.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes a response status, a {@code Content-Length} and the body bytes, with no
 * {@code Content-Type} (D-066).
 *
 * <p>{@code Content-Length} is the only header this class sets (D-267). Headers the caller set
 * before the call are sent as set. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 200, "8.5".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} with no {@code Content-Type}
     * header (D-066, D-267).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length};</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the response; the
     *       stream is left open for the container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, no other header is added, the
     * response writer is never opened and the response is not reset.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response, for example {@code 200}
     * @param body     the body bytes, written unchanged; an empty array sends
     *                 {@code Content-Length: 0} and no body
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}; a
     *                              {@code null} body is detected after the status is set
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
