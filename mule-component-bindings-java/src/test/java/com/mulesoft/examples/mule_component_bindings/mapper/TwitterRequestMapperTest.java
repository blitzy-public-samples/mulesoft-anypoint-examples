package com.mulesoft.examples.mule_component_bindings.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link TwitterRequestMapper#toInvocationProperties(String, Date, int, int)}, with no
 * Spring application context and no mocks (D-452).
 *
 * <p>Each test builds the mapper with {@code new TwitterRequestMapper()} and the {@code since} day with
 * {@link SimpleDateFormat} pattern {@code yyyy-MM-dd} in the JVM default time zone. The expected values
 * are those of {@code stockstats.impl.mule.TwitterRequestProcessor}: the {@code $}-prefixed stock
 * symbol, the {@code since} day, {@code since} plus ten calendar days, and the page size and page number
 * unchanged as {@link Integer}. Key assertions use the literal names {@code stock}, {@code since},
 * {@code until}, {@code rpp} and {@code page}; only {@link #constantsMatchInvocationPropertyNames()}
 * reads the mapper's constants. These tests cover the {@code mapper} package under the JaCoCo LINE
 * covered ratio rule of at least 0.80 (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
public class TwitterRequestMapperTest {

    /**
     * Parses an ISO calendar day with {@code yyyy-MM-dd} in the JVM default time zone, the format the
     * mapper writes.
     *
     * @param isoDate the day, for example {@code 2012-10-26}
     * @return midnight of that day in the default time zone
     * @throws ParseException if {@code isoDate} does not match {@code yyyy-MM-dd}
     */
    private static Date date(String isoDate) throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd").parse(isoDate);
    }

    /**
     * Asserts the returned map is a {@link LinkedHashMap}.
     *
     * @throws Exception if the {@code since} day cannot be parsed
     */
    @Test
    public void toInvocationPropertiesReturnsLinkedHashMap() throws Exception {
        TwitterRequestMapper mapper = new TwitterRequestMapper();

        assertInstanceOf(LinkedHashMap.class, mapper.toInvocationProperties("AAPL", date("2012-10-26"), 100, 3));
    }

    /**
     * Asserts the map holds exactly five keys in the insertion order {@code stock}, {@code since},
     * {@code until}, {@code rpp}, {@code page}.
     *
     * @throws Exception if the {@code since} day cannot be parsed
     */
    @Test
    public void toInvocationPropertiesKeepsKeyOrder() throws Exception {
        TwitterRequestMapper mapper = new TwitterRequestMapper();

        Map<String, Object> result = mapper.toInvocationProperties("AAPL", date("2012-10-26"), 100, 3);

        assertEquals(List.of("stock", "since", "until", "rpp", "page"), new ArrayList<>(result.keySet()));
        assertEquals(5, result.size());
    }

    /**
     * Asserts {@code stock} is the symbol {@code AAPL} prefixed with {@code $}.
     *
     * @throws Exception if the {@code since} day cannot be parsed
     */
    @Test
    public void toInvocationPropertiesPrefixesStockWithDollar() throws Exception {
        TwitterRequestMapper mapper = new TwitterRequestMapper();

        Map<String, Object> result = mapper.toInvocationProperties("AAPL", date("2012-10-26"), 100, 3);

        assertEquals("$AAPL", result.get("stock"));
    }

    /**
     * Asserts {@code since} is the passed day formatted as {@code yyyy-MM-dd}.
     *
     * @throws Exception if the {@code since} day cannot be parsed
     */
    @Test
    public void toInvocationPropertiesFormatsSince() throws Exception {
        TwitterRequestMapper mapper = new TwitterRequestMapper();

        Map<String, Object> result = mapper.toInvocationProperties("AAPL", date("2012-10-26"), 100, 3);

        assertEquals("2012-10-26", result.get("since"));
    }

    /**
     * Asserts {@code until} is ten calendar days after {@code since} 2012-10-26, that is 2012-11-05: the
     * span crosses the October to November month boundary and the 2012-11-04 end of US daylight saving
     * time.
     *
     * @throws Exception if the {@code since} day cannot be parsed
     */
    @Test
    public void toInvocationPropertiesSetsUntilTenDaysAfterSince() throws Exception {
        TwitterRequestMapper mapper = new TwitterRequestMapper();

        Map<String, Object> result = mapper.toInvocationProperties("AAPL", date("2012-10-26"), 100, 3);

        // until is since plus ten days
        assertEquals("2012-11-05", result.get("until"));
    }

    /**
     * Asserts {@code since} 2012-12-26 gives {@code until} 2013-01-05 across the year boundary, with
     * {@code since} still 2012-12-26.
     *
     * @throws Exception if the {@code since} day cannot be parsed
     */
    @Test
    public void toInvocationPropertiesRollsUntilOverYearEnd() throws Exception {
        TwitterRequestMapper mapper = new TwitterRequestMapper();

        Map<String, Object> result = mapper.toInvocationProperties("AAPL", date("2012-12-26"), 100, 3);

        assertEquals("2012-12-26", result.get("since"));
        assertEquals("2013-01-05", result.get("until"));
    }

    /**
     * Asserts {@code rpp} and {@code page} are the passed values 100 and 3, each an {@link Integer}.
     *
     * @throws Exception if the {@code since} day cannot be parsed
     */
    @Test
    public void toInvocationPropertiesKeepsRppAndPageAsIntegers() throws Exception {
        TwitterRequestMapper mapper = new TwitterRequestMapper();

        Map<String, Object> result = mapper.toInvocationProperties("AAPL", date("2012-10-26"), 100, 3);

        assertInstanceOf(Integer.class, result.get("rpp"));
        assertEquals(Integer.valueOf(100), result.get("rpp"));
        assertInstanceOf(Integer.class, result.get("page"));
        assertEquals(Integer.valueOf(3), result.get("page"));
    }

    /**
     * Asserts the passed {@code since} instance keeps its instant after the call: the ten-day addition
     * works on a {@link java.util.Calendar} of its own.
     *
     * @throws Exception if the {@code since} day cannot be parsed
     */
    @Test
    public void toInvocationPropertiesLeavesSinceUnchanged() throws Exception {
        TwitterRequestMapper mapper = new TwitterRequestMapper();
        Date since = date("2012-10-26");
        long instant = since.getTime();

        mapper.toInvocationProperties("AAPL", since, 100, 3);

        assertEquals(instant, since.getTime());
        assertEquals(date("2012-10-26"), since);
    }

    /**
     * Asserts the mapper's key constants are {@code stock}, {@code since}, {@code until}, {@code rpp}
     * and {@code page}, and its date pattern is {@code yyyy-MM-dd}.
     *
     * @throws Exception not raised by these assertions
     */
    @Test
    public void constantsMatchInvocationPropertyNames() throws Exception {
        assertEquals("stock", TwitterRequestMapper.STOCK);
        assertEquals("since", TwitterRequestMapper.SINCE);
        assertEquals("until", TwitterRequestMapper.UNTIL);
        assertEquals("rpp", TwitterRequestMapper.RPP);
        assertEquals("page", TwitterRequestMapper.PAGE);
        assertEquals("yyyy-MM-dd", TwitterRequestMapper.DATE_PATTERN);
    }
}
