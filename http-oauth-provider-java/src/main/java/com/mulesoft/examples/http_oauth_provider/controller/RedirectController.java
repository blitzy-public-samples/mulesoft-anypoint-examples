package com.mulesoft.examples.http_oauth_provider.controller;

import com.mulesoft.examples.http_oauth_provider.service.RedirectService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Serves {@code /redirect} on the listener port ({@code http.listener.port}, D-011) for Mule flow
 * {@code redirectFlow} [http-oauth-provider/src/main/app/http-oauth-provider.xml:53-57].
 *
 * <p>Each request on {@code /redirect} is bound and delegated, nothing more:
 * <ul>
 *   <li>the {@code code} query parameter ({@code http.query.params}, :56) goes to
 *       {@link RedirectService#redirectFlow(String)}, whose result is the {@code Location}
 *       header;</li>
 *   <li>the status is 302 (:55);</li>
 *   <li>the request body is the response body, unchanged, framed with {@code Content-Length} and
 *       sent with no {@code Content-Type} (D-066).</li>
 * </ul>
 *
 * <p>Mapping: the literal path {@code /redirect} (D-440, D-499) for every HTTP method [:54, no
 * {@code allowedMethods}]. {@link #redirectFlow(HttpServletRequest, HttpServletResponse)} carries a
 * mapping with no method restriction; OPTIONS reaches the same method through a second mapping
 * limited to OPTIONS. Port ownership is enforced by {@code config.PortPathGuardFilter} (D-011) and
 * access by {@code config.ResourceServerConfig} (D-041); this class holds no security code.
 *
 * <pre>{@code
 * GET  /redirect?code=abc          -> 302, Location (one line):
 *     http://localhost:<http.provider.port>/token?grant_type=authorization_code&&client_id=<client id>
 *     &client_secret=<client secret>&code=abc&redirect_uri=http://localhost:<http.listener.port>/redirect
 * GET  /redirect                   -> 302, Location: ...&code=null&redirect_uri=...
 * GET  /redirect?code=a%2Bb+c      -> 302, Location: ...&code=a+b c&redirect_uri=...
 * POST /redirect?code=abc, "hello" -> 302, the same Location, body "hello", no Content-Type
 * }</pre>
 */
@RestController
@RequestMapping("/redirect")
public class RedirectController {

    /** The implementation of flow {@code redirectFlow} that builds the {@code Location} value. */
    private final RedirectService redirectService;

    /**
     * Creates the adapter over the service that implements the flow.
     *
     * @param redirectService the implementation of {@code redirectFlow}
     */
    public RedirectController(RedirectService redirectService) {
        this.redirectService = redirectService;
    }

    /**
     * Answers 302 with the {@code Location} built by {@link RedirectService} from the query
     * parameter {@code code}, echoes the request body, and sends no {@code Content-Type} (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>{@code code} is the last {@code code} parameter of the raw query string, URL-decoded,
     *       or {@code null} when the query string has none or that parameter has no {@code =};
     *       a form-encoded request body is never read for it;</li>
     *   <li>the request body is read in full from the servlet input stream, byte for byte; a
     *       request without a body gives an empty body;</li>
     *   <li>the {@code Location} header is set to
     *       {@link RedirectService#redirectFlow(String)} of {@code code};</li>
     *   <li>{@link RawBody#write(HttpServletResponse, int, byte[])} writes status 302 and the body
     *       with {@code Content-Length} and no {@code Content-Type}, and commits the response.</li>
     * </ol>
     *
     * @param request  the HTTP request; its raw query string and its body are read
     * @param response the HTTP response the 302 is written to
     * @throws IOException              if the request body cannot be read or the response cannot
     *                                  be written
     * @throws IllegalArgumentException if the selected {@code code} value holds a malformed
     *                                  percent escape, such as {@code %zz} or a trailing {@code %}
     */
    @RequestMapping
    public void redirectFlow(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String code = queryParameter(request.getQueryString(), "code");
        byte[] body = request.getInputStream().readAllBytes();
        response.setHeader(HttpHeaders.LOCATION, redirectService.redirectFlow(code));
        RawBody.write(response, HttpServletResponse.SC_FOUND, body);
    }

    // Decision: OPTIONS on /redirect runs redirectFlow through this private OPTIONS-only mapping
    // (D-499).
    /**
     * Runs {@link #redirectFlow(HttpServletRequest, HttpServletResponse)} for OPTIONS on
     * {@code /redirect}: the client receives the flow's 302, {@code Location} and echoed body, and
     * no {@code Allow} header.
     *
     * @param request  the HTTP OPTIONS request; its raw query string and its body are read
     * @param response the HTTP response the 302 is written to
     * @throws IOException if the request body cannot be read or the response cannot be written
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    private void redirectFlowOnOptions(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        redirectFlow(request, response);
    }

    /**
     * Returns the last URL-decoded value of a query-string parameter, or {@code null}.
     *
     * <p>The raw query string is split into parameters by {@link UriComponentsBuilder#query(String)},
     * whose names are compared with {@code name} as received, undecoded and case-sensitively. The
     * last parameter named {@code name} gives the result: {@code null} when it has no {@code =},
     * the empty string for {@code name=}, and otherwise its value decoded by
     * {@link URLDecoder#decode(String, java.nio.charset.Charset)} in UTF-8, which turns {@code +}
     * into a space and decodes each {@code %xx}.
     *
     * <pre>{@code
     * queryParameter(null, "code")                 -> null
     * queryParameter("code=abc", "code")           -> "abc"
     * queryParameter("code=a%2Bb+c", "code")       -> "a+b c"
     * queryParameter("code=1&state=x&code=2", "code") -> "2"
     * queryParameter("code=", "code")              -> ""
     * queryParameter("code", "code")               -> null
     * queryParameter("state=x", "code")            -> null
     * }</pre>
     *
     * @param rawQuery the raw, undecoded query string, or {@code null} when the request has none
     * @param name     the parameter name
     * @return the decoded value of the last {@code name} parameter, or {@code null}
     * @throws IllegalArgumentException if that value holds a malformed percent escape
     */
    private static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null) {
            return null;
        }
        MultiValueMap<String, String> parameters =
                UriComponentsBuilder.newInstance().query(rawQuery).build().getQueryParams();
        List<String> values = parameters.get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        String value = values.get(values.size() - 1);
        return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
