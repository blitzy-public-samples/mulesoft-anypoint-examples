package com.mulesoft.examples.mule_component_bindings.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code stockstats.*} keys: the Stocklytics and Sentiment140 API keys and base URLs used by
 * the two stock-stats clients, {@code client.StocklyticsClient} and {@code client.Sentiment140Client}.
 *
 * <p>Source: the singleton bean {@code stackStatsResource}
 * [mule-component-bindings/src/main/app/mule-component-bindings.xml:17-29], whose
 * {@code StocklyticsStockService} constructor argument is {@code ${stockstats.stocklyticsApiKey}}
 * [:20] and whose {@code Sentiment140SentimentService} constructor argument is
 * {@code ${stockstats.sentiment140ApiKey}} [:25]. The two base URLs replace the {@code BASE_URL}
 * constants of those classes
 * [mule-component-bindings/src/main/java/stockstats/impl/stocklytics/StocklyticsStockService.java:36;
 * mule-component-bindings/src/main/java/stockstats/impl/sentiment140/Sentiment140SentimentService.java:38];
 * their defaults in {@code application.yml} are the original values (D-106).
 *
 * <p>Binding:
 * <ul>
 *   <li>The record is bound through its canonical constructor under the prefix {@code stockstats} and
 *       registered by the {@code @ConfigurationPropertiesScan} of
 *       {@code MuleComponentBindingsApplication}; it is not a component (D-322).</li>
 *   <li>Relaxed binding maps {@code stockstats.stocklyticsApiKey},
 *       {@code stockstats.sentiment140ApiKey}, {@code stockstats.stocklytics-base-url} and
 *       {@code stockstats.sentiment140-base-url} to the components of the same name; the environment
 *       variable {@code STOCKSTATS_STOCKLYTICSAPIKEY} sets the first of them.</li>
 *   <li>The record declares no default value and validates nothing (D-528). A key that is absent
 *       binds {@code null}; the committed {@code TODO} placeholder binds as the text
 *       {@code TODO}.</li>
 *   <li>The API key values are supplied outside committed files, for example as command-line
 *       arguments or environment variables; tests take theirs from {@code application-test.yml}
 *       (D-012).</li>
 * </ul>
 *
 * <p>Example: {@code --stockstats.stocklytics-base-url=<base>} makes {@link #stocklyticsBaseUrl()}
 * return {@code <base>} unchanged, and the Stocklytics client then requests
 * {@code <base>historicalPrices/1.0}.
 *
 * <p>{@code equals}, {@code hashCode} and {@code toString} are the record's generated members;
 * {@code toString} prints every component as bound, the two API keys included (D-528).
 *
 * @param stocklyticsApiKey  {@code stockstats.stocklyticsApiKey}: the Stocklytics API key, sent as the
 *                           {@code api_key} request header
 * @param sentiment140ApiKey {@code stockstats.sentiment140ApiKey}: the Sentiment140 app id (the
 *                           registration email), sent as the {@code appid} request header
 * @param stocklyticsBaseUrl {@code stockstats.stocklytics-base-url}: the Stocklytics API base URL, to
 *                           which the client appends {@code historicalPrices/1.0}
 * @param sentiment140BaseUrl {@code stockstats.sentiment140-base-url}: the Sentiment140 API base URL, to
 *                           which the client appends {@code bulkClassifyJson}
 */
@ConfigurationProperties("stockstats")
public record StockStatsProperties(
        String stocklyticsApiKey,
        String sentiment140ApiKey,
        String stocklyticsBaseUrl,
        String sentiment140BaseUrl) {
}
