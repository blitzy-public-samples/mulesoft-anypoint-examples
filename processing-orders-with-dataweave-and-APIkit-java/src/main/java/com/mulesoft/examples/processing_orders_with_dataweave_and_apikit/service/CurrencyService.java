package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * Serves the currency rates of {@code GET /api/currencies}: the bytes of
 * {@code classpath:currency.json}, unchanged. Replaces flow {@code get:/currencies:currency-config}
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml:37-39]; no template tokens
 * are substituted (D-056), and the string-typed {@code currency} values are returned as stored
 * (D-043).
 *
 * <p>The resource is read on the first call to {@link #getCurrencies()}, not at construction, and
 * its bytes are kept for every later call (D-258). A resource that cannot be read raises
 * {@link UncheckedIOException} from that call.
 *
 * <p>Example:
 *
 * <pre>{@code
 * byte[] body = new CurrencyService(new DefaultResourceLoader()).getCurrencies();
 * // body holds the bytes of src/main/resources/currency.json
 * }</pre>
 */
@Service
public class CurrencyService {

    /** Location of the currency template, resolved through {@link #resourceLoader}. */
    private static final String LOCATION = "classpath:currency.json";

    /** Loader that resolves {@link #LOCATION}; the application context when Spring creates the bean. */
    private final ResourceLoader resourceLoader;

    /** Bytes of {@link #LOCATION} after the first successful read; {@code null} before it. */
    private volatile byte[] cached;

    /**
     * Creates the service over the given resource loader. The resource is not read here.
     *
     * @param resourceLoader loader that resolves {@code classpath:currency.json}
     * @throws NullPointerException if {@code resourceLoader} is {@code null}
     */
    public CurrencyService(ResourceLoader resourceLoader) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
    }

    /**
     * Returns the bytes of {@code classpath:currency.json} exactly as stored: no decoding, parsing or
     * substitution. The first call reads the resource; every call returns a new copy of the cached
     * bytes, and a change to a returned array never reaches a later result.
     *
     * @return a copy of the cached bytes of {@code classpath:currency.json}
     * @throws UncheckedIOException if the resource is missing or cannot be read
     */
    public byte[] getCurrencies() {
        byte[] bytes = cached;
        if (bytes == null) {
            // Read on the first call, not at construction or context startup (D-258).
            bytes = read();
            cached = bytes;
        }
        return bytes.clone();
    }

    /**
     * Reads every byte of {@link #LOCATION}.
     *
     * @return the resource's bytes
     * @throws UncheckedIOException wrapping the {@link IOException} (including
     *     {@link java.io.FileNotFoundException} for a missing resource) raised while opening or
     *     reading the resource
     */
    private byte[] read() {
        try (InputStream in = resourceLoader.getResource(LOCATION).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("currency.json could not be read", e);
        }
    }
}
