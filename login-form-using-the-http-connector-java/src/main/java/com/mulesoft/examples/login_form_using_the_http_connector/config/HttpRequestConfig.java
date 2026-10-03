package com.mulesoft.examples.login_form_using_the_http_connector.config;

import java.util.Objects;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Target address of the HTTP request configuration {@code HttpRequestConfig}
 * [login-form-using-the-http-connector/src/main/app/login-form-using-the-http-connector.xml:5],
 * which the {@code CallLoginFlowUsingRequester} flow uses for its {@code POST /login} request to the
 * application's own {@code DoLoginFlow} (:42).
 *
 * <p>{@link #baseUrl()} builds the base URL of that loopback login request from two
 * {@code application.yml} keys:
 *
 * <ul>
 *   <li>{@code request.http-request-config.host}, default {@code 0.0.0.0}, the host of the original
 *       configuration;
 *   <li>{@code request.http-request-config.port}, falling back to {@code http.port} and then to
 *       {@code 8081} (D-065).
 * </ul>
 *
 * <p>Both keys are read from the Spring {@link Environment} on every call and never cached; the
 * constructor reads no key (D-431). A key value that holds a placeholder, for example
 * {@code ${local.server.port}}, is resolved at the call, against the property sources present at that
 * moment.
 *
 * <p>Usage, for the loopback request of {@code client/LoginRequesterClient}:
 *
 * <pre>{@code
 * restClient.post()
 *     .uri(httpRequestConfig.baseUrl() + "/login")
 *     .contentType(MediaType.APPLICATION_FORM_URLENCODED)
 *     .body(form)
 * }</pre>
 *
 * <p>With the committed {@code application.yml}, {@code baseUrl()} returns
 * {@code http://0.0.0.0:8081}.
 */
@Component
public class HttpRequestConfig {

    /** Key of the request host, the {@code host} attribute of the original configuration. */
    private static final String HOST_KEY = "request.http-request-config.host";

    /** Key of the request port, the {@code port} attribute of the original configuration. */
    private static final String PORT_KEY = "request.http-request-config.port";

    /** Key of the application port, the fallback of {@link #PORT_KEY} (D-065). */
    private static final String HTTP_PORT_KEY = "http.port";

    /** Host used when {@link #HOST_KEY} is absent: the literal {@code host} of the original. */
    private static final String DEFAULT_HOST = "0.0.0.0";

    /** Port used when neither {@link #PORT_KEY} nor {@link #HTTP_PORT_KEY} is present (D-065). */
    private static final String DEFAULT_PORT = "8081";

    /** Property source read by every {@link #baseUrl()} call. */
    private final Environment environment;

    /**
     * Creates the configuration over the application's environment. No key is read here.
     *
     * @param environment the Spring environment that {@link #baseUrl()} reads; not {@code null}
     * @throws NullPointerException when {@code environment} is {@code null}
     */
    public HttpRequestConfig(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment");
    }

    /**
     * Builds the base URL of the loopback login request from
     * {@code request.http-request-config.host} and {@code request.http-request-config.port}, falling
     * back to {@code http.port} and then 8081 (D-065).
     *
     * <p>The host is {@code request.http-request-config.host}, or {@code 0.0.0.0} when the key is
     * absent. The port is {@code request.http-request-config.port}, or {@code http.port} when that
     * key is absent, or {@code 8081} when both are absent. Both values are read from the environment
     * at this call and used as read, with no trimming or validation. The result is
     * {@code "http://" + host + ":" + port}, with no trailing slash, for example
     * {@code http://0.0.0.0:8081}.
     *
     * @return the base URL {@code http://<host>:<port>} of the loopback login request
     * @throws IllegalArgumentException when a read value holds a placeholder that no property source
     *     resolves at this call, for example {@code ${local.server.port}} before the embedded server
     *     has started
     */
    public String baseUrl() {
        String host = environment.getProperty(HOST_KEY, DEFAULT_HOST);
        String port = environment.getProperty(PORT_KEY, environment.getProperty(HTTP_PORT_KEY, DEFAULT_PORT));
        return "http://" + host + ":" + port;
    }
}
