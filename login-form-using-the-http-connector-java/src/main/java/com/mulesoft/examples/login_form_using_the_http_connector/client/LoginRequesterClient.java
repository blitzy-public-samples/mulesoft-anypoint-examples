package com.mulesoft.examples.login_form_using_the_http_connector.client;

import com.mulesoft.examples.login_form_using_the_http_connector.config.HttpRequestConfig;
import com.mulesoft.examples.login_form_using_the_http_connector.exception.ResponseValidatorException;
import com.mulesoft.examples.login_form_using_the_http_connector.model.PageResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

/**
 * Outbound login request of the flow {@code CallLoginFlowUsingRequester}: the
 * {@code http:request config-ref="HttpRequestConfig" method="POST" path="/login"} element
 * [login-form-using-the-http-connector/src/main/app/login-form-using-the-http-connector.xml:42],
 * sent through the request configuration {@code HttpRequestConfig} (:5).
 *
 * <p>{@link #postLogin(MultiValueMap)} sends one {@code POST} to
 * {@link HttpRequestConfig#baseUrl()}{@code + "/login"} with the form encoded as
 * {@code application/x-www-form-urlencoded}, and returns the status, the raw {@code Content-Type}
 * header and the body bytes of the reply as a {@link PageResponse}.
 *
 * <p>Status validation is the requester's default success range {@code 0..399}. Any other status
 * raises {@link ResponseValidatorException} with the message
 * {@code Response code <status> mapped as failure.}. A connection failure surfaces as
 * {@link org.springframework.web.client.ResourceAccessException}. Each call makes exactly one
 * attempt, with no retry, no fallback and no timeout (D-020, D-654).
 *
 * <p>Usage, as in {@code service/LoginService#callLoginFlowUsingRequester}:
 *
 * <pre>{@code
 * MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
 * form.add("username", loginProperties.username());
 * form.add("password", loginProperties.password());
 * PageResponse page = loginRequesterClient.postLogin(form);
 * }</pre>
 *
 * <p>Instances are thread-safe: both fields are immutable after construction and the
 * {@link RestClient} is shared across calls.
 */
@Component
public class LoginRequesterClient {

    private static final String LOGIN_PATH = "/login";

    private static final int MIN_SUCCESS_STATUS = 0;

    private static final int MAX_SUCCESS_STATUS = 399;

    private final RestClient restClient;

    private final HttpRequestConfig httpRequestConfig;

    /**
     * Builds the {@link RestClient} of the login request from the injected builder.
     *
     * <p>The client has no base URL, no default header and no request interceptor. Its request
     * factory is a {@link SimpleClientHttpRequestFactory}, which streams the request body and sends
     * each request once (D-654).
     *
     * @param restClientBuilder the Spring Boot {@link RestClient.Builder}, with its HTTP message
     *     converters
     * @param httpRequestConfig the target address of the request, read on every call
     */
    public LoginRequesterClient(RestClient.Builder restClientBuilder, HttpRequestConfig httpRequestConfig) {
        this.httpRequestConfig = httpRequestConfig;
        // Request factory: SimpleClientHttpRequestFactory on every classpath, one attempt per call;
        // departs from keeping the builder's auto-detected factory (D-654, D-020).
        this.restClient = restClientBuilder
                .requestFactory(new SimpleClientHttpRequestFactory())
                .build();
    }

    /**
     * Sends {@code form} as an {@code application/x-www-form-urlencoded} {@code POST} to
     * {@link HttpRequestConfig#baseUrl()}{@code + "/login"}
     * [login-form-using-the-http-connector/src/main/app/login-form-using-the-http-connector.xml:42].
     *
     * <p>The base URL is read from {@link HttpRequestConfig} at each call. The form is encoded once
     * by the builder's form message converter. The reply is read in full before the response
     * closes.
     *
     * @param form the form fields, sent in their iteration order
     * @return the reply's status, its {@code Content-Type} header exactly as received, or
     *     {@code null} when the reply has none (D-066), and its body bytes
     * @throws ResponseValidatorException when the reply status is outside {@code 0..399}, with the
     *     message {@code Response code <status> mapped as failure.}
     * @throws org.springframework.web.client.ResourceAccessException when the request cannot be sent
     *     or the reply cannot be read, for example on a refused connection
     */
    public PageResponse postLogin(MultiValueMap<String, String> form) {
        return restClient.post()
                .uri(httpRequestConfig.baseUrl() + LOGIN_PATH)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (!isSuccess(status)) {
                        throw new ResponseValidatorException("Response code " + status + " mapped as failure.");
                    }
                    String contentType = response.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE);
                    byte[] body = StreamUtils.copyToByteArray(response.getBody());
                    return new PageResponse(status, contentType, body);
                });
    }

    /**
     * Returns whether {@code status} lies in the success range {@code 0..399}.
     *
     * @param status the reply's status code
     * @return {@code true} for {@code 0 <= status <= 399}
     */
    private static boolean isSuccess(int status) {
        return status >= MIN_SUCCESS_STATUS && status <= MAX_SUCCESS_STATUS;
    }
}
