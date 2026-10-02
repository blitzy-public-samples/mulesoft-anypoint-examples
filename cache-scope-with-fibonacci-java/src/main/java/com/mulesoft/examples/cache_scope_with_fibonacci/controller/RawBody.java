package com.mulesoft.examples.cache_scope_with_fibonacci.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/**
 * Writes an HTTP status and raw body bytes to a servlet response without a {@code Content-Type}
 * header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length}. Headers the caller set before the
 * call, for example {@code cost}, are sent as set, and a reason phrase already written for the same
 * status stays on the status line (D-010). Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * response.setHeader("cost", "11");
 * RawBody.write(response, 200, "Fibonacci(10) = 55\nCOST: 11".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and commits it.
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the status is set only when {@code response.getStatus()} differs from {@code status};
     *       an equal status leaves the response status, and any reason phrase already set for it,
     *       unchanged (D-010);</li>
     *   <li>{@code Content-Length} is set to {@code body.length};</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body.</li>
     * </ol>
     *
     * <p>No {@code Content-Type}, character encoding or locale is set, and the response writer is
     * never opened: the response carries no media-type header (D-066).
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes; an empty array sends {@code Content-Length: 0} and no body
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}; the
     *                              response is left untouched
     * @throws IOException          if the output stream cannot be written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(body, "body");
        if (response.getStatus() != status) {
            response.setStatus(status);
        }
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
