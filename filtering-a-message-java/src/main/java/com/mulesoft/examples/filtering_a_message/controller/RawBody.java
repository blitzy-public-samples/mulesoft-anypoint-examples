/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes the status, the content length and the raw bytes of a response; sets no Content-Type
 * header (D-066).
 *
 * <p>{@code Content-Length} is the only header this class sets. Usage from a handler method that
 * returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_OK,
 *         "the discount was granted.".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes the status, the content length and the raw bytes of a response; sets no Content-Type
     * header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length};</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the status and
     *       headers.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, and the response writer is never
     * opened. An empty array sends {@code Content-Length: 0} and no body.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response
     * @param body     the body bytes, written unchanged
     * @throws NullPointerException if {@code response} is {@code null}, or if {@code body} is
     *                              {@code null}, in which case the status is already set
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }
}
