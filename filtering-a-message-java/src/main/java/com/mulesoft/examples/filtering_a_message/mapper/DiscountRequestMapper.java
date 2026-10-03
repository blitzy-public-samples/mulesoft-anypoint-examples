package com.mulesoft.examples.filtering_a_message.mapper;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Converts a JSON request body into a {@link HashMap}.
 *
 * <p>The body is read by a private {@link ObjectMapper} with Jackson's default settings: no
 * feature is enabled or disabled and no module is registered. The mapper holds no other state,
 * and each call only reads its argument.
 *
 * <p>Usage:
 * <pre>{@code
 * Map<String, Object> order = new DiscountRequestMapper()
 *         .toMap("{\"purchases\": 2000, \"months\": 12, \"membership\": \"free\"}"
 *                 .getBytes(StandardCharsets.UTF_8));
 * // order is {purchases=2000, months=12, membership=free}; purchases and months are Integer
 * }</pre>
 */
@Component
public class DiscountRequestMapper {

    /** Jackson mapper with default settings that parses every request body. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parses {@code body} as one JSON document into a {@link HashMap}.
     *
     * <p>Values take Jackson's default types: a JSON integer becomes an {@link Integer}, or a
     * {@link Long} or {@link java.math.BigInteger} when it is out of {@code int} range; a decimal
     * becomes a {@link Double}; a string a {@link String}; {@code true} and {@code false} a
     * {@link Boolean}; a nested object a {@link java.util.LinkedHashMap}; an array an
     * {@link java.util.ArrayList}; and {@code null} a {@code null} value.
     *
     * @param body the request body bytes; the encoding (UTF-8, UTF-16 or UTF-32) is detected by
     *     Jackson
     * @return the parsed map, or {@code null} when the document is the JSON literal {@code null}
     * @throws UncheckedIOException when the body is not a JSON object: malformed JSON, an empty
     *     body, or a root of another type such as an array, with the Jackson
     *     {@link IOException} as its cause
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toMap(byte[] body) {
        try {
            return (Map<String, Object>) objectMapper.readValue(body, HashMap.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
