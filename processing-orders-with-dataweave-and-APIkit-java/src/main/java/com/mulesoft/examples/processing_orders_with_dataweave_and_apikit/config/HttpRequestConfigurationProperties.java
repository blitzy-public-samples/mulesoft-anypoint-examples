package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * Host, base path, response timeout and request path of the currency API request
 * {@code HTTP_Request_Configuration}
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:3], bound from the
 * {@code request.http-request-configuration} keys of {@code application.yml}.
 *
 * <p>Each component binds the key of the same name in kebab case: {@code host},
 * {@code base-path}, {@code response-timeout} and {@code path}. The {@code path} component is the
 * request path key of D-140. {@code client/CurrencyClient} sends
 * {@code GET http://<host>:<server port><basePath><path>} with these values (D-054).
 *
 * @param host            host of the currency API request; {@code localhost} in
 *                        {@code application.yml}
 * @param basePath        base path of the currency API request; {@code /api/} in
 *                        {@code application.yml}
 * @param responseTimeout response timeout of the currency API request, a bare number read as
 *                        milliseconds; {@code 10000} in {@code application.yml}
 * @param path            path of the Get Currencies request
 *                        [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:10],
 *                        which follows {@code basePath} in the request URI; {@code /currencies}
 *                        in {@code application.yml} (D-140)
 */
@ConfigurationProperties("request.http-request-configuration")
public record HttpRequestConfigurationProperties(
        String host,
        String basePath,
        @DurationUnit(ChronoUnit.MILLIS) Duration responseTimeout,
        String path) {
}
