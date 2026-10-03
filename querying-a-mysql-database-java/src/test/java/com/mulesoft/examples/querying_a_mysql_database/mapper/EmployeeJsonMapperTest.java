package com.mulesoft.examples.querying_a_mysql_database.mapper;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link EmployeeJsonMapper#toJson(List)}, the JSON rendering of the employee query rows that
 * replaces {@code json:object-to-json-transformer} at {@code database-to-json.xml:12}.
 *
 * <p>The mapper is instantiated directly, with no Spring context and no mocks. Every rendering assertion compares
 * the full returned byte array with the UTF-8 bytes of the expected JSON: the array is compact, keeps row order and
 * key order, writes {@code null} values as {@code null} and encodes non-ASCII characters as UTF-8. A value Jackson
 * cannot write raises {@link UncheckedIOException} (D-256, D-049).
 */
public class EmployeeJsonMapperTest {

    private final EmployeeJsonMapper mapper = new EmployeeJsonMapper();

    /** toJson renders an empty row list as the empty JSON array {@code []}. */
    @Test
    public void emptyListRendersEmptyArray() {
        byte[] actual = mapper.toJson(List.of());

        assertArrayEquals("[]".getBytes(StandardCharsets.UTF_8), actual);
    }

    /**
     * toJson renders the {@code import.sql} rows for {@code lastname=Puckett} as compact UTF-8 JSON, byte-identical
     * to the original test's {@code REPLY}.
     */
    @Test
    public void twoRowsRenderCompactBytes() {
        Map<String, Object> chava = Map.of("first_name", "Chava");
        Map<String, Object> quentin = Map.of("first_name", "Quentin");
        List<Map<String, Object>> rows = List.of(chava, quentin);

        byte[] actual = mapper.toJson(rows);

        assertArrayEquals(
                "[{\"first_name\":\"Chava\"},{\"first_name\":\"Quentin\"}]".getBytes(StandardCharsets.UTF_8),
                actual);
    }

    /** toJson keeps the list order of the rows and the key iteration order inside each row. */
    @Test
    public void rowAndKeyOrderKept() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("last_name", "Zed");
        first.put("first_name", "Zoe");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("last_name", "Abe");
        second.put("first_name", "Ann");
        List<Map<String, Object>> rows = List.of(first, second);

        byte[] actual = mapper.toJson(rows);

        assertArrayEquals(
                "[{\"last_name\":\"Zed\",\"first_name\":\"Zoe\"},{\"last_name\":\"Abe\",\"first_name\":\"Ann\"}]"
                        .getBytes(StandardCharsets.UTF_8),
                actual);
    }

    /** toJson writes a {@code null} column value as the JSON literal {@code null}. */
    @Test
    public void nullValueRendersJsonNull() {
        Map<String, Object> row = new HashMap<>();
        row.put("first_name", null);
        List<Map<String, Object>> rows = List.of(row);

        byte[] actual = mapper.toJson(rows);

        assertArrayEquals("[{\"first_name\":null}]".getBytes(StandardCharsets.UTF_8), actual);
    }

    /** toJson writes non-ASCII names unescaped as UTF-8 bytes; {@code é} appears as the pair {@code 0xC3 0xA9}. */
    @Test
    public void nonAsciiNamesEncodedAsUtf8() {
        Map<String, Object> jose = Map.of("first_name", "Jos\u00e9");
        Map<String, Object> zoe = Map.of("first_name", "Zo\u00eb");
        List<Map<String, Object>> rows = List.of(jose, zoe);

        byte[] actual = mapper.toJson(rows);

        assertArrayEquals(
                "[{\"first_name\":\"Jos\u00e9\"},{\"first_name\":\"Zo\u00eb\"}]".getBytes(StandardCharsets.UTF_8),
                actual);
        boolean found = false;
        for (int i = 0; i + 1 < actual.length; i++) {
            if (actual[i] == (byte) 0xC3 && actual[i + 1] == (byte) 0xA9) {
                found = true;
                break;
            }
        }
        assertTrue(found, "UTF-8 byte pair 0xC3 0xA9 for \u00e9 is present in the output");
    }

    /** toJson raises {@link UncheckedIOException} for a row value that Jackson cannot write. */
    @Test
    public void unserializableValueRaisesUncheckedIOException() {
        Map<String, Object> row = new HashMap<>();
        row.put("first_name", new Object());
        List<Map<String, Object>> rows = List.of(row);

        assertThrows(UncheckedIOException.class, () -> mapper.toJson(rows));
    }
}
