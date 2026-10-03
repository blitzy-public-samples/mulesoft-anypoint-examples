package com.mulesoft.examples.sending_json_data_to_a_jms_queue.exception;

import com.mulesoft.examples.sending_json_data_to_a_jms_queue.controller.RawBody;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Error answers of the HTTP listener of flow {@code json-to-jmsFlow}
 * [sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:5-11], a flow that defines no
 * exception strategy: the listener's no-listener 404, its {@code allowedMethods="POST"} 405
 * [json-to-jms.xml:6] and the default exception strategy's 500. No other error answer exists for
 * this endpoint (D-062).
 *
 * <p>Answers, one handler method per case:
 * <table>
 *   <caption>Handler, trigger and answer</caption>
 *   <tr><th>Handler</th><th>Trigger</th><th>Status line</th><th>Body (UTF-8)</th></tr>
 *   <tr><td>{@link #noListener}</td>
 *       <td>{@link NoHandlerFoundException} or {@link NoResourceFoundException}: every method on
 *           any path other than {@code /sales}, including {@code /}, {@code /other},
 *           {@code /error} and the trailing-slash {@code /sales/}</td>
 *       <td>{@code 404 Not Found}</td>
 *       <td>{@code No listener for endpoint: <endpoint>}</td></tr>
 *   <tr><td>{@link #methodNotAllowed}</td>
 *       <td>{@link HttpRequestMethodNotSupportedException}: {@code GET}, {@code HEAD}, {@code PUT},
 *           {@code DELETE} and {@code PATCH} on {@code /sales} from Spring MVC's method matching,
 *           {@code OPTIONS} from {@code SalesController.optionsNotAllowed} (D-467), {@code TRACE}
 *           through {@code spring.mvc.dispatch-trace-request: true} (D-089)</td>
 *       <td>{@code 405 Method Not Allowed}</td>
 *       <td>{@code Method not allowed for endpoint: <endpoint>}</td></tr>
 *   <tr><td>{@link #unexpected}</td>
 *       <td>any other {@link Exception}, for example a {@code JmsException} of
 *           {@code SalesPublisher.jsonToJmsFlow}</td>
 *       <td>{@code 500 Internal Server Error}</td>
 *       <td>the exception message, or an empty body when the message is {@code null}</td></tr>
 * </table>
 *
 * <p>{@code <endpoint>} is {@link HttpServletRequest#getRequestURI()} as received, followed by
 * {@code ?} and {@link HttpServletRequest#getQueryString()} when the request has a query string;
 * neither part is decoded, re-encoded or escaped: {@code GET /x?a=1} answers
 * {@code No listener for endpoint: /x?a=1}. The texts, the query string and the 500 body are D-637.
 *
 * <p>Every body is written through {@link RawBody#write(HttpServletResponse, int, byte[])}, which sets
 * the status and {@code Content-Length} and no {@code Content-Type} (D-066, D-172). No handler sets an
 * {@code Allow} header, a reason phrase or a media type, returns a value or calls
 * {@code sendError}; the status lines carry Undertow's standard phrases (D-010). The bodies are
 * hand-read listener and default-strategy answers, pinned by the Tier 2A fixtures once they are
 * captured (D-023).
 *
 * <p>The advice carries no {@code assignableTypes}, {@code basePackages} or {@code annotations}
 * selector and receives the exceptions of requests that matched no controller. Spring MVC selects the
 * handler of the closest declared exception type: the 404 and 405 types never reach
 * {@link #unexpected}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Logger of the ERROR event that {@link #unexpected} writes. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Body text of the 404 before the endpoint. */
    private static final String NO_LISTENER_PREFIX = "No listener for endpoint: ";

    /** Body text of the 405 before the endpoint. */
    private static final String METHOD_NOT_ALLOWED_PREFIX = "Method not allowed for endpoint: ";

    /**
     * Answers a request for a path the listener does not own with {@code 404 Not Found} and the body
     * {@code No listener for endpoint: <endpoint>}, for example
     * {@code No listener for endpoint: /sales/} for {@code POST /sales/}. Logs nothing.
     *
     * @param ex       the {@link NoHandlerFoundException} or {@link NoResourceFoundException} raised for
     *                 the unmatched path
     * @param request  the unmatched request
     * @param response the response that receives the 404
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public void noListener(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String text = NO_LISTENER_PREFIX + endpoint(request);
        RawBody.write(response, HttpServletResponse.SC_NOT_FOUND, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Answers a method other than {@code POST} on {@code /sales} with {@code 405 Method Not Allowed} and
     * the body {@code Method not allowed for endpoint: <endpoint>}, for example
     * {@code Method not allowed for endpoint: /sales} for {@code GET /sales}. Sends no {@code Allow}
     * header and logs nothing.
     *
     * @param ex       the method mismatch raised by Spring MVC or by {@code SalesController}
     * @param request  the rejected request
     * @param response the response that receives the 405
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public void methodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String text = METHOD_NOT_ALLOWED_PREFIX + endpoint(request);
        RawBody.write(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED,
                text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Default exception strategy of {@code json-to-jmsFlow}: logs one ERROR event
     * {@code Flow json-to-jmsFlow failed for <method> <endpoint>} with the stack trace of {@code ex},
     * then answers {@code 500 Internal Server Error} with {@code ex.getMessage()} as body, or an empty
     * body ({@code Content-Length: 0}) when the message is {@code null}. A response already committed
     * when the handler runs keeps the status line and bytes already sent: the event is logged and
     * nothing is written (D-637).
     *
     * @param ex       the exception that left the request handling
     * @param request  the failed request
     * @param response the response that receives the 500
     * @throws IOException if the body cannot be written
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        LOG.error("Flow json-to-jmsFlow failed for {} {}", request.getMethod(), endpoint(request), ex);
        // A committed response keeps the status line and bytes already sent; no 500 is written (D-637).
        if (response.isCommitted()) {
            return;
        }
        String message = ex.getMessage();
        String text = message != null ? message : "";
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns the request URI as received, followed by {@code ?} and the query string when the request
     * has one, both unescaped: {@code /x?a=1} for {@code GET /x?a=1}, {@code /sales} for
     * {@code GET /sales}.
     *
     * @param request the request whose endpoint is returned
     * @return the request URI, with {@code ?} and the query string when present
     */
    private static String endpoint(HttpServletRequest request) {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }
}
