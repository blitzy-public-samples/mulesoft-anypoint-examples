package com.mulesoft.examples.document_integration_using_the_cmis_connector.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link CmisObjectIdJsonMapper#toJson(String)}, the JSON rendering of the created CMIS document's
 * object id.
 *
 * <p>The mapper is instantiated directly, with no Spring context and no mocks. Every assertion compares the full
 * returned string exactly: the JSON is compact, its single key is {@code id}, {@code /} is written unescaped,
 * {@code "} and {@code \} are escaped, and a {@code null} id is written as the JSON literal {@code null}.
 */
public class CmisObjectIdJsonMapperTest {

    private static final String ORIGINAL_RESPONSE_PREFIX = "{\"id\":\"/okm:root/pic";

    private final CmisObjectIdJsonMapper mapper = new CmisObjectIdJsonMapper();

    /** toJson writes the id compactly with '/' unescaped, starting with the original test's expected prefix. */
    @Test
    public void toJsonWritesCompactIdWithUnescapedSlash() {
        String json = mapper.toJson("/okm:root/pic1.jpg");

        assertEquals("{\"id\":\"/okm:root/pic1.jpg\"}", json);
        assertTrue(json.startsWith(ORIGINAL_RESPONSE_PREFIX), json);
    }

    /** toJson escapes '"' as '\"' and '\' as '\\' inside the id value. */
    @Test
    public void toJsonEscapesQuoteAndBackslash() {
        String json = mapper.toJson("a\"b\\c");

        assertEquals("{\"id\":\"a\\\"b\\\\c\"}", json);
    }

    /** toJson writes a null id as the JSON literal null. */
    @Test
    public void toJsonWritesNullId() {
        String json = mapper.toJson(null);

        assertEquals("{\"id\":null}", json);
    }
}
