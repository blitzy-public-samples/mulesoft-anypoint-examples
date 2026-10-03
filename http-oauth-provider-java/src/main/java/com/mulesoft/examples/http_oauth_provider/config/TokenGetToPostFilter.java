package com.mulesoft.examples.http_oauth_provider.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Presents a {@code GET /token} as a form {@code POST} with the query parameters as form
 * parameters (D-041).
 *
 * <p>Source: the {@code Location} that {@code redirectFlow} answers with
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:56], a {@code GET} of
 * <pre>
 * http://localhost:${http.provider.port}/token?grant_type=authorization_code&amp;&amp;client_id=myclientid
 *     &amp;client_secret=&lt;secret&gt;&amp;code=&lt;code&gt;&amp;redirect_uri=http://localhost:${http.listener.port}/redirect
 * </pre>
 * which the browser follows and the provider answers with the token JSON
 * [http-oauth-provider/src/test/java/org/mule/examples/HttpOauthProviderIT.java:98].
 *
 * <p>Behaviour per request:
 * <ul>
 *   <li>{@code GET /token}: the rest of the filter chain receives a request whose method is
 *       {@code POST}, whose content type is {@code application/x-www-form-urlencoded}, which has no
 *       query string, and whose parameters are the request parameters read when the filter ran
 *       ({@code grant_type}, {@code client_id}, {@code client_secret}, {@code code},
 *       {@code redirect_uri}; the empty segment of {@code &&} carries no parameter). The token
 *       endpoint on {@code /token} reads them as form parameters, authenticates the client with
 *       {@code client_secret_post} and exchanges the code (D-041).</li>
 *   <li>Any other method on {@code /token}, a {@code POST} included: the filter chain receives the
 *       same request instance, unchanged.</li>
 *   <li>Any path other than {@code /token}: the filter does not run.</li>
 * </ul>
 *
 * <p>The filter is not a Spring bean. {@code AuthorizationServerConfig.tokenGetToPostFilterRegistration()}
 * registers one instance for the URL pattern {@code /token}, ordered ahead of the
 * {@code springSecurityFilterChain} registration:
 *
 * <pre>{@code
 * FilterRegistrationBean<TokenGetToPostFilter> registration =
 *         new FilterRegistrationBean<>(new TokenGetToPostFilter());
 * registration.addUrlPatterns("/token");
 * registration.setOrder(SecurityProperties.DEFAULT_FILTER_ORDER - 1);
 * }</pre>
 *
 * <p>The filter holds no per-request state and is safe for concurrent requests.
 */
public class TokenGetToPostFilter extends OncePerRequestFilter {

    /** Path of the token endpoint (D-041). */
    private static final String TOKEN_PATH = "/token";

    /** Method of the requests this filter presents as form posts. */
    private static final String GET = "GET";

    /** Method the presented request reports. */
    private static final String POST = "POST";

    /**
     * Creates the filter; it carries no configuration.
     */
    public TokenGetToPostFilter() {
        super();
    }

    /**
     * Hands a {@code GET} to the rest of the chain as a form {@code POST} carrying the captured
     * request parameters, and any other method unchanged (D-041).
     *
     * @param request  the incoming request
     * @param response the response, passed on unchanged
     * @param chain    the rest of the filter chain
     * @throws ServletException if a later filter or the servlet fails
     * @throws IOException      if reading the request or writing the response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (GET.equals(request.getMethod())) {
            FormPostRequestWrapper formPost = new FormPostRequestWrapper(request);
            if (logger.isDebugEnabled()) {
                logger.debug("GET " + TOKEN_PATH + " presented as a form POST with "
                        + formPost.getParameterMap().size() + " parameter(s)");
            }
            chain.doFilter(formPost, response);
        } else {
            chain.doFilter(request, response);
        }
    }

    /**
     * Skips every request whose URI is not {@code /token}.
     *
     * @param request the incoming request
     * @return {@code true} when the request URI is not {@code /token}
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !TOKEN_PATH.equals(request.getRequestURI());
    }

    /**
     * A {@code GET} request shown as an {@code application/x-www-form-urlencoded} {@code POST}
     * whose form parameters are the parameters of the wrapped request (D-041).
     *
     * <p>The parameter map is copied at construction, in the wrapped request's order, and is
     * read-only. Every other request property, other headers included, comes from the wrapped
     * request.
     */
    private static final class FormPostRequestWrapper extends HttpServletRequestWrapper {

        /** Parameters of the wrapped request, read-only, in their original order. */
        private final Map<String, String[]> parameters;

        /**
         * Copies the parameters of {@code request}.
         *
         * @param request the {@code GET} request to present as a form {@code POST}
         */
        FormPostRequestWrapper(HttpServletRequest request) {
            super(request);
            Map<String, String[]> captured = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
                String[] values = entry.getValue();
                captured.put(entry.getKey(), values == null ? new String[0] : values.clone());
            }
            this.parameters = Collections.unmodifiableMap(captured);
        }

        /**
         * Returns {@code POST}.
         *
         * @return {@code "POST"}
         */
        @Override
        public String getMethod() {
            return POST;
        }

        /**
         * Returns {@code application/x-www-form-urlencoded}.
         *
         * @return the form media type
         */
        @Override
        public String getContentType() {
            return MediaType.APPLICATION_FORM_URLENCODED_VALUE;
        }

        /**
         * Returns {@code application/x-www-form-urlencoded} for {@code Content-Type}, matched in any
         * letter case, and the wrapped request's value for every other header.
         *
         * @param name the header name
         * @return the header value, or {@code null} when the wrapped request has no such header
         */
        @Override
        public String getHeader(String name) {
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                return MediaType.APPLICATION_FORM_URLENCODED_VALUE;
            }
            return super.getHeader(name);
        }

        /**
         * Returns {@code null}: the parameters are form parameters only.
         *
         * @return {@code null}
         */
        @Override
        public String getQueryString() {
            return null;
        }

        /**
         * Returns the first captured value of the parameter.
         *
         * @param name the parameter name
         * @return the first value, or {@code null} when the parameter is absent or has no value
         */
        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        /**
         * Returns the captured parameters.
         *
         * @return a read-only map from parameter name to its values, in the original order
         */
        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        /**
         * Returns the captured parameter names in their original order.
         *
         * @return an enumeration of the parameter names
         */
        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        /**
         * Returns a copy of the captured values of the parameter.
         *
         * @param name the parameter name
         * @return the values, or {@code null} when the parameter is absent
         */
        @Override
        public String[] getParameterValues(String name) {
            String[] values = parameters.get(name);
            return values == null ? null : values.clone();
        }
    }
}
