package com.mulesoft.examples.scatter_gather_flow_control.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link ContactCsvMapper#toCsv(List)} (DW-28)
 * [scatter-gather-flow-control/src/main/app/scatter-gather.xml:47-54], called directly with no Spring
 * application context.
 *
 * <p>The tests assert the header record {@code Id,Name,Email}, one {@code \n}-terminated record per
 * contact in list order, the empty string for a {@code null} or empty list, unquoted empty fields for
 * absent, {@code null} and empty values, and minimal quoting of a value holding a comma.
 */
class ContactCsvMapperTest {

    private final ContactCsvMapper mapper = new ContactCsvMapper();

    /**
     * The three merged contacts of the example flow (vlado, michal, peter, as
     * {@code ContactMerge.mergeList} returns them) render to the exact UTF-8 bytes of the expected
     * CSV text. Id column is empty for merged contacts (D-043).
     */
    @Test
    void flowDataRendersExactBytes() {
        Map<String, String> vlado = Map.of(
                "Name", "vlado",
                "Email", "vlado@email.com",
                "IDInA", "1",
                "IDInB", "2");
        Map<String, String> michal = Map.of(
                "Name", "michal",
                "Email", "michal@email.com",
                "IDInA", "2",
                "IDInB", "");
        Map<String, String> peter = Map.of(
                "Name", "peter",
                "Email", "peter@email.com",
                "IDInA", "",
                "IDInB", "1");
        List<Map<String, String>> contacts = List.of(vlado, michal, peter);
        String expected = "Id,Name,Email\n"
                + ",vlado,vlado@email.com\n"
                + ",michal,michal@email.com\n"
                + ",peter,peter@email.com\n";

        String actual = mapper.toCsv(contacts);

        assertThat(actual).isEqualTo(expected);
        assertThat(actual.getBytes(StandardCharsets.UTF_8))
                .isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
    }

    /** An empty list renders the empty string, with no header record. */
    @Test
    void emptyListRendersEmptyString() {
        assertThat(mapper.toCsv(List.of())).isEqualTo("");
    }

    /** A {@code null} list renders the empty string, with no header record. */
    @Test
    void nullListRendersEmptyString() {
        assertThat(mapper.toCsv(null)).isEqualTo("");
    }

    /**
     * Absent, {@code null} and empty values render as empty, unquoted fields in every column, the
     * first column included; the output holds no double quote.
     */
    @Test
    void nullAndEmptyValuesRenderUnquotedEmptyFields() {
        Map<String, String> noIdKey = Map.of("Name", "a", "Email", "b");

        Map<String, String> nullId = new HashMap<>();
        nullId.put("Id", null);
        nullId.put("Name", "a");
        nullId.put("Email", "b");

        Map<String, String> emptyId = Map.of("Id", "", "Name", "a", "Email", "b");

        Map<String, String> emptyIdEmptyNameNullEmail = new HashMap<>();
        emptyIdEmptyNameNullEmail.put("Id", "");
        emptyIdEmptyNameNullEmail.put("Name", "");
        emptyIdEmptyNameNullEmail.put("Email", null);

        Map<String, String> idNullNameEmptyEmail = new HashMap<>();
        idNullNameEmptyEmail.put("Id", "1");
        idNullNameEmptyEmail.put("Name", null);
        idNullNameEmptyEmail.put("Email", "");

        String output = mapper.toCsv(List.of(
                noIdKey, nullId, emptyId, emptyIdEmptyNameNullEmail, idNullNameEmptyEmail));

        assertThat(output).isEqualTo("Id,Name,Email\n,a,b\n,a,b\n,a,b\n,,\n1,,\n");
        assertThat(output).doesNotContain("\"");
    }

    /**
     * The columns are {@code Id}, {@code Name}, {@code Email} in that order, whatever the insertion
     * order of the contact map; {@code IDInA} and {@code IDInB} and their values are not written.
     */
    @Test
    void headerOrderIdNameEmail() {
        Map<String, String> contact = new LinkedHashMap<>();
        contact.put("Email", "e@x.com");
        contact.put("IDInB", "B-ONLY");
        contact.put("Name", "n");
        contact.put("IDInA", "A-ONLY");
        contact.put("Id", "9");

        String output = mapper.toCsv(List.of(contact));

        assertThat(output.split("\n", -1)[0]).isEqualTo("Id,Name,Email");
        assertThat(output).isEqualTo("Id,Name,Email\n9,n,e@x.com\n");
        assertThat(output).doesNotContain("A-ONLY", "B-ONLY", "IDInA", "IDInB");
    }

    /** The first column holds the value of the contact's {@code Id} key. */
    @Test
    void idColumnReadsIdKey() {
        Map<String, String> contact = Map.of("Id", "7", "Name", "a", "Email", "b");

        assertThat(mapper.toCsv(List.of(contact))).isEqualTo("Id,Name,Email\n7,a,b\n");
    }

    /** A value holding a comma is enclosed in double quotes; the other fields stay unquoted. */
    @Test
    void valueWithCommaQuoted() {
        Map<String, String> contact = Map.of("Id", "3", "Name", "Doe, John", "Email", "d@e.com");

        assertThat(mapper.toCsv(List.of(contact)))
                .isEqualTo("Id,Name,Email\n3,\"Doe, John\",d@e.com\n");
    }
}
