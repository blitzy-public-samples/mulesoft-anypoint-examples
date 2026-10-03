package com.mulesoft.examples.sending_json_data_to_a_jms_queue.controller;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.service.SalesPublisher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP ingress of flow {@code json-to-jmsFlow}
 * [sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:5-11]: accepts {@code POST /sales} and
 * answers 200 with the forwarded text and no {@code Content-Type} header (D-066).
 *
 * <p>The listener of the original, {@code <http:listener allowedMethods="POST" path="sales"/>} on
 * {@code <http:listener-config host="0.0.0.0" port="${http.port}"/>} [json-to-jms.xml:4,6], is the
 * request mapping of this class on the embedded Undertow server (D-010). Its address is
 * {@code server.port} ({@code http.port}, default 8081, D-065) and {@code server.address}
 * ({@code listener.http-listener-configuration.host}, default {@code 0.0.0.0}); its path is the key
 * {@code listener.http-listener-configuration.path}, default {@code sales} (D-140). Every key is set in
 * {@code application.yml}.
 *
 * <p>Answers outside the mapping, all without {@code Content-Type}:
 * <ul>
 *   <li>{@code GET}, {@code HEAD}, {@code PUT}, {@code DELETE}, {@code PATCH} and {@code OPTIONS} on
 *       {@code /sales}: 405 {@code Method not allowed for endpoint: <uri>} from
 *       {@code GlobalExceptionHandler.methodNotAllowed}; {@code OPTIONS} reaches it through
 *       {@link #optionsNotAllowed()} (D-467).</li>
 *   <li>{@code TRACE} on {@code /sales}: the same 405, dispatched to Spring MVC by
 *       {@code spring.mvc.dispatch-trace-request: true} (D-089).</li>
 *   <li>Any other path, {@code /sales/} included: 404 {@code No listener for endpoint: <uri>} from
 *       {@code GlobalExceptionHandler.noListener}.</li>
 *   <li>A failed JMS send: 500 from {@code GlobalExceptionHandler.unexpected}, the default exception
 *       strategy of {@code json-to-jmsFlow}.</li>
 * </ul>
 *
 * <p>The class binds the request and delegates to {@link SalesPublisher}; it transforms, routes and
 * logs nothing itself.
 */
@RestController
public class SalesController {

    /**
     * Sends the decoded request body to queue {@code sales} and logs it: the
     * {@code byte-array-to-string-transformer}, {@code jms:outbound-endpoint} and {@code logger} steps
     * of {@code json-to-jmsFlow} [json-to-jms.xml:8-10].
     */
    private final SalesPublisher salesPublisher;

    /**
     * Creates the controller over the publisher of {@code json-to-jmsFlow}.
     *
     * @param salesPublisher the service that sends each request body to queue {@code sales}
     */
    public SalesController(SalesPublisher salesPublisher) {
        this.salesPublisher = salesPublisher;
    }

    /**
     * Runs {@code json-to-jmsFlow} for one {@code POST /sales} request: answers 200 with the forwarded
     * text, a {@code Content-Length} header and no {@code Content-Type} header (D-066).
     *
     * <p>Steps:
     * <ol>
     *   <li>Reads the request body as raw bytes from the servlet input stream, unparsed, for every request
     *       media type, {@code application/x-www-form-urlencoded} included. A {@code multipart/form-data}
     *       body is read by Spring's multipart resolution before this method runs, and the stream then
     *       yields no bytes (D-468).</li>
     *   <li>Takes the charset of the request {@code Content-Type} header; a missing header, a header
     *       without charset, a malformed header and an unsupported charset give UTF-8, the encoding of
     *       {@code mule-deploy.properties} (D-468).</li>
     *   <li>Hands bytes and charset to {@link SalesPublisher#jsonToJmsFlow(byte[], Charset)}, which
     *       decodes the text, sends it as one {@code TextMessage} to queue {@code sales}, logs it at
     *       INFO and returns it.</li>
     *   <li>Writes the returned text, encoded in the same charset, as the 200 body.</li>
     * </ol>
     *
     * <p>Exceptions are not caught here: a failed send reaches
     * {@code GlobalExceptionHandler.unexpected}, the default-strategy 500.
     *
     * <p>The mapped path is the value of {@code listener.http-listener-configuration.path}, default
     * {@code sales}, with a missing leading {@code /} prefixed by Spring MVC; only that exact path
     * matches, without a trailing slash (D-140).
     *
     * <p>Example exchange, with the 73-byte {@code message.json} of the original test:
     * <pre>{@code
     * POST /sales HTTP/1.1
     * Content-Type: application/json
     * Content-Length: 73
     *
     * <the 73 bytes of message.json>
     *
     * HTTP/1.1 200 OK
     * Content-Length: 73
     *
     * <the same 73 bytes>
     * }</pre>
     *
     * @param request  the {@code POST /sales} request whose body is forwarded
     * @param response the response that receives the 200 status and the forwarded text
     * @throws IOException if the request body cannot be read or the response body cannot be written
     */
    @PostMapping("${listener.http-listener-configuration.path}")
    public void jsonToJmsFlow(HttpServletRequest request, HttpServletResponse response) throws IOException {
        byte[] bytes = request.getInputStream().readAllBytes();
        Charset charset = charsetOf(request.getHeader(HttpHeaders.CONTENT_TYPE));
        String text = salesPublisher.jsonToJmsFlow(bytes, charset);
        RawBody.write(response, HttpServletResponse.SC_OK, text.getBytes(charset));
    }

    /**
     * Rejects {@code OPTIONS /sales}, a method outside the listener's {@code allowedMethods="POST"}
     * [json-to-jms.xml:6]: throws {@link HttpRequestMethodNotSupportedException} for {@code OPTIONS}
     * with supported method {@code POST}, which {@code GlobalExceptionHandler.methodNotAllowed} answers
     * with 405 {@code Method not allowed for endpoint: <uri>} and no {@code Content-Type} (D-467).
     *
     * @throws HttpRequestMethodNotSupportedException on every call
     */
    @RequestMapping(value = "${listener.http-listener-configuration.path}", method = RequestMethod.OPTIONS)
    public void optionsNotAllowed() throws HttpRequestMethodNotSupportedException {
        throw new HttpRequestMethodNotSupportedException(RequestMethod.OPTIONS.name(), List.of("POST"));
    }

    /**
     * Returns the charset parameter of a {@code Content-Type} header value, or UTF-8 when the value is
     * {@code null} or blank, carries no charset, cannot be parsed or names an unknown charset (D-468).
     *
     * @param contentType the request {@code Content-Type} header value, possibly {@code null}
     * @return the charset that decodes the request body and encodes the response body
     */
    private static Charset charsetOf(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        try {
            MimeType mimeType = MimeTypeUtils.parseMimeType(contentType);
            Charset charset = mimeType.getCharset();
            return charset != null ? charset : StandardCharsets.UTF_8;
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }
}
