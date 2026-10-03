package com.mulesoft.examples.sending_json_data_to_a_amqp_queue.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes a status, a {@code Content-Length} header and the given bytes to a servlet response, and
 * sets no {@code Content-Type} header (D-066, D-272).
 *
 * <p>Callers in this project: {@code SalesController.jsonToRabbitmqFlow}, which writes the AMQP
 * reply of flow {@code json-to-rabbitmqFlow} with status 200, and {@code GlobalExceptionHandler},
 * which writes the listener's error bodies. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_OK, reply);
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
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length}, {@code 0} for an empty array;</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, and left open.</li>
     * </ol>
     *
     * <p>No {@code Content-Type}, character encoding, locale or header other than
     * {@code Content-Length} is set and the response writer is never opened: the response carries no
     * media-type header (D-066). Headers the caller set before the call are sent as set.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes, never {@code null}; an empty array sends
     *                 {@code Content-Length: 0} and no body bytes
     * @throws IOException if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
