package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Unit tests of {@link CustomerCsvMapper#toDocuments(byte[])} (DW-12). */
class CustomerCsvMapperTest {

    private static final String HEADER = "firstname,surname,phone,email";

    private static final String JOHN = "John,Doe,096548763,john.doe@texasComp.com";

    private static final String JANE = "Jane,Roe,012345678,jane.roe@example.com";

    private final CustomerCsvMapper mapper = new CustomerCsvMapper();

    @Test
    @DisplayName("DW-12: the 72-byte sample input.csv maps to one document keyed firstname, surname, phone, email")
    void mapsSampleInputToOneOrderedDocument() throws IOException {
        byte[] sample;
        try (InputStream in = new ClassPathResource("input.csv").getInputStream()) {
            sample = in.readAllBytes();
        }

        List<Map<String, Object>> documents = mapper.toDocuments(sample);

        assertThat(sample).hasSize(72);
        assertThat(documents).hasSize(1);
        Map<String, Object> document = documents.get(0);
        assertThat(document).isInstanceOf(LinkedHashMap.class);
        assertThat(document.keySet()).containsExactly("firstname", "surname", "phone", "email");
        assertThat(document.get("firstname")).isEqualTo("John");
        assertThat(document.get("surname")).isEqualTo("Doe");
        assertThat(document.get("phone")).isEqualTo("096548763");
        assertThat(document.get("email")).isEqualTo("john.doe@texasComp.com");
    }

    @Test
    @DisplayName("DW-12: an input of zero bytes maps to an empty list")
    void returnsEmptyListForEmptyInput() {
        List<Map<String, Object>> documents = mapper.toDocuments(new byte[0]);

        assertThat(documents).isNotNull();
        assertThat(documents).isEmpty();
    }

    @Test
    @DisplayName("DW-12: a header row alone, with or without a trailing CRLF, maps to an empty list")
    void returnsEmptyListForHeaderOnlyInput() {
        List<Map<String, Object>> withoutSeparator = mapper.toDocuments(utf8(HEADER));
        List<Map<String, Object>> withCrlf = mapper.toDocuments(utf8(HEADER + "\r\n"));

        assertThat(withoutSeparator).isNotNull();
        assertThat(withoutSeparator).isEmpty();
        assertThat(withCrlf).isNotNull();
        assertThat(withCrlf).isEmpty();
    }

    @Test
    @DisplayName("DW-12: LF, CR and CRLF record separators each map two records to the same two ordered documents")
    void acceptsLfCrAndCrlfRecordSeparators() {
        Map<String, Object> john = new LinkedHashMap<>();
        john.put("firstname", "John");
        john.put("surname", "Doe");
        john.put("phone", "096548763");
        john.put("email", "john.doe@texasComp.com");
        Map<String, Object> jane = new LinkedHashMap<>();
        jane.put("firstname", "Jane");
        jane.put("surname", "Roe");
        jane.put("phone", "012345678");
        jane.put("email", "jane.roe@example.com");
        List<Map<String, Object>> expected = List.of(john, jane);

        List<Map<String, Object>> lf = mapper.toDocuments(utf8(HEADER + "\n" + JOHN + "\n" + JANE));
        List<Map<String, Object>> cr = mapper.toDocuments(utf8(HEADER + "\r" + JOHN + "\r" + JANE));
        List<Map<String, Object>> crlf = mapper.toDocuments(utf8(HEADER + "\r\n" + JOHN + "\r\n" + JANE));

        for (List<Map<String, Object>> documents : List.of(lf, cr, crlf)) {
            assertThat(documents).hasSize(2);
            assertThat(documents).isEqualTo(expected);
            for (Map<String, Object> document : documents) {
                assertThat(document.keySet()).containsExactly("firstname", "surname", "phone", "email");
            }
        }
        assertThat(lf).isEqualTo(cr);
        assertThat(cr).isEqualTo(crlf);
        assertThat(lf).isEqualTo(crlf);
    }

    @Test
    @DisplayName("DW-12: a quoted surname holding a comma maps to one value without its quotes")
    void keepsQuotedCommaInOneValue() {
        List<Map<String, Object>> documents =
                mapper.toDocuments(utf8(HEADER + "\r\nJohn,\"Doe, Jr.\",096548763,john.doe@texasComp.com"));

        assertThat(documents).hasSize(1);
        Map<String, Object> document = documents.get(0);
        assertThat(document.keySet()).containsExactly("firstname", "surname", "phone", "email");
        assertThat(document.get("firstname")).isEqualTo("John");
        assertThat(document.get("surname")).isEqualTo("Doe, Jr.");
        assertThat(document.get("phone")).isEqualTo("096548763");
        assertThat(document.get("email")).isEqualTo("john.doe@texasComp.com");
    }

    @Test
    @DisplayName("DW-12: a record without its phone and email columns keeps both keys with null values")
    void mapsMissingTrailingColumnsToNull() {
        List<Map<String, Object>> documents = mapper.toDocuments(utf8(HEADER + "\r\nJohn,Doe"));

        assertThat(documents).hasSize(1);
        Map<String, Object> document = documents.get(0);
        assertThat(document.keySet()).containsExactly("firstname", "surname", "phone", "email");
        assertThat(document).containsEntry("phone", null);
        assertThat(document).containsEntry("email", null);
        assertThat(document.get("firstname")).isEqualTo("John");
        assertThat(document.get("surname")).isEqualTo("Doe");
    }

    @Test
    @DisplayName("DW-12: a value beyond the four header columns is left out of the document")
    void ignoresColumnsBeyondTheHeader() {
        List<Map<String, Object>> documents = mapper.toDocuments(utf8(HEADER + "\r\n" + JOHN + ",extra"));

        assertThat(documents).hasSize(1);
        Map<String, Object> document = documents.get(0);
        assertThat(document.keySet()).containsExactly("firstname", "surname", "phone", "email");
        assertThat(document).hasSize(4);
        assertThat(document.values()).doesNotContain("extra");
        assertThat(document.get("firstname")).isEqualTo("John");
        assertThat(document.get("surname")).isEqualTo("Doe");
        assertThat(document.get("phone")).isEqualTo("096548763");
        assertThat(document.get("email")).isEqualTo("john.doe@texasComp.com");
    }

    @Test
    @DisplayName("DW-12: the phone 096548763 maps to the String \"096548763\" with its leading zero")
    void keepsLeadingZeroInPhoneAsString() {
        List<Map<String, Object>> documents = mapper.toDocuments(utf8(HEADER + "\r\n" + JOHN));

        assertThat(documents).hasSize(1);
        Object phone = documents.get(0).get("phone");
        assertThat(phone).isInstanceOf(String.class);
        assertThat(phone).isNotInstanceOf(Number.class);
        assertThat(phone).isEqualTo("096548763");
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
