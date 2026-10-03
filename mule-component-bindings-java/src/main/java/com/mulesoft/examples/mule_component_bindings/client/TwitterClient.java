/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import java.util.List;
import java.util.Objects;
import java.util.Properties;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.mule_component_bindings.config.TwitterProperties;

import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.v1.Query;
import twitter4j.v1.QueryResult;
import twitter4j.v1.Status;

/**
 * Searches Twitter through the REST API v1.1 with the credentials and base URL of
 * {@link TwitterProperties}: the {@code twitter:config} element {@code Twitter}
 * [mule-component-bindings/src/main/app/mule-component-bindings.xml:10] and its two
 * {@code twitter:search} operations
 * [mule-component-bindings/src/main/app/mule-component-bindings.xml:34,59], on twitter4j 4.1.2
 * (D-033, D-674).
 *
 * <p>Configuration read from {@link TwitterProperties}:
 * <ul>
 *   <li>{@code twitter.apiKey} and {@code twitter.apiSecret}: the OAuth 1.0a consumer key and
 *       secret;</li>
 *   <li>{@code twitter.accessKey} and {@code twitter.accessSecret}: the OAuth 1.0a access token and
 *       token secret;</li>
 *   <li>{@code twitter.rest-base-url}: the REST base URL, ending in {@code /}, given to twitter4j as
 *       its {@code restBaseURL} property (D-106). When the key is unset, twitter4j's own default
 *       REST API v1.1 base URL applies.</li>
 * </ul>
 *
 * <p>The twitter4j {@link Twitter} instance is built at the first {@link #search} call, never at
 * construction, and is reused for every later call (D-674). The build is thread-safe: concurrent
 * first calls create exactly one instance. The class sets no twitter4j HTTP setting, leaving
 * twitter4j's defaults: no retry, a 20 s connection timeout and a 120 s read timeout. Every search
 * is one {@code GET <restBaseURL>search/tweets.json} request.
 *
 * <p>This class catches, wraps and logs nothing: a {@link TwitterException} or a runtime exception
 * of a search reaches the caller unchanged (D-674).
 *
 * <p>Example, with the {@code mule-component-bindingsFlow1} search values:
 * <pre>{@code
 * List<Status> statuses = twitterClient.search("AAPL", "2014-10-26", "2014-11-04");
 * // GET <twitter.rest-base-url>search/tweets.json?q=AAPL&since=2014-10-26&until=2014-11-04&...
 * // statuses: the "statuses" array of the response, as twitter4j.v1.Status objects
 * }</pre>
 */
@Component
public class TwitterClient {

    /** The twitter4j configuration property that holds the REST base URL. */
    private static final String REST_BASE_URL_PROPERTY = "restBaseURL";

    /** The {@code twitter.*} configuration. */
    private final TwitterProperties properties;

    /** Guards the one-time build of {@link #twitter}. */
    private final Object buildLock = new Object();

    /** The twitter4j instance; {@code null} until the first search. */
    private volatile Twitter twitter;

    /**
     * Stores the Twitter configuration. No twitter4j object is built and no request is sent.
     *
     * @param properties the {@code twitter.*} configuration; read at the first search
     */
    public TwitterClient(TwitterProperties properties) {
        this.properties = properties;
    }

    /**
     * Runs one Twitter search and returns the statuses of the response, in the order received.
     *
     * <p>The request carries {@code q} = {@code query}, plus {@code since} and {@code until} when
     * they are not {@code null}, twitter4j's implicit parameters {@code with_twitter_user_id=true},
     * {@code include_entities=true}, {@code include_ext_alt_text=true} and
     * {@code tweet_mode=extended} (D-674), and an OAuth 1.0a {@code Authorization} header signed with
     * the configured consumer and access credentials. Only the first page of results is requested.
     *
     * @param query the search query, for example {@code AAPL} or {@code $AAPL}; must not be
     *              {@code null}
     * @param since the first day of the search, {@code yyyy-MM-dd}, or {@code null} to send none
     * @param until the day before which tweets are returned, {@code yyyy-MM-dd}, or {@code null} to
     *              send none
     * @return the statuses of the search response, as twitter4j returns them
     * @throws TwitterException     if the request fails or Twitter answers with an error status
     * @throws NullPointerException if {@code query} is {@code null}; no request is sent
     */
    public List<Status> search(String query, String since, String until) throws TwitterException {
        Query q = Query.of(Objects.requireNonNull(query, "query"));
        // Query is immutable: since(...) and until(...) return a new Query
        if (since != null) {
            q = q.since(since);
        }
        if (until != null) {
            q = q.until(until);
        }
        QueryResult result = twitter().v1().search().search(q);
        return result.getTweets();
    }

    /**
     * Returns the twitter4j instance, building it at the first call with double-checked locking
     * on {@link #twitter}.
     *
     * <p>The build loads {@code restBaseURL} = {@code twitter.rest-base-url} when that key is set,
     * then sets the consumer key and secret from {@code twitter.apiKey} and {@code twitter.apiSecret}
     * and the access token and secret from {@code twitter.accessKey} and
     * {@code twitter.accessSecret} (D-674).
     *
     * @return the single twitter4j instance of this client
     */
    private Twitter twitter() {
        Twitter instance = twitter;
        if (instance == null) {
            synchronized (buildLock) {
                instance = twitter;
                if (instance == null) {
                    Properties props = new Properties();
                    if (properties.restBaseUrl() != null) {
                        props.setProperty(REST_BASE_URL_PROPERTY, properties.restBaseUrl());
                    }
                    instance = Twitter.newBuilder()
                            .load(props)
                            .oAuthConsumer(properties.apiKey(), properties.apiSecret())
                            .oAuthAccessToken(properties.accessKey(), properties.accessSecret())
                            .build();
                    twitter = instance;
                }
            }
        }
        return instance;
    }
}
