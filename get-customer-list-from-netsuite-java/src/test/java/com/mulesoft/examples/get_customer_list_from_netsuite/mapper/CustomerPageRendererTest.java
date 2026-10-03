package com.mulesoft.examples.get_customer_list_from_netsuite.mapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link CustomerPageRenderer}, the SC-09 expression of the customer list page: the template
 * {@code templates/customer/index.html} holds {@link #TOKEN} once, inside {@code <tbody>} on line 23, and
 * {@link CustomerPageRenderer#render(String)} replaces the whole expression with the lines of the customer rows
 * text after the first one, empty lines dropped, joined with {@code \n} and with no trailing newline. Every other
 * template byte is left unchanged (D-056).
 *
 * <p>Each test calls a {@link CustomerPageRenderer} created with {@code new}, with no Spring application context and
 * no mocks. The template bytes are read only from the classpath resource {@value #TEMPLATE_RESOURCE} and are
 * checked against their pinned length, SHA-256 digest and expression offset. An expected page is the template text
 * before the expression, then the inserted text, then the template text after the expression. The inserted texts
 * are held inline: the four customer rows and the single {@code No customers found} cell.
 *
 * <p>The construction failures are produced by a thread context class loader that serves no template, an
 * unreadable template, a template without the expression or a template holding it twice. The previous context
 * class loader is restored after each construction.
 *
 * <p>These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least 0.80
 * (D-049).
 */
class CustomerPageRendererTest {

    /** Absolute classpath name of the page template. */
    private static final String TEMPLATE_RESOURCE = "/templates/customer/index.html";

    /** Byte length of the page template. */
    private static final int TEMPLATE_LENGTH = 420;

    /** SHA-256 digest of the page template, in lower-case hexadecimal. */
    private static final String TEMPLATE_SHA256 = "d21f5c0ff57b42025d7f78dbb2a3daa8b14748175062e1ec922c41339509cee4";

    /**
     * The SC-09 expression of the template: 51 characters, each {@code \n} in it being the two characters backslash
     * and {@code n}.
     */
    private static final String TOKEN = "#[groovy: payload.tokenize('\\n')[1..-1].join('\\n')]";

    /** Character and byte offset of {@link #TOKEN} in the template. */
    private static final int TOKEN_OFFSET = 335;

    /** Number of {@code \n} characters in the template before {@link #TOKEN}. */
    private static final int LINE_FEEDS_BEFORE_TOKEN = 22;

    /** Line 23 of the template: two tabs, then {@link #TOKEN} between the {@code tbody} tags. */
    private static final String TOKEN_LINE = "\t\t<tbody>" + TOKEN + "</tbody>";

    /** Heading of the page. */
    private static final String HEADING = "<h1>Netsuite Customer List</h1>";

    /** The declaration line that starts the customer rows text: 38 bytes in UTF-8. */
    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** A declaration line naming another encoding, dropped like {@link #DECLARATION}. */
    private static final String OTHER_DECLARATION = "<?xml version='1.0' encoding='windows-1250'?>";

    /**
     * The {@code div} element of four customer rows, 30 lines joined with {@code \n}, with no trailing newline; 475
     * bytes in UTF-8.
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

    /** The {@code div} element for an empty customer list, 7 lines joined with {@code \n}; 84 bytes in UTF-8. */
    private static final String NO_RESULTS = String.join("\n",
            "<div>",
            "  <line>",
            "    <tr>",
            "      <td>No customers found</td>",
            "    </tr>",
            "  </line>",
            "</div>");

    /** Byte length of the page rendered for {@link #ROWS_TEXT}. */
    private static final int ROWS_PAGE_LENGTH = 844;

    /** Byte length of the page rendered for {@link #NO_RESULTS}. */
    private static final int NO_RESULTS_PAGE_LENGTH = 453;

    /** The renderer under test. */
    private final CustomerPageRenderer renderer = new CustomerPageRenderer();

    /** Asserts the byte lengths and line counts of the inline texts. */
    @Test
    @DisplayName("Inline expression, declaration, rows and no-results texts have their pinned lengths")
    void inlineDataHasPinnedLengths() {
        assertEquals(51, TOKEN.length());
        assertEquals(51, TOKEN.getBytes(UTF_8).length);
        assertEquals(CustomerPageRenderer.TOKEN, TOKEN);
        assertEquals(38, DECLARATION.getBytes(UTF_8).length);
        assertEquals(475, ROWS_TEXT.getBytes(UTF_8).length);
        assertEquals(30, ROWS_TEXT.split("\n", -1).length);
        assertEquals(84, NO_RESULTS.getBytes(UTF_8).length);
        assertEquals(7, NO_RESULTS.split("\n", -1).length);
    }

    /**
     * Asserts the template resource is 420 ASCII bytes with the pinned SHA-256 digest, line feeds only and no final
     * newline, and holds {@link #TOKEN} exactly once at offset 335 on line 23, between {@code <tbody>} and
     * {@code </tbody>}, below {@link #HEADING} (D-056).
     */
    @Test
    @DisplayName("Template resource has its pinned bytes, digest and single expression on line 23")
    void templateHasPinnedBytes() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = templateBytes();
        String template = new String(bytes, UTF_8);

        assertEquals(TEMPLATE_LENGTH, bytes.length);
        assertEquals(TEMPLATE_SHA256, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        for (byte b : bytes) {
            assertTrue(b >= 0, "template holds a non-ASCII byte");
        }
        assertFalse(template.contains("\r"), "template contains a carriage return");
        assertFalse(template.endsWith("\n"), "template ends with a newline");
        assertEquals(TOKEN_OFFSET, template.indexOf(TOKEN));
        assertEquals(TOKEN_OFFSET, template.lastIndexOf(TOKEN));
        assertEquals(LINE_FEEDS_BEFORE_TOKEN, prefix().chars().filter(c -> c == '\n').count());
        assertEquals(TOKEN_LINE, template.split("\n", -1)[LINE_FEEDS_BEFORE_TOKEN]);
        assertTrue(prefix().endsWith("<tbody>"));
        assertTrue(suffix().startsWith("</tbody>"));
        assertEquals(template, prefix() + TOKEN + suffix());
        assertTrue(template.contains(HEADING));
        assertEquals(CustomerPageRenderer.TEMPLATE_LOCATION, TEMPLATE_RESOURCE.substring(1));
    }

    /**
     * Asserts the rows text after its declaration line replaces the whole expression and every other template byte
     * is unchanged: the page is 844 bytes, the template length less the expression plus the inserted text (SC-09,
     * D-056).
     */
    @Test
    @DisplayName("Customer rows replace the whole expression between the tbody tags, byte for byte")
    void rowsReplaceTheExpression() throws IOException {
        String page = renderer.render(DECLARATION + "\n" + ROWS_TEXT);

        byte[] actual = page.getBytes(UTF_8);
        assertArrayEquals((prefix() + ROWS_TEXT + suffix()).getBytes(UTF_8), actual);
        assertEquals(ROWS_PAGE_LENGTH, actual.length);
        assertEquals(TEMPLATE_LENGTH - TOKEN.getBytes(UTF_8).length + ROWS_TEXT.getBytes(UTF_8).length,
                actual.length);
        assertTrue(page.contains(HEADING));
        assertNoCarriageReturnOrToken(page);
    }

    /**
     * Asserts empty lines are dropped wherever they occur: a blank line after the declaration and between every
     * rows line, a trailing newline, and blank lines before the declaration give the 844-byte rows page (SC-09).
     */
    @Test
    @DisplayName("Empty lines and a trailing newline in the rows text are dropped from the page")
    void emptyLinesAreDropped() throws IOException {
        byte[] expected = (prefix() + ROWS_TEXT + suffix()).getBytes(UTF_8);
        String doubled = DECLARATION + "\n\n" + ROWS_TEXT.replace("\n", "\n\n");

        String page = renderer.render(doubled);
        String trailing = renderer.render(doubled + "\n");
        String leading = renderer.render("\n\n" + DECLARATION + "\n" + ROWS_TEXT);

        assertArrayEquals(expected, page.getBytes(UTF_8));
        assertArrayEquals(expected, trailing.getBytes(UTF_8));
        assertArrayEquals(expected, leading.getBytes(UTF_8));
        assertEquals(ROWS_PAGE_LENGTH, trailing.getBytes(UTF_8).length);
        assertNoCarriageReturnOrToken(page);
        assertNoCarriageReturnOrToken(trailing);
        assertNoCarriageReturnOrToken(leading);
    }

    /**
     * Asserts the no-results text after its declaration line replaces the whole expression: the page is 453 bytes,
     * line 23 reads {@code \t\t<tbody><div>} and the inserted text ends in {@code </div></tbody>} (SC-09, D-056).
     */
    @Test
    @DisplayName("The No customers found cell replaces the whole expression between the tbody tags, byte for byte")
    void noResultsReplaceTheExpression() throws IOException {
        String page = renderer.render(DECLARATION + "\n" + NO_RESULTS);

        byte[] actual = page.getBytes(UTF_8);
        assertArrayEquals((prefix() + NO_RESULTS + suffix()).getBytes(UTF_8), actual);
        assertEquals(NO_RESULTS_PAGE_LENGTH, actual.length);
        assertEquals(TEMPLATE_LENGTH - TOKEN.getBytes(UTF_8).length + NO_RESULTS.getBytes(UTF_8).length,
                actual.length);
        assertEquals("\t\t<tbody><div>", page.split("\n", -1)[LINE_FEEDS_BEFORE_TOKEN]);
        assertTrue(page.contains("\n</div></tbody>\n"));
        assertTrue(page.contains(HEADING));
        assertNoCarriageReturnOrToken(page);
    }

    /** Asserts the first non-empty line is dropped whatever its text (SC-09). */
    @Test
    @DisplayName("A first line naming another encoding is dropped like the UTF-8 declaration")
    void firstLineIsDroppedWhateverItsText() throws IOException {
        String page = renderer.render(OTHER_DECLARATION + "\n" + ROWS_TEXT);

        byte[] actual = page.getBytes(UTF_8);
        assertArrayEquals((prefix() + ROWS_TEXT + suffix()).getBytes(UTF_8), actual);
        assertArrayEquals(renderer.render(DECLARATION + "\n" + ROWS_TEXT).getBytes(UTF_8), actual);
        assertEquals(ROWS_PAGE_LENGTH, actual.length);
        assertFalse(page.contains("windows-1250"));
        assertNoCarriageReturnOrToken(page);
    }

    /** Asserts a line holding only spaces is kept as a line of the inserted text (SC-09). */
    @Test
    @DisplayName("A line holding only spaces is kept in the page")
    void spaceOnlyLineIsKept() throws IOException {
        String inserted = "<div>\n  \n</div>";

        String page = renderer.render(DECLARATION + "\n" + inserted);

        assertEquals(prefix() + inserted + suffix(), page);
        assertNoCarriageReturnOrToken(page);
    }

    /** Asserts {@code $} and {@code \} in the inserted text reach the page unchanged (D-056). */
    @Test
    @DisplayName("Dollar signs and backslashes in the rows text are inserted literally")
    void dollarAndBackslashAreInsertedLiterally() throws IOException {
        String inserted = "<td>$1 \\ ${0} $</td>";

        String page = renderer.render(DECLARATION + "\n" + inserted);

        assertEquals(prefix() + inserted + suffix(), page);
        assertNoCarriageReturnOrToken(page);
    }

    /** Asserts a {@code null} text is rejected with {@link NullPointerException} naming {@code dwXml}. */
    @Test
    @DisplayName("A null rows text is rejected with NullPointerException")
    void nullTextIsRejected() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> renderer.render(null));

        assertEquals("dwXml", thrown.getMessage());
    }

    /**
     * Asserts a text with no non-empty line, or with the declaration line alone, is rejected with
     * {@link IllegalArgumentException} naming the number of non-empty lines found.
     */
    @Test
    @DisplayName("A rows text with fewer than two non-empty lines is rejected with IllegalArgumentException")
    void fewerThanTwoLinesAreRejected() {
        assertLineCountRejected("", 0);
        assertLineCountRejected("\n\n\n", 0);
        assertLineCountRejected(DECLARATION, 1);
        assertLineCountRejected("\n" + DECLARATION + "\n\n", 1);
    }

    /**
     * Asserts construction fails with {@link UncheckedIOException} naming the template location when the class
     * loader finds no template.
     */
    @Test
    @DisplayName("A missing template resource fails construction with UncheckedIOException")
    void missingTemplateFailsConstruction() {
        ClassLoader loader = templateLoader(() -> null);

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> constructWith(loader));

        assertEquals("Cannot read template " + CustomerPageRenderer.TEMPLATE_LOCATION, thrown.getMessage());
        assertInstanceOf(FileNotFoundException.class, thrown.getCause());
    }

    /**
     * Asserts construction fails with {@link UncheckedIOException} carrying the read failure when the template
     * stream cannot be read.
     */
    @Test
    @DisplayName("An unreadable template resource fails construction with UncheckedIOException")
    void unreadableTemplateFailsConstruction() {
        IOException failure = new IOException("read failed");
        ClassLoader loader = templateLoader(() -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw failure;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                throw failure;
            }
        });

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> constructWith(loader));

        assertEquals("Cannot read template " + CustomerPageRenderer.TEMPLATE_LOCATION, thrown.getMessage());
        assertSame(failure, thrown.getCause());
    }

    /**
     * Asserts construction fails with {@link IllegalStateException} when the template lacks {@link #TOKEN}: the
     * served template is the pinned template with the expression removed.
     */
    @Test
    @DisplayName("A template without the expression fails construction with IllegalStateException")
    void templateWithoutExpressionFailsConstruction() throws IOException {
        byte[] served = (prefix() + suffix()).getBytes(UTF_8);
        ClassLoader loader = templateLoader(() -> new ByteArrayInputStream(served));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> constructWith(loader));

        assertEquals(expressionCountMessage(), thrown.getMessage());
    }

    /**
     * Asserts construction fails with {@link IllegalStateException} when the template holds {@link #TOKEN} twice:
     * the served template is the pinned template with the expression repeated.
     */
    @Test
    @DisplayName("A template holding the expression twice fails construction with IllegalStateException")
    void templateWithExpressionTwiceFailsConstruction() throws IOException {
        byte[] served = (prefix() + TOKEN + TOKEN + suffix()).getBytes(UTF_8);
        ClassLoader loader = templateLoader(() -> new ByteArrayInputStream(served));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> constructWith(loader));

        assertEquals(expressionCountMessage(), thrown.getMessage());
    }

    /**
     * Returns the bytes of the classpath resource {@link #TEMPLATE_RESOURCE}.
     *
     * @return the template bytes
     * @throws IOException if the resource cannot be read
     */
    private byte[] templateBytes() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(TEMPLATE_RESOURCE)) {
            assertNotNull(in, TEMPLATE_RESOURCE + " is not on the classpath");
            return in.readAllBytes();
        }
    }

    /**
     * Returns the template text before {@link #TOKEN}: its first {@link #TOKEN_OFFSET} characters.
     *
     * @return the template text up to and including {@code <tbody>}
     * @throws IOException if the template cannot be read
     */
    private String prefix() throws IOException {
        return new String(templateBytes(), UTF_8).substring(0, TOKEN_OFFSET);
    }

    /**
     * Returns the template text after {@link #TOKEN}.
     *
     * @return the template text from {@code </tbody>} to its end
     * @throws IOException if the template cannot be read
     */
    private String suffix() throws IOException {
        return new String(templateBytes(), UTF_8).substring(TOKEN_OFFSET + TOKEN.length());
    }

    /**
     * Asserts {@code page} holds no {@code \r} and no {@link #TOKEN}.
     *
     * @param page the rendered page
     */
    private static void assertNoCarriageReturnOrToken(String page) {
        assertFalse(page.contains("\r"), "page contains a carriage return");
        assertFalse(page.contains(TOKEN), "page contains the template expression");
    }

    /**
     * Asserts rendering {@code dwXml} fails with {@link IllegalArgumentException} reporting {@code found} non-empty
     * lines.
     *
     * @param dwXml the rows text
     * @param found the number of non-empty lines in {@code dwXml}
     */
    private void assertLineCountRejected(String dwXml, int found) {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> renderer.render(dwXml));

        assertEquals("dwXml must hold at least 2 non-empty lines, found " + found, thrown.getMessage());
    }

    /**
     * Returns the message of the construction failure for a template that does not hold {@link #TOKEN} exactly once.
     *
     * @return the expected {@link IllegalStateException} message
     */
    private static String expressionCountMessage() {
        return "Template " + CustomerPageRenderer.TEMPLATE_LOCATION + " must contain " + TOKEN + " exactly once";
    }

    /**
     * Returns a class loader with no parent that answers {@link CustomerPageRenderer#TEMPLATE_LOCATION} with the
     * stream {@code template} supplies, and every other resource name with {@code null}.
     *
     * @param template supplies the template stream, or {@code null} for a missing template
     * @return the class loader
     */
    private static ClassLoader templateLoader(Supplier<InputStream> template) {
        return new ClassLoader(null) {
            @Override
            public InputStream getResourceAsStream(String name) {
                return CustomerPageRenderer.TEMPLATE_LOCATION.equals(name) ? template.get() : null;
            }
        };
    }

    /**
     * Creates a {@link CustomerPageRenderer} with {@code loader} as the thread context class loader, then restores
     * the previous context class loader.
     *
     * @param loader the context class loader in effect during construction
     * @return the created renderer
     */
    private static CustomerPageRenderer constructWith(ClassLoader loader) {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return new CustomerPageRenderer();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
