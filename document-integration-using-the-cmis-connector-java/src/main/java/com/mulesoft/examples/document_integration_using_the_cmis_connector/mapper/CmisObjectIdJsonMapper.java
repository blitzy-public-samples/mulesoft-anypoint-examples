package com.mulesoft.examples.document_integration_using_the_cmis_connector.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;

/**
 * Renders a CMIS object id as the JSON object {@code {"id":"<objectId>"}}.
 *
 * <p>The output is compact: no whitespace and no line breaks. The single field is {@code id}. String
 * escaping is Jackson's default: {@code "}, {@code \} and control characters are escaped, while
 * {@code /} and non-ASCII characters are written unchanged.
 *
 * <p>The instance owns a private Jackson {@link ObjectMapper} with default settings, independent of the
 * application's {@code spring.jackson.*} properties. Instances hold no mutable state; {@link #toJson}
 * is side-effect free and safe for concurrent use.
 *
 * <p>Example: {@code toJson("/okm:root/pic1.jpg")} returns {@code {"id":"/okm:root/pic1.jpg"}}.
 */
@Component
public class CmisObjectIdJsonMapper {

    /** Jackson mapper with default settings; writes compact JSON. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Writes the object id of a created CMIS document as a JSON object with the single field {@code id}.
     *
     * @param objectId the {@code cmis:objectId} of the created document, for example
     *     {@code /okm:root/pic1712345678901.jpg}; may be {@code null}
     * @return compact JSON with the single field {@code id}; {@code {"id":null}} when the id is null
     * @throws UncheckedIOException if Jackson fails to write the JSON text
     */
    public String toJson(String objectId) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", objectId);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) { throw new UncheckedIOException(e); }
    }
}
