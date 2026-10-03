package com.mulesoft.examples.import_leads_into_salesforce.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests of {@link LeadMapper#toLead(Reader)}, the DW-11 mapping (D-034).
 */
public class LeadMapperTest {

    /** Classpath location of the committed sample {@code leads.csv}. */
    private static final String LEADS_CSV = "/leads.csv";

    /** Header record of the committed {@code leads.csv}, in its column order. */
    private static final String HEADER = "Company,FirstName,LastName,Birthday,Email";

    /** First data record of the committed {@code leads.csv}. */
    private static final String ROW_1 = "Aliquam Tincidunt Nunc LLC,Ishmael,Alexander,03/08/2014,faucibus@egetmetus.org";

    /** Second data record of the committed {@code leads.csv}. */
    private static final String ROW_2 = "Eu Odio PC,Cole,Burks,12/16/2013,sem.egestas@mollis.org";

    /** Lead keys in DW-11 output order. */
    private static final List<String> KEY_ORDER = List.of("Company", "Email", "FirstName", "LastName");

    private final LeadMapper mapper = new LeadMapper();

    /**
     * Input: the classpath {@code /leads.csv}, 176 bytes, CR-separated, no LF, no final separator. Output: the
     * Aliquam Tincidunt Nunc LLC and Eu Odio PC Leads, in file order, each with the four DW-11 keys only.
     */
    @Test
    @DisplayName("DW-11 maps the committed CR-only leads.csv to two Lead maps")
    public void toLeadMapsCommittedCrOnlyLeadsCsv() throws IOException {
        byte[] bytes;
        try (InputStream stream = LeadMapperTest.class.getResourceAsStream(LEADS_CSV)) {
            assertNotNull(stream, "classpath resource " + LEADS_CSV);
            bytes = stream.readAllBytes();
        }
        assertEquals(176, bytes.length);
        int carriageReturns = 0;
        boolean lineFeed = false;
        for (byte value : bytes) {
            if (value == '\r') {
                carriageReturns++;
            } else if (value == '\n') {
                lineFeed = true;
            }
        }
        assertTrue(carriageReturns > 0, "leads.csv holds at least one CR");
        assertFalse(lineFeed, "leads.csv holds no LF");
        assertTrue(bytes[bytes.length - 1] != '\r', "leads.csv ends without a final CR");
        assertEquals(String.join("\r", HEADER, ROW_1, ROW_2), new String(bytes, StandardCharsets.UTF_8));

        List<Map<String, Object>> leads = classpathLeadsCsv();

        assertEquals(2, leads.size());
        assertEquals(expectedLeads(), leads);
        assertKeyOrder(leads);
        for (Map<String, Object> lead : leads) {
            assertFalse(lead.containsKey("Birthday"), "Birthday is not a Lead key");
            assertEquals(4, lead.size());
        }

        Map<String, Object> first = leads.get(0);
        assertEquals("Aliquam Tincidunt Nunc LLC", first.get("Company"));
        assertEquals("faucibus@egetmetus.org", first.get("Email"));
        assertEquals("Ishmael", first.get("FirstName"));
        assertEquals("Alexander", first.get("LastName"));

        Map<String, Object> second = leads.get(1);
        assertEquals("Eu Odio PC", second.get("Company"));
        assertEquals("sem.egestas@mollis.org", second.get("Email"));
        assertEquals("Cole", second.get("FirstName"));
        assertEquals("Burks", second.get("LastName"));
    }

    /**
     * Input: the {@code leads.csv} records joined by LF, then by CRLF. Output: the same Leads as the
     * CR-separated classpath {@code /leads.csv}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n"})
    @DisplayName("DW-11 gives the leads.csv result for LF and CRLF record separators")
    public void toLeadGivesSameResultForLfAndCrlfSeparators(String separator) throws IOException {
        List<Map<String, Object>> leads = map(String.join(separator, HEADER, ROW_1, ROW_2));
        List<Map<String, Object>> committed = classpathLeadsCsv();

        assertEquals(committed, leads);
        assertEquals(expectedLeads(), leads);
        assertKeyOrder(leads);
    }

    /** Input: the header record alone, without and with a final CR. Output: an empty list, not null. */
    @Test
    @DisplayName("DW-11 gives an empty list for a header-only document")
    public void toLeadReturnsEmptyListForHeaderOnlyInput() throws IOException {
        List<Map<String, Object>> withoutSeparator = map(HEADER);
        assertNotNull(withoutSeparator);
        assertTrue(withoutSeparator.isEmpty(), "header-only document gives no Lead");

        List<Map<String, Object>> withSeparator = map(HEADER + "\r");
        assertNotNull(withSeparator);
        assertTrue(withSeparator.isEmpty(), "header-only document with a final CR gives no Lead");
    }

    /**
     * Input: a header without an {@code Email} column. Output: one Lead whose {@code Email} key is present,
     * second in key order, with a {@code null} value.
     */
    @Test
    @DisplayName("DW-11 keeps an absent Email column as a null value in second position")
    public void toLeadKeepsAbsentColumnAsNullInSecondPosition() throws IOException {
        List<Map<String, Object>> leads = map("Company,FirstName,LastName\nAcme,Jane,Roe");

        assertEquals(1, leads.size());
        Map<String, Object> lead = leads.get(0);
        assertTrue(lead.containsKey("Email"), "Email key is present");
        assertNull(lead.get("Email"));
        List<String> keys = new ArrayList<>(lead.keySet());
        assertEquals(KEY_ORDER, keys);
        assertEquals(1, keys.indexOf("Email"));
        assertEquals(lead("Acme", null, "Jane", "Roe"), lead);
    }

    /** Input: a quoted {@code Company} value holding a comma. Output: the value without quotes, comma kept. */
    @Test
    @DisplayName("DW-11 keeps a comma inside a quoted value")
    public void toLeadKeepsCommaInsideQuotedField() throws IOException {
        List<Map<String, Object>> leads = map("Company,Email,FirstName,LastName\n\"Acme, Inc.\",a@acme.org,Jane,Roe");

        assertEquals(1, leads.size());
        Map<String, Object> lead = leads.get(0);
        assertEquals("Acme, Inc.", lead.get("Company"));
        assertEquals("a@acme.org", lead.get("Email"));
        assertEquals("Jane", lead.get("FirstName"));
        assertEquals("Roe", lead.get("LastName"));
        assertEquals(lead("Acme, Inc.", "a@acme.org", "Jane", "Roe"), lead);
    }

    /** Input: the {@code leads.csv} records with an empty line between them. Output: the two Leads only. */
    @Test
    @DisplayName("DW-11 skips an empty line between records")
    public void toLeadIgnoresEmptyLineBetweenRecords() throws IOException {
        List<Map<String, Object>> leads = map(HEADER + "\n" + ROW_1 + "\n\n" + ROW_2);

        assertEquals(2, leads.size());
        assertEquals(expectedLeads(), leads);
        assertKeyOrder(leads);
    }

    /** Input: a data record whose opening quote is never closed. Output: a checked {@link IOException}. */
    @Test
    @DisplayName("DW-11 throws IOException for an unterminated quoted value")
    public void toLeadThrowsIOExceptionForUnterminatedQuote() throws IOException {
        assertThrows(IOException.class,
                () -> map("Company,Email,FirstName,LastName\n\"Acme,a@acme.org,Jane,Roe"));
    }

    /**
     * Input: a data record holding only {@code Company} and {@code Email}. Output: one Lead with
     * {@code FirstName} and {@code LastName} present and {@code null}.
     */
    @Test
    @DisplayName("DW-11 gives null values for cells missing from a short record")
    public void toLeadGivesNullForValuesMissingFromShortRecord() throws IOException {
        List<Map<String, Object>> leads = map("Company,Email,FirstName,LastName\nAcme,a@acme.org");

        assertEquals(1, leads.size());
        Map<String, Object> lead = leads.get(0);
        assertEquals(lead("Acme", "a@acme.org", null, null), lead);
        assertKeyOrder(leads);
        assertTrue(lead.containsKey("FirstName"), "FirstName key is present");
        assertNull(lead.get("FirstName"));
        assertTrue(lead.containsKey("LastName"), "LastName key is present");
        assertNull(lead.get("LastName"));
    }

    /** A Lead map with the four DW-11 keys in {@link #KEY_ORDER}; any value may be {@code null}. */
    private static LinkedHashMap<String, Object> lead(
            String company, String email, String firstName, String lastName) {
        LinkedHashMap<String, Object> lead = new LinkedHashMap<>();
        lead.put("Company", company);
        lead.put("Email", email);
        lead.put("FirstName", firstName);
        lead.put("LastName", lastName);
        return lead;
    }

    /** The two Leads of the committed {@code leads.csv}, in file order. */
    private static List<Map<String, Object>> expectedLeads() {
        return List.of(
                lead("Aliquam Tincidunt Nunc LLC", "faucibus@egetmetus.org", "Ishmael", "Alexander"),
                lead("Eu Odio PC", "sem.egestas@mollis.org", "Cole", "Burks"));
    }

    /** Maps {@code csv} through {@link LeadMapper#toLead(Reader)}. */
    private List<Map<String, Object>> map(String csv) throws IOException {
        return mapper.toLead(new StringReader(csv));
    }

    /** Maps the classpath {@code /leads.csv}, read as UTF-8, through {@link LeadMapper#toLead(Reader)}. */
    private List<Map<String, Object>> classpathLeadsCsv() throws IOException {
        InputStream stream = LeadMapperTest.class.getResourceAsStream(LEADS_CSV);
        assertNotNull(stream, "classpath resource " + LEADS_CSV);
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return mapper.toLead(reader);
        }
    }

    /** Asserts that every map iterates its keys in {@link #KEY_ORDER}. */
    private static void assertKeyOrder(List<Map<String, Object>> leads) {
        for (Map<String, Object> lead : leads) {
            assertEquals(KEY_ORDER, new ArrayList<>(lead.keySet()));
        }
    }
}
