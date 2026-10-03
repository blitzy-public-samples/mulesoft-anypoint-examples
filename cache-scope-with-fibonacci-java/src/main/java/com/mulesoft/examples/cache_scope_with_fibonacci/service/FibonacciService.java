package com.mulesoft.examples.cache_scope_with_fibonacci.service;

import com.mulesoft.examples.cache_scope_with_fibonacci.config.CacheConfig;
import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciReply;
import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciResult;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Implements the two flows of {@code cache-scope-with-fibonacci/src/main/app/cache-scope.xml}: the HTTP-triggered
 * flow {@code cache-exampleFlow1} [cache-scope.xml:43-59], the VM request-response flow {@code calculateFibonacci}
 * with its cache scope [cache-scope.xml:61-82], and the MEL global function {@code fibonacciRequest}
 * [cache-scope.xml:7-27] (SC-10, D-055).
 *
 * <ul>
 *   <li>{@link #cacheExampleFlow1(String, String, boolean, boolean)} is the entry method that
 *       {@code FibonacciController.cacheExampleFlow1} calls with the bound request: the favicon filter, the
 *       {@code n < 20} choice and the two reply texts (D-680, D-681, D-684).</li>
 *   <li>{@link #calculateFibonacci(String, boolean, boolean)} is the cache scope and its calculation: a
 *       {@code @Cacheable} method over the cache {@value CacheConfig#FIBONACCI_CACHE}, keyed on the raw {@code n}
 *       text by the bean {@code fibonacciKeyGenerator}, with no lookup and no store when {@code bypass} is
 *       {@code true} (D-055, D-685). A cache hit returns the stored value with cost 0 and runs no part of the
 *       method body (D-055, D-682).</li>
 *   <li>{@link #fibonacciRequest(int, boolean)} is the recursive VM request of the calculation (D-685).</li>
 * </ul>
 *
 * <p>Every call of {@code calculateFibonacci}, the top-level call and each recursive call, is made on {@code self},
 * the lazy-resolution proxy injected into the constructor. It resolves to the caching proxy of this bean on its
 * first method call, and the cache applies to every call (D-055, D-686).
 *
 * <p>The class holds no mutable state. The {@value CacheConfig#FIBONACCI_CACHE} cache is the only state shared
 * between requests and it lives for the life of the JVM (D-055). Two concurrent misses for one key both run the
 * calculation and both store their result (D-686).
 *
 * <p>Replies on a cold cache, in this order:
 *
 * <pre>{@code
 * cacheExampleFlow1("", "10", false, false);           // FibonacciReply[body=Fibonacci(10) = 55\nCOST: 11, cost=11]
 * cacheExampleFlow1("", "5", false, false);            // FibonacciReply[body=Fibonacci(5) = 5\nCOST: 0, cost=0]
 * cacheExampleFlow1("", "10", true, false);            // FibonacciReply[body=Fibonacci(10) = 55\nCOST: 1, cost=1]
 * cacheExampleFlow1("", "20", false, false);           // FibonacciReply[body=ERROR: n must be less than 20, cost=null]
 * cacheExampleFlow1("/favicon.ico", "10", false, false); // FibonacciReply[body=, cost=null]
 * }</pre>
 */
@Service
public class FibonacciService {

    /** Writes the INFO line of the logger {@code Log the input} [cache-scope.xml:64] (D-682). */
    private static final Logger log = LoggerFactory.getLogger(FibonacciService.class);

    /** Lazy-resolution proxy of this bean; every call of {@link #calculateFibonacci} is made on it (D-686). */
    private final FibonacciService self;

    /**
     * Creates the service with a lazy-resolution proxy of itself. The proxy resolves the {@code FibonacciService}
     * bean, the caching proxy, on its first method call (D-055, D-686).
     *
     * @param self the lazy-resolution proxy of the {@code FibonacciService} bean
     */
    public FibonacciService(@Lazy FibonacciService self) {
        this.self = self;
    }

    /**
     * Implements flow {@code cache-exampleFlow1} [cache-scope.xml:43-59] and returns its reply. The steps run in the
     * order of the flow:
     *
     * <ol>
     *   <li>The favicon filter [cache-scope.xml:45-49] compares {@code body} with {@code /favicon.ico}, exactly and
     *       case-sensitively. A match returns the body {@code ""} with no cost, and nothing else runs (D-681).</li>
     *   <li>The choice [cache-scope.xml:50-58] compares {@code n} numerically with 20 [cache-scope.xml:51]. An absent
     *       {@code n} takes the {@code otherwise} branch. A non-numeric or empty {@code n} throws
     *       {@link NumberFormatException} (D-684).</li>
     *   <li>For {@code n < 20} the flow reference [cache-scope.xml:52] calls
     *       {@link #calculateFibonacci(String, boolean, boolean)} through {@code self} with {@code n},
     *       {@code nocacheHeader} as {@code bypass} and {@code nocacheQuery} (D-685). The reply
     *       [cache-scope.xml:53] is {@code Fibonacci(<n>) = <value>\nCOST: <cost>}, with the raw {@code n} text and a
     *       single LF, and its cost is the same {@code <cost>} (D-680).</li>
     *   <li>Otherwise [cache-scope.xml:55-57] the reply is {@code ERROR: n must be less than 20} with no cost
     *       (D-680, D-684).</li>
     * </ol>
     *
     * @param body          the request body as text, the payload of the flow; {@code null} passes the filter
     * @param n             the {@code n} query parameter, or {@code null} when the request has none
     * @param nocacheHeader {@code true} when the request carries a {@code nocache} header, whatever its value
     * @param nocacheQuery  {@code true} when the request carries a {@code nocache} query parameter with a value,
     *                      {@code nocache=} included and a bare {@code nocache} excluded
     * @return the reply text and the value of the {@code cost} response header; the cost is {@code null} for the
     *         favicon and {@code otherwise} replies, which carry no {@code cost} header (D-680)
     * @throws NumberFormatException if {@code n} is present and is not a decimal number (D-684)
     * @throws ArithmeticException   if {@code n} is at least 2, less than 20 and has a non-zero fractional part
     *                               (D-684)
     */
    public FibonacciReply cacheExampleFlow1(String body, String n, boolean nocacheHeader, boolean nocacheQuery) {
        // message-filter "Filter favicon" (:45-49): not-filter over wildcard-filter "/favicon.ico", case-sensitive.
        if ("/favicon.ico".equals(body)) {
            return new FibonacciReply("", null);
        }

        // choice "Choice" (:50-58): when #[message.inboundProperties['http.query.params'].n < 20] (:51).
        if (n != null && new BigDecimal(n).compareTo(BigDecimal.valueOf(20)) < 0) {
            // flow-ref "Call calculateFibonacci flow" (:52).
            FibonacciResult result = self.calculateFibonacci(n, nocacheHeader, nocacheQuery);
            // expression-component "Set the calculation output" (:53).
            return new FibonacciReply(
                    "Fibonacci(" + n + ") = " + result.value() + "\nCOST: " + result.cost(), result.cost());
        }

        // otherwise (:55-57): expression-component "Input is too high" (:56).
        return new FibonacciReply("ERROR: n must be less than 20", null);
    }

    /**
     * Implements flow {@code calculateFibonacci} [cache-scope.xml:61-82]: the cache scope [cache-scope.xml:63] and
     * the calculation inside it [cache-scope.xml:64-80].
     *
     * <p>Cache scope (D-055, D-685): when {@code bypass} is {@code false} the result is looked up in the cache
     * {@value CacheConfig#FIBONACCI_CACHE} under the key {@code n}, the raw text, by the bean
     * {@code fibonacciKeyGenerator} [cache-scope.xml:35]. A hit returns the stored value with cost 0 and runs nothing
     * below; a miss runs the body and stores its result. When {@code bypass} is {@code true}, the inbound
     * {@code nocache} property of the scope's filter expression [cache-scope.xml:63], the body runs with no lookup
     * and no store.
     *
     * <p>Body, run once per miss or bypass:
     *
     * <ol>
     *   <li>One INFO line {@code aaa: null} is logged [cache-scope.xml:64] (D-682).</li>
     *   <li>{@code n} is read as a decimal number. Below 2 [cache-scope.xml:66-70] the result is the raw {@code n}
     *       text with cost 1, negative and fractional values included (D-684).</li>
     *   <li>Otherwise [cache-scope.xml:71-78] the integer value {@code k} of {@code n} is computed, then
     *       {@link #fibonacciRequest(int, boolean)} runs for {@code k - 1} and after it for {@code k - 2}, both with
     *       {@code cached} equal to {@code !nocacheQuery} [cache-scope.xml:72]. The result is the decimal text of the
     *       {@code long} sum of the two values with cost {@code cost(k - 1) + cost(k - 2) + 1}
     *       [cache-scope.xml:76-77] (SC-10).</li>
     * </ol>
     *
     * <pre>{@code
     * calculateFibonacci("10", false, false); // cold cache: FibonacciResult[value=55, cost=11], 11 "aaa: null" lines
     * calculateFibonacci("5", false, false);  // then:       FibonacciResult[value=5, cost=0], no line
     * calculateFibonacci("-1", true, false);  //             FibonacciResult[value=-1, cost=1], 1 line
     * }</pre>
     *
     * @param n            the {@code n} text, the cache key
     * @param bypass       {@code true} when the call carries the inbound {@code nocache} property: no cache lookup
     *                     and no store for this call (D-685)
     * @param nocacheQuery {@code true} when the call carries a {@code nocache} query parameter with a value: both
     *                     direct recursive calls bypass the cache (D-685)
     * @return the Fibonacci value text and the cost of this call: 0 for a cache hit
     * @throws NumberFormatException if {@code n} is not a decimal number (D-684)
     * @throws ArithmeticException   if {@code n} is at least 2 and has a non-zero fractional part (D-684)
     */
    @Cacheable(cacheNames = CacheConfig.FIBONACCI_CACHE, keyGenerator = "fibonacciKeyGenerator", condition = "!#bypass")
    public FibonacciResult calculateFibonacci(String n, boolean bypass, boolean nocacheQuery) {
        // logger "Log the input" (:64): message "aaa: #[]".
        log.info("aaa: null");

        // expression-component "Perform calculation" (:65-80): n = the n query parameter (:66).
        BigDecimal d = new BigDecimal(n);
        if (d.compareTo(BigDecimal.valueOf(2)) < 0) {
            // if (n < 2) (:67-70): payload = n; outbound cost = 1.
            return new FibonacciResult(n, 1);
        }

        // else (:71-78): cached = the nocache query parameter is absent (:72).
        boolean cached = !nocacheQuery;
        int k = d.intValueExact();
        // fib1 = fibonacciRequest(n-1, cached) (:74), then fib2 = fibonacciRequest(n-2, cached) (:75).
        FibonacciResult fib1 = fibonacciRequest(k - 1, cached);
        FibonacciResult fib2 = fibonacciRequest(k - 2, cached);
        // outbound cost = cost1 + cost2 + 1 (:76); payload = parseLong(fib1) + parseLong(fib2) (:77).
        return new FibonacciResult(
                Long.toString(Long.parseLong(fib1.value()) + Long.parseLong(fib2.value())),
                fib1.cost() + fib2.cost() + 1);
    }

    /**
     * Implements the MEL global function {@code fibonacciRequest(n, cached)} [cache-scope.xml:7-27] (SC-10): the
     * request-response call of {@code vm://fibonacci}. The request carries only {@code n}, as its decimal text, in
     * its query map [cache-scope.xml:16-18], and the inbound {@code nocache} property when {@code cached} is
     * {@code false} [cache-scope.xml:20-23]. It is made on {@code self}, through the cache (D-055, D-685).
     *
     * @param n      the Fibonacci index of the request
     * @param cached {@code false} to bypass the cache for this call
     * @return the result of {@link #calculateFibonacci(String, boolean, boolean)} for {@code n}, with
     *         {@code bypass = !cached} and no {@code nocache} query parameter
     */
    FibonacciResult fibonacciRequest(int n, boolean cached) {
        // new message with query map {n: Integer.toString(n)} (:14-18); outbound nocache when !cached (:20-23);
        // client.send("vm://fibonacci", request) (:25-26).
        return self.calculateFibonacci(Integer.toString(n), !cached, false);
    }
}
