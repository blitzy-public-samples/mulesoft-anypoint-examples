package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config;

import java.net.URI;
import java.net.URISyntaxException;
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
 * <p>The compact constructor validates every component at binding (D-332). It rejects a {@code host}
 * that is absent, blank or the {@code TODO} placeholder (compared after trimming, ignoring case), or that
 * {@link URI} does not parse as exactly a server-based host: a host name, an IPv4 address or an IPv6
 * address in square brackets, with no scheme, port, user information, path, query, fragment or
 * whitespace. It rejects a {@code basePath} or {@code path} that is absent, blank or the {@code TODO}
 * placeholder; neither needs a leading slash. It rejects a {@code responseTimeout} that is absent, zero or
 * negative. Each rejection is an {@link IllegalArgumentException} whose message names the full
 * {@code request.http-request-configuration.*} key and omits the bound value.
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

    /** Key prefix of every component. */
    private static final String PREFIX = "request.http-request-configuration.";

    /** Placeholder value of a key still to be supplied, matched after trimming and ignoring case. */
    private static final String PLACEHOLDER = "TODO";

    /**
     * Validates every component in declaration order and keeps each value unchanged.
     *
     * @param host            the bound {@code host}
     * @param basePath        the bound {@code base-path}
     * @param responseTimeout the bound {@code response-timeout}
     * @param path            the bound {@code path}
     * @throws IllegalArgumentException naming the first invalid key: {@code host} absent, blank, the
     *                                  placeholder or not a server-based URI host; {@code base-path} or
     *                                  {@code path} absent, blank or the placeholder;
     *                                  {@code response-timeout} absent, zero or negative
     */
    public HttpRequestConfigurationProperties {
        requireHost(host, PREFIX + "host");
        requireText(basePath, PREFIX + "base-path");
        requirePositive(responseTimeout, PREFIX + "response-timeout");
        requireText(path, PREFIX + "path");
    }

    /**
     * Rejects a value that is absent, blank or the placeholder.
     *
     * @param value the bound value
     * @param key   the full configuration key of {@code value}
     * @throws IllegalArgumentException naming {@code key} when {@code value} is rejected
     */
    private static void requireText(String value, String key) {
        if (value == null) {
            throw new IllegalArgumentException(key + " must be set");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
        if (PLACEHOLDER.equalsIgnoreCase(value.strip())) {
            throw new IllegalArgumentException(key + " must be replaced: it holds the placeholder");
        }
    }

    /**
     * Rejects a value that {@link #requireText(String, String)} rejects, or that {@link URI} does not
     * parse, as the host of an {@code http} URI, into a server-based host equal to the whole value.
     *
     * @param host the bound host
     * @param key  the full configuration key of {@code host}
     * @throws IllegalArgumentException naming {@code key} when {@code host} is rejected, with no cause
     */
    private static void requireHost(String host, String key) {
        requireText(host, key);
        String parsedHost;
        try {
            parsedHost = new URI("http", null, host, -1, null, null, null).getHost();
        } catch (URISyntaxException e) {
            parsedHost = null;
        }
        if (!host.equals(parsedHost)) {
            throw new IllegalArgumentException(key + " must be a host name, an IPv4 address or an IPv6"
                    + " address in square brackets, with no scheme, port, user information, path, query,"
                    + " fragment or whitespace");
        }
    }

    /**
     * Rejects a duration that is absent, zero or negative.
     *
     * @param value the bound duration
     * @param key   the full configuration key of {@code value}
     * @throws IllegalArgumentException naming {@code key} when {@code value} is rejected
     */
    private static void requirePositive(Duration value, String key) {
        if (value == null) {
            throw new IllegalArgumentException(key + " must be set");
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + " must be a positive duration");
        }
    }
}
