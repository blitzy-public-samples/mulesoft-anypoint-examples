package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/**
 * Writes an HTTP status and raw body bytes to a servlet response with a {@code Content-Length}
 * header and no {@code Content-Type} header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length} (D-157). Headers the caller set
 * before the call are sent as set. Usage from an exception handler method that returns
 * {@code void}:
 *
 * <pre>{@code
 * response.reset();
 * RawBody.write(response, HttpServletResponse.SC_NOT_FOUND,
 *         ("No listener for endpoint: " + request.getRequestURI()).getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    /** Declares the only constructor, which is private and does nothing. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} with a {@code Content-Length}
     * header and no {@code Content-Type} header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>{@code response} and {@code body} are checked for {@code null}; a {@code null} argument
     *       leaves the response untouched;</li>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length} (D-157);</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the response; the
     *       stream is left open for the servlet container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never
     * opened, and the response is neither reset nor closed.
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
