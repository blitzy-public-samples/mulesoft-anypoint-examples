package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status, an optional {@code Location} header and raw body bytes to a servlet
 * response without a {@code Content-Type} header (D-066, D-219).
 *
 * <p>Neither method sets a content type, character encoding, locale or {@code Content-Length},
 * opens the response writer, or calls {@code sendError} or {@code sendRedirect}. Undertow frames
 * the response: a {@code null} or empty body is sent with {@code Content-Length: 0}; a non-empty
 * body is flushed by the call and sent with {@code Transfer-Encoding: chunked} on a keep-alive
 * connection, or close-delimited after {@code Connection: close} (D-219). Headers the caller set
 * before the call are sent as set, and a reason phrase already set for the status stays on the
 * status line (D-010). Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 200, reply.getBytes(StandardCharsets.UTF_8));
 * RawBody.write(response, 302, authorizationUrl, null);
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    /** Not instantiable. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} with no {@code Content-Type}
     * header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>a non-empty body is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()} and the stream is flushed, which commits
     *       the status and headers; the stream is left open for the container to close;</li>
     *   <li>a {@code null} or empty body writes no byte and leaves the output stream unopened; the
     *       response is sent with a zero-length body.</li>
     * </ol>
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code, for example {@code 200} or {@code 500}
     * @param body     the body bytes, written unchanged; {@code null} or an empty array sends no body
     * @throws NullPointerException if {@code response} is {@code null}; nothing is written
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        writeBody(response, body);
    }

    /**
     * Writes {@code status}, a {@code Location} header and {@code body} to {@code response} with no
     * {@code Content-Type} header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>when {@code location} is not {@code null}, the {@code Location} header is set to it,
     *       replacing any earlier value, and the response carries exactly one {@code Location}
     *       header; a {@code null} location adds no header;</li>
     *   <li>the body is written as {@link #write(HttpServletResponse, int, byte[])} writes it, after
     *       the header is set.</li>
     * </ol>
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code, for example {@code 302}
     * @param location the {@code Location} header value, sent unchanged; {@code null} for none
     * @param body     the body bytes, written unchanged; {@code null} or an empty array sends no body
     * @throws NullPointerException if {@code response} is {@code null}; nothing is written
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, String location, byte[] body)
            throws IOException {
        response.setStatus(status);
        if (location != null) {
            response.setHeader("Location", location);
        }
        writeBody(response, body);
    }

    /**
     * Writes a non-empty {@code body} through the response output stream and flushes it; writes
     * nothing and opens no stream for a {@code null} or empty body.
     *
     * @param response the servlet response to write to
     * @param body     the body bytes, or {@code null}
     * @throws IOException if the output stream cannot be obtained, written or flushed
     */
    private static void writeBody(HttpServletResponse response, byte[] body) throws IOException {
        if (body == null || body.length == 0) {
            return;
        }
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
