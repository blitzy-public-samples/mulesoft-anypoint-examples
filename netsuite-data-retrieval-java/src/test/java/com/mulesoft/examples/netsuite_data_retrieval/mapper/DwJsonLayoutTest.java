package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.mulesoft.examples.netsuite_data_retrieval.support.RamlExampleReader;

/**
 * Unit tests of {@link DwJsonLayout}, the Jackson pretty printer that writes the JSON layout of the
 * committed NetSuite response examples (D-016). Each test writes a {@link JsonNode} through
 * {@code MAPPER.writer(new DwJsonLayout())}, with no Spring application context, and asserts
 *
 * <ul>
 *   <li>that {@code api/customers-response.json} and {@code api/items-response.json}, read with
 *       {@link RamlExampleReader#read(String)} (D-045), are written back to their committed bytes;</li>
 *   <li>that {@code api/opportunities-response.json}, read the same way, is written as its committed
 *       bytes with each raw line feed inside its two {@code addrText} strings in the two-character
 *       escaped form {@code \n} (D-045, D-532);</li>
 *   <li>a two-space indent per nesting level after each {@code \n}, the name-value separator
 *       {@code ": "}, and the empty containers {@code []} and {@code {}} (D-180);</li>
 *   <li>that every output ends with its closing {@code ]} or <code>&#125;</code>, with no line feed
 *       and no carriage return;</li>
 *   <li>that one {@code ObjectWriter} writes the same value to the same bytes on every call, each
 *       write through a new printer from {@link DwJsonLayout#createInstance()}.</li>
 * </ul>
 */
class DwJsonLayoutTest {

    /** Mapper that parses the inline JSON inputs and writes every output. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Classpath name of the committed customers response example. */
    private static final String CUSTOMERS = "api/customers-response.json";

    /** Classpath name of the committed items response example. */
    private static final String ITEMS = "api/items-response.json";

    /** Classpath name of the committed opportunities response example. */
    private static final String OPPORTUNITIES = "api/opportunities-response.json";

    /** Number of line feeds in the expected opportunities output: the file's 112 less the 8 in addrText. */
    private static final long OPPORTUNITIES_OUTPUT_LINE_FEEDS = 104;

    @ParameterizedTest(name = "{0} is written back to its committed bytes")
    @ValueSource(strings = {CUSTOMERS, ITEMS})
    @DisplayName("The parsed customers and items examples are written back byte for byte")
    void examplesAreWrittenBackByteForByte(String example) throws JsonProcessingException {
        byte[] committed = RamlExampleReader.bytes(example);

        byte[] written = write(RamlExampleReader.read(example));

        assertThat(new String(written, StandardCharsets.UTF_8))
                .isEqualTo(new String(committed, StandardCharsets.UTF_8));
        assertThat(written).isEqualTo(committed);
    }

    /**
     * Compares the written opportunities example with its committed file, in which the raw line feeds
     * inside the two {@code addrText} strings are compared in escaped form (D-045, D-532): each
     * {@code "<raw addrText>"} literal, which occurs exactly once in the file, is replaced by the same
     * value with every line feed written as the two characters {@code \n}. The indentation spaces after
     * each line feed stay part of the value, and every other byte of the file is compared unchanged.
     *
     * @throws JsonProcessingException when Jackson cannot write the value
     */
    @Test
    @DisplayName("The parsed opportunities example is written as its committed bytes with escaped addrText line feeds")
    void opportunitiesExampleIsWrittenWithEscapedAddrTextLineFeeds() throws JsonProcessingException {
        String fileText = new String(RamlExampleReader.bytes(OPPORTUNITIES), StandardCharsets.UTF_8);
        JsonNode opportunities = RamlExampleReader.read(OPPORTUNITIES);

        String expected = fileText;
        for (String address : List.of("shippingAddress", "billingAddress")) {
            String raw = opportunities.get(0).get(address).get("addrText").asText();
            String rawLiteral = "\"" + raw + "\"";

            assertThat(raw).as("%s.addrText holds a raw line feed", address).contains("\n");
            assertThat(occurrences(fileText, rawLiteral))
                    .as("occurrences of the raw %s.addrText literal in %s", address, OPPORTUNITIES)
                    .isEqualTo(1);
            expected = expected.replace(rawLiteral, "\"" + raw.replace("\n", "\\n") + "\"");
        }
        assertThat(expected.chars().filter(c -> c == '\n').count())
                .as("line feeds in the expected opportunities output")
                .isEqualTo(OPPORTUNITIES_OUTPUT_LINE_FEEDS);

        byte[] written = write(opportunities);

        assertThat(new String(written, StandardCharsets.UTF_8)).isEqualTo(expected);
        assertThat(written).isEqualTo(expected.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("An empty array is written as two brackets")
    void emptyArrayIsWrittenAsTwoBrackets() throws JsonProcessingException {
        assertThat(writeJson("[]")).isEqualTo("[]");
    }

    @Test
    @DisplayName("An empty object is written as two braces")
    void emptyObjectIsWrittenAsTwoBraces() throws JsonProcessingException {
        assertThat(writeJson("{}")).isEqualTo("{}");
    }

    @Test
    @DisplayName("Empty containers as member values are written without inner whitespace")
    void emptyContainersAsMemberValuesHaveNoInnerWhitespace() throws JsonProcessingException {
        assertThat(writeJson("{\"a\":[],\"b\":{}}")).isEqualTo("{\n  \"a\": [],\n  \"b\": {}\n}");
    }

    @Test
    @DisplayName("An empty object inside an array is written on its own indented line")
    void emptyObjectInsideArrayIsOnItsOwnLine() throws JsonProcessingException {
        assertThat(writeJson("[{}]")).isEqualTo("[\n  {}\n]");
    }

    @Test
    @DisplayName("A member name and its value are separated by a colon and one space")
    void memberNameAndValueAreSeparatedByColonAndSpace() throws JsonProcessingException {
        String written = writeJson("{\"k\":\"v\"}");

        assertThat(written).isEqualTo("{\n  \"k\": \"v\"\n}");
        assertThat(written).doesNotContain(" : ");
    }

    @Test
    @DisplayName("Array elements are written one per line with a two-space indent")
    void arrayElementsAreWrittenOnePerLine() throws JsonProcessingException {
        assertThat(writeJson("[1,2]")).isEqualTo("[\n  1,\n  2\n]");
    }

    @Test
    @DisplayName("A nested object is indented two more spaces than its parent")
    void nestedObjectIsIndentedTwoMoreSpaces() throws JsonProcessingException {
        assertThat(writeJson("{\"o\":{\"n\":null}}"))
                .isEqualTo("{\n  \"o\": {\n    \"n\": null\n  }\n}");
    }

    @Test
    @DisplayName("Every output ends with its closing bracket or brace and holds no carriage return")
    void everyOutputEndsWithClosingBracketOrBrace() throws JsonProcessingException {
        List<byte[]> outputs = List.of(
                write(RamlExampleReader.read(CUSTOMERS)),
                write(RamlExampleReader.read(ITEMS)),
                write(RamlExampleReader.read(OPPORTUNITIES)),
                write(MAPPER.readTree("[]")),
                write(MAPPER.readTree("{}")),
                write(MAPPER.readTree("{\"a\":[],\"b\":{}}")),
                write(MAPPER.readTree("[{}]")),
                write(MAPPER.readTree("{\"k\":\"v\"}")),
                write(MAPPER.readTree("[1,2]")),
                write(MAPPER.readTree("{\"o\":{\"n\":null}}")));

        assertThat(outputs).allSatisfy(output -> {
            assertThat(output[output.length - 1]).isIn((byte) ']', (byte) '}');
            assertThat(output).doesNotContain((byte) '\r');
        });
    }

    @Test
    @DisplayName("One writer gives identical bytes on every write through a new printer instance")
    void oneWriterGivesIdenticalBytesOnEveryWrite() throws JsonProcessingException {
        DwJsonLayout layout = new DwJsonLayout();
        ObjectWriter writer = MAPPER.writer(layout);
        JsonNode customers = RamlExampleReader.read(CUSTOMERS);

        byte[] first = writer.writeValueAsBytes(customers);
        byte[] second = writer.writeValueAsBytes(customers);

        assertThat(second).isEqualTo(first).isEqualTo(RamlExampleReader.bytes(CUSTOMERS));
        assertThat(layout.createInstance()).isInstanceOf(DwJsonLayout.class).isNotSameAs(layout);
    }

    /**
     * Writes {@code node} through a new {@link DwJsonLayout}.
     *
     * @param node the value to write
     * @return the UTF-8 bytes written
     * @throws JsonProcessingException when Jackson cannot write the value
     */
    private static byte[] write(JsonNode node) throws JsonProcessingException {
        return MAPPER.writer(new DwJsonLayout()).writeValueAsBytes(node);
    }

    /**
     * Parses {@code json} with {@link #MAPPER} and writes it through a new {@link DwJsonLayout}.
     *
     * @param json one JSON document
     * @return the written JSON, decoded as UTF-8
     * @throws JsonProcessingException when Jackson cannot parse or write the value
     */
    private static String writeJson(String json) throws JsonProcessingException {
        return new String(write(MAPPER.readTree(json)), StandardCharsets.UTF_8);
    }

    /**
     * Counts the non-overlapping occurrences of {@code part} in {@code text}.
     *
     * @param text the text searched
     * @param part the non-empty text counted
     * @return the number of occurrences, scanning from the start of {@code text}
     * @throws IllegalArgumentException if {@code part} is empty
     */
    private static int occurrences(String text, String part) {
        if (part.isEmpty()) {
            throw new IllegalArgumentException("part must not be empty");
        }
        int count = 0;
        int from = text.indexOf(part);
        while (from >= 0) {
            count++;
            from = text.indexOf(part, from + part.length());
        }
        return count;
    }
}
