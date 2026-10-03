package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Reads the HTTP status of each Workday Revenue_Management response before Spring WS processes it.
 * Throws {@link UpstreamHttpStatusException} for HTTP 401 and 429 responses and returns every other
 * response unchanged (D-020, D-173).
 *
 * <p>For a 401 or 429 response the interceptor copies the raw {@code Retry-After} header value, or
 * {@code null} when the header is absent, closes the response and throws. {@code WebServiceTemplate}
 * wraps the exception in a {@code WebServiceIOException} ("I/O error: ..."), and
 * {@code WorkdayRevenueClient.execute} finds it in the cause chain: 401 takes the authentication
 * branch and 429 the rate-limit branch (D-020). Every other status, 2xx and the HTTP 500 that carries
 * a SOAP fault included, passes through unread, and Spring WS handles it as usual; a SOAP fault still
 * becomes a {@code SoapFaultClientException}.
 *
 * <p>The request is executed exactly once per call: the interceptor never re-executes a request and
 * never waits. It holds no state and is safe for concurrent use; {@code config.WorkdayWsConfig}
 * creates the single instance.
 *
 * <p>Expects an HttpClient without an Authenticator. With an {@code Authenticator} set, the JDK
 * {@code HttpClient} raises its own {@code IOException} for a 401 response that carries no
 * {@code WWW-Authenticate} header, and this interceptor never sees the status (D-173).
 *
 * <p>Example, as {@code config.WorkdayWsConfig} wires it:
 * <pre>{@code
 * HttpClient httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
 * ClientHttpRequestFactory factory = new InterceptingClientHttpRequestFactory(
 *         new JdkClientHttpRequestFactory(httpClient), List.of(new WorkdayHttpStatusInterceptor()));
 * template.setMessageSender(new ClientHttpRequestMessageSender(factory));
 * }</pre>
 */
public class WorkdayHttpStatusInterceptor implements ClientHttpRequestInterceptor {

    /**
     * Executes the request once and inspects the status of its response (D-020, D-173).
     *
     * <p>A 401 or 429 response is closed and reported as {@link UpstreamHttpStatusException}. Any
     * other response is returned as received, with its body unread. A response whose status cannot be
     * read is closed and the failure is propagated.
     *
     * @param request   the outgoing Workday SOAP request
     * @param body      the serialized SOAP envelope
     * @param execution the remaining request execution chain
     * @return the response of the execution, unchanged, for every status other than 401 and 429
     * @throws UpstreamHttpStatusException for an HTTP 401 or 429 response; it carries the status and the
     *                                     raw {@code Retry-After} value, or {@code null} when absent
     * @throws IOException                 when the execution fails or the status cannot be read,
     *                                     propagated unchanged
     */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        int status;
        try {
            status = response.getStatusCode().value();
        } catch (IOException | RuntimeException ex) {
            response.close();
            throw ex;
        }
        if (status == HttpStatus.UNAUTHORIZED.value() || status == HttpStatus.TOO_MANY_REQUESTS.value()) {
            String retryAfter = response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
            response.close();
            throw new UpstreamHttpStatusException(status, retryAfter);
        }
        return response;
    }

    /**
     * Reports an HTTP 401 or 429 response from Workday (D-020, D-173). Carries the status code and the
     * raw {@code Retry-After} header value of that response. Spring WS delivers it wrapped in a
     * {@code WebServiceIOException}.
     *
     * <p>Example:
     * <pre>{@code
     * UpstreamHttpStatusException ex = new UpstreamHttpStatusException(429, "30");
     * ex.getMessage();   // "Workday responded with HTTP status 429"
     * ex.status();       // 429
     * ex.retryAfter();   // "30"
     * }</pre>
     */
    static final class UpstreamHttpStatusException extends IOException {

        /** Serialization version of this exception type. */
        private static final long serialVersionUID = 1L;

        /** HTTP status code of the Workday response, 401 or 429. */
        private final int status;

        /** {@code Retry-After} header value exactly as received, or {@code null} when absent. */
        private final String retryAfter;

        /**
         * Creates the exception with the message {@code Workday responded with HTTP status <status>}.
         *
         * @param status     HTTP status code of the Workday response, 401 or 429
         * @param retryAfter the raw {@code Retry-After} header value (delay-seconds or an HTTP-date),
         *                   or {@code null} when the response carries none; stored without parsing
         */
        UpstreamHttpStatusException(int status, String retryAfter) {
            super("Workday responded with HTTP status " + status);
            this.status = status;
            this.retryAfter = retryAfter;
        }

        /**
         * Returns the HTTP status code of the Workday response.
         *
         * @return 401 or 429
         */
        int status() {
            return status;
        }

        /**
         * Returns the {@code Retry-After} header value of the Workday response.
         *
         * @return the header value exactly as received, or {@code null} when the response carried none
         */
        String retryAfter() {
            return retryAfter;
        }
    }
}
