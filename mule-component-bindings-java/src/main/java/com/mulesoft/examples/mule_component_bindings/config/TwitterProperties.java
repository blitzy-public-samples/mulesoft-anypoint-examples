package com.mulesoft.examples.mule_component_bindings.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code twitter.*} keys by constructor binding: the Twitter credentials and REST base
 * URL of the {@code twitter:config} element {@code Twitter}
 * [mule-component-bindings/src/main/app/mule-component-bindings.xml:10], and the search query and
 * date range of the {@code twitter:search} in {@code mule-component-bindingsFlow1}
 * [mule-component-bindings/src/main/app/mule-component-bindings.xml:34]. The Twitter client built
 * from these values is twitter4j 4.1.2 (D-033).
 *
 * <p>Keys and components, with the environment-variable form that Spring relaxed binding also
 * accepts:
 * <ul>
 *   <li>{@code twitter.accessKey} ({@code TWITTER_ACCESSKEY}) to {@link #accessKey()};</li>
 *   <li>{@code twitter.accessSecret} ({@code TWITTER_ACCESSSECRET}) to
 *       {@link #accessSecret()};</li>
 *   <li>{@code twitter.apiKey} ({@code TWITTER_APIKEY}) to {@link #apiKey()};</li>
 *   <li>{@code twitter.apiSecret} ({@code TWITTER_APISECRET}) to {@link #apiSecret()};</li>
 *   <li>{@code twitter.rest-base-url} ({@code TWITTER_RESTBASEURL}) to {@link #restBaseUrl()};</li>
 *   <li>{@code twitter.search.query}, {@code twitter.search.since} and {@code twitter.search.until}
 *       ({@code TWITTER_SEARCH_QUERY}, {@code TWITTER_SEARCH_SINCE},
 *       {@code TWITTER_SEARCH_UNTIL}) to the components of {@link #search()}.</li>
 * </ul>
 *
 * <p>The record declares no default values: every value comes from the bound keys. A key that is
 * absent binds {@code null}, and {@link #search()} is {@code null} when no {@code twitter.search.*}
 * key is set. The committed {@code application.yml} holds placeholder values for the four
 * credentials; their real values are supplied outside the committed files, in the git-ignored
 * {@code application-local.yml} read under the {@code local} profile, or through the environment
 * (D-012). The base URL and the search keys default in {@code application.yml} to
 * {@code https://api.twitter.com/1.1/} and to the original search literals (D-106).
 *
 * <p>The record is registered by {@code @ConfigurationPropertiesScan} on
 * {@code MuleComponentBindingsApplication} (D-322). Its accessors, {@code equals}, {@code hashCode}
 * and {@code toString} are the generated record members; {@code toString} renders every component,
 * the four credentials included.
 *
 * <p>Example, with the committed defaults:
 * <pre>{@code
 * twitterProperties.restBaseUrl();      // https://api.twitter.com/1.1/
 * twitterProperties.search().query();   // AAPL
 * twitterProperties.search().since();   // 2014-10-26
 * twitterProperties.search().until();   // 2014-11-04
 * }</pre>
 *
 * @param accessKey    {@code twitter.accessKey}: the OAuth access token, the element's
 *                     {@code accessKey} attribute
 * @param accessSecret {@code twitter.accessSecret}: the OAuth access token secret, the element's
 *                     {@code accessSecret} attribute
 * @param apiKey       {@code twitter.apiKey}: the OAuth consumer key, the element's
 *                     {@code consumerKey} attribute
 * @param apiSecret    {@code twitter.apiSecret}: the OAuth consumer secret, the element's
 *                     {@code consumerSecret} attribute
 * @param restBaseUrl  {@code twitter.rest-base-url}: the base URL of the Twitter REST API v1.1
 *                     requests, ending in {@code /}
 * @param search       {@code twitter.search.*}: the query and date range of the search that a
 *                     request to {@code /} on the first listener runs
 */
@ConfigurationProperties("twitter")
public record TwitterProperties(
        String accessKey,
        String accessSecret,
        String apiKey,
        String apiSecret,
        String restBaseUrl,
        Search search) {

    /**
     * Binds the {@code twitter.search.*} keys: the query and the date range of the
     * {@code twitter:search} in {@code mule-component-bindingsFlow1}
     * [mule-component-bindings/src/main/app/mule-component-bindings.xml:34].
     *
     * <p>The dates are held as the configured text, for example {@code 2014-10-26}; the record
     * neither parses nor validates them. Its accessors, {@code equals}, {@code hashCode} and
     * {@code toString} are the generated record members.
     *
     * @param query {@code twitter.search.query}: the search query, the element's {@code query}
     *              attribute
     * @param since {@code twitter.search.since}: the first day of the search, {@code yyyy-MM-dd},
     *              the element's {@code since} attribute
     * @param until {@code twitter.search.until}: the day before which tweets are returned,
     *              {@code yyyy-MM-dd}, the element's {@code until} attribute
     */
    public record Search(String query, String since, String until) {
    }
}
