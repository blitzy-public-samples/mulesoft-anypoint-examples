package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.mapper;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link EmployeesXmlMapper#toXml(byte[])}, the Java form of DW-34 in
 * {@code upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:9-26} (D-034, D-057).
 *
 * <p>Each test calls an {@link EmployeesXmlMapper} created with {@code new}, with no Spring application context
 * and no mocks, and compares the whole output document as UTF-8 text and then byte for byte. Together these
 * tests cover the {@code mapper} package under the JaCoCo LINE covered ratio floor of 0.80 (D-049).
 */
public class EmployeesXmlMapperTest {

    /**
     * Document for the two employees of {@code original/message.json} and {@code employees.json}: the
     * single-quoted declaration, one element per line, one tab per nesting level, LF line ends and no LF after
     * {@code </employees>}; 637 UTF-8 bytes.
     */
    private static final byte[] EXPECTED = String.join("\n",
            "<?xml version='1.0' encoding='UTF-8'?>",
            "<employees>",
            "\t<employee>",
            "\t\t<name>John</name>",
            "\t\t<lastName>Doe</lastName>",
            "\t\t<addresses>",
            "\t\t\t<address>",
            "\t\t\t\t<street>123 Main Street</street>",
            "\t\t\t\t<zipCode>111</zipCode>",
            "\t\t\t</address>",
            "\t\t\t<address>",
            "\t\t\t\t<street>987 Cypress Avenue</street>",
            "\t\t\t\t<zipCode>222</zipCode>",
            "\t\t\t</address>",
            "\t\t</addresses>",
            "\t</employee>",
            "\t<employee>",
            "\t\t<name>Jane</name>",
            "\t\t<lastName>Doe</lastName>",
            "\t\t<addresses>",
            "\t\t\t<address>",
            "\t\t\t\t<street>345 Main Street</street>",
            "\t\t\t\t<zipCode>111</zipCode>",
            "\t\t\t</address>",
            "\t\t\t<address>",
            "\t\t\t\t<street>654 Sunset Boulevard</street>",
            "\t\t\t\t<zipCode>333</zipCode>",
            "\t\t\t</address>",
            "\t\t</addresses>",
            "\t</employee>",
            "</employees>").getBytes(UTF_8);

    /** Unit under test. */
    private final EmployeesXmlMapper mapper = new EmployeesXmlMapper();

    // ---------------------------------------------------------------------------------------------
    // Committed inputs
    // ---------------------------------------------------------------------------------------------

    /** Asserts that {@link #EXPECTED} is 637 bytes long and has the pinned SHA-256 digest. */
    @Test
    public void expectedDocumentConstantIsPinned() throws Exception {
        assertThat(EXPECTED).as("length of the expected document in bytes").hasSize(637);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(EXPECTED)))
                .as("SHA-256 of the expected document")
                .isEqualTo("e851e002933e07980446eb6ff73e99fd7aeed5c566fc250edf64e2a9d274f5c1");
    }

    /** Asserts that the original test request {@code original/message.json} maps to {@link #EXPECTED}. */
    @Test
    public void messageJsonProducesExactDocument() throws Exception {
        assertSameDocument(mapper.toXml(resource("original/message.json")), EXPECTED);
    }

    /** Asserts that the pretty-printed sample request {@code employees.json} maps to {@link #EXPECTED}. */
    @Test
    public void employeesJsonProducesExactDocument() throws Exception {
        assertSameDocument(mapper.toXml(resource("employees.json")), EXPECTED);
    }

    /**
     * Asserts the original assertion of {@code UploadToFtpAfterConvertingJsonToXmlIT#testDataWeave}: the output
     * for {@code original/message.json} equals {@code original/reply.xml} once every whitespace character is
     * removed from both.
     */
    @Test
    public void outputMatchesReplyXmlWithoutWhitespace() throws Exception {
        byte[] out = mapper.toXml(resource("original/message.json"));
        String reply = new String(resource("original/reply.xml"), UTF_8);

        assertThat(new String(out, UTF_8).replaceAll("\\s", ""))
                .as("output without whitespace equals original/reply.xml without whitespace")
                .isEqualTo(reply.replaceAll("\\s", ""));
    }

    /** Asserts that the output for {@code original/message.json} is valid against {@code original/employees.xsd}. */
    @Test
    public void outputValidatesAgainstEmployeesXsd() throws Exception {
        byte[] out = mapper.toXml(resource("original/message.json"));
        URL xsd = EmployeesXmlMapperTest.class.getResource("/original/employees.xsd");
        assertThat(xsd).as("test classpath resource /original/employees.xsd").isNotNull();
        Schema schema = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI).newSchema(xsd);

        assertThatCode(() -> schema.newValidator().validate(new StreamSource(new ByteArrayInputStream(out))))
                .as("output is valid against original/employees.xsd")
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------------------
    // Content cases
    // ---------------------------------------------------------------------------------------------

    /** Asserts that {@code &}, {@code <} and {@code >} are written as entities and quotes are written as is. */
    @Test
    public void escapesMarkupCharactersInLeaf() {
        byte[] out = mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"A & B <C>\","
                + "\"lastName\":\"O'Hara \\\"Jr\\\"\","
                + "\"addresses\":{\"address\":[{\"street\":\"1 Main Street\",\"zipCode\":\"111\"}]}}]}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees>",
                "\t<employee>",
                "\t\t<name>A &amp; B &lt;C&gt;</name>",
                "\t\t<lastName>O'Hara \"Jr\"</lastName>",
                "\t\t<addresses>",
                "\t\t\t<address>",
                "\t\t\t\t<street>1 Main Street</street>",
                "\t\t\t\t<zipCode>111</zipCode>",
                "\t\t\t</address>",
                "\t\t</addresses>",
                "\t</employee>",
                "</employees>"));
    }

    /** Asserts that every field of an input address is written, in input order. */
    @Test
    public void emitsExtraAddressFieldInInputOrder() {
        byte[] out = mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                + "\"addresses\":{\"address\":[{\"street\":\"1 Main Street\",\"city\":\"Springfield\","
                + "\"zipCode\":\"111\"}]}}]}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees>",
                "\t<employee>",
                "\t\t<name>John</name>",
                "\t\t<lastName>Doe</lastName>",
                "\t\t<addresses>",
                "\t\t\t<address>",
                "\t\t\t\t<street>1 Main Street</street>",
                "\t\t\t\t<city>Springfield</city>",
                "\t\t\t\t<zipCode>111</zipCode>",
                "\t\t\t</address>",
                "\t\t</addresses>",
                "\t</employee>",
                "</employees>"));
    }

    /** Asserts that numbers are written in their JSON text and booleans as {@code true} and {@code false}. */
    @Test
    public void writesNumberAndBooleanLeavesAsJsonText() {
        byte[] out = mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                + "\"addresses\":{\"address\":[{\"street\":\"1 Main Street\",\"zipCode\":111,\"primary\":true,"
                + "\"billing\":false,\"ratio\":1.10,\"scale\":1e3}]}}]}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees>",
                "\t<employee>",
                "\t\t<name>John</name>",
                "\t\t<lastName>Doe</lastName>",
                "\t\t<addresses>",
                "\t\t\t<address>",
                "\t\t\t\t<street>1 Main Street</street>",
                "\t\t\t\t<zipCode>111</zipCode>",
                "\t\t\t\t<primary>true</primary>",
                "\t\t\t\t<billing>false</billing>",
                "\t\t\t\t<ratio>1.10</ratio>",
                "\t\t\t\t<scale>1e3</scale>",
                "\t\t\t</address>",
                "\t\t</addresses>",
                "\t</employee>",
                "</employees>"));
    }

    /**
     * Asserts that a {@code null} value is written as an empty-element tag and the empty string as a start tag
     * followed by an end tag.
     */
    @Test
    public void writesNullLeafAsMapperDefines() {
        byte[] out = mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":null,"
                + "\"addresses\":{\"address\":[{\"street\":\"\",\"zipCode\":null}]}}]}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees>",
                "\t<employee>",
                "\t\t<name>John</name>",
                "\t\t<lastName/>",
                "\t\t<addresses>",
                "\t\t\t<address>",
                "\t\t\t\t<street></street>",
                "\t\t\t\t<zipCode/>",
                "\t\t\t</address>",
                "\t\t</addresses>",
                "\t</employee>",
                "</employees>"));
    }

    /**
     * Asserts that an object value is written as a nested element one level deeper, or as an empty-element tag
     * when none of its fields writes an element, and that an array value writes each item under the same name
     * at the same level, nested arrays flattened and an empty array writing nothing.
     */
    @Test
    public void writesNestedObjectAndArrayValuesAsMapperDefines() {
        byte[] out = mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                + "\"addresses\":{\"address\":[{\"street\":\"1 Main Street\",\"geo\":{\"lat\":\"1\",\"lon\":\"2\"},"
                + "\"phones\":{\"phone\":[\"10\",\"20\"]},\"tags\":[\"a\",[\"b\",\"c\"],[]],\"notes\":{},\"links\":[],"
                + "\"meta\":{\"refs\":[[]]},\"zipCode\":\"111\"}]}}]}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees>",
                "\t<employee>",
                "\t\t<name>John</name>",
                "\t\t<lastName>Doe</lastName>",
                "\t\t<addresses>",
                "\t\t\t<address>",
                "\t\t\t\t<street>1 Main Street</street>",
                "\t\t\t\t<geo>",
                "\t\t\t\t\t<lat>1</lat>",
                "\t\t\t\t\t<lon>2</lon>",
                "\t\t\t\t</geo>",
                "\t\t\t\t<phones>",
                "\t\t\t\t\t<phone>10</phone>",
                "\t\t\t\t\t<phone>20</phone>",
                "\t\t\t\t</phones>",
                "\t\t\t\t<tags>a</tags>",
                "\t\t\t\t<tags>b</tags>",
                "\t\t\t\t<tags>c</tags>",
                "\t\t\t\t<notes/>",
                "\t\t\t\t<meta/>",
                "\t\t\t\t<zipCode>111</zipCode>",
                "\t\t\t</address>",
                "\t\t</addresses>",
                "\t</employee>",
                "</employees>"));
    }

    /** Asserts that an empty {@code employee} array gives an empty {@code employees} element. */
    @Test
    public void emptyEmployeeArrayAsMapperDefines() {
        byte[] out = mapper.toXml(json("{\"employees\":{\"employee\":[]}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees/>"));
    }

    /** Asserts that an absent {@code employee} array gives an empty {@code employees} element. */
    @Test
    public void absentEmployeeAsMapperDefines() {
        byte[] out = mapper.toXml(json("{\"employees\":{}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees/>"));
    }

    /** Asserts that an empty {@code address} array gives an empty {@code addresses} element. */
    @Test
    public void emptyAddressArrayAsMapperDefines() {
        byte[] out = mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                + "\"addresses\":{\"address\":[]}}]}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees>",
                "\t<employee>",
                "\t\t<name>John</name>",
                "\t\t<lastName>Doe</lastName>",
                "\t\t<addresses/>",
                "\t</employee>",
                "</employees>"));
    }

    /** Asserts that an absent {@code address} array gives an empty {@code addresses} element. */
    @Test
    public void absentAddressAsMapperDefines() {
        byte[] out = mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"Doe\","
                + "\"addresses\":{}}]}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees>",
                "\t<employee>",
                "\t\t<name>John</name>",
                "\t\t<lastName>Doe</lastName>",
                "\t\t<addresses/>",
                "\t</employee>",
                "</employees>"));
    }

    /** Asserts that U+00FC in a UTF-8 input is written as its UTF-8 bytes {@code 0xC3 0xBC}. */
    @Test
    public void roundTripsNonAsciiUtf8() {
        byte[] out = mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\",\"lastName\":\"M\u00fcller\","
                + "\"addresses\":{\"address\":[{\"street\":\"1 Main Street\",\"zipCode\":\"111\"}]}}]}}"));

        assertSameDocument(out, doc(
                "<?xml version='1.0' encoding='UTF-8'?>",
                "<employees>",
                "\t<employee>",
                "\t\t<name>John</name>",
                "\t\t<lastName>M\u00fcller</lastName>",
                "\t\t<addresses>",
                "\t\t\t<address>",
                "\t\t\t\t<street>1 Main Street</street>",
                "\t\t\t\t<zipCode>111</zipCode>",
                "\t\t\t</address>",
                "\t\t</addresses>",
                "\t</employee>",
                "</employees>"));
        assertThat("\u00fc".getBytes(UTF_8)).as("UTF-8 bytes of U+00FC").containsExactly((byte) 0xC3, (byte) 0xBC);
        assertThat(out).as("output holds the UTF-8 bytes of the lastName element")
                .containsSequence("<lastName>M\u00fcller</lastName>".getBytes(UTF_8));
    }

    // ---------------------------------------------------------------------------------------------
    // Rejections
    // ---------------------------------------------------------------------------------------------

    /** Asserts that malformed JSON is rejected with the parser exception as the cause. */
    @Test
    public void rejectsInvalidJson() {
        assertThatThrownBy(() -> mapper.toXml(json("{\"employees\": {")))
                .as("unterminated object")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body is not valid JSON")
                .cause().isNotNull();
        assertThatThrownBy(() -> mapper.toXml(json("not json")))
                .as("text that is no JSON value")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body is not valid JSON")
                .cause().isNotNull();
        assertThatThrownBy(() -> mapper.toXml(json("{}x")))
                .as("root object followed by text that is no JSON value")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body is not valid JSON")
                .cause().isNotNull();
    }

    /** Asserts that zero bytes, whitespace only and a {@code null} array are rejected as an empty body. */
    @Test
    public void rejectsEmptyInput() {
        assertThatThrownBy(() -> mapper.toXml(new byte[0]))
                .as("zero bytes")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body is empty");
        assertThatThrownBy(() -> mapper.toXml(json(" \t\r\n")))
                .as("whitespace only")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body is empty");
        assertThatThrownBy(() -> mapper.toXml(null))
                .as("null array")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Request body is empty");
    }

    /** Asserts that a second JSON value after the root value is rejected. */
    @Test
    public void rejectsTrailingContent() {
        assertThatThrownBy(() -> mapper.toXml(json("{\"employees\":{\"employee\":[]}} {}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unexpected content after the JSON root value");
    }

    /** Asserts that an array root is rejected. */
    @Test
    public void rejectsArrayRoot() {
        assertThatThrownBy(() -> mapper.toXml(json("[]")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON root is not an object");
    }

    /** Asserts that an {@code employee} value that is an object is rejected. */
    @Test
    public void rejectsEmployeeObject() {
        assertThatThrownBy(() -> mapper.toXml(json("{\"employees\":{\"employee\":{}}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("employees.employee is not an array");
    }

    /** Asserts that an {@code address} value that is a string is rejected. */
    @Test
    public void rejectsAddressString() {
        assertThatThrownBy(() -> mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\","
                + "\"lastName\":\"Doe\",\"addresses\":{\"address\":\"x\"}}]}}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("addresses.address is not an array");
    }

    /**
     * Asserts that a written key that is not an XML element name ({@code 1bad}, {@code a:b}, the empty key and
     * {@code xml:} with no local part) and a written string holding U+0000 are rejected.
     */
    @Test
    public void rejectsInvalidElementNameAndDisallowedCharacter() {
        assertThatThrownBy(() -> mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\","
                + "\"lastName\":\"Doe\",\"addresses\":{\"address\":[{\"1bad\":\"x\"}]}}]}}")))
                .as("key starting with a digit")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON key is not a valid XML element name");
        assertThatThrownBy(() -> mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\","
                + "\"lastName\":\"Doe\",\"addresses\":{\"address\":[{\"a:b\":\"x\"}]}}]}}")))
                .as("key with an unbound prefix")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON key is not a valid XML element name");
        assertThatThrownBy(() -> mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\","
                + "\"lastName\":\"Doe\",\"addresses\":{\"address\":[{\"\":\"x\"}]}}]}}")))
                .as("empty key")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON key is not a valid XML element name");
        assertThatThrownBy(() -> mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"John\","
                + "\"lastName\":\"Doe\",\"addresses\":{\"address\":[{\"xml:\":\"x\"}]}}]}}")))
                .as("xml prefix with no local part")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON key is not a valid XML element name");
        assertThatThrownBy(() -> mapper.toXml(json("{\"employees\":{\"employee\":[{\"name\":\"a\\u0000b\","
                + "\"lastName\":\"Doe\"}]}}")))
                .as("string holding U+0000")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("JSON string holds a character that XML 1.0 does not allow");
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    /**
     * Reads a test classpath resource.
     *
     * @param path the resource path relative to the classpath root, for example {@code original/message.json}
     * @return the resource bytes
     * @throws IllegalStateException when the resource is not on the test classpath
     * @throws Exception when the resource cannot be read
     */
    private static byte[] resource(String path) throws Exception {
        try (InputStream in = EmployeesXmlMapperTest.class.getResourceAsStream("/" + path)) {
            if (in == null) {
                throw new IllegalStateException("Test classpath resource not found: /" + path);
            }
            return in.readAllBytes();
        }
    }

    /** The UTF-8 bytes of {@code text}. */
    private static byte[] json(String text) {
        return text.getBytes(UTF_8);
    }

    /** The UTF-8 bytes of {@code lines} joined with LF, with no LF after the last line. */
    private static byte[] doc(String... lines) {
        return String.join("\n", lines).getBytes(UTF_8);
    }

    /** Asserts that {@code actual} equals {@code expected}, first as UTF-8 text and then byte for byte. */
    private static void assertSameDocument(byte[] actual, byte[] expected) {
        assertThat(new String(actual, UTF_8)).as("output document as UTF-8 text")
                .isEqualTo(new String(expected, UTF_8));
        assertThat(actual).as("output document bytes").isEqualTo(expected);
    }
}
