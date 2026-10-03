package com.mulesoft.examples.addition_using_javascript_transformer.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Business logic of the Mule flow {@code javascript-calculatorFlow1}
 * [addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:4-16]: sums the
 * numeric members of a JSON object or array in JavaScript {@code for-in} order and renders
 * {@code Sum is: <sum>.}; see D-034, D-066 and D-169.
 *
 * <p>The flow's processors map to {@link #javascriptCalculatorFlow1(byte[])} as follows:
 * <ul>
 *   <li>{@code byte-array-to-string-transformer} (:6): the body is decoded as UTF-8, the
 *       application encoding of {@code mule-deploy.properties};</li>
 *   <li>JavaScript {@code scripting:transformer} SC-01 (:7-13): {@link #sum(String)};</li>
 *   <li>{@code logger} (:14): INFO line {@code Sum is: <sum>};</li>
 *   <li>{@code set-payload} (:15): the returned text {@code Sum is: <sum>.}, which the controller
 *       writes with no {@code Content-Type} (D-066).</li>
 * </ul>
 *
 * <p>The sum is rendered with {@link Double#toString(double)}, for example {@code 3.0},
 * {@code 3.5}, {@code 1.0E21}, {@code Infinity} or {@code NaN}. Invalid input raises
 * {@link IllegalArgumentException}, which the project's {@code GlobalExceptionHandler} answers
 * with the default 500.
 *
 * <p>The bean holds no request state. Its {@link ObjectMapper} is configured once at construction
 * and is shared by concurrent calls.
 *
 * <pre>{@code
 * CalculatorService service = new CalculatorService();
 * service.javascriptCalculatorFlow1("{ \"a\" : 1, \"b\": 2 }".getBytes(StandardCharsets.UTF_8));
 * // returns "Sum is: 3.0." and logs "Sum is: 3.0"
 * service.javascriptCalculatorFlow1("[1,2.5]".getBytes(StandardCharsets.UTF_8));
 * // returns "Sum is: 3.5."
 * service.javascriptCalculatorFlow1("{\"a\":\"x\"}".getBytes(StandardCharsets.UTF_8));
 * // throws IllegalArgumentException
 * }</pre>
 */
@Service
public class CalculatorService {

    /** Writes the flow's {@code Sum is: <sum>} INFO line. */
    private static final Logger LOG = LoggerFactory.getLogger(CalculatorService.class);

    /** Largest canonical array index of a JavaScript object key, 2^32 - 2. */
    private static final long MAX_ARRAY_INDEX = 4_294_967_294L;

    /** Number of decimal digits of {@link #MAX_ARRAY_INDEX}. */
    private static final int MAX_ARRAY_INDEX_DIGITS = 10;

    /**
     * JSON reader with Jackson's defaults plus {@link DeserializationFeature#FAIL_ON_TRAILING_TOKENS}:
     * a decimal number reads as a {@code double}, an integer beyond {@code long} as a
     * {@code BigInteger}, and a repeated object key keeps its first position with its last value.
     */
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Runs flow {@code javascript-calculatorFlow1} on a request body.
     *
     * <p>The body is decoded as UTF-8 and summed by {@link #sum(String)}. The sum is logged at INFO
     * as {@code Sum is: <sum>} and returned as {@code Sum is: <sum>.}, with {@code <sum>} rendered
     * by {@link Double#toString(double)}. For example {@code { "a" : 1, "b": 2 }} returns
     * {@code Sum is: 3.0.} and {@code { "a": 3, "b": 4 }} logs {@code Sum is: 7.0}. The response
     * text is written with no {@code Content-Type} (D-066).
     *
     * @param body the raw request body; {@code null} is read as an empty body
     * @return {@code "Sum is: " + Double.toString(sum) + "."}
     * @throws IllegalArgumentException when {@link #sum(String)} rejects the decoded body; it is
     *     propagated unchanged
     */
    public String javascriptCalculatorFlow1(byte[] body) {
        // byte-array-to-string-transformer (:6): UTF-8, the encoding of mule-deploy.properties.
        String text = body == null ? "" : new String(body, StandardCharsets.UTF_8);

        // scripting:transformer SC-01 (:7-13).
        double s = sum(text);
        String rendered = Double.toString(s);

        // logger (:14): "Sum is: #[payload]".
        LOG.info("Sum is: {}", rendered);

        // set-payload (:15): "Sum is: #[payload].".
        return "Sum is: " + rendered + ".";
    }

    /**
     * Sums the numeric members of a JSON object or array, re-implementing the JavaScript of SC-01
     * [addition-using-javascript-transformer/src/main/app/javascript-calculator.xml:7-13]; see D-034
     * and D-169.
     *
     * <p>The payload is read as one strict JSON document (JSON-only SC-01 reading, D-169):
     * <ul>
     *   <li>an array is summed element by element, from index 0 upwards;</li>
     *   <li>an object is summed in JavaScript {@code for-in} order: first the keys that are
     *       canonical array indices ({@code "0"}, {@code "7"}, {@code "10"}, up to
     *       {@code "4294967294"}) in ascending numeric order, then every other key in document
     *       order. {@code {"b":1,"2":10,"1":100}} is summed in the order {@code 1}, {@code 2},
     *       {@code b};</li>
     *   <li>the sum starts at {@code 0.0} and adds each member's {@code double} value, with no
     *       rounding and no overflow check, so an empty object or array sums to {@code 0.0}.</li>
     * </ul>
     *
     * <p>No partial sum is ever returned. {@link IllegalArgumentException}, the only exception
     * thrown, is raised for:
     * <ul>
     *   <li>a {@code null}, empty or blank payload;</li>
     *   <li>malformed JSON, or content after the first JSON value such as {@code {"a":1} {}}; the
     *       Jackson exception is kept as the cause;</li>
     *   <li>a root value that is neither an object nor an array, such as {@code 5}, {@code "x"},
     *       {@code true} or {@code null};</li>
     *   <li>a member or element that is not a number: a string, boolean, {@code null}, object or
     *       array. The message names the offending key or index.</li>
     * </ul>
     *
     * @param payload the decoded request body
     * @return the {@code double} sum of the members
     * @throws IllegalArgumentException when the payload is not a JSON object or array of numbers
     */
    double sum(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Request body is empty");
        }

        // JSON-only SC-01 reading (D-169): a strict JSON document replaces the script's eval.
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Request body is not a single valid JSON document: " + e.getOriginalMessage(), e);
        }
        if (root == null || root.isMissingNode()) {
            throw new IllegalArgumentException("Request body holds no JSON value");
        }

        if (root.isArray()) {
            return sumArray(root);
        }
        if (root.isObject()) {
            return sumObject(root);
        }
        throw new IllegalArgumentException("JSON root is not an object or array: " + root.getNodeType());
    }

    /**
     * Sums the elements of a JSON array in index order.
     *
     * @param array a JSON array node
     * @return the {@code double} sum of the elements
     * @throws IllegalArgumentException naming the index of the first element that is not a number
     */
    private static double sumArray(JsonNode array) {
        double sum = 0.0;
        for (int index = 0; index < array.size(); index++) {
            JsonNode node = array.get(index);
            if (!node.isNumber()) {
                throw notANumber("element at index " + index, node);
            }
            sum += node.doubleValue();
        }
        return sum;
    }

    /**
     * Sums the members of a JSON object in JavaScript {@code for-in} order: canonical array-index
     * keys in ascending numeric order, then the other keys in document order.
     *
     * @param object a JSON object node
     * @return the {@code double} sum of the members
     * @throws IllegalArgumentException naming the key of the first member, in that order, that is
     *     not a number
     */
    private static double sumObject(JsonNode object) {
        TreeMap<Long, String> indexKeys = new TreeMap<>();
        List<String> otherKeys = new ArrayList<>();
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (isArrayIndexKey(name)) {
                indexKeys.put(Long.parseLong(name), name);
            } else {
                otherKeys.add(name);
            }
        }

        List<String> order = new ArrayList<>(indexKeys.size() + otherKeys.size());
        order.addAll(indexKeys.values());
        order.addAll(otherKeys);

        double sum = 0.0;
        for (String name : order) {
            JsonNode node = object.get(name);
            if (!node.isNumber()) {
                throw notANumber("member \"" + name + "\"", node);
            }
            sum += node.doubleValue();
        }
        return sum;
    }

    /**
     * Tells whether an object key is a canonical JavaScript array index: a non-empty string of
     * ASCII digits, without a leading zero unless it is exactly {@code "0"}, whose value is at most
     * 4294967294. {@code "0"}, {@code "7"} and {@code "10"} qualify; {@code "07"}, {@code "-1"},
     * {@code "1.5"}, {@code " 1"} and {@code "4294967295"} do not.
     *
     * @param key an object key
     * @return {@code true} when the key is a canonical array index
     */
    private static boolean isArrayIndexKey(String key) {
        int length = key.length();
        if (length == 0 || length > MAX_ARRAY_INDEX_DIGITS) {
            return false;
        }
        if (length > 1 && key.charAt(0) == '0') {
            return false;
        }
        for (int i = 0; i < length; i++) {
            char c = key.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return Long.parseLong(key) <= MAX_ARRAY_INDEX;
    }

    /**
     * Builds the exception for a member or element that is not a number.
     *
     * @param position the key or index of the value, as it appears in the message
     * @param node the offending value
     * @return the exception to throw
     */
    private static IllegalArgumentException notANumber(String position, JsonNode node) {
        return new IllegalArgumentException(
                "JSON " + position + " is not a number: " + node.getNodeType());
    }
}
