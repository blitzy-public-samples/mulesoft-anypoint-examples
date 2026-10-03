/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.client;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.mulesoft.examples.mule_component_bindings.config.StockStatsProperties;
import com.mulesoft.examples.mule_component_bindings.model.BulkClassifyRequest;
import com.mulesoft.examples.mule_component_bindings.model.ClassifyRequest;
import com.mulesoft.examples.mule_component_bindings.model.Sentiment;
import com.mulesoft.examples.mule_component_bindings.model.Tweet;

/**
 * A SentimentService implementation that delegates to the Sentiment140 cloud API.
 *
 * <p>Port of {@code stockstats.impl.sentiment140.Sentiment140SentimentService}
 * [mule-component-bindings/src/main/java/stockstats/impl/sentiment140/Sentiment140SentimentService.java:34],
 * the bean that {@code stackStatsResource} constructs with {@code ${stockstats.sentiment140ApiKey}}
 * [mule-component-bindings/src/main/app/mule-component-bindings.xml:23-27]. A Spring {@link RestClient}
 * replaces the original's JAX-RS client (D-033).
 *
 * <p>Configuration read once from {@link StockStatsProperties}, in the constructor:
 * <ul>
 *   <li>{@code stockstats.sentiment140ApiKey}: the Sentiment140 app id, sent as the {@code appid}
 *       request header (D-012);</li>
 *   <li>{@code stockstats.sentiment140-base-url}: the API base URL, ending in {@code /}, to which
 *       {@code bulkClassifyJson} is appended. Its default in {@code application.yml} is the original
 *       {@code http://www.sentiment140.com/api/} (D-106).</li>
 * </ul>
 *
 * <p>Each {@link #classify} call sends exactly one request:
 * <pre>
 * POST &lt;stockstats.sentiment140-base-url&gt;bulkClassifyJson
 * Accept: application/json
 * appid: &lt;stockstats.sentiment140ApiKey&gt;
 * Content-Type: text/plain
 *
 * {"data":[{"id":"&lt;tweet id&gt;","text":"&lt;tweet text&gt;"}, ...]}
 * </pre>
 * The body is the Jackson JSON of a {@link BulkClassifyRequest}, encoded as UTF-8, with one entry per
 * tweet in list order. The body of a 200 response is read as UTF-8 text; the body of any other
 * response is not read (D-693).
 *
 * <p>The client sets no timeout and no retry, and logs nothing. Its failures reach the caller as
 * runtime exceptions:
 * <ul>
 *   <li>a connection or I/O failure: the {@code RestClientException} of {@link RestClient},
 *       unchanged;</li>
 *   <li>a response status other than 200: a {@code RuntimeException} with the message
 *       {@code Sentiment140 returned status <status>};</li>
 *   <li>a response body that is not JSON, or a result id that matches no given tweet: a
 *       {@code RuntimeException} whose cause is the Jackson exception or the
 *       {@code NullPointerException}.</li>
 * </ul>
 *
 * <p>Example, with {@code stockstats.sentiment140-base-url=http://localhost:9000/}:
 * <pre>{@code
 * // tweet.getId() is "1" and tweet.getText() is "$AAPL up"
 * sentiment140Client.classify(List.of(tweet));
 * // request:  POST http://localhost:9000/bulkClassifyJson  {"data":[{"id":"1","text":"$AAPL up"}]}
 * // response: 200 {"data":[{"id":"1","polarity":4}]}
 * // result:   tweet.getSentiment() is Sentiment.POSITIVE
 * }</pre>
 */
@Component
public class Sentiment140Client implements SentimentService {

    /** The HTTP client for the Sentiment140 API. */
    private final RestClient restClient;

    /** The Sentiment140 app id, sent as the {@code appid} header. */
    private final String appId;

    /** The Sentiment140 API base URL, to which {@code bulkClassifyJson} is appended. */
    private final String baseUrl;

    /** Writes the request body and reads the response body. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Builds the {@link RestClient} and reads the Sentiment140 app id and base URL. No request is sent.
     *
     * <p>No timeout, retry, interceptor or base URL is set on the builder.
     *
     * @param restClientBuilder the {@link RestClient.Builder} of the application context
     * @param properties        the {@code stockstats.*} configuration; only
     *                          {@link StockStatsProperties#sentiment140ApiKey()} and
     *                          {@link StockStatsProperties#sentiment140BaseUrl()} are read
     */
    public Sentiment140Client(RestClient.Builder restClientBuilder, StockStatsProperties properties) {
        this.restClient = restClientBuilder.build();
        this.appId = properties.sentiment140ApiKey();
        this.baseUrl = properties.sentiment140BaseUrl();
    }

    /**
     * Sends the tweets to the Sentiment140 bulk classification API and sets each tweet's sentiment from
     * the returned polarity: 0 negative, 2 neutral, any other value positive.
     *
     * <p>The request lists every given tweet once, in list order, as {@code {"id":..., "text":...}};
     * the list is not sorted, deduplicated or filtered, and an empty list sends {@code {"data":[]}}.
     * Each element of the response's {@code data} array is read as its {@code id} text and its
     * {@code polarity} integer; a missing or non-numeric polarity reads as 0. The sentiment is set on
     * the tweet with that id; when two given tweets share an id, it is set on the later of them. A
     * tweet that no result names keeps its sentiment, and a response without {@code data} changes no
     * tweet.
     *
     * @param tweets the tweets to classify; their sentiments are set in place
     * @throws org.springframework.web.client.RestClientException if the request cannot be sent or the
     *                                                            response cannot be read
     * @throws RuntimeException     if the response status is not 200, with the message
     *                              {@code Sentiment140 returned status <status>}; if the response body
     *                              is not JSON, with the Jackson exception as its cause; or if a
     *                              result id matches no given tweet, with a
     *                              {@code NullPointerException} as its cause
     * @throws NullPointerException if {@code tweets} is {@code null} or holds a {@code null} element;
     *                              no request is sent
     */
    @Override
    public void classify(List<Tweet> tweets) {
        BulkClassifyRequest bulkRequest = new BulkClassifyRequest();
        Map<String, Tweet> idToTweet = new HashMap<>();
        List<ClassifyRequest> data = new ArrayList<>();
        for (Tweet tweet : tweets) {
            ClassifyRequest request = new ClassifyRequest();
            request.setId(tweet.getId());
            request.setText(tweet.getText());
            data.add(request);
            idToTweet.put(tweet.getId(), tweet);
        }
        bulkRequest.setData(data);

        byte[] json;
        try {
            json = objectMapper.writeValueAsBytes(bulkRequest);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }

        // exchange(...) returns every status to this method; RestClientException propagates.
        // The body is read for status 200 only; any other status yields an empty body (D-693).
        RawResponse raw = restClient.post()
                .uri(baseUrl + "bulkClassifyJson")
                .accept(MediaType.APPLICATION_JSON)
                .header("appid", appId)
                .contentType(MediaType.TEXT_PLAIN)
                .body(json)
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    String body = (status == 200)
                            ? StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)
                            : "";
                    return new RawResponse(status, body);
                });

        if (raw.status() != 200) {
            throw new RuntimeException("Sentiment140 returned status " + raw.status());
        }

        try {
            JsonNode root = objectMapper.readTree(raw.body());
            for (JsonNode resultNode : root.path("data")) {
                String id = resultNode.path("id").textValue();
                int polarity = resultNode.path("polarity").intValue();

                Sentiment sentiment;
                if (polarity == 0) {
                    sentiment = Sentiment.NEGATIVE;
                } else if (polarity == 2) {
                    sentiment = Sentiment.NEUTRAL;
                } else {
                    sentiment = Sentiment.POSITIVE;
                }

                Tweet tweet = idToTweet.get(id);
                tweet.setSentiment(sentiment);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The status code and the UTF-8 decoded body of a Sentiment140 response.
     *
     * @param status the HTTP status code
     * @param body   the response body when {@code status} is 200, empty when that response has none;
     *               empty for any other status
     */
    private record RawResponse(int status, String body) {
    }
}
