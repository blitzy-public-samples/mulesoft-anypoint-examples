package com.mulesoft.examples.hello_world.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes a response status, a {@code Content-Length} and the body bytes, with no
 * {@code Content-Type} (D-066).
 *
 * <p>{@code Content-Length} is the only header this class sets (D-197). Headers and a reason
 * phrase the caller set before the call are sent as set (D-010). Usage from a handler method that
 * returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 200, "Hello World".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and commits the response, with no
     * {@code Content-Type} header (D-066, D-197).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the status is set only when {@code response.getStatus()} differs from {@code status};
     *       an equal status leaves the response status, and a reason phrase already set for it,
     *       unchanged (D-010);</li>
     *   <li>{@code Content-Length} is set to {@code body.length};</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>{@link HttpServletResponse#flushBuffer()} sends the status, the headers and any buffered
     *       body bytes, also for an empty body.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, and the response writer is never
     * opened. The response is committed when this method returns.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response, for example {@code 200}
     * @param body     the body bytes, written unchanged; an empty array sends
     *                 {@code Content-Length: 0} and no body
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}; a
     *                              {@code null} body is detected after step 1
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        if (response.getStatus() != status) {
            response.setStatus(status);
        }
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
