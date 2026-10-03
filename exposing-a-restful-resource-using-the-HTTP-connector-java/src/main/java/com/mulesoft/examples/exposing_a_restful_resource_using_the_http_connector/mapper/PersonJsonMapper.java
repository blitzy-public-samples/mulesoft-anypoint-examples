package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.mapper;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.model.Person;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collection;
import org.springframework.stereotype.Component;

/**
 * Converts {@link Person} values to and from compact JSON bytes.
 *
 * <ul>
 *   <li>{@link #fromJson(byte[])} replaces {@code json:json-to-object-transformer}
 *       ({@code http-restful-resource.xml:21}).</li>
 *   <li>{@link #toJson(Collection)} replaces {@code json:object-to-json-transformer}
 *       ({@code http-restful-resource.xml:13}).</li>
 *   <li>{@link #toJson(Person)} renders the person payload set at
 *       {@code http-restful-resource.xml:36}.</li>
 * </ul>
 *
 * <p>Reading accepts a JSON object whose keys are {@code firstname}, {@code lastname},
 * {@code address} and {@code age}, and rejects any other key. A scalar is coerced to the
 * property type: {@code "age": "12"} reads as {@code 12}. A missing {@code age} reads as
 * {@code 0}; a missing string or {@code address} reads as {@code null}.
 *
 * <p>Writing produces UTF-8 JSON with no whitespace between tokens, no byte order mark and no
 * trailing newline. Keys follow the {@code @JsonPropertyOrder} of {@code model.Person} and
 * {@code model.Address}, and {@code null} properties are written as {@code null}.
 *
 * <p>Example: reading
 * {@code {"firstname":"Tito","lastname":"Lamela","address":{"streetAddress":"Lincoln St.",
 * "city":"San Francisco","state":"CA","zipCode":"90401"},"age":12}} and writing the result
 * with {@link #toJson(Person)} returns the same bytes.
 *
 * <p>Instances hold one immutable, fully configured {@link ObjectMapper} and are safe for
 * concurrent use.
 */
@Component
public class PersonJsonMapper {

    /**
     * Jackson mapper owned by this class: scalar coercion on, unknown properties rejected,
     * every property included, no indentation.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .enable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .serializationInclusion(JsonInclude.Include.ALWAYS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .build();

    /**
     * Reads a request body as a {@link Person}.
     *
     * @param body the JSON bytes of the request body; {@code null} is read as an empty body
     * @return the person the JSON object describes
     * @throws UncheckedIOException wrapping Jackson's {@link IOException} when the body is empty,
     *         is not well-formed JSON, has a root that is not an object, or has a key that
     *         {@link Person} does not declare
     */
    public Person fromJson(byte[] body) {
        byte[] bytes = body == null ? new byte[0] : body;
        try {
            return objectMapper.readValue(bytes, Person.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes persons as a JSON array.
     *
     * @param persons the persons to write, in the collection's iteration order
     * @return the UTF-8 bytes of the JSON array; an empty collection gives {@code []}
     * @throws UncheckedIOException wrapping Jackson's {@link JsonProcessingException} when a
     *         value cannot be written
     */
    public byte[] toJson(Collection<Person> persons) {
        return write(persons);
    }

    /**
     * Writes one person as a JSON object.
     *
     * @param person the person to write
     * @return the UTF-8 bytes of the JSON object
     * @throws UncheckedIOException wrapping Jackson's {@link JsonProcessingException} when a
     *         value cannot be written
     */
    public byte[] toJson(Person person) {
        return write(person);
    }

    /**
     * Writes a value as compact UTF-8 JSON bytes.
     *
     * @param value the value to write
     * @return the UTF-8 bytes of the JSON document
     * @throws UncheckedIOException wrapping Jackson's {@link JsonProcessingException} when the
     *         value cannot be written
     */
    private byte[] write(Object value) {
        try {
            return objectMapper.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
