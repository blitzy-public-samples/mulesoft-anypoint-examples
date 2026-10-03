package com.mulesoft.examples.http_oauth_provider.exception;

import com.mulesoft.examples.http_oauth_provider.controller.RawBody;
import com.mulesoft.examples.http_oauth_provider.controller.RedirectController;
import com.mulesoft.examples.http_oauth_provider.controller.ResourcesController;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Answers the default 500 for exceptions raised by {@link ResourcesController} and
 * {@link RedirectController} (D-007, D-066).
 *
 * <p>The two controllers replace the {@code http:listener} flows {@code protectedAuthcodeFlow}
 * ({@code /resources}) and {@code redirectFlow} ({@code /redirect}) on {@code http.listener.port}
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:44-49,53-57]. Neither flow declares an
 * exception strategy; Mule's default strategy is their only error branch, and this class has
 * exactly one handler for it, {@link #unexpected(Exception, HttpServletResponse)}.
 *
 * <p>Scope of the advice:
 * <ul>
 *   <li>{@code assignableTypes} limits it to {@link ResourcesController} and
 *       {@link RedirectController}; no other handler and no request without a handler reaches
 *       it (D-667);</li>
 *   <li>examples of exceptions it receives: a {@code JsonProcessingException} or any runtime
 *       exception from {@link ResourcesController#protectedAuthcodeFlow()}, and an
 *       {@link IOException} from {@code RedirectController.redirectFlow} when the request body
 *       cannot be read;</li>
 *   <li>the 401 and 403 of token validation are written by {@code config/ResourceServerConfig}
 *       before any controller runs (D-041), and the 404 for an unknown path or a path on the wrong
 *       port by {@code config/PortPathGuardFilter} (D-011); neither reaches this class.</li>
 * </ul>
 *
 * <p>Example exchange, a failure inside {@code /resources} with the message {@code boom}:
 *
 * <pre>
 * HTTP/1.1 500 Internal Server Error
 * Content-Length: 4
 *
 * boom
 * </pre>
 *
 * <p>No {@code Content-Type} header is sent (D-066). No reason phrase is set; the status line
 * carries Undertow's standard phrase (D-010). The exact body Mule writes for the same failure is
 * recorded by the Tier 2A fixture of the scenario.
 *
 * <p>The class holds no mutable state and serves concurrent requests.
 */
@RestControllerAdvice(assignableTypes = {ResourcesController.class, RedirectController.class})
public class GlobalExceptionHandler {

    /** Receives one ERROR event per exception answered by {@link #unexpected}. */
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Logs the exception at ERROR and writes status 500 with the exception message as the UTF-8
     * body and no Content-Type.
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>one ERROR event carries the exception message and, as its throwable, the exception
     *       with its stack trace;</li>
     *   <li>the body is the UTF-8 encoding of {@link Exception#getMessage()}, unchanged, or zero
     *       bytes when the message is {@code null};</li>
     *   <li>{@link RawBody#write(HttpServletResponse, int, byte[])} sets status 500 and
     *       {@code Content-Length}, writes the body and commits the response, with no
     *       {@code Content-Type} header (D-066).</li>
     * </ol>
     *
     * <p>The method returns nothing; Spring MVC takes the response as handled and renders no view
     * and no further body.
     *
     * @param ex       the exception raised while {@link ResourcesController} or
     *                 {@link RedirectController} handled the request
     * @param response the response that receives the 500 status and the body
     * @throws IOException if the body cannot be written to the response
     */
    @ExceptionHandler(Exception.class)
    public void unexpected(Exception ex, HttpServletResponse response) throws IOException {
        LOG.error("Exception while processing the request: {}", ex.getMessage(), ex);
        byte[] body = ex.getMessage() == null
                ? new byte[0]
                : ex.getMessage().getBytes(StandardCharsets.UTF_8);
        RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, body);
    }
}
