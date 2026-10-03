/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.config;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.implementing_a_choice_exception_strategy.controller.RawBody;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.exception.ReasonPhrase;

/**
 * Writes the {@link RawBody} of a {@code ResponseEntity<RawBody>} as the reply of flow
 * {@code choice-error-handlingFlow1} [choice-error-handling.xml:4-48]: the body bytes with their
 * {@code Content-Length} and no {@code Content-Type} header (D-066), and the body's reason phrase on the
 * HTTP/1.1 status line (D-010).
 *
 * <p>The class implements {@link HttpMessageConverter} itself, with no Spring base converter, and every
 * header it writes is set in {@link #write} (D-573). Spring Boot adds this {@code @Component} to the
 * Spring MVC message converters ahead of the default ones, and the controller methods and the exception
 * handlers write their return values through that one list. The converter:
 * <ul>
 *   <li>writes {@link RawBody} for every requested media type: {@code Accept: application/json},
 *       {@code Accept: text/html}, the wildcard {@link MediaType#ALL} and a request without an
 *       {@code Accept} header all receive the same bytes, and none receives 406;</li>
 *   <li>lists {@link MediaType#ALL} as its only supported media type;</li>
 *   <li>reads no request body: {@link #canRead} answers {@code false} and {@link #read} throws
 *       {@link UnsupportedOperationException}.</li>
 * </ul>
 *
 * <p>Wire forms of the flow's replies (D-066, D-010):
 * <pre>
 * ResponseEntity.ok(RawBody.of("Input data validation passed."))   HTTP/1.1 200 OK, Content-Length: 29
 * ResponseEntity.ok(RawBody.empty())                                HTTP/1.1 200 OK, Content-Length: 0
 * ResponseEntity.status(400).body(RawBody.of(text, "Invalid input data"))   HTTP/1.1 400 Invalid input data
 * ResponseEntity.status(400).body(RawBody.of(text, "Missing input data"))   HTTP/1.1 400 Missing input data
 * </pre>
 * None of these replies carries a {@code Content-Type} header. The converter holds no state and is
 * safe for concurrent requests.
 */
@Component
public class RawBodyHttpMessageConverter implements HttpMessageConverter<RawBody> {

    /** The media types returned by {@link #getSupportedMediaTypes()}: {@link MediaType#ALL} only. */
    private static final List<MediaType> SUPPORTED_MEDIA_TYPES = List.of(MediaType.ALL);

    /**
     * Reports that no request body is read by this converter.
     *
     * @param clazz     the class a request body would be read as
     * @param mediaType the media type of the request body, or {@code null}
     * @return {@code false} for every class and media type
     */
    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return false;
    }

    /**
     * Reports whether a response body of class {@code clazz} is written by this converter; the media
     * type does not change the answer.
     *
     * @param clazz     the class of the response body, for example {@code RawBody.class}
     * @param mediaType the media type selected from the request's {@code Accept} header, or
     *                  {@code null}; any value, {@code application/json} and {@code text/html} included
     * @return {@code true} when {@code clazz} is {@link RawBody} or a subtype of it, otherwise
     *         {@code false}
     */
    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        return RawBody.class.isAssignableFrom(clazz);
    }

    /**
     * Lists the media types this converter writes.
     *
     * @return an unmodifiable list holding {@link MediaType#ALL} only
     */
    @Override
    public List<MediaType> getSupportedMediaTypes() {
        return SUPPORTED_MEDIA_TYPES;
    }

    /**
     * Rejects every read: a {@link RawBody} is only written as a response body.
     *
     * @param clazz        the class the body would be read as
     * @param inputMessage the request whose body would be read; left unread
     * @return never returns normally
     * @throws UnsupportedOperationException always, with the message {@code RawBody is write-only}
     */
    @Override
    public RawBody read(Class<? extends RawBody> clazz, HttpInputMessage inputMessage) {
        throw new UnsupportedOperationException("RawBody is write-only");
    }

    /**
     * Writes {@code body} to {@code outputMessage} with its reason phrase, a {@code Content-Length}
     * header and its bytes, and with no {@code Content-Type} header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>a non-{@code null} {@link RawBody#reasonPhrase()} is handed to
     *       {@link ReasonPhrase#set(String)}, which puts it after the status code on the HTTP/1.1
     *       status line of the current Undertow exchange (D-010); a {@code null} phrase leaves the
     *       standard phrase of the status code, for example {@code OK} for 200;</li>
     *   <li>{@code Content-Length} is set to the length of {@link RawBody#bytes()}, {@code 0} for a
     *       zero-length array;</li>
     *   <li>the bytes are written unchanged to {@link HttpOutputMessage#getBody()}, which is then
     *       flushed; a zero-length array writes no body bytes.</li>
     * </ol>
     *
     * <p>{@code contentType} is never written to the response, and no header other than
     * {@code Content-Length} is added (D-573).
     *
     * @param body          the response body; its bytes and reason phrase are written unchanged
     * @param contentType   the media type selected from the request's {@code Accept} header, or
     *                      {@code null}, for example {@code application/json}; not written
     * @param outputMessage the response to write to, with its status already set and not yet committed
     * @throws NullPointerException when {@code body} or its {@link RawBody#bytes()} is {@code null}
     * @throws IOException          when the response body stream cannot be obtained, written or flushed
     */
    @Override
    public void write(RawBody body, MediaType contentType, HttpOutputMessage outputMessage) throws IOException {
        String reasonPhrase = body.reasonPhrase();
        if (reasonPhrase != null) {
            ReasonPhrase.set(reasonPhrase);
        }
        byte[] bytes = body.bytes();
        outputMessage.getHeaders().setContentLength(bytes.length);
        var out = outputMessage.getBody();
        out.write(bytes);
        out.flush();
    }
}
