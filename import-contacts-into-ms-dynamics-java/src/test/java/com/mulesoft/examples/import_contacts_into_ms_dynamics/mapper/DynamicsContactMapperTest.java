package com.mulesoft.examples.import_contacts_into_ms_dynamics.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DynamicsContactMapper#readRows(byte[])} and
 * {@link DynamicsContactMapper#toContact(Map)}, the CSV reader and contact map of DW-09
 * ({@code import-contacts-into-ms-dynamics/src/main/app/import-contacts-into-ms-dynamics.xml:9}) and
 * of the four contact attributes ({@code :24}-{@code :27}).
 *
 * <p>Each test calls one mapper instance directly, with no Spring application context and no mocks.
 * The sample input is the classpath resource {@code contacts.csv}; every other input is an inline
 * string encoded as UTF-8. Together the tests execute every line of the mapper (D-049).
 */
public class DynamicsContactMapperTest {

    /** Header row of the sample file. */
    private static final String HEADER = "firstname,surname,phone,email";

    /** First data row of the sample file. */
    private static final String JOHN = "John,Doe,096548763,john.doe@texasComp.com";

    /** Second data row of the sample file. */
    private static final String JANE = "Jane,Doe,091558780,jane.doe@texasComp.com";

    private final DynamicsContactMapper mapper = new DynamicsContactMapper();

    /** The sample file yields John Doe, then Jane Doe, each with the four attributes in order. */
    @Test
    public void sampleFileYieldsTwoContactsInOrder() throws IOException {
        List<Map<String, Object>> contacts = contactsOf(sampleBytes());

        assertThat(contacts).hasSize(2);
        assertThat(contacts.get(0)).containsExactly(
                entry("firstname", "John"),
                entry("lastname", "Doe"),
                entry("emailaddress1", "john.doe@texasComp.com"),
                entry("telephone1", "096548763"));
        assertThat(contacts.get(1)).containsExactly(
                entry("firstname", "Jane"),
                entry("lastname", "Doe"),
                entry("emailaddress1", "jane.doe@texasComp.com"),
                entry("telephone1", "091558780"));
    }

    /**
     * The contact is a {@link LinkedHashMap} whose keys iterate as {@code firstname}, {@code lastname},
     * {@code emailaddress1}, {@code telephone1}, for the sample header and for a header listing the
     * columns in another order.
     */
    @Test
    public void contactKeysAreInAttributeOrder() throws IOException {
        Map<String, Object> sample = mapper.toContact(mapper.readRows(sampleBytes()).get(0));
        Map<String, Object> reordered = contactsOf(utf8("email,phone,surname,firstname\n"
                + "ann@x.com,012345678,Smith,Ann\n")).get(0);

        assertThat(sample).isInstanceOf(LinkedHashMap.class);
        assertThat(sample.keySet()).containsExactly("firstname", "lastname", "emailaddress1", "telephone1");
        assertThat(reordered).isInstanceOf(LinkedHashMap.class);
        assertThat(reordered).containsExactly(
                entry("firstname", "Ann"),
                entry("lastname", "Smith"),
                entry("emailaddress1", "ann@x.com"),
                entry("telephone1", "012345678"));
    }

    /**
     * A column the header lacks yields its attribute with a {@code null} value, an empty cell yields
     * {@code ""}, and a record shorter than the header maps its missing cells to {@code null}.
     */
    @Test
    public void missingColumnIsNullAndEmptyCellIsEmptyString() {
        List<Map<String, String>> rows = mapper.readRows(utf8("firstname,surname,email\n"
                + "Ann,,ann@x.com\n"
                + "Bob\n"));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsExactly(
                entry("firstname", "Ann"),
                entry("surname", ""),
                entry("email", "ann@x.com"));
        assertThat(rows.get(1)).containsExactly(
                entry("firstname", "Bob"),
                entry("surname", null),
                entry("email", null));

        Map<String, Object> ann = mapper.toContact(rows.get(0));
        assertThat(ann).containsKey("telephone1");
        assertThat(ann.get("telephone1")).isNull();
        assertThat(ann).containsEntry("lastname", "");

        Map<String, Object> bob = mapper.toContact(rows.get(1));
        assertThat(bob).containsExactly(
                entry("firstname", "Bob"),
                entry("lastname", null),
                entry("emailaddress1", null),
                entry("telephone1", null));
    }

    /** A quoted cell holding a comma and backslash-escaped quotes reads as one value. */
    @Test
    public void quotedCommaAndBackslashEscapedQuoteFormOneValue() {
        List<Map<String, String>> rows = mapper.readRows(utf8(HEADER + "\n"
                + "John,\"Doe, Jr. \\\"JD\\\"\",096548763,j@x.com\n"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).hasSize(4);
        assertThat(rows.get(0)).containsExactly(
                entry("firstname", "John"),
                entry("surname", "Doe, Jr. \"JD\""),
                entry("phone", "096548763"),
                entry("email", "j@x.com"));
        assertThat(mapper.toContact(rows.get(0))).containsEntry("lastname", "Doe, Jr. \"JD\"");
    }

    /**
     * CRLF and CR-only record separators yield the same rows as LF, and empty lines between or after
     * records are ignored.
     */
    @Test
    public void crlfAndCrOnlyLineEndingsMatchLf() {
        List<Map<String, String>> lf = mapper.readRows(utf8(HEADER + "\n" + JOHN + "\n" + JANE + "\n"));
        List<Map<String, String>> crlf = mapper.readRows(utf8(HEADER + "\r\n" + JOHN + "\r\n" + JANE + "\r\n"));
        List<Map<String, String>> cr = mapper.readRows(utf8(HEADER + "\r" + JOHN + "\r" + JANE + "\r"));
        List<Map<String, String>> blankLines = mapper.readRows(utf8(HEADER + "\n" + JOHN + "\n\n" + JANE + "\n\n"));
        List<Map<String, String>> blankCrlfLines = mapper.readRows(
                utf8(HEADER + "\r\n\r\n" + JOHN + "\r\n\r\n" + JANE + "\r\n"));

        assertThat(lf).hasSize(2);
        assertThat(lf.get(0)).containsEntry("firstname", "John").containsEntry("email", "john.doe@texasComp.com");
        assertThat(lf.get(1)).containsEntry("firstname", "Jane").containsEntry("email", "jane.doe@texasComp.com");
        assertThat(crlf).isEqualTo(lf);
        assertThat(cr).isEqualTo(lf);
        assertThat(blankLines).isEqualTo(lf);
        assertThat(blankCrlfLines).isEqualTo(lf);
    }

    /** A file holding only the header row yields no rows, with or without a trailing line feed. */
    @Test
    public void headerOnlyFileYieldsNoRows() {
        assertThat(mapper.readRows(utf8(HEADER + "\n"))).isEmpty();
        assertThat(mapper.readRows(utf8(HEADER))).isEmpty();
    }

    /** The sample phone {@code 096548763} reaches {@code telephone1} as a string with its leading zero. */
    @Test
    public void phoneKeepsLeadingZeroAsString() throws IOException {
        Map<String, Object> john = contactsOf(sampleBytes()).get(0);

        assertThat(john.get("telephone1")).isInstanceOf(String.class);
        assertThat(john.get("telephone1")).isEqualTo("096548763");
    }

    /** An empty byte array yields no rows. */
    @Test
    public void emptyInputYieldsNoRows() {
        assertThat(mapper.readRows(new byte[0])).isEmpty();
    }

    /** Cells beyond the last header column are not copied into the row. */
    @Test
    public void cellsBeyondHeaderAreDropped() {
        List<Map<String, String>> rows = mapper.readRows(utf8(HEADER + "\n" + JOHN + ",extra,more\n"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsExactly(
                entry("firstname", "John"),
                entry("surname", "Doe"),
                entry("phone", "096548763"),
                entry("email", "john.doe@texasComp.com"));
    }

    /** Spaces around a cell value are kept, quoted or not. */
    @Test
    public void surroundingSpacesAreKept() {
        List<Map<String, String>> rows = mapper.readRows(utf8(HEADER + "\n"
                + " John ,Doe  ,\"096548763 \", john.doe@texasComp.com\n"));

        assertThat(rows.get(0)).containsExactly(
                entry("firstname", " John "),
                entry("surname", "Doe  "),
                entry("phone", "096548763 "),
                entry("email", " john.doe@texasComp.com"));
    }

    /** The bytes are decoded as UTF-8, in header names and in cell values. */
    @Test
    public void utf8NamesAreDecoded() {
        List<Map<String, String>> rows = mapper.readRows(utf8(HEADER + ",präfix\n"
                + "José,Müller-Øster,096548763,jose@x.com,ü\n"));

        assertThat(rows.get(0))
                .containsEntry("firstname", "José")
                .containsEntry("surname", "Müller-Øster")
                .containsEntry("präfix", "ü");
        assertThat(mapper.toContact(rows.get(0)))
                .containsEntry("firstname", "José")
                .containsEntry("lastname", "Müller-Øster");
    }

    /**
     * A quoted value left open to the end of the input throws {@link UncheckedIOException}, in the
     * header row and in a data row.
     */
    @Test
    public void unterminatedQuotedValueThrowsUncheckedIOException() {
        byte[] openHeader = utf8("\"firstname,surname,phone,email\n" + JOHN + "\n");
        byte[] openRow = utf8(HEADER + "\n" + "John,\"Doe,096548763,john.doe@texasComp.com\n");

        assertThatThrownBy(() -> mapper.readRows(openHeader))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class);
        assertThatThrownBy(() -> mapper.readRows(openRow))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    /** A {@code null} document or row throws {@link NullPointerException} naming the argument. */
    @Test
    public void nullInputIsRejected() {
        assertThatThrownBy(() -> mapper.readRows(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("csv");
        assertThatThrownBy(() -> mapper.toContact(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("row");
    }

    /**
     * A row with {@code null} column values, and a row with no columns, each yield all four attributes,
     * the unset ones with {@code null} values.
     */
    @Test
    public void absentOrNullColumnValuesYieldNullAttributes() {
        Map<String, String> partial = new HashMap<>();
        partial.put("firstname", null);
        partial.put("surname", "Doe");
        partial.put("phone", null);

        assertThat(mapper.toContact(partial)).containsExactly(
                entry("firstname", null),
                entry("lastname", "Doe"),
                entry("emailaddress1", null),
                entry("telephone1", null));
        assertThat(mapper.toContact(Map.of())).containsExactly(
                entry("firstname", null),
                entry("lastname", null),
                entry("emailaddress1", null),
                entry("telephone1", null));
    }

    /**
     * Each call returns a new map, the input row keeps its own keys and values, and DW-09's intermediate
     * {@code businessphone} key and the source column names do not appear in the contact.
     */
    @Test
    public void contactIsNewMapAndRowIsUnchanged() throws IOException {
        Map<String, String> row = mapper.readRows(sampleBytes()).get(0);
        Map<String, String> before = new LinkedHashMap<>(row);

        Map<String, Object> first = mapper.toContact(row);
        Map<String, Object> second = mapper.toContact(row);

        assertThat(first).isNotSameAs(second).isEqualTo(second);
        assertThat(row).containsExactlyEntriesOf(before);
        assertThat(first).doesNotContainKeys("businessphone", "surname", "phone", "email");
    }

    /** Reads the bytes of the classpath resource {@code contacts.csv}. */
    private byte[] sampleBytes() throws IOException {
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream("contacts.csv")) {
            assertThat(stream).isNotNull();
            return stream.readAllBytes();
        }
    }

    /** Reads {@code csv} and maps every row to its contact, in record order. */
    private List<Map<String, Object>> contactsOf(byte[] csv) {
        List<Map<String, Object>> contacts = new ArrayList<>();
        for (Map<String, String> row : mapper.readRows(csv)) {
            contacts.add(mapper.toContact(row));
        }
        return contacts;
    }

    /** Encodes {@code text} as UTF-8. */
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

