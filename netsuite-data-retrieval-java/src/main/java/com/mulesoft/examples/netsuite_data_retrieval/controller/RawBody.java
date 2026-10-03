package com.mulesoft.examples.netsuite_data_retrieval.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.http.MediaType;

/**
 * Writes an HTTP status and raw body bytes to a servlet response and controls the
 * {@code Content-Type} header exactly (D-066).
 *
 * <ul>
 *   <li>{@link #write(HttpServletResponse, int, byte[])} sends no {@code Content-Type} header;</li>
 *   <li>{@link #writeJson(HttpServletResponse, int, byte[])} sends {@code Content-Type:
 *       application/json} with no {@code charset} parameter.</li>
 * </ul>
 *
 * <p>Both methods set the status and {@code Content-Length}, write the bytes unchanged through
 * {@link HttpServletResponse#getOutputStream()} and flush the stream, which commits the response
 * (D-189). Neither method resets the response: headers the caller set before the call, for
 * example {@code Retry-After}, are sent as set. The response writer is never opened, and no
 * character encoding or locale is set.
 *
 * <p>Usage from an exception handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.writeJson(response, HttpServletResponse.SC_NOT_FOUND,
 *         "{ \"message\": \"Resource not found\" }".getBytes(StandardCharsets.UTF_8));
 *
 * RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
 *         message.getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state, is safe for concurrent use and is not instantiable.
 */
public final class RawBody {

    /** Body written for a {@code null} {@code body} argument: zero bytes. */
    private static final byte[] EMPTY_BODY = new byte[0];

    /** Declares the only constructor, which is private and does nothing. */
    private RawBody() {
    }

    /**
     * Writes the status, {@code Content-Length} and the raw bytes without a {@code Content-Type}
     * header (D-066, D-189).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to the body length, {@code 0} for a {@code null} or
     *       empty body;</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, and left open for the container
     *       to close.</li>
     * </ol>
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes; {@code null} is written as an empty body
     * @throws NullPointerException if {@code response} is {@code null}; the response is untouched
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        writeBytes(response, status, body, null);
    }

    /**
     * Writes the status, {@code Content-Type: application/json} with no {@code charset}
     * parameter, {@code Content-Length} and the raw bytes (D-066, D-189).
     *
     * <p>The steps are those of {@link #write(HttpServletResponse, int, byte[])}, with the content
     * type set to exactly {@value MediaType#APPLICATION_JSON_VALUE} after the status and before
     * any byte is written.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the JSON body bytes, written unchanged; {@code null} is written as an empty
     *                 body
     * @throws NullPointerException if {@code response} is {@code null}; the response is untouched
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void writeJson(HttpServletResponse response, int status, byte[] body) throws IOException {
        writeBytes(response, status, body, MediaType.APPLICATION_JSON_VALUE);
    }

    /**
     * Sets the status, the content type when {@code contentType} is not {@code null}, and
     * {@code Content-Length}, then writes and flushes the bytes through the servlet output stream.
     *
     * @param response    the servlet response to write to
     * @param status      the HTTP status code
     * @param body        the body bytes; {@code null} is written as an empty body
     * @param contentType the exact {@code Content-Type} value, or {@code null} for no
     *                    {@code Content-Type} header
     * @throws IOException if the output stream cannot be obtained, written or flushed
     */
    private static void writeBytes(HttpServletResponse response, int status, byte[] body, String contentType)
            throws IOException {
        Objects.requireNonNull(response, "response");
        byte[] bytes = body == null ? EMPTY_BODY : body;
        response.setStatus(status);
        if (contentType != null) {
            response.setContentType(contentType);
        }
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        if (bytes.length > 0) {
            out.write(bytes);
        }
        out.flush();
    }
}
