package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status and raw body bytes to a servlet response without a {@code Content-Type}
 * header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length} (D-212). Headers the caller set
 * before the call, for example {@code Location}, are sent as set, and a reason phrase set through
 * {@code exception.ReasonPhrase} before the call stays on the status line (D-010). Usage from a
 * handler method that returns {@code void}:
 *
 * <pre>{@code
 * response.setHeader("Location", "http://0.0.0.0:8081/person/1");
 * RawBody.write(response, 201, reply.getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state, is safe for concurrent use on distinct responses and is not
 * instantiable.
 */
public final class RawBody {

    /** Not instantiable; {@link #write} is the only entry point. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and commits it, without a
     * {@code Content-Type} header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length} (D-212);</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body; the stream is left open for the
     *       container.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened
     * and {@code sendError} is never called. A zero-length array sends {@code Content-Length: 0} and
     * no body bytes.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes, sent unchanged; an empty array sends no body
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}; the response
     *                              is left untouched
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        if (response == null) {
            throw new NullPointerException("response");
        }
        if (body == null) {
            throw new NullPointerException("body");
        }
        response.setStatus(status);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
