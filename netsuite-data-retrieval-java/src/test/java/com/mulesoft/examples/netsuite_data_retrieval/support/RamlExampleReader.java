package com.mulesoft.examples.netsuite_data_retrieval.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Reads the RAML response examples and the REST stub JSON from the test classpath (D-045, AAP 0.7.1).
 * {@link #read(String)} and {@link #parse(byte[])} accept unescaped control characters inside string
 * literals, such as the raw line feeds of the {@code addrText} values in
 * {@code api/opportunities-response.json}, relax no other JSON syntax rule, and return every number with a
 * fraction as a {@code BigDecimal} node; {@link #readStrict(String)} parses with Jackson's default settings.
 *
 * <p>Classpath names: the copied RAML examples are {@code api/customers-response.json},
 * {@code api/items-response.json} and {@code api/opportunities-response.json}; the REST stubs are
 * {@code stubs/<file>}, for example {@code stubs/customer-record.json}. One leading {@code /} is accepted
 * and removed.
 *
 * <p>Behaviour shared by every method (D-320):
 *
 * <ul>
 *   <li>no method declares a checked exception;</li>
 *   <li>a missing resource throws {@link IllegalStateException} naming the resource;</li>
 *   <li>a Jackson {@link JsonProcessingException}, for example {@code JsonParseException}, reaches the
 *       caller unwrapped and unchanged;</li>
 *   <li>any other {@link IOException} is thrown as an {@link UncheckedIOException};</li>
 *   <li>no file is written, created, moved or deleted, and {@link #bytes(String)} returns a new array on
 *       every call.</li>
 * </ul>
 *
 * <p>Both Jackson mappers are configured once, at class initialization, and are only read afterwards; the
 * class holds no other state and its methods can be called from any thread.
 *
 * <p>Usage, including from a static initializer or a lambda without {@code try/catch}:
 *
 * <pre>
 * JsonNode opportunities = RamlExampleReader.read("api/opportunities-response.json");
 * JsonNode customers = RamlExampleReader.readStrict("api/customers-response.json");
 * assertThrows(JsonParseException.class,
 *         () -&gt; RamlExampleReader.readStrict("api/opportunities-response.json"));
 * </pre>
 */
public final class RamlExampleReader {

    /**
     * Mapper of {@link #read(String)} and {@link #parse(byte[])}: Jackson's defaults plus
     * {@link JsonReadFeature#ALLOW_UNESCAPED_CONTROL_CHARS} and
     * {@link DeserializationFeature#USE_BIG_DECIMAL_FOR_FLOATS}, and no other changed feature (D-045, D-320).
     */
    private static final JsonMapper LENIENT = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    /** Mapper of {@link #readStrict(String)}: Jackson's defaults, with no feature changed. */
    private static final JsonMapper STRICT = JsonMapper.builder().build();

    /** Not instantiable; every member is static. */
    private RamlExampleReader() {
    }

    /**
     * Returns the bytes of a classpath resource exactly as stored, without decoding or normalization.
     *
     * <p>The resource is looked up through the current thread's context class loader, or through the class
     * loader of {@code RamlExampleReader} when the thread has none. One leading {@code /} is removed from the
     * name before the lookup.
     *
     * @param classpathResource classpath name of the resource, for example
     *                          {@code api/opportunities-response.json} or {@code stubs/customer-record.json}
     * @return a new array holding every byte of the resource
     * @throws NullPointerException  if {@code classpathResource} is {@code null}
     * @throws IllegalStateException if no resource with that name is on the classpath; the message names it
     * @throws UncheckedIOException  if reading the resource fails; the cause is the original
     *                               {@link IOException}
     */
    public static byte[] bytes(String classpathResource) {
        Objects.requireNonNull(classpathResource, "classpathResource");
        String name = classpathResource.startsWith("/") ? classpathResource.substring(1) : classpathResource;
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = RamlExampleReader.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("Classpath resource not found: " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read classpath resource " + name, e);
        }
    }

    /**
     * Reads a classpath resource with {@link #bytes(String)} and parses it with {@link #parse(byte[])}.
     *
     * @param classpathResource classpath name of the resource, for example
     *                          {@code api/opportunities-response.json}
     * @return the root node of the parsed document
     * @throws NullPointerException  if {@code classpathResource} is {@code null}
     * @throws IllegalStateException if no resource with that name is on the classpath; the message names it
     * @throws UncheckedIOException  if reading the resource fails
     */
    public static JsonNode read(String classpathResource) {
        return parse(bytes(classpathResource));
    }

    /**
     * Parses JSON bytes with Jackson's defaults plus two changes (D-045, AAP 0.7.1):
     *
     * <ul>
     *   <li>unescaped control characters, for example a raw line feed, are accepted inside string literals
     *       and kept in the string value;</li>
     *   <li>every number with a fraction or an exponent becomes a {@code BigDecimal} node.</li>
     * </ul>
     *
     * <p>Single quotes, unquoted names, comments, trailing commas, {@code NaN} and leading zeros are rejected,
     * as by the default parser. A Jackson {@link JsonProcessingException}, for example
     * {@code JsonParseException}, reaches the caller unwrapped and unchanged. The array is not modified.
     *
     * @param json UTF-8 (or other JSON-detected encoding) bytes of one JSON document
     * @return the root node of the parsed document
     * @throws NullPointerException if {@code json} is {@code null}
     * @throws UncheckedIOException if Jackson reports an {@link IOException} that is not a
     *                              {@link JsonProcessingException}
     */
    public static JsonNode parse(byte[] json) {
        Objects.requireNonNull(json, "json");
        return readTree(LENIENT, json, "byte array");
    }

    /**
     * Reads a classpath resource with {@link #bytes(String)} and parses it with Jackson's default settings.
     * {@code api/customers-response.json} and {@code api/items-response.json} parse;
     * {@code api/opportunities-response.json} fails with {@code JsonParseException} at line 61 (D-045).
     *
     * <p>A Jackson {@link JsonProcessingException}, for example {@code JsonParseException}, reaches the
     * caller unwrapped and unchanged.
     *
     * @param classpathResource classpath name of the resource, for example {@code api/customers-response.json}
     * @return the root node of the parsed document
     * @throws NullPointerException  if {@code classpathResource} is {@code null}
     * @throws IllegalStateException if no resource with that name is on the classpath; the message names it
     * @throws UncheckedIOException  if reading the resource fails, or if Jackson reports an
     *                               {@link IOException} that is not a {@link JsonProcessingException}
     */
    public static JsonNode readStrict(String classpathResource) {
        return readTree(STRICT, bytes(classpathResource), classpathResource);
    }

    /**
     * Parses {@code json} with {@code mapper}. A {@link JsonProcessingException} is thrown unchanged; any
     * other {@link IOException} is thrown as an {@link UncheckedIOException} whose message names
     * {@code source}.
     *
     * @param mapper {@link #LENIENT} or {@link #STRICT}
     * @param json   bytes of one JSON document
     * @param source name of the input used in the {@link UncheckedIOException} message
     * @return the root node of the parsed document
     */
    private static JsonNode readTree(JsonMapper mapper, byte[] json, String source) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw RamlExampleReader.<RuntimeException>rethrow(e);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot parse JSON from " + source, e);
        }
    }

    /**
     * Throws {@code t} itself, without wrapping or copying (D-320). The type argument {@code T} sets only the
     * compile-time {@code throws} clause: with {@code T} bound to {@link RuntimeException} the call site
     * declares no checked exception, and at run time the original {@code t} propagates. The method never
     * returns normally; its return type lets a call site write {@code throw rethrow(e)}.
     *
     * @param <T> the exception type the call site declares
     * @param t   the throwable to throw
     * @return never returns
     * @throws T always; the thrown object is {@code t}
     */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException rethrow(Throwable t) throws T {
        throw (T) t;
    }
}
