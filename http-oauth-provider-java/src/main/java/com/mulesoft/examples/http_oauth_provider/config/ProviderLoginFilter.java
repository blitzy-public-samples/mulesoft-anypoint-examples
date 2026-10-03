package com.mulesoft.examples.http_oauth_provider.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.HtmlUtils;

/**
 * Serves the login page for an unauthenticated {@code GET /authorize} and authenticates
 * {@code POST /authorize} (D-041, D-307, D-665).
 *
 * <p>Source: the login step of {@code oauth2-provider:config} {@code oauth2Provider}
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:26], whose resource owner is checked by
 * {@code ss:authentication-manager} {@code resourceOwnerAuthenticationManager}
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:13-19]. The original test opens
 * <pre>
 * GET http://localhost:&lt;provider&gt;/authorize?response_type=code&amp;client_id=myclientid
 *     &amp;scope=READ_RESOURCE&amp;redirect_uri=http://localhost:&lt;listener&gt;/redirect
 * </pre>
 * types the resource owner's credentials into the inputs with the ids {@code username} and
 * {@code password} and submits the form
 * [http-oauth-provider/src/test/java/org/mule/examples/HttpOauthProviderIT.java:85-96].
 *
 * <p>Behaviour per request on {@code /authorize}, in this order:
 * <ol>
 *   <li>The security context already holds an authentication that is neither {@code null} nor an
 *       {@link AnonymousAuthenticationToken} and reports {@link Authentication#isAuthenticated()}:
 *       the rest of the chain receives the request unchanged.</li>
 *   <li>{@code GET}: the response is 200 with the login page {@code templates/provider/login.html},
 *       {@code Content-Type: text/html;charset=UTF-8} and its byte length as
 *       {@code Content-Length}. Every {@code #[providerName]} is replaced, unescaped, by
 *       {@code oauth2-provider.provider-name} and {@code #[hiddenFields]} by one hidden input
 *       per OAuth parameter of the request ({@code response_type}, {@code client_id}, {@code scope},
 *       {@code redirect_uri}, {@code state}, in that order, each only when present, values
 *       HTML-escaped). The chain does not continue.</li>
 *   <li>{@code POST} carrying a {@code username} parameter: the {@code username} and
 *       {@code password} parameters (an absent {@code password} is the empty string) are checked
 *       against the {@link UserDetailsService} with the delegating password encoder.
 *       <ul>
 *         <li>Accepted: an existing HTTP session receives a new session id (D-665), a new security context
 *             holding the authenticated resource owner is set on {@link SecurityContextHolder} and
 *             stored in the HTTP session under
 *             {@link HttpSessionSecurityContextRepository#SPRING_SECURITY_CONTEXT_KEY}, and the rest
 *             of the chain receives the request as a {@code GET /authorize} whose parameters and
 *             query string are the OAuth parameters of the {@code POST}; the authorization endpoint
 *             then answers 302 to {@code redirect_uri} with {@code code} (D-041).</li>
 *         <li>Rejected (any {@link AuthenticationException}): the login page is answered again with
 *             200 and the OAuth parameters of the {@code POST} as hidden inputs; nothing is stored
 *             in the session, the chain does not continue and no code is issued (D-665).</li>
 *       </ul></li>
 *   <li>Any other request ({@code POST} without {@code username}, any other method): the rest of
 *       the chain receives the request unchanged.</li>
 * </ol>
 * Any path other than {@code /authorize} does not run the filter.
 *
 * <p>The filter is not a Spring bean. {@code AuthorizationServerConfig} adds one instance to the
 * authorization-server security filter chain ahead of the authorization endpoint:
 * <pre>{@code
 * http.addFilterBefore(new ProviderLoginFilter(properties, userDetailsService),
 *         AbstractPreAuthenticatedProcessingFilter.class);
 * }</pre>
 *
 * <p>The page template is read once, at construction. The filter holds no per-request state and is
 * safe for concurrent requests.
 */
public class ProviderLoginFilter extends OncePerRequestFilter {

    /** Path of the authorization endpoint (D-041). */
    private static final String AUTHORIZE_PATH = "/authorize";

    /** Classpath location of the login page template (D-307). */
    private static final String TEMPLATE_LOCATION = "templates/provider/login.html";

    /** Template token replaced by {@code oauth2-provider.provider-name} wherever it occurs. */
    private static final String PROVIDER_NAME_TOKEN = "#[providerName]";

    /** Template token replaced by the hidden OAuth parameter inputs. */
    private static final String HIDDEN_FIELDS_TOKEN = "#[hiddenFields]";

    /** Request parameter carrying the resource owner's user name. */
    private static final String USERNAME_PARAMETER = "username";

    /** Request parameter carrying the resource owner's password. */
    private static final String PASSWORD_PARAMETER = "password";

    /** Method of the requests answered with the login page. */
    private static final String GET = "GET";

    /** Method of the login form submission. */
    private static final String POST = "POST";

    /** {@code Content-Type} of the login page. */
    private static final String LOGIN_PAGE_CONTENT_TYPE = "text/html;charset=UTF-8";

    /**
     * OAuth parameters carried as hidden inputs and forwarded after a successful login, in page and
     * forwarding order (D-041).
     */
    private static final List<String> FORWARDED_PARAMETERS = List.of(
            OAuth2ParameterNames.RESPONSE_TYPE,
            OAuth2ParameterNames.CLIENT_ID,
            OAuth2ParameterNames.SCOPE,
            OAuth2ParameterNames.REDIRECT_URI,
            OAuth2ParameterNames.STATE);

    /** Checks the submitted credentials against the resource-owner user service. */
    private final DaoAuthenticationProvider daoAuthenticationProvider;

    /** Stores the security context of an accepted login in the HTTP session. */
    private final HttpSessionSecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    /** The login page template as read from {@code templates/provider/login.html}. */
    private final String template;

    /** {@code oauth2-provider.provider-name}, written into the login page. */
    private final String providerName;

    /**
     * Creates the filter for the configured provider and resource-owner user service (D-041).
     *
     * <p>The credentials of a login submission are checked by a {@link DaoAuthenticationProvider}
     * over {@code userDetailsService} with
     * {@link PasswordEncoderFactories#createDelegatingPasswordEncoder()}, which reads stored
     * passwords in the {@code {id}encoded} form ({@code {noop}<password>} included). The template
     * {@code templates/provider/login.html} is read from the classpath as UTF-8.
     *
     * @param properties         the bound {@code oauth2-provider.*} keys; its
     *                           {@link OAuthProviderProperties#providerName()} is written into the page
     * @param userDetailsService the service that loads the resource owner by user name
     * @throws NullPointerException  if {@code properties} or {@code userDetailsService} is {@code null}
     * @throws IllegalStateException if {@code oauth2-provider.provider-name} is not set, or the
     *                               template does not contain {@code #[hiddenFields]} (D-665)
     * @throws UncheckedIOException  if the template cannot be read from the classpath
     */
    public ProviderLoginFilter(OAuthProviderProperties properties, UserDetailsService userDetailsService) {
        Objects.requireNonNull(properties, "properties must not be null");
        Objects.requireNonNull(userDetailsService, "userDetailsService must not be null");
        // Startup checks of the provider name and the template token (D-665).
        if (properties.providerName() == null) {
            throw new IllegalStateException("oauth2-provider.provider-name must be set");
        }
        this.providerName = properties.providerName();
        this.template = readTemplate();
        if (!template.contains(HIDDEN_FIELDS_TOKEN)) {
            throw new IllegalStateException(
                    "Login page template " + TEMPLATE_LOCATION + " must contain " + HIDDEN_FIELDS_TOKEN);
        }
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
        this.daoAuthenticationProvider = provider;
    }

    /**
     * Skips every request whose URI is not exactly {@code /authorize}.
     *
     * @param request the incoming request
     * @return {@code true} when the undecoded request URI is not {@code /authorize}
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AUTHORIZE_PATH.equals(request.getRequestURI());
    }

    /**
     * Passes an authenticated request on unchanged, answers an unauthenticated {@code GET} with the
     * login page, authenticates a {@code POST} carrying {@code username}, and passes any other
     * request on unchanged (D-041, D-665).
     *
     * @param request  the incoming request on {@code /authorize}
     * @param response the response
     * @param chain    the rest of the security filter chain
     * @throws ServletException if a later filter or the servlet fails
     * @throws IOException      if reading the request or writing the response fails
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
            chain.doFilter(request, response);
            return;
        }
        String method = request.getMethod();
        if (GET.equals(method)) {
            renderLoginPage(request, response);
            return;
        }
        if (POST.equals(method)) {
            String username = request.getParameter(USERNAME_PARAMETER);
            if (username != null) {
                authenticateLogin(request, response, chain, username);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /**
     * Checks the submitted credentials; on success stores the security context and forwards the
     * OAuth parameters as a {@code GET /authorize}, on failure answers the login page again (D-041,
     * D-665).
     *
     * @param request  the login {@code POST}
     * @param response the response
     * @param chain    the rest of the security filter chain
     * @param username the submitted {@code username} parameter
     * @throws ServletException if a later filter or the servlet fails
     * @throws IOException      if writing the response fails
     */
    private void authenticateLogin(HttpServletRequest request, HttpServletResponse response, FilterChain chain,
            String username) throws ServletException, IOException {
        String submittedPassword = request.getParameter(PASSWORD_PARAMETER);
        String password = submittedPassword == null ? "" : submittedPassword;
        UsernamePasswordAuthenticationToken token =
                UsernamePasswordAuthenticationToken.unauthenticated(username, password);
        Authentication result;
        try {
            result = daoAuthenticationProvider.authenticate(token);
        } catch (AuthenticationException ex) {
            if (logger.isDebugEnabled()) {
                logger.debug("Login on " + AUTHORIZE_PATH + " rejected: " + ex.getClass().getSimpleName());
            }
            renderLoginPage(request, response);
            return;
        }
        // An existing session receives a new id before the login is stored in it (D-665).
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(result);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
        AuthorizationRequestWrapper authorizationRequest =
                new AuthorizationRequestWrapper(request, FORWARDED_PARAMETERS);
        if (logger.isDebugEnabled()) {
            logger.debug("Login on " + AUTHORIZE_PATH + " accepted; forwarding "
                    + authorizationRequest.getParameterMap().size() + " OAuth parameter(s) as GET");
        }
        chain.doFilter(authorizationRequest, response);
    }

    /**
     * Writes the login page: status 200, {@code Content-Type: text/html;charset=UTF-8},
     * {@code Content-Length} and the UTF-8 bytes of the template with {@code #[providerName]}
     * replaced by the provider name and {@code #[hiddenFields]} by the hidden OAuth parameter
     * inputs of {@code request} (D-307).
     *
     * @param request  the request whose OAuth parameters become hidden inputs
     * @param response the response to write
     * @throws IOException if writing the response fails
     */
    private void renderLoginPage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String page = template
                .replace(PROVIDER_NAME_TOKEN, providerName)
                .replace(HIDDEN_FIELDS_TOKEN, hiddenFields(request));
        byte[] body = page.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(LOGIN_PAGE_CONTENT_TYPE);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
        if (logger.isDebugEnabled()) {
            logger.debug("Login page served on " + AUTHORIZE_PATH + " (" + body.length + " bytes)");
        }
    }

    /**
     * Builds one hidden input per OAuth parameter present on {@code request}, in the order of
     * {@code response_type}, {@code client_id}, {@code scope}, {@code redirect_uri}, {@code state}.
     *
     * <p>Example: {@code client_id=myclientid} gives
     * {@code <input type="hidden" name="client_id" value="myclientid"/>}; a value
     * {@code a"b} is written as {@code a&quot;b}.
     *
     * @param request the request carrying the OAuth parameters
     * @return the inputs separated by {@code \n}; the empty string when no OAuth parameter is present
     */
    private static String hiddenFields(HttpServletRequest request) {
        StringJoiner inputs = new StringJoiner("\n");
        for (String name : FORWARDED_PARAMETERS) {
            String value = request.getParameter(name);
            if (value != null) {
                inputs.add("<input type=\"hidden\" name=\"" + name + "\" value=\""
                        + HtmlUtils.htmlEscape(value) + "\"/>");
            }
        }
        return inputs.toString();
    }

    /**
     * Tells whether {@code authentication} is a non-anonymous, authenticated principal.
     *
     * @param authentication the authentication of the current security context, possibly {@code null}
     * @return {@code true} when it is not {@code null}, not an {@link AnonymousAuthenticationToken} and
     *         reports {@link Authentication#isAuthenticated()}
     */
    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && !(authentication instanceof AnonymousAuthenticationToken)
                && authentication.isAuthenticated();
    }

    /**
     * Reads the login page template {@code templates/provider/login.html} from the classpath as UTF-8.
     *
     * @return the template text
     * @throws UncheckedIOException if the template is missing or cannot be read
     */
    private static String readTemplate() {
        ClassPathResource resource = new ClassPathResource(TEMPLATE_LOCATION);
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read login page template " + TEMPLATE_LOCATION, ex);
        }
    }

    /**
     * A login {@code POST} shown as the {@code GET /authorize} authorization request it carries
     * (D-041).
     *
     * <p>The parameters are the OAuth parameters of the wrapped request that are present, with all
     * their non-{@code null} values, in the order given at construction; {@code username},
     * {@code password} and every other parameter are left out. The parameter map is copied at
     * construction and is read-only. Every other request property comes from the wrapped request.
     */
    private static final class AuthorizationRequestWrapper extends HttpServletRequestWrapper {

        /** The forwarded OAuth parameters, read-only, in forwarding order. */
        private final Map<String, String[]> parameters;

        /**
         * Copies the parameters named in {@code names} that {@code request} carries.
         *
         * @param request the login {@code POST}
         * @param names   the OAuth parameter names to forward, in forwarding order
         */
        AuthorizationRequestWrapper(HttpServletRequest request, List<String> names) {
            super(request);
            Map<String, String[]> captured = new LinkedHashMap<>();
            for (String name : names) {
                String[] values = request.getParameterValues(name);
                if (values == null) {
                    continue;
                }
                String[] present = Arrays.stream(values).filter(Objects::nonNull).toArray(String[]::new);
                if (present.length > 0) {
                    captured.put(name, present);
                }
            }
            this.parameters = Collections.unmodifiableMap(captured);
        }

        /**
         * Returns {@code GET}.
         *
         * @return {@code "GET"}
         */
        @Override
        public String getMethod() {
            return GET;
        }

        /**
         * Returns the first forwarded value of the parameter.
         *
         * @param name the parameter name
         * @return the first value, or {@code null} when the parameter is not forwarded
         */
        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values == null || values.length == 0 ? null : values[0];
        }

        /**
         * Returns the forwarded parameters.
         *
         * @return a read-only map from parameter name to its values, in forwarding order
         */
        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        /**
         * Returns the forwarded parameter names in forwarding order.
         *
         * @return an enumeration of the parameter names
         */
        @Override
        public Enumeration<String> getParameterNames() {
            return Collections.enumeration(parameters.keySet());
        }

        /**
         * Returns a copy of the forwarded values of the parameter.
         *
         * @param name the parameter name
         * @return the values, or {@code null} when the parameter is not forwarded
         */
        @Override
        public String[] getParameterValues(String name) {
            String[] values = parameters.get(name);
            return values == null ? null : values.clone();
        }

        /**
         * Returns the forwarded parameters as a query string: one {@code name=value} pair per value,
         * the value encoded with {@link URLEncoder#encode(String, java.nio.charset.Charset)} in UTF-8,
         * pairs joined by {@code &} in forwarding order.
         *
         * <p>Example: {@code redirect_uri=http://localhost:8082/redirect} is written as
         * {@code redirect_uri=http%3A%2F%2Flocalhost%3A8082%2Fredirect}.
         *
         * @return the query string, or {@code null} when no parameter is forwarded
         */
        @Override
        public String getQueryString() {
            if (parameters.isEmpty()) {
                return null;
            }
            StringJoiner query = new StringJoiner("&");
            for (Map.Entry<String, String[]> entry : parameters.entrySet()) {
                for (String value : entry.getValue()) {
                    query.add(entry.getKey() + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8));
                }
            }
            return query.toString();
        }
    }
}

