package com.mulesoft.examples.filtering_a_message.mapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link DiscountRequestMapper#toMap(byte[])}, with no Spring application context.
 *
 * <p>Each test calls a mapper built with {@code new DiscountRequestMapper()} directly (D-200). The
 * cases are the original request body {@code original/message.json}, a malformed document and the
 * JSON literal {@code null}. These tests cover the {@code mapper} package under the JaCoCo LINE
 * covered ratio rule of at least 0.80 (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
public class DiscountRequestMapperTest {

    /** The mapper under test. */
    private final DiscountRequestMapper mapper = new DiscountRequestMapper();

    /**
     * Asserts the bytes of {@code original/message.json} parse into a {@link HashMap} holding
     * exactly the keys {@code purchases}, {@code months} and {@code membership}, with
     * {@code purchases} the {@link Integer} 2000, {@code months} the {@link Integer} 12 and
     * {@code membership} the {@link String} {@code "free"}.
     *
     * @throws IOException when the test resource cannot be read
     */
    @Test
    public void toMapParsesOriginalMessageIntoHashMap() throws IOException {
        Map<String, Object> result;
        try (InputStream in = DiscountRequestMapperTest.class.getResourceAsStream("/original/message.json")) {
            assertNotNull(in);
            result = mapper.toMap(in.readAllBytes());
        }

        assertNotNull(result);
        assertEquals(HashMap.class, result.getClass());
        assertEquals(Set.of("purchases", "months", "membership"), result.keySet());
        assertEquals(Integer.class, result.get("purchases").getClass());
        assertEquals(Integer.valueOf(2000), result.get("purchases"));
        assertEquals(Integer.class, result.get("months").getClass());
        assertEquals(Integer.valueOf(12), result.get("months"));
        assertEquals("free", result.get("membership"));
        assertEquals(String.class, result.get("membership").getClass());
    }

    /**
     * Asserts the malformed document {@code "{"} raises {@link UncheckedIOException} (D-200).
     */
    @Test
    public void toMapWrapsMalformedJsonInUncheckedIOException() {
        assertThrows(UncheckedIOException.class, () -> mapper.toMap("{".getBytes(UTF_8)));
    }

    /** Asserts the JSON literal {@code null} yields {@code null} (D-200). */
    @Test
    public void toMapReturnsNullForJsonNull() {
        assertNull(mapper.toMap("null".getBytes(UTF_8)));
    }
}
