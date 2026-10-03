package com.mulesoft.examples.implementing_a_choice_exception_strategy.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import com.mulesoft.examples.implementing_a_choice_exception_strategy.exception.ReasonPhrase;

/**
 * Response body of flow {@code choice-error-handlingFlow1} [choice-error-handling.xml:4-48], written
 * with the status, {@code Content-Length} and the body bytes only; no {@code Content-Type} header is
 * set (D-066, D-426).
 *
 * <p>A {@code RawBody} carries the bytes of one {@code set-payload} text that has no
 * {@code mimeType}: {@code Input data validation passed.} [:14], {@code Invalid input data: #[payload]}
 * [:29] or {@code Missing input data: #[payload]} [:44]. It also carries the custom reason phrase that
 * the listener's response builder takes from {@code flowVars['reason']} [:6] (D-010). A {@code null}
 * {@link #reasonPhrase()} leaves Undertow's standard phrase for the status code, for example
 * {@code OK} for 200 and {@code Internal Server Error} for 500.
 *
 * <p>A {@code ResponseEntity<RawBody>} is written by {@code config.RawBodyHttpMessageConverter}. The
 * static {@code write} methods put a body directly on an {@link HttpServletResponse}. Usage:
 *
 * <pre>{@code
 * ResponseEntity.ok(RawBody.of("Input data validation passed."));         // 200 OK, Content-Length: 29
 * ResponseEntity.ok(RawBody.empty());                                     // 200 OK, Content-Length: 0
 * ResponseEntity.status(400).body(RawBody.of(text, "Invalid input data")); // 400 Invalid input data
 *
 * RawBody.write(response, 200, "Input data validation passed.".getBytes(StandardCharsets.UTF_8));
 * RawBody.write(response, 400, "Missing input data", body.bytes());
 * }</pre>
 *
 * <p>The record checks no argument for {@code null} and copies no array: {@link #bytes()} returns the
 * array the record was created with, and the generated {@code equals} and {@code hashCode} compare
 * that array by reference.
 *
 * @param bytes        the body bytes, written unchanged; a zero-length array gives
 *                     {@code Content-Length: 0} and no body
 * @param reasonPhrase the text written after the status code on the HTTP/1.1 status line, for example
 *                     {@code Invalid input data}; {@code null} when the standard phrase applies
 */
public record RawBody(byte[] bytes, String reasonPhrase) {

    /**
     * Body made of the UTF-8 bytes of {@code utf8Text}, with the standard reason phrase.
     *
     * @param utf8Text the body text, encoded unchanged as UTF-8, for example
     *                 {@code Input data validation passed.} (29 bytes)
     * @return a body whose {@link #reasonPhrase()} is {@code null}
     * @throws NullPointerException when {@code utf8Text} is {@code null}
     */
    public static RawBody of(String utf8Text) {
        return new RawBody(utf8Text.getBytes(StandardCharsets.UTF_8), null);
    }

    /**
     * Body made of the UTF-8 bytes of {@code utf8Text}, with a custom reason phrase.
     *
     * @param utf8Text     the body text, encoded unchanged as UTF-8, for example
     *                     {@code Missing input data: {item units=10}}
     * @param reasonPhrase the reason phrase for the status line, for example
     *                     {@code Missing input data}; {@code null} keeps the standard phrase
     * @return a body carrying the text's bytes and {@code reasonPhrase} unchanged
     * @throws NullPointerException when {@code utf8Text} is {@code null}
     */
    public static RawBody of(String utf8Text, String reasonPhrase) {
        return new RawBody(utf8Text.getBytes(StandardCharsets.UTF_8), reasonPhrase);
    }

    /**
     * Zero-length body with the standard reason phrase, sent as {@code Content-Length: 0} and no
     * body bytes.
     *
     * @return a body with a new zero-length array and a {@code null} {@link #reasonPhrase()}
     */
    public static RawBody empty() {
        return new RawBody(new byte[0], null);
    }

    /**
     * Writes {@code body} to {@code response} with the standard reason phrase.
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length}, {@code 0} for an empty array;</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()}.</li>
     * </ol>
     *
     * <p>No {@code Content-Type} header, character encoding or locale is set, and the response writer
     * is never opened (D-066).
     *
     * @param response the servlet response to write to, not yet committed
     * @param status   the HTTP status code, for example {@code 200}
     * @param body     the body bytes; an empty array sends {@code Content-Length: 0} and no body
     * @throws NullPointerException when {@code response} or {@code body} is {@code null}
     * @throws IOException          when the output stream cannot be obtained or written
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /**
     * Writes {@code body} to {@code response} with the reason phrase {@code reasonPhrase}.
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@link ReasonPhrase#set(String)} is called with {@code reasonPhrase}, directly after the
     *       status (D-010);</li>
     *   <li>{@code Content-Length} is set to {@code body.length}, {@code 0} for an empty array;</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()}.</li>
     * </ol>
     *
     * <p>No {@code Content-Type} header, character encoding or locale is set, and the response writer
     * is never opened (D-066).
     *
     * @param response     the servlet response to write to, not yet committed
     * @param status       the HTTP status code, for example {@code 400}
     * @param reasonPhrase the reason phrase for the status line, for example
     *                     {@code Invalid input data}; {@code null} leaves the standard phrase for
     *                     {@code status}
     * @param body         the body bytes; an empty array sends {@code Content-Length: 0} and no body
     * @throws NullPointerException when {@code response} or {@code body} is {@code null}
     * @throws IOException          when the output stream cannot be obtained or written
     */
    public static void write(HttpServletResponse response, int status, String reasonPhrase, byte[] body)
            throws IOException {
        response.setStatus(status);
        ReasonPhrase.set(reasonPhrase);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
