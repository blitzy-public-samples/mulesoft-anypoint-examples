/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.mapper;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Builds the Twitter search parameters for a stock symbol: the {@code $}-prefixed query, the
 * {@code since} and {@code until} dates ({@code since} plus 10 days) in {@code yyyy-MM-dd}, and the
 * page size and page number.
 *
 * <p>The class holds no state. Each call creates its own {@link SimpleDateFormat} and
 * {@link Calendar}, both in the default locale and the JVM default time zone, and returns a new map.
 * Concurrent calls on the single Spring bean share nothing.
 *
 * <p>Example, with {@code since} at 2014-10-26 in the default time zone:
 * <pre>{@code
 * Map<String, Object> properties = twitterRequestMapper.toInvocationProperties("AAPL", since, 100, 1);
 * // {stock=$AAPL, since=2014-10-26, until=2014-11-05, rpp=100, page=1}
 * }</pre>
 *
 * <p>The search reads the {@link #STOCK}, {@link #SINCE} and {@link #UNTIL} values; the Twitter
 * client is twitter4j 4.1.2 (D-033).
 */
@Component
public class TwitterRequestMapper {

    /** Key of the {@code $}-prefixed stock symbol, the search query; value type {@link String}. */
    public static final String STOCK = "stock";

    /** Key of the first search day in {@link #DATE_PATTERN}; value type {@link String}. */
    public static final String SINCE = "since";

    /** Key of the last search day, {@code since} plus 10 days, in {@link #DATE_PATTERN}; value type String. */
    public static final String UNTIL = "until";

    /** Key of the number of results per page; value type {@link Integer}. */
    public static final String RPP = "rpp";

    /** Key of the page number; value type {@link Integer}. */
    public static final String PAGE = "page";

    /** Pattern of the {@link #SINCE} and {@link #UNTIL} values. */
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    /**
     * Returns the search parameters for one stock symbol and start date, in this insertion order:
     * <ol>
     *   <li>{@value #STOCK}: {@link String}, {@code "$"} followed by {@code stock}; a {@code null}
     *       symbol gives {@code "$null"};</li>
     *   <li>{@value #SINCE}: {@link String}, {@code since} formatted with {@value #DATE_PATTERN};</li>
     *   <li>{@value #UNTIL}: {@link String}, {@code since} plus 10 calendar days, formatted with
     *       {@value #DATE_PATTERN};</li>
     *   <li>{@value #RPP}: {@link Integer}, {@code rpp} unchanged;</li>
     *   <li>{@value #PAGE}: {@link Integer}, {@code page} unchanged.</li>
     * </ol>
     *
     * <p>The method performs no I/O and leaves {@code since} unmodified.
     *
     * @param stock the stock symbol, for example {@code AAPL}
     * @param since the first search day; must not be {@code null}
     * @param rpp the number of results per page
     * @param page the page number
     * @return a new mutable {@link LinkedHashMap} holding the five entries above
     * @throws NullPointerException if {@code since} is {@code null}
     */
    public Map<String, Object> toInvocationProperties(String stock, Date since, int rpp, int page) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(STOCK, "$" + stock);

        DateFormat df = new SimpleDateFormat(DATE_PATTERN);
        properties.put(SINCE, df.format(since));

        // until is since plus ten calendar days; the Calendar holds its own copy of the instant
        Calendar cal = Calendar.getInstance();
        cal.setTime(since);
        cal.add(Calendar.DATE, 10);
        properties.put(UNTIL, df.format(cal.getTime()));

        properties.put(RPP, rpp);
        properties.put(PAGE, page);
        return properties;
    }

}
