package com.mulesoft.examples.import_contacts_into_salesforce.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ContactMapper#toContact(String)}, the hand re-implementation (D-034) of DW-10
 * {@code import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml:12-21}:
 * {@code payload map { FirstName: $.firstname, LastName: $.surname, Email: $.email, Phone: $.phone }}.
 *
 * <p>Each test calls a plain {@code new ContactMapper()}, with no Spring application context and no mocks.
 * Together the tests check the committed {@code contacts.csv} sample, key order, string values, line
 * endings, quoting, header-name mapping, an absent column or cell, header-only input and empty input. The
 * only resource read is the classpath file {@code /contacts.csv}.
 */
public class ContactMapperTest {

    /** Contact keys in DW-10 order. */
    private static final List<String> CONTACT_KEYS = List.of("FirstName", "LastName", "Email", "Phone");

    /** Header row of the committed {@code contacts.csv} sample. */
    private static final String SAMPLE_HEADER = "firstname,surname,phone,email";

    private final ContactMapper mapper = new ContactMapper();

    /** The committed sample maps to the John and Jane Contacts, in record order, each a {@link LinkedHashMap}. */
    @Test
    @DisplayName("DW-10 maps the committed contacts.csv sample to two Contact maps")
    public void mapsCommittedSampleFile() throws IOException {
        List<Map<String, Object>> contacts = mapper.toContact(sampleCsv());

        assertEquals(expectedSample(), contacts);
        assertEquals(2, contacts.size());
        assertEquals("John", contacts.get(0).get("FirstName"));
        assertEquals("Jane", contacts.get(1).get("FirstName"));
        for (Map<String, Object> contact : contacts) {
            assertInstanceOf(LinkedHashMap.class, contact);
        }
    }

    /** Every Contact map iterates its keys as {@code FirstName}, {@code LastName}, {@code Email}, {@code Phone}. */
    @Test
    @DisplayName("DW-10 Contact keys are FirstName, LastName, Email, Phone in that order")
    public void keepsKeyOrderFirstNameLastNameEmailPhone() throws IOException {
        List<Map<String, Object>> contacts = mapper.toContact(sampleCsv());

        assertEquals(2, contacts.size());
        for (Map<String, Object> contact : contacts) {
            assertEquals(CONTACT_KEYS, new ArrayList<>(contact.keySet()));
        }
    }

    /** DW-10: {@code Phone} is the CSV string unchanged, leading zero included. */
    @Test
    @DisplayName("DW-10 Phone stays a String with its leading zero")
    public void keepsPhoneAsStringWithLeadingZero() throws IOException {
        List<Map<String, Object>> contacts = mapper.toContact(sampleCsv());

        assertEquals(2, contacts.size());
        Object johnPhone = contacts.get(0).get("Phone");
        assertInstanceOf(String.class, johnPhone);
        assertEquals("096548763", johnPhone);
        Object janePhone = contacts.get(1).get("Phone");
        assertInstanceOf(String.class, janePhone);
        assertEquals("091558780", janePhone);
    }

    /** The sample with CRLF and with CR-only record separators gives the same Contacts as with LF. */
    @Test
    @DisplayName("DW-10 CRLF and CR-only line endings give the same Contact maps")
    public void crlfAndCrOnlyLineEndingsGiveSameMaps() throws IOException {
        String sample = sampleCsv();

        assertEquals(expectedSample(), mapper.toContact(sample.replace("\n", "\r\n")));
        assertEquals(expectedSample(), mapper.toContact(sample.replace("\n", "\r")));
    }

    /**
     * A double-quoted cell containing a comma maps to its unquoted text as one value. A header row whose
     * quoted value has no closing quote throws {@link UncheckedIOException}.
     */
    @Test
    @DisplayName("DW-10 a quoted value containing a comma is unquoted; an unclosed quote in the header is rejected")
    public void quotedValueWithCommaIsUnquoted() {
        String csv = SAMPLE_HEADER + "\n"
                + "John,\"Doe, Jr.\",096548763,john.doe@texasComp.com";

        List<Map<String, Object>> contacts = mapper.toContact(csv);

        assertEquals(List.of(contact("John", "Doe, Jr.", "john.doe@texasComp.com", "096548763")), contacts);
        assertThrows(UncheckedIOException.class, () -> mapper.toContact("\"" + SAMPLE_HEADER));
    }

    /** DW-10 selects columns by header name: reordered header columns give the same Contacts and key order. */
    @Test
    @DisplayName("DW-10 maps by header name, independent of column order")
    public void reorderedHeaderColumnsGiveSameMapping() {
        String csv = "email,phone,surname,firstname\n"
                + "john.doe@texasComp.com,096548763,Doe,John\n"
                + "jane.doe@texasComp.com,091558780,Doe,Jane";

        List<Map<String, Object>> contacts = mapper.toContact(csv);

        assertEquals(expectedSample(), contacts);
        for (Map<String, Object> contact : contacts) {
            assertEquals(CONTACT_KEYS, new ArrayList<>(contact.keySet()));
        }
    }

    /**
     * DW-10: absent column maps to null; the {@code Phone} key is present and keeps its position. A record
     * that ends before its {@code phone} cell gives {@code Phone} null in the same way, while a present but
     * empty {@code phone} cell gives {@code ""}.
     */
    @Test
    @DisplayName("DW-10 an absent phone column gives Phone null")
    public void missingPhoneColumnGivesNullPhone() {
        String csv = "firstname,surname,email\n"
                + "John,Doe,john.doe@texasComp.com";

        List<Map<String, Object>> contacts = mapper.toContact(csv);

        assertEquals(1, contacts.size());
        Map<String, Object> contact = contacts.get(0);
        assertTrue(contact.containsKey("Phone"));
        assertNull(contact.get("Phone"));
        assertEquals(contact("John", "Doe", "john.doe@texasComp.com", null), contact);
        assertEquals(CONTACT_KEYS, new ArrayList<>(contact.keySet()));

        String shortRecord = "firstname,surname,email,phone\n"
                + "John,Doe,john.doe@texasComp.com";

        List<Map<String, Object>> shortContacts = mapper.toContact(shortRecord);

        assertEquals(List.of(contact("John", "Doe", "john.doe@texasComp.com", null)), shortContacts);
        assertTrue(shortContacts.get(0).containsKey("Phone"));
        assertEquals(CONTACT_KEYS, new ArrayList<>(shortContacts.get(0).keySet()));

        String emptyPhoneCell = SAMPLE_HEADER + "\n"
                + "John,Doe,,john.doe@texasComp.com";

        assertEquals(List.of(contact("John", "Doe", "john.doe@texasComp.com", "")), mapper.toContact(emptyPhoneCell));
    }

    /** A header row with no data records, with or without a trailing line feed, gives an empty list. */
    @Test
    @DisplayName("DW-10 a header-only file gives no Contacts")
    public void headerOnlyInputGivesEmptyList() {
        List<Map<String, Object>> withoutLineFeed = mapper.toContact(SAMPLE_HEADER);
        List<Map<String, Object>> withLineFeed = mapper.toContact(SAMPLE_HEADER + "\n");

        assertNotNull(withoutLineFeed);
        assertTrue(withoutLineFeed.isEmpty());
        assertNotNull(withLineFeed);
        assertTrue(withLineFeed.isEmpty());
    }

    /** Empty input gives an empty list, never {@code null}. */
    @Test
    @DisplayName("DW-10 empty input gives no Contacts")
    public void emptyInputGivesEmptyList() {
        List<Map<String, Object>> contacts = mapper.toContact("");

        assertNotNull(contacts);
        assertTrue(contacts.isEmpty());
    }

    /**
     * Builds an expected Contact map with keys in DW-10 order; values may be {@code null}.
     *
     * @param firstName expected {@code FirstName}
     * @param lastName expected {@code LastName}
     * @param email expected {@code Email}
     * @param phone expected {@code Phone}
     * @return a new {@link LinkedHashMap} keyed {@code FirstName}, {@code LastName}, {@code Email}, {@code Phone}
     */
    private static Map<String, Object> contact(String firstName, String lastName, String email, String phone) {
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("FirstName", firstName);
        contact.put("LastName", lastName);
        contact.put("Email", email);
        contact.put("Phone", phone);
        return contact;
    }

    /**
     * Reads the committed {@code contacts.csv} sample from the classpath as UTF-8 text.
     *
     * @return the full file content
     * @throws IOException if the resource cannot be read
     */
    private static String sampleCsv() throws IOException {
        try (InputStream in = ContactMapperTest.class.getResourceAsStream("/contacts.csv")) {
            assertNotNull(in, "classpath resource /contacts.csv");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The Contacts DW-10 produces for the committed {@code contacts.csv} sample, in record order.
     *
     * @return the John and Jane Contact maps
     */
    private static List<Map<String, Object>> expectedSample() {
        return List.of(
                contact("John", "Doe", "john.doe@texasComp.com", "096548763"),
                contact("Jane", "Doe", "jane.doe@texasComp.com", "091558780"));
    }
}
