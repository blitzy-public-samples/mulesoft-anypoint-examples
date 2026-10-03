package com.mulesoft.examples.salesforce_data_retrieval.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.mulesoft.examples.salesforce_data_retrieval.model.SobjectSummary;

/**
 * Unit tests of {@link SobjectOptionsMapper#toOptions(List)}, the re-implementation of DataWeave setter DW-26
 * [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:12-19]:
 *
 * <pre>{@code
 * %dw 1.0
 * %output application/xml
 * ---
 * div: { (payload.sobjects map { option @(value: $.name): $.label }) }
 * }</pre>
 *
 * <p>Each test calls a {@code new SobjectOptionsMapper()} directly, with no Spring application context and no
 * mocks, and asserts the exact text of D-390:
 * <ul>
 *   <li>the declaration line {@code <?xml version='1.0' encoding='UTF-8'?>}, then {@code <div>}, one
 *       {@code   <option value="NAME">LABEL</option>} line per entry in list order and {@code </div>}, joined
 *       with {@code \n} and with no {@code \n} after {@code </div>};</li>
 *   <li>the self-closed {@code <div/>} for an empty list;</li>
 *   <li>{@code &}, {@code <} and {@code >} escaped in the label text, and {@code &}, {@code <}, {@code >} and
 *       {@code "} escaped in the {@code value} attribute;</li>
 *   <li>a match for the option-tag regular expression {@code OPTION_REGEX} of the original
 *       {@code SalesforceIdRetrievalIT#testDisplayData}
 *       [salesforce-data-retrieval/src/test/java/org/mule/examples/SalesforceIdRetrievalIT.java:37,68-70];</li>
 *   <li>the {@code div} the original rendered into {@code /original/index.html}, a byte-identical copy of
 *       [salesforce-data-retrieval/src/test/resources/index.html], rebuilt from its 292 entries.</li>
 * </ul>
 * These tests cover the empty-list and non-empty-list branches and the text and attribute escape paths of
 * {@link SobjectOptionsMapper} under the JaCoCo LINE covered ratio rule of at least 0.80 on the {@code mapper}
 * package (D-049).
 */
public class SobjectOptionsMapperTest {

    /** The declaration line that starts every DW-26 result (D-390). */
    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** The option-tag regular expression of the original {@code SalesforceIdRetrievalIT}. */
    private static final String OPTION_REGEX = "<option\\svalue=\\\".*\\\">.*<\\/option>";

    /** One {@code option} line of the original rendered page: two spaces, the element and {@code \n}. */
    private static final Pattern RENDERED_OPTION = Pattern.compile("  <option value=\"([^\"]*)\">([^<]*)</option>\n");

    /** The unit under test, created without a Spring context. */
    private final SobjectOptionsMapper mapper = new SobjectOptionsMapper();

    /**
     * A non-empty list gives the declaration line, {@code <div>}, one two-space-indented {@code option} line per
     * entry in list order and {@code </div>}, joined with {@code \n} and with nothing after {@code </div>}
     * (DW-26, D-390).
     */
    @Test
    public void toOptionsRendersDeclarationDivAndOneOptionPerEntryInOrder() {
        String result = mapper.toOptions(List.of(
                new SobjectSummary("Account", "Account"),
                new SobjectSummary("UserRole", "Role"),
                new SobjectSummary("WebLink", "Custom Button or Link")));

        assertEquals(DECLARATION + "\n"
                + "<div>\n"
                + "  <option value=\"Account\">Account</option>\n"
                + "  <option value=\"UserRole\">Role</option>\n"
                + "  <option value=\"WebLink\">Custom Button or Link</option>\n"
                + "</div>", result);
        assertFalse(result.endsWith("\n"));
    }

    /** An empty list gives the declaration line and the self-closed {@code <div/>} (DW-26, D-390). */
    @Test
    public void toOptionsRendersSelfClosingDivForEmptyList() {
        assertEquals(DECLARATION + "\n<div/>", mapper.toOptions(List.of()));
    }

    /**
     * In the label text, {@code &}, {@code <} and {@code >} are written as {@code &amp;}, {@code &lt;} and
     * {@code &gt;}; the spaces are unchanged (DW-26, D-390).
     */
    @Test
    public void toOptionsEscapesMarkupCharactersInLabelText() {
        String result = mapper.toOptions(List.of(new SobjectSummary("DandBCompany", "D&B <Co> x")));

        assertEquals(DECLARATION + "\n"
                + "<div>\n"
                + "  <option value=\"DandBCompany\">D&amp;B &lt;Co&gt; x</option>\n"
                + "</div>", result);
    }

    /**
     * In the {@code value} attribute, {@code &}, {@code <}, {@code >} and {@code "} are written as
     * {@code &amp;}, {@code &lt;}, {@code &gt;} and {@code &quot;} (DW-26, D-390).
     */
    @Test
    public void toOptionsEscapesMarkupCharactersAndQuoteInValueAttribute() {
        String result = mapper.toOptions(List.of(new SobjectSummary("A&B<C>\"D", "L")));

        assertEquals(DECLARATION + "\n"
                + "<div>\n"
                + "  <option value=\"A&amp;B&lt;C&gt;&quot;D\">L</option>\n"
                + "</div>", result);
    }

    /**
     * A one-entry result contains a match for the original {@code SalesforceIdRetrievalIT} option-tag regular
     * expression {@code OPTION_REGEX} (DW-26).
     */
    @Test
    public void toOptionsMatchesOriginalOptionTagRegex() {
        String result = mapper.toOptions(List.of(new SobjectSummary("Account", "Account")));

        assertTrue(Pattern.compile(OPTION_REGEX).matcher(result).find());
    }

    /**
     * The 292 {@code option} lines of the original rendered page, read back as name and label pairs in page
     * order, give the page's {@code div} text, from the declaration line to {@code </div>}, character for
     * character; the labels of {@code DandBCompany} and {@code DatacloudDandBCompany} are {@code D&B Company}
     * and render as {@code D&amp;B Company} (DW-26, D-390).
     *
     * @throws IOException if {@code /original/index.html} cannot be read
     */
    @Test
    public void toOptionsReproducesOriginalRenderedOptionList() throws IOException {
        byte[] page = readResource("/original/index.html");
        assertEquals(17911, page.length);
        String content = new String(page, StandardCharsets.UTF_8);

        List<SobjectSummary> sobjects = new ArrayList<>();
        Matcher option = RENDERED_OPTION.matcher(content);
        while (option.find()) {
            sobjects.add(new SobjectSummary(unescape(option.group(1)), unescape(option.group(2))));
        }
        assertEquals(292, sobjects.size());
        assertEquals(new SobjectSummary("AcceptedEventRelation", "Accepted Event Relation"), sobjects.get(0));
        assertEquals(new SobjectSummary("WebLink", "Custom Button or Link"), sobjects.get(sobjects.size() - 1));
        assertTrue(sobjects.contains(new SobjectSummary("DandBCompany", "D&B Company")));
        assertTrue(sobjects.contains(new SobjectSummary("DatacloudDandBCompany", "D&B Company")));

        String expected = content.substring(content.indexOf("<?xml"), content.indexOf("</div>") + 6);
        assertEquals(17296, expected.length());

        String actual = mapper.toOptions(sobjects);

        assertEquals(expected, actual);
    }

    /**
     * Returns the bytes of a classpath resource.
     *
     * @param path the absolute classpath path of the resource
     * @return the resource's bytes
     * @throws IOException if the resource cannot be read
     */
    private static byte[] readResource(String path) throws IOException {
        try (InputStream in = SobjectOptionsMapperTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "classpath resource " + path);
            return in.readAllBytes();
        }
    }

    /**
     * Returns {@code text} with the XML entities {@code &lt;}, {@code &gt;}, {@code &quot;} and {@code &apos;}
     * replaced by their characters, then {@code &amp;} replaced by {@code &}.
     *
     * @param text the escaped attribute value or text content
     * @return the unescaped text
     */
    private static String unescape(String text) {
        return text.replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&");
    }
}
