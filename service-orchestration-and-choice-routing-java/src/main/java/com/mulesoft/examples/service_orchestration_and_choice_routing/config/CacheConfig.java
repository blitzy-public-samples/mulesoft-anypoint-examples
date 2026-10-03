package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import java.lang.reflect.Method;

import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.OrderItem;

/**
 * In-memory price cache keyed on the product id, replacing the {@code Caching_Strategy} object store
 * (fulfillment.xml:16-18).
 *
 * <p>Source: the {@code ee:object-store-caching-strategy} {@code Caching_Strategy} with
 * {@code keyGenerationExpression="#[payload.productId]"} and an {@code in-memory-store}
 * [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:16-18], used by the
 * {@code ee:cache} scope of {@code inhouseOrder} around the {@code Invoke Price Service} request
 * {@code GET api/prices/#[payload.productId]} on port 9999 and its {@code object-to-string-transformer}
 * [same file:104-107]. The scope declares no bypass condition, and the strategy configures no
 * expiry and no size bound.
 *
 * <ul>
 *   <li>{@link #cacheManager()} is the cache manager of the application: a
 *       {@link ConcurrentMapCacheManager} in static mode whose only cache is {@value #PRICES}, a
 *       per-JVM {@link java.util.concurrent.ConcurrentHashMap} with no expiry and no size bound.
 *       {@code getCache} returns {@code null} for every other name, and no further cache is created
 *       on demand. Values are stored by reference, and {@code null} values are allowed, as
 *       {@link ConcurrentMapCacheManager} sets by default.</li>
 *   <li>{@link #productIdKeyGenerator()} is the key generator {@value #PRODUCT_ID_KEY_GENERATOR}: the
 *       key is the product id of the first argument of the cached method, the value of
 *       {@code #[payload.productId]}. An {@link OrderItem} argument gives
 *       {@link OrderItem#getProductId()}; a {@link String} argument is the product id and is used
 *       unchanged.</li>
 * </ul>
 *
 * <p>{@code client.PriceClient} reads both names: its price lookup
 * {@code getPrice(String productId)} is annotated
 * {@code @Cacheable(cacheNames = CacheConfig.PRICES, keyGenerator = CacheConfig.PRODUCT_ID_KEY_GENERATOR)}.
 * A second lookup with a product id already in {@value #PRICES} returns the stored price and issues
 * no HTTP request. A lookup that throws stores nothing, as a failed {@code ee:cache} scope stores
 * nothing; the price request of this example answers 404 (D-067), so {@value #PRICES} stays empty in
 * the in-house order scenario. {@code @EnableCaching} is declared on the main class
 * {@code ServiceOrchestrationAndChoiceRoutingApplication}, not here.
 *
 * <pre>{@code
 * KeyGenerator keys = cacheConfig.productIdKeyGenerator();
 * OrderItem item = new OrderItem();
 * item.setProductId("AX02");
 * keys.generate(priceClient, getPrice, item);      // "AX02"
 * keys.generate(priceClient, getPrice, "AX02");    // "AX02"
 * keys.generate(priceClient, getPrice, 42);        // IllegalArgumentException naming PriceClient.getPrice
 * keys.generate(priceClient, getPrice);            // IllegalArgumentException naming PriceClient.getPrice
 *
 * CacheManager manager = cacheConfig.cacheManager();
 * manager.getCacheNames();                          // [prices]
 * manager.getCache("other");                        // null
 * }</pre>
 */
@Configuration(proxyBeanMethods = false)
public class CacheConfig {

    /** Name of the price cache, the one cache of the application. */
    public static final String PRICES = "prices";

    /** Bean name of the key generator that keys the price cache on the product id. */
    public static final String PRODUCT_ID_KEY_GENERATOR = "productIdKeyGenerator";

    /**
     * Returns the cache manager of the application: a {@link ConcurrentMapCacheManager} whose fixed
     * cache set is {@value #PRICES}, held in memory for the life of the JVM with no expiry, as the
     * {@code in-memory-store} of {@code Caching_Strategy}
     * [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:16-18].
     *
     * @return the cache manager bean {@code cacheManager}
     */
    @Bean
    public CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(PRICES);
    }

    /**
     * Returns the key generator {@value #PRODUCT_ID_KEY_GENERATOR}, the counterpart of
     * {@code keyGenerationExpression="#[payload.productId]"}
     * [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:16].
     *
     * <ul>
     *   <li>First argument an {@link OrderItem}: the key is {@link OrderItem#getProductId()}. A
     *       {@code null} product id gives a {@code null} key, which Spring's cache interceptor
     *       rejects with {@link IllegalArgumentException} before the cached method runs.</li>
     *   <li>First argument a {@link String}: the key is that string, unchanged.</li>
     *   <li>Any other first argument, a {@code null} first argument, or no argument: the generator
     *       throws {@link IllegalArgumentException} whose message names the cached method and the
     *       argument type it received; the cached method does not run.</li>
     * </ul>
     *
     * <p>Arguments after the first do not take part in the key.
     *
     * @return the key generator bean {@value #PRODUCT_ID_KEY_GENERATOR}
     */
    @Bean(PRODUCT_ID_KEY_GENERATOR)
    public KeyGenerator productIdKeyGenerator() {
        return CacheConfig::productIdKey;
    }

    /**
     * Derives the price cache key from the first argument of a cached method.
     *
     * @param target the bean whose method is cached
     * @param method the cached method
     * @param params the arguments of the call
     * @return the product id of an {@link OrderItem} first argument, or a {@link String} first
     *         argument unchanged
     * @throws IllegalArgumentException when the first argument is missing, {@code null}, or neither
     *         an {@link OrderItem} nor a {@link String}
     */
    private static Object productIdKey(Object target, Method method, Object... params) {
        if (params != null && params.length > 0) {
            Object first = params[0];
            if (first instanceof OrderItem item) {
                return item.getProductId();
            }
            if (first instanceof String productId) {
                return productId;
            }
        }
        throw new IllegalArgumentException(PRODUCT_ID_KEY_GENERATOR + " needs an "
                + OrderItem.class.getSimpleName() + " or a String product id as the first argument of "
                + method.getDeclaringClass().getSimpleName() + "." + method.getName() + ", but received "
                + describeFirstArgument(params));
    }

    /**
     * Describes the first argument of a call for the message of a rejected key.
     *
     * @param params the arguments of the call
     * @return {@code "no argument"}, {@code "null"}, or the fully qualified class name of the first
     *         argument
     */
    private static String describeFirstArgument(Object[] params) {
        if (params == null || params.length == 0) {
            return "no argument";
        }
        Object first = params[0];
        return first == null ? "null" : first.getClass().getName();
    }
}
