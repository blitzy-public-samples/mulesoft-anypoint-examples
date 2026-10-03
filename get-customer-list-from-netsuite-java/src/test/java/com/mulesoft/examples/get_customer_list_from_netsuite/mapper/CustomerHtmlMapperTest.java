package com.mulesoft.examples.get_customer_list_from_netsuite.mapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link CustomerHtmlMapper#toRows(List)}, the DW-04 transform
 * [get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:13-32]: the customer list
 * becomes an XML {@code div} holding one {@code line/tr} block of three {@code td} cells (last name, first name,
 * email) per customer, or one {@code line/tr/td} block with the text {@code No customers found} for an empty list.
 *
 * <p>Each test calls a {@link CustomerHtmlMapper} created with {@code new}, with no Spring application context and
 * no mocks, and compares the returned text with expected text held inline in this class; no file is read. The
 * expected rows are lines 2-31 of {@code get-customer-list-from-netsuite/src/test/resources/example.xml}, preceded by
 * the declaration line {@code <?xml version='1.0' encoding='UTF-8'?>} (D-113). The cell forms for absent,
 * {@code null}, empty and escaped values follow D-113. Every returned text is also checked for {@code \n} line
 * separators only, with no {@code \r} and no trailing newline.
 *
 * <p>These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least 0.80
 * (D-049).
 */
class CustomerHtmlMapperTest {

    /** The declaration line that starts every output: 38 bytes in UTF-8. */
    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /**
     * The {@code div} element for {@link #ROWS}: lines 2-31 of
     * {@code get-customer-list-from-netsuite/src/test/resources/example.xml}, joined with {@code \n}, with no trailing
     * newline; 475 bytes in UTF-8.
     */
    private static final String ROWS_TEXT = String.join("\n",
            "<div>",
            "  <line>",
            "    <tr>",
            "      <td>Abbott</td>",
            "      <td>Byron</td>",
            "      <td>ab@example.com</td>",
            "    </tr>",
            "  </line>",
            "  <line>",
            "    <tr>",
            "      <td>Norman</td>",
            "      <td>Gustaffson</td>",
            "      <td>gustaff@example.com</td>",
            "    </tr>",
            "  </line>",
            "  <line>",
            "    <tr>",
            "      <td>Analytics</td>",
            "      <td>Angels</td>",
            "      <td>aa@example.com</td>",
            "    </tr>",
            "  </line>",
            "  <line>",
            "    <tr>",
            "      <td>McGill</td>",
            "      <td>Stuart</td>",
            "      <td>mcgill@example.com</td>",
            "    </tr>",
            "  </line>",
            "</div>");

    /** The {@code div} element for an empty customer list, joined with {@code \n}; 84 bytes in UTF-8. */
    private static final String NO_RESULTS = String.join("\n",
            "<div>",
            "  <line>",
            "    <tr>",
            "      <td>No customers found</td>",
            "    </tr>",
            "  </line>",
            "</div>");

    /**
     * The four SuiteQL rows of {@link #ROWS_TEXT}, in output order, each keyed {@code lastname}, {@code firstname}
     * and {@code email}, put in that order.
     */
    private static final List<Map<String, Object>> ROWS = List.of(
            row("Abbott", "Byron", "ab@example.com"),
            row("Norman", "Gustaffson", "gustaff@example.com"),
            row("Analytics", "Angels", "aa@example.com"),
            row("McGill", "Stuart", "mcgill@example.com"));

    /** The mapper under test. */
    private final CustomerHtmlMapper mapper = new CustomerHtmlMapper();

    /** Asserts the byte lengths and line count of the inline expected data. */
    @Test
    @DisplayName("Inline declaration, rows and no-results texts have their pinned UTF-8 byte lengths")
    void inlineDataHasPinnedByteLengths() {
        assertEquals(38, DECLARATION.getBytes(UTF_8).length);
        assertEquals(475, ROWS_TEXT.getBytes(UTF_8).length);
        assertEquals(30, ROWS_TEXT.split("\n", -1).length);
        assertEquals(84, NO_RESULTS.getBytes(UTF_8).length);
        assertEquals(7, NO_RESULTS.split("\n", -1).length);
        assertEquals(CustomerHtmlMapper.XML_DECLARATION, DECLARATION);
    }

    /** Asserts the four example rows render as the declaration, a newline and {@link #ROWS_TEXT}, byte for byte. */
    @Test
    @DisplayName("Four customer rows render as the example div, byte for byte")
    void fourRowsRenderAsExampleDiv() {
        String output = mapper.toRows(ROWS);

        byte[] actual = output.getBytes(UTF_8);
        assertArrayEquals((DECLARATION + "\n" + ROWS_TEXT).getBytes(UTF_8), actual);
        assertEquals(514, actual.length);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /** Asserts an empty list renders as the declaration, a newline and {@link #NO_RESULTS}, byte for byte. */
    @Test
    @DisplayName("Empty customer list renders the single No customers found cell, byte for byte")
    void emptyListRendersNoCustomersFound() {
        String output = mapper.toRows(List.of());

        byte[] actual = output.getBytes(UTF_8);
        assertArrayEquals((DECLARATION + "\n" + NO_RESULTS).getBytes(UTF_8), actual);
        assertEquals(123, actual.length);
        assertTrue(output.contains("      <td>" + CustomerHtmlMapper.NO_CUSTOMERS_FOUND + "</td>"));
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /**
     * Asserts the cell order is lastname, firstname, email for a map filled in the order email, firstname,
     * lastname.
     */
    @Test
    @DisplayName("Cells follow lastname, firstname, email whatever the row's key order")
    void cellOrderIsFixedWhateverMapOrder() {
        Map<String, Object> reversed = new LinkedHashMap<>();
        reversed.put("email", "ab@example.com");
        reversed.put("firstname", "Byron");
        reversed.put("lastname", "Abbott");

        String output = mapper.toRows(List.of(reversed));

        assertEquals(document(
                "<div>",
                "  <line>",
                "    <tr>",
                "      <td>Abbott</td>",
                "      <td>Byron</td>",
                "      <td>ab@example.com</td>",
                "    </tr>",
                "  </line>",
                "</div>"), output);
        assertEquals(mapper.toRows(List.of(row("Abbott", "Byron", "ab@example.com"))), output);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /** Asserts a key other than {@code lastname}, {@code firstname} and {@code email} leaves the output unchanged. */
    @Test
    @DisplayName("Keys other than lastname, firstname and email are ignored")
    void extraKeysAreIgnored() {
        Map<String, Object> withLinks = row("Abbott", "Byron", "ab@example.com");
        withLinks.put("links", List.of());
        withLinks.put("id", "1001");

        String output = mapper.toRows(List.of(withLinks));

        assertEquals(mapper.toRows(List.of(row("Abbott", "Byron", "ab@example.com"))), output);
        assertFalse(output.contains("1001"));
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /**
     * Asserts the keys are matched exactly in lower case: a row keyed {@code lastName}, {@code firstName} and
     * {@code email} renders {@code <td/>} for the two camel-case keys (D-113).
     */
    @Test
    @DisplayName("Camel-case lastName and firstName keys do not match the lower-case column names")
    void keysAreMatchedExactlyInLowerCase() {
        Map<String, Object> camelCase = new LinkedHashMap<>();
        camelCase.put("lastName", "Abbott");
        camelCase.put("firstName", "Byron");
        camelCase.put("email", "ab@example.com");

        String output = mapper.toRows(List.of(camelCase));

        assertEquals(singleRowDocument("<td/>", "<td/>", "<td>ab@example.com</td>"), output);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /**
     * Asserts {@code &}, {@code <} and {@code >} are written as {@code &amp;}, {@code &lt;} and {@code &gt;}
     * (D-113).
     */
    @Test
    @DisplayName("Ampersand, less-than and greater-than in a cell are written as entity references")
    void markupCharactersAreEscaped() {
        String output = mapper.toRows(List.of(row("Smith & Sons", "a<b", "c>d@example.com")));

        assertTrue(output.contains("      <td>Smith &amp; Sons</td>"));
        assertTrue(output.contains("      <td>a&lt;b</td>"));
        assertTrue(output.contains("      <td>c&gt;d@example.com</td>"));
        assertFalse(output.contains("<td>a<b</td>"));
        assertEquals(singleRowDocument(
                "<td>Smith &amp; Sons</td>",
                "<td>a&lt;b</td>",
                "<td>c&gt;d@example.com</td>"), output);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /** Asserts an ampersand that already starts an entity reference is escaped again (D-113). */
    @Test
    @DisplayName("Entity-like text in a cell has its ampersand escaped")
    void entityLikeTextIsEscapedAgain() {
        String output = mapper.toRows(List.of(row("&lt;", "&amp;", "x&gt;y@example.com")));

        assertEquals(singleRowDocument(
                "<td>&amp;lt;</td>",
                "<td>&amp;amp;</td>",
                "<td>x&amp;gt;y@example.com</td>"), output);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /** Asserts quotes, apostrophes, whitespace and non-ASCII characters are written unchanged (D-113). */
    @Test
    @DisplayName("Quotes, apostrophes, whitespace and non-ASCII characters in a cell are written unchanged")
    void otherCharactersAreWrittenUnchanged() {
        String output = mapper.toRows(List.of(row("O'Brien \"Jr\"", "  Zo\u00eb  ", "m\u00fcller@example.com")));

        String expected = singleRowDocument(
                "<td>O'Brien \"Jr\"</td>",
                "<td>  Zo\u00eb  </td>",
                "<td>m\u00fcller@example.com</td>");
        assertEquals(expected, output);
        assertArrayEquals(expected.getBytes(UTF_8), output.getBytes(UTF_8));
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /**
     * Asserts a row with no {@code firstname} key renders {@code <td/>} as the second cell line of its block
     * (D-113).
     */
    @Test
    @DisplayName("A row without a firstname key renders an empty td element as its second cell")
    void absentFirstnameRendersEmptyElement() {
        Map<String, Object> noFirstname = new LinkedHashMap<>();
        noFirstname.put("lastname", "Abbott");
        noFirstname.put("email", "ab@example.com");

        String output = mapper.toRows(List.of(noFirstname));

        String[] lines = output.split("\n", -1);
        assertEquals("      <td>Abbott</td>", lines[4]);
        assertEquals("      <td/>", lines[5]);
        assertEquals("      <td>ab@example.com</td>", lines[6]);
        assertEquals(singleRowDocument("<td>Abbott</td>", "<td/>", "<td>ab@example.com</td>"), output);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /** Asserts a {@code null} value renders {@code <td/>}, the form of an absent key (D-113). */
    @Test
    @DisplayName("A null cell value renders an empty td element")
    void nullValueRendersEmptyElement() {
        String output = mapper.toRows(List.of(row(null, "Byron", null)));

        assertEquals(singleRowDocument("<td/>", "<td>Byron</td>", "<td/>"), output);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /** Asserts the empty string renders a start tag followed by an end tag (D-113). */
    @Test
    @DisplayName("An empty-string cell value renders a start and an end tag")
    void emptyStringRendersStartAndEndTag() {
        String output = mapper.toRows(List.of(row("Abbott", "", "ab@example.com")));

        assertEquals(singleRowDocument("<td>Abbott</td>", "<td></td>", "<td>ab@example.com</td>"), output);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /** Asserts a non-string value renders the text of {@link String#valueOf(Object)}. */
    @Test
    @DisplayName("A non-string cell value renders its String.valueOf text")
    void nonStringValueRendersItsText() {
        Map<String, Object> numeric = new LinkedHashMap<>();
        numeric.put("lastname", 42L);
        numeric.put("firstname", Boolean.TRUE);
        numeric.put("email", 'x');

        String output = mapper.toRows(List.of(numeric));

        assertEquals(singleRowDocument("<td>42</td>", "<td>true</td>", "<td>x</td>"), output);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /** Asserts a {@code null} list element renders one block of three {@code <td/>} cells in input order (D-113). */
    @Test
    @DisplayName("A null row renders three empty td elements in its position")
    void nullRowRendersThreeEmptyElements() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Abbott", "Byron", "ab@example.com"));
        rows.add(null);

        String output = mapper.toRows(rows);

        assertEquals(document(
                "<div>",
                "  <line>",
                "    <tr>",
                "      <td>Abbott</td>",
                "      <td>Byron</td>",
                "      <td>ab@example.com</td>",
                "    </tr>",
                "  </line>",
                "  <line>",
                "    <tr>",
                "      <td/>",
                "      <td/>",
                "      <td/>",
                "    </tr>",
                "  </line>",
                "</div>"), output);
        assertLfOnlyWithoutTrailingNewline(output);
    }

    /** Asserts a {@code null} list is rejected with {@link NullPointerException} naming {@code rows}. */
    @Test
    @DisplayName("A null customer list is rejected with NullPointerException")
    void nullListIsRejected() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> mapper.toRows(null));

        assertEquals("rows", thrown.getMessage());
    }

    /** Asserts rendering leaves the input list and its row maps unchanged. */
    @Test
    @DisplayName("Rendering leaves the input rows unchanged")
    void inputRowsAreLeftUnchanged() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> source : ROWS) {
            rows.add(new LinkedHashMap<>(source));
        }
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (Map<String, Object> source : rows) {
            snapshot.add(new LinkedHashMap<>(source));
        }

        String first = mapper.toRows(rows);
        String second = mapper.toRows(rows);

        assertEquals(snapshot, rows);
        assertEquals(first, second);
        assertLfOnlyWithoutTrailingNewline(first);
    }

    /**
     * Returns a mutable row holding {@code lastname}, {@code firstname} and {@code email}, put in that order.
     *
     * @param last the {@code lastname} value
     * @param first the {@code firstname} value
     * @param email the {@code email} value
     * @return a new {@link LinkedHashMap} with the three entries
     */
    private static Map<String, Object> row(String last, String first, String email) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("lastname", last);
        row.put("firstname", first);
        row.put("email", email);
        return row;
    }

    /**
     * Returns {@link #DECLARATION} followed by the given lines, joined with {@code \n}.
     *
     * @param divLines the lines of the {@code div} element
     * @return the expected mapper output
     */
    private static String document(String... divLines) {
        return DECLARATION + "\n" + String.join("\n", divLines);
    }

    /**
     * Returns the expected output for a single row whose three cells are the given {@code td} elements, each
     * indented with six spaces.
     *
     * @param lastCell the {@code td} element of {@code lastname}
     * @param firstCell the {@code td} element of {@code firstname}
     * @param emailCell the {@code td} element of {@code email}
     * @return the expected mapper output
     */
    private static String singleRowDocument(String lastCell, String firstCell, String emailCell) {
        return document(
                "<div>",
                "  <line>",
                "    <tr>",
                "      " + lastCell,
                "      " + firstCell,
                "      " + emailCell,
                "    </tr>",
                "  </line>",
                "</div>");
    }

    /**
     * Asserts {@code output} uses {@code \n} line separators only: it holds no {@code \r} and does not end with
     * {@code \n}.
     *
     * @param output the mapper output
     */
    private static void assertLfOnlyWithoutTrailingNewline(String output) {
        assertFalse(output.endsWith("\n"), "output ends with a newline");
        assertFalse(output.contains("\r"), "output contains a carriage return");
    }
}
