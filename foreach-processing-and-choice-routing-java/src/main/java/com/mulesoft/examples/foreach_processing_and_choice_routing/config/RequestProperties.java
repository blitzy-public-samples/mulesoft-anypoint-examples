package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the two outbound SOAP requests of the loan broker, bound from the
 * {@code request.*} keys of {@code application.yml}.
 *
 * <p>Each component binds the key of the same name in kebab case:
 * {@code http-request-configuration-1} holds the credit-agency request of
 * {@code HTTP_Request_Configuration_1}
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:5], and
 * {@code http-request-configuration-2} holds the bank request of
 * {@code HTTP_Request_Configuration_2}
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:6]. The request
 * {@code path} and {@code responseTimeout} of each come from its {@code http:request}
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:73,108].
 *
 * <p>The application's configuration-properties scan registers this record as a bean.
 *
 * @param httpRequestConfiguration1 credit-agency request settings, bound from
 *                                  {@code request.http-request-configuration-1}
 * @param httpRequestConfiguration2 bank request settings, bound from
 *                                  {@code request.http-request-configuration-2}
 */
@ConfigurationProperties("request")
public record RequestProperties(
        HttpRequestConfiguration1 httpRequestConfiguration1,
        HttpRequestConfiguration2 httpRequestConfiguration2) {

    /**
     * Target of the credit-agency request, sent as an HTTP {@code POST} to
     * {@code http://<host>:<port><basePath><path>}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:5,73].
     *
     * @param host            host of the credit agency; {@code 0.0.0.0} in {@code application.yml}
     * @param port            port of the credit agency; {@code 18080} in {@code application.yml}
     * @param basePath        base path of the credit agency, bound from {@code base-path};
     *                        {@code /mule/TheCreditAgencyService} in {@code application.yml}
     * @param path            request path appended to {@code basePath}; {@code /*} in
     *                        {@code application.yml}
     * @param responseTimeout response timeout in milliseconds, bound from
     *                        {@code response-timeout}; {@code 10000} in {@code application.yml}
     */
    public record HttpRequestConfiguration1(
            String host,
            int port,
            String basePath,
            String path,
            int responseTimeout) {
    }

    /**
     * Settings of the bank request, sent as an HTTP {@code POST} to the host, port and path of the
     * bank URI passed with each call, followed by {@code path}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:6,108]. Host, port
     * and base path are read from that URI and have no keys here.
     *
     * @param path            request path appended to the bank URI's path; {@code /*} in
     *                        {@code application.yml}
     * @param responseTimeout response timeout in milliseconds, bound from
     *                        {@code response-timeout}; {@code 10000} in {@code application.yml}
     */
    public record HttpRequestConfiguration2(
            String path,
            int responseTimeout) {
    }
}
