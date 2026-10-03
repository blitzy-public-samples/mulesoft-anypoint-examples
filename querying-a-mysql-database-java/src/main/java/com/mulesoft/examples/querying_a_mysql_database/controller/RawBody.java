package com.mulesoft.examples.querying_a_mysql_database.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status, a {@code Content-Length} and raw body bytes to a servlet response, with
 * no {@code Content-Type} header (D-066).
 *
 * <p>{@code Content-Length} is the only header this class sets (D-248). Headers and a reason
 * phrase the caller set before the call are sent as set (D-010). Usage from an exception handler
 * method that returns {@code void}:
 *
 * <pre>{@code
 * response.setStatus(404);
 * ReasonPhrase.set(response, "Not Found");
 * RawBody.write(response, 404, "No listener for endpoint: /other".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response}, with no {@code Content-Type}
     * header (D-066, D-248).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the status is set only when {@code response.getStatus()} differs from {@code status};
     *       an equal status leaves the response status, and a reason phrase already set for it,
     *       unchanged (D-010);</li>
     *   <li>a {@code null} body is taken as an empty array;</li>
     *   <li>{@code Content-Length} is set to the body length;</li>
     *   <li>every byte of the body is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the status line and
     *       the headers; the stream is left open for the container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * no other header is added, changed or removed, and the response is not reset.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response, for example {@code 500}
     * @param body     the body bytes, written unchanged; an empty array or {@code null} sends
     *                 {@code Content-Length: 0} and no body
     * @throws NullPointerException if {@code response} is {@code null}
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        if (response.getStatus() != status) {
            response.setStatus(status);
        }
        byte[] bytes = body == null ? new byte[0] : body;
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}
