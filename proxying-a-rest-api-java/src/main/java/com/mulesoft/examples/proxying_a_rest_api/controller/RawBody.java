package com.mulesoft.examples.proxying_a_rest_api.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/**
 * Writes an HTTP status and raw body bytes to a servlet response with a {@code Content-Length}
 * header and no {@code Content-Type} header (D-066, D-208).
 *
 * <p>{@code Content-Length} is the only header this class sets. Headers the caller set before the
 * call are sent as set. Usage from an exception handler that has not committed the response:
 *
 * <pre>{@code
 * RawBody.write(response, 500, message.getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} with a matching
     * {@code Content-Length} and no {@code Content-Type} header (D-066, D-208).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>a {@code null} response or body throws {@link NullPointerException} and the response is
     *       left untouched;</li>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length};</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the status and
     *       headers; the stream is left open for the container to finish the response.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * and the response is neither reset nor sent through {@code sendError}.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response
     * @param body     the body bytes, written unchanged; an empty array sends
     *                 {@code Content-Length: 0} and no body
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
