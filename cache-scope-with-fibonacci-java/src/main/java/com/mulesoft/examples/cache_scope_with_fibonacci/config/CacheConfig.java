package com.mulesoft.examples.cache_scope_with_fibonacci.config;

import com.mulesoft.examples.cache_scope_with_fibonacci.mapper.FibonacciResponseMapper;
import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciResult;
import java.util.List;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cache store for Fibonacci results: one in-memory cache {@value #FIBONACCI_CACHE}, keyed on the raw {@code n}
 * text; a cache hit returns the stored result with cost 0 (D-055).
 *
 * <p>Source: the {@code spring:bean} {@code responseGenerator}
 * [cache-scope-with-fibonacci/src/main/app/cache-scope.xml:32] and the {@code ee:object-store-caching-strategy}
 * {@code Caching_Strategy} [cache-scope-with-fibonacci/src/main/app/cache-scope.xml:35], which names no object
 * store.
 *
 * <ul>
 *   <li>{@link #cacheManager(FibonacciResponseMapper)} is the only {@link CacheManager} of the application. It holds
 *       the single cache {@value #FIBONACCI_CACHE}, a per-JVM {@link java.util.concurrent.ConcurrentHashMap} with no
 *       expiry and no size bound; {@code getCache} returns {@code null} for every other name.</li>
 *   <li>{@link #fibonacciKeyGenerator()} uses the first argument of the cached method, unchanged, as the key: the
 *       {@code n} text of {@code FibonacciService.calculateFibonacci}.</li>
 *   <li>Every read from the cache passes a stored {@link FibonacciResult} through
 *       {@link FibonacciResponseMapper#toCachedResponse(FibonacciResult)}: the caller receives the stored value with
 *       cost 0, and the stored entry keeps the cost of its calculation
 *       [cache-scope-with-fibonacci/src/main/java/com/mulesoft/mule/cache/FibonacciResponseGenerator.java:26].</li>
 * </ul>
 *
 * <p>The {@code nocache} bypass of the cache scope [cache-scope-with-fibonacci/src/main/app/cache-scope.xml:63] is
 * the {@code condition} of the {@code @Cacheable} method that uses this cache; this class does not evaluate it.
 *
 * <pre>{@code
 * Cache cache = cacheManager.getCache(CacheConfig.FIBONACCI_CACHE);
 * cache.put("10", new FibonacciResult("55", 11));
 * cache.get("10").get();             // FibonacciResult[value=55, cost=0]
 * cache.getNativeCache();            // {10=FibonacciResult[value=55, cost=11]}
 * cacheManager.getCache("other");    // null
 * }</pre>
 */
@Configuration
public class CacheConfig {

    /** Name of the one cache of the application. */
    public static final String FIBONACCI_CACHE = "fibonacci";

    /**
     * Returns the cache manager of the application: a {@link ConcurrentMapCacheManager} in static mode whose only
     * cache, {@value #FIBONACCI_CACHE}, is a {@code FibonacciCache} that reads stored results through
     * {@code mapper} (D-055).
     *
     * @param mapper the mapper applied to every {@link FibonacciResult} read from the cache
     * @return the cache manager bean {@code cacheManager}
     */
    @Bean
    public CacheManager cacheManager(FibonacciResponseMapper mapper) {
        FibonacciCacheManager manager = new FibonacciCacheManager(mapper);
        manager.setCacheNames(List.of(FIBONACCI_CACHE));
        return manager;
    }

    /**
     * Returns the key generator {@code fibonacciKeyGenerator}: the key is the first argument of the cached method,
     * unchanged. For {@code FibonacciService.calculateFibonacci} it is the raw {@code n} text, the value of the
     * original {@code keyGenerationExpression} [cache-scope-with-fibonacci/src/main/app/cache-scope.xml:35]
     * (D-055).
     *
     * @return the key generator bean {@code fibonacciKeyGenerator}
     */
    @Bean("fibonacciKeyGenerator")
    public KeyGenerator fibonacciKeyGenerator() {
        return (target, method, params) -> params[0];
    }

    /**
     * {@link ConcurrentMapCacheManager} whose caches are {@link FibonacciCache} instances that read through one
     * {@link FibonacciResponseMapper}.
     */
    private static final class FibonacciCacheManager extends ConcurrentMapCacheManager {

        private final FibonacciResponseMapper mapper;

        /**
         * Creates a manager with no cache names; {@link CacheConfig#cacheManager(FibonacciResponseMapper)} sets them
         * after the mapper is assigned.
         *
         * @param mapper the mapper handed to every cache this manager creates
         */
        FibonacciCacheManager(FibonacciResponseMapper mapper) {
            super();
            this.mapper = mapper;
        }

        /**
         * Returns a {@link FibonacciCache} with the given name that reads through the mapper of this manager.
         *
         * @param name the cache name
         * @return the new cache
         */
        @Override
        protected Cache createConcurrentMapCache(String name) {
            return new FibonacciCache(name, mapper);
        }
    }

    /**
     * {@link ConcurrentMapCache} that stores values by reference and allows {@code null} values. Every
     * {@link FibonacciResult} it returns is the stored result mapped through
     * {@link FibonacciResponseMapper#toCachedResponse(FibonacciResult)}, with cost 0 (D-055).
     */
    private static final class FibonacciCache extends ConcurrentMapCache {

        private final FibonacciResponseMapper mapper;

        /**
         * Creates an empty cache with the given name over a new {@link java.util.concurrent.ConcurrentHashMap}.
         *
         * @param name the cache name
         * @param mapper the mapper applied to every {@link FibonacciResult} read from this cache
         */
        FibonacciCache(String name, FibonacciResponseMapper mapper) {
            super(name);
            this.mapper = mapper;
        }

        /**
         * Returns the value read from the store, with a {@link FibonacciResult} replaced by
         * {@code mapper.toCachedResponse(result)}; the stored entry is not modified. Every other value, {@code null}
         * included, is returned as {@link ConcurrentMapCache} returns it.
         *
         * @param storeValue the value held in the store
         * @return the value handed to the caller of the cache
         */
        @Override
        protected Object fromStoreValue(Object storeValue) {
            Object value = super.fromStoreValue(storeValue);
            if (value instanceof FibonacciResult result) {
                return mapper.toCachedResponse(result);
            }
            return value;
        }
    }
}
