package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

/**
 * Unit tests of {@link CurrencyService#getCurrencies()}, the body of {@code GET /api/currencies}: the
 * bytes of {@code classpath:currency.json}, with no template token substituted (D-056).
 *
 * <p>Each test constructs the service directly over a {@link DefaultResourceLoader}, with no Spring
 * application context, reads nothing but classpath resources and writes nothing, and asserts
 * <ul>
 *   <li>the result holds the 134 bytes of the committed {@code currency.json}, unchanged;</li>
 *   <li>every call returns a new array, and a change to one result never reaches a later one;</li>
 *   <li>construction over a loader of a missing resource succeeds, and the first call raises
 *       {@link UncheckedIOException}.</li>
 * </ul>
 * These tests cover the {@code service} package under the JaCoCo LINE covered ratio rule of at least
 * 0.80 (D-049).
 */
public class CurrencyServiceTest {

    /** Classpath location of the committed currency rates, relative to the classpath root. */
    private static final String CURRENCY_JSON = "currency.json";

    /** Classpath location that resolves to no resource. */
    private static final String MISSING_CURRENCY_JSON = "missing-currency.json";

    /** Byte length of the committed {@code src/main/resources/currency.json}, without a trailing newline. */
    private static final int CURRENCY_JSON_BYTES = 134;

    /**
     * Asserts {@link CurrencyService#getCurrencies()} over a {@link DefaultResourceLoader} returns 134
     * bytes equal, byte for byte, to {@code currency.json} as read from the classpath.
     *
     * @throws IOException when the reference copy of {@code currency.json} cannot be read
     */
    @Test
    public void returnsTheBytesOfCurrencyJsonUnchanged() throws IOException {
        byte[] actual = new CurrencyService(new DefaultResourceLoader()).getCurrencies();

        assertEquals(CURRENCY_JSON_BYTES, actual.length, "byte length of the currencies body");
        assertArrayEquals(expectedBytes(), actual, "currencies body equals the bytes of currency.json");
    }

    /**
     * Asserts two calls on one service return distinct arrays: after the first byte of the first result
     * is set to {@code 0}, the second result still equals {@code currency.json} and is not the same
     * array as the first.
     *
     * @throws IOException when the reference copy of {@code currency.json} cannot be read
     */
    @Test
    public void returnsADefensiveCopyOnEveryCall() throws IOException {
        CurrencyService service = new CurrencyService(new DefaultResourceLoader());

        byte[] first = service.getCurrencies();
        first[0] = 0;
        byte[] second = service.getCurrencies();

        assertArrayEquals(expectedBytes(), second,
                "second currencies body equals currency.json after the first result is changed");
        assertNotSame(first, second, "each call returns a new array");
    }

    /**
     * Asserts a service over a loader that resolves every location to the absent
     * {@code missing-currency.json} is constructed without an exception, and that its first
     * {@link CurrencyService#getCurrencies()} call raises {@link UncheckedIOException}.
     */
    @Test
    public void missingResourceThrowsUncheckedIOExceptionOnCall() {
        assertFalse(new ClassPathResource(MISSING_CURRENCY_JSON).exists(),
                "missing-currency.json is absent from the classpath");
        ResourceLoader loader = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return new ClassPathResource(MISSING_CURRENCY_JSON);
            }
        };

        CurrencyService service = assertDoesNotThrow(() -> new CurrencyService(loader),
                "construction over a loader of a missing resource");

        assertThrows(UncheckedIOException.class, service::getCurrencies,
                "first call over a missing resource");
    }

    /**
     * Reads the committed {@code currency.json} from the classpath, independently of
     * {@link CurrencyService}.
     *
     * @return every byte of {@code currency.json}
     * @throws IOException when the resource cannot be read
     */
    private static byte[] expectedBytes() throws IOException {
        return new ClassPathResource(CURRENCY_JSON).getContentAsByteArray();
    }
}
