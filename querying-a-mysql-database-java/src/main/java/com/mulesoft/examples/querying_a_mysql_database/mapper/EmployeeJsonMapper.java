/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.querying_a_mysql_database.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Renders query rows as a compact UTF-8 JSON array, keeping row and key order and writing null values.
 *
 * <p>The array holds one JSON object per row, in list order. Each object holds one member per map
 * entry, in the map's key iteration order, with the map key as the member name. Values are written by
 * Jackson's default serializers: strings with Jackson's default escaping, numbers and booleans as JSON
 * literals, and {@code null} values as {@code null}. The output carries no whitespace, no line breaks
 * and no trailing newline.
 *
 * <p>The instance owns a private Jackson {@link ObjectMapper} with default settings, independent of the
 * application's {@code spring.jackson.*} properties (D-256). Instances hold no mutable state;
 * {@link #toJson} is side-effect free and safe for concurrent use.
 *
 * <p>Example: the rows {@code [{first_name=Chava}, {first_name=Quentin}]} render as
 * {@code [{"first_name":"Chava"},{"first_name":"Quentin"}]}, and an empty list renders as {@code []}.
 */
@Component
public class EmployeeJsonMapper {

    /** Jackson mapper with default settings; writes compact JSON. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Writes the query rows as a JSON array of objects, one object per row.
     *
     * @param rows the result rows of the employee query in result order, each a map from column label to
     *     column value
     * @return the UTF-8 bytes of the compact JSON array; {@code []} for an empty list
     * @throws UncheckedIOException wrapping the {@link JsonProcessingException} raised when Jackson cannot
     *     write a row value
     */
    public byte[] toJson(List<Map<String, Object>> rows) {
        try {
            return objectMapper.writeValueAsBytes(rows);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
