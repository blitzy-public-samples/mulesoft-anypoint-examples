package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.mapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.springframework.stereotype.Component;

/**
 * Converts a JSON employee list to the XML document of DW-34
 * [upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:9-26], implemented by hand in
 * Java (D-034, D-270).
 *
 * <p>Input: one JSON document whose root is an object; its encoding (UTF-8, UTF-16 or UTF-32) is detected
 * from the bytes. Output: the UTF-8 bytes of the declaration {@code <?xml version='1.0' encoding='UTF-8'?>}
 * (single quotes) followed by one {@code employees} element. Each element starts on its own line, each
 * nesting level adds one tab of indentation, lines end with LF and the last line, {@code </employees>} or
 * {@code <employees/>}, has no line feed after it. Element shape:
 *
 * <ul>
 *   <li>{@code employees} holds one {@code employee} per item of {@code employees.employee}, in input
 *       order;</li>
 *   <li>each {@code employee} holds exactly {@code name}, {@code lastName} and {@code addresses}, in that
 *       order, whatever the key order of the input item;</li>
 *   <li>{@code addresses} holds one {@code address} per item of the item's {@code addresses.address}, in
 *       input order, each written as the input item stands: an object item keeps every field in input
 *       order, duplicate keys included.</li>
 * </ul>
 *
 * <p>Selection: a key is read from its first occurrence in an object, and a JSON {@code null} counts as
 * absent. An absent {@code employees}, or one that is not an object, and an absent {@code employee} list
 * give {@code <employees/>}. An absent {@code address} list, or an {@code addresses} value that is not an
 * object, gives {@code <addresses/>}. An {@code employee} item that is not an object gives
 * {@code <name/>}, {@code <lastName/>} and {@code <addresses/>}.
 *
 * <p>Value forms, for an element named {@code k}:
 *
 * <ul>
 *   <li>{@code null} or an absent {@code name} or {@code lastName} gives {@code <k/>};</li>
 *   <li>the empty string gives {@code <k></k>};</li>
 *   <li>any other string, a number in its JSON text (for example {@code 111}, {@code 1.10}, {@code 1e3})
 *       and a boolean ({@code true}, {@code false}) give {@code <k>text</k>} on one line, with {@code &},
 *       {@code <} and {@code >} written as {@code &amp;}, {@code &lt;} and {@code &gt;} and every other
 *       character, quotes and control characters included, unchanged;</li>
 *   <li>an object gives {@code <k>}, its fields one level deeper and {@code </k>}, or {@code <k/>} when
 *       none of its fields writes an element, as for {@code {}};</li>
 *   <li>an array writes each of its items under the same name at the same level: nested arrays flatten,
 *       and an empty array writes nothing.</li>
 * </ul>
 *
 * <p>JSON keys are written verbatim as element names. The input
 * {@code {"employees":{"employee":[{"lastName":"Doe","name":"A&B<c>","addresses":{"address":[{}]}}]}}}
 * gives, with tabs as indentation:
 *
 * <pre>{@code
 * <?xml version='1.0' encoding='UTF-8'?>
 * <employees>
 * 	<employee>
 * 		<name>A&amp;B&lt;c&gt;</name>
 * 		<lastName>Doe</lastName>
 * 		<addresses>
 * 			<address/>
 * 		</addresses>
 * 	</employee>
 * </employees>
 * }</pre>
 *
 * <p>Errors, each an {@link IllegalArgumentException}:
 *
 * <ul>
 *   <li>{@code Request body is empty}: a {@code null} array, zero bytes or whitespace only;</li>
 *   <li>{@code Request body is not valid JSON}: malformed JSON, including text after the root value that is
 *       no JSON value (for example {@code {}x}), or a violated Jackson stream-read constraint; the parser
 *       exception is the cause;</li>
 *   <li>{@code Unexpected content after the JSON root value}: a second root value, as in {@code {} {}};</li>
 *   <li>{@code JSON root is not an object}: an array, scalar or {@code null} root;</li>
 *   <li>{@code employees.employee is not an array}: an {@code employee} value that is an object, string,
 *       number or boolean;</li>
 *   <li>{@code addresses.address is not an array}: an {@code address} value that is an object, string,
 *       number or boolean.</li>
 * </ul>
 *
 * <p>Instances hold no state; {@link #toXml(byte[])} performs no I/O and is safe for concurrent use.
 */
@Component
public class EmployeesXmlMapper {

    /** First line of every document. */
    private static final String XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    private static final String EMPLOYEES = "employees";
    private static final String EMPLOYEE = "employee";
    private static final String NAME = "name";
    private static final String LAST_NAME = "lastName";
    private static final String ADDRESSES = "addresses";
    private static final String ADDRESS = "address";

    private static final String EMPTY_BODY = "Request body is empty";
    private static final String INVALID_JSON = "Request body is not valid JSON";
    private static final String TRAILING_CONTENT = "Unexpected content after the JSON root value";
    private static final String ROOT_NOT_OBJECT = "JSON root is not an object";
    private static final String EMPLOYEE_NOT_ARRAY = "employees.employee is not an array";
    private static final String ADDRESS_NOT_ARRAY = "addresses.address is not an array";

    /** Items of an absent or {@code null} list. */
    private static final Object[] NO_ITEMS = new Object[0];

    /** Jackson streaming factory with its default features and default stream-read constraints. */
    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    /**
     * Converts the JSON request body to the DW-34 XML document described on this class.
     *
     * @param json the request body: JSON whose root is an object, in UTF-8, UTF-16 or UTF-32
     * @return the UTF-8 bytes of the XML document, ending with {@code </employees>} or
     *     {@code <employees/>} and no line feed
     * @throws IllegalArgumentException with one of the messages listed on this class, when the body is
     *     empty or not valid JSON, holds more than one root value, has a root that is not an object, or
     *     holds an {@code employee} or {@code address} value that is neither an array nor {@code null}
     */
    public byte[] toXml(byte[] json) {
        List<Map.Entry<String, Object>> root = parse(json);
        StringBuilder xml = new StringBuilder(XML_DECLARATION.length() + json.length + 1);
        xml.append(XML_DECLARATION).append('\n');
        writeElement(xml, EMPLOYEES, employees(root), 0);
        xml.setLength(xml.length() - 1);
        return xml.toString().getBytes(StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------------------------------------
    // Parsing into the ordered value model: object = List of entries, array = Object[], string and
    // number = their JSON text, boolean = "true"/"false", null = null.
    // ---------------------------------------------------------------------------------------------

    /** Reads the whole document and returns its root object; every failure is an IllegalArgumentException. */
    private static List<Map.Entry<String, Object>> parse(byte[] json) {
        // A null array is read as an empty body (D-270).
        if (json == null) {
            throw new IllegalArgumentException(EMPTY_BODY);
        }
        Object root;
        try (JsonParser parser = JSON_FACTORY.createParser(json)) {
            JsonToken first = parser.nextToken();
            if (first == null) {
                throw new IllegalArgumentException(EMPTY_BODY);
            }
            root = readValue(parser, first);
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException(TRAILING_CONTENT);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(INVALID_JSON, e);
        }
        List<Map.Entry<String, Object>> object = asObject(root);
        if (object == null) {
            throw new IllegalArgumentException(ROOT_NOT_OBJECT);
        }
        return object;
    }

    /**
     * Reads the value that starts at {@code token}. Objects keep their fields in input order, duplicate
     * keys included; numbers keep their JSON text.
     */
    private static Object readValue(JsonParser parser, JsonToken token) throws IOException {
        if (token == JsonToken.START_OBJECT) {
            List<Map.Entry<String, Object>> fields = new ArrayList<>();
            JsonToken next = parser.nextToken();
            while (next != JsonToken.END_OBJECT) {
                if (next != JsonToken.FIELD_NAME) {
                    throw new IllegalArgumentException(INVALID_JSON);
                }
                String key = parser.currentName();
                fields.add(entry(key, readValue(parser, parser.nextToken())));
                next = parser.nextToken();
            }
            return fields;
        }
        if (token == JsonToken.START_ARRAY) {
            List<Object> items = new ArrayList<>();
            JsonToken next = parser.nextToken();
            while (next != JsonToken.END_ARRAY) {
                items.add(readValue(parser, next));
                next = parser.nextToken();
            }
            return items.toArray();
        }
        if (token == JsonToken.VALUE_STRING
                || token == JsonToken.VALUE_NUMBER_INT
                || token == JsonToken.VALUE_NUMBER_FLOAT) {
            return parser.getText();
        }
        if (token == JsonToken.VALUE_TRUE) {
            return "true";
        }
        if (token == JsonToken.VALUE_FALSE) {
            return "false";
        }
        if (token == JsonToken.VALUE_NULL) {
            return null;
        }
        throw new IllegalArgumentException(INVALID_JSON);
    }

    // ---------------------------------------------------------------------------------------------
    // Output tree, built in the same value model.
    // ---------------------------------------------------------------------------------------------

    /** Fields of the output {@code employees} element: one {@code employee} entry per input item. */
    private static List<Map.Entry<String, Object>> employees(List<Map.Entry<String, Object>> root) {
        // An employees value that is not an object reads as absent (D-270).
        List<Map.Entry<String, Object>> employees = asObject(valueOf(first(root, EMPLOYEES)));
        List<Map.Entry<String, Object>> output = new ArrayList<>();
        for (Object item : items(valueOf(first(employees, EMPLOYEE)), EMPLOYEE_NOT_ARRAY)) {
            output.add(entry(EMPLOYEE, employee(asObject(item))));
        }
        return output;
    }

    /**
     * Fields of one output {@code employee}: {@code name}, {@code lastName} and {@code addresses}, in that
     * order. A {@code null} item, which stands for an input item that is not an object, gives
     * {@code null}, {@code null} and an empty {@code addresses} object (D-270).
     */
    private static List<Map.Entry<String, Object>> employee(List<Map.Entry<String, Object>> item) {
        List<Map.Entry<String, Object>> output = new ArrayList<>(3);
        output.add(entry(NAME, valueOf(first(item, NAME))));
        output.add(entry(LAST_NAME, valueOf(first(item, LAST_NAME))));
        output.add(entry(ADDRESSES, addresses(asObject(valueOf(first(item, ADDRESSES))))));
        return output;
    }

    /** Fields of one output {@code addresses}: one {@code address} entry per input item, carried as is. */
    private static List<Map.Entry<String, Object>> addresses(List<Map.Entry<String, Object>> addresses) {
        List<Map.Entry<String, Object>> output = new ArrayList<>();
        for (Object address : items(valueOf(first(addresses, ADDRESS)), ADDRESS_NOT_ARRAY)) {
            output.add(entry(ADDRESS, address));
        }
        return output;
    }

    /**
     * The first field of {@code object} named {@code key}, or {@code null} when the key is absent or
     * {@code object} is {@code null}; a present field may hold a {@code null} value (D-270).
     */
    private static Map.Entry<String, Object> first(List<Map.Entry<String, Object>> object, String key) {
        if (object != null) {
            for (Map.Entry<String, Object> field : object) {
                if (key.equals(field.getKey())) {
                    return field;
                }
            }
        }
        return null;
    }

    /** The value of {@code field}; an absent field and a JSON {@code null} both give {@code null}. */
    private static Object valueOf(Map.Entry<String, Object> field) {
        return field == null ? null : field.getValue();
    }

    /** The items of a list value: none for {@code null}, the array's items for an array, else an error. */
    private static Object[] items(Object value, String notArrayMessage) {
        if (value == null) {
            return NO_ITEMS;
        }
        if (value instanceof Object[]) {
            return (Object[]) value;
        }
        throw new IllegalArgumentException(notArrayMessage);
    }

    /** {@code value} as an object of the value model, or {@code null} when it is anything else. */
    @SuppressWarnings("unchecked")
    private static List<Map.Entry<String, Object>> asObject(Object value) {
        return value instanceof List ? (List<Map.Entry<String, Object>>) value : null;
    }

    /** A field of the value model; the value may be {@code null}. */
    private static Map.Entry<String, Object> entry(String key, Object value) {
        return new AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    // ---------------------------------------------------------------------------------------------
    // Rendering in the DW 1.0 XML writer layout: tab indentation, one element per line (D-270).
    // ---------------------------------------------------------------------------------------------

    /**
     * Appends the element {@code key} holding {@code value} at {@code depth} tabs, each line ending with LF.
     * An array appends one element per item under the same key and depth.
     */
    private static void writeElement(StringBuilder xml, String key, Object value, int depth) {
        if (value instanceof Object[]) {
            for (Object item : (Object[]) value) {
                writeElement(xml, key, item, depth);
            }
            return;
        }
        indent(xml, depth);
        List<Map.Entry<String, Object>> object = asObject(value);
        if (value == null || (object != null && !writesAnyElement(object))) {
            xml.append('<').append(key).append("/>\n");
        } else if (object != null) {
            xml.append('<').append(key).append(">\n");
            for (Map.Entry<String, Object> field : object) {
                writeElement(xml, field.getKey(), field.getValue(), depth + 1);
            }
            indent(xml, depth);
            xml.append("</").append(key).append(">\n");
        } else {
            xml.append('<').append(key).append('>');
            appendEscaped(xml, (String) value);
            xml.append("</").append(key).append(">\n");
        }
    }

    /** True when at least one field of {@code object} writes an element. */
    private static boolean writesAnyElement(List<Map.Entry<String, Object>> object) {
        for (Map.Entry<String, Object> field : object) {
            if (writesElement(field.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** False only for an array whose items, at every nesting level, write no element, as {@code [[]]}. */
    private static boolean writesElement(Object value) {
        if (value instanceof Object[]) {
            for (Object item : (Object[]) value) {
                if (writesElement(item)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** Appends {@code depth} tab characters. */
    private static void indent(StringBuilder xml, int depth) {
        for (int level = 0; level < depth; level++) {
            xml.append('\t');
        }
    }

    /** Appends {@code text}, writing {@code &}, {@code <} and {@code >} as entities and all else as is. */
    private static void appendEscaped(StringBuilder xml, String text) {
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '&') {
                xml.append("&amp;");
            } else if (c == '<') {
                xml.append("&lt;");
            } else if (c == '>') {
                xml.append("&gt;");
            } else {
                xml.append(c);
            }
        }
    }
}
