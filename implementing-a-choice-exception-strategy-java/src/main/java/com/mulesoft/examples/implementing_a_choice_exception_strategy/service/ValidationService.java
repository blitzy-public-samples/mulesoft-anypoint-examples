package com.mulesoft.examples.implementing_a_choice_exception_strategy.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.exception.InvalidInputDataException;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.exception.MissingInputDataException;

/**
 * Implements flow {@code choice-error-handlingFlow1} [choice-error-handling.xml:4-48]: parses the
 * request JSON into a map, validates the map, filters the request on its {@code email} value and
 * replies {@code Input data validation passed.}.
 *
 * <p>{@code ValidationController.choiceErrorHandlingFlow1} passes the raw request body to
 * {@link #choiceErrorHandlingFlow1(byte[])}. The two branches of the flow's
 * {@code choice-exception-strategy} [choice-error-handling.xml:15-47] are raised here as
 * {@link InvalidInputDataException} and {@link MissingInputDataException}. Each carries the parsed
 * request map (D-053, D-122), and {@code GlobalExceptionHandler} turns each into its HTTP response.
 * Every other failure propagates unchanged to the default strategy.
 *
 * <p>The class holds no per-request state, and concurrent calls do not affect each other.
 *
 * <pre>{@code
 * ValidationService service = new ValidationService(new InputDataValidator());
 * byte[] valid = "{\"email\":\"aaa@aaa.aa\",\"item units\":10,\"item price per unit\":1}".getBytes();
 * service.choiceErrorHandlingFlow1(valid);         // Optional[Input data validation passed.]
 * byte[] badEmail = "{\"email\":\"bad\",\"item units\":10,\"item price per unit\":1}".getBytes();
 * service.choiceErrorHandlingFlow1(badEmail);      // Optional.empty
 * byte[] noEmail = "{\"item units\":10,\"item price per unit\":1}".getBytes();
 * service.choiceErrorHandlingFlow1(noEmail);       // throws MissingInputDataException
 * byte[] zeroUnits = "{\"email\":\"aaa@aaa.aa\",\"item units\":0,\"item price per unit\":1}".getBytes();
 * service.choiceErrorHandlingFlow1(zeroUnits);     // throws InvalidInputDataException
 * service.choiceErrorHandlingFlow1("not json".getBytes()); // throws UncheckedIOException
 * }</pre>
 */
@Service
public class ValidationService {

    /**
     * Pattern of the {@code regex-filter} "Validate email" [choice-error-handling.xml:13], with the
     * XML entities {@code &amp;} decoded to {@code &}; compiled with no flags.
     */
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9  -]*[a-z0-9])?");

    /** Body parsed in place of a {@code null} request body (D-394). */
    private static final byte[] EMPTY_BODY = new byte[0];

    /** The custom filter "Perform custom validation" [choice-error-handling.xml:9]. */
    private final InputDataValidator inputDataValidator;

    /**
     * Parses the request body for the {@code json-to-object-transformer} "Convert JSON to HashMap"
     * [choice-error-handling.xml:8]; default Jackson configuration, independent of
     * {@code spring.jackson.*} properties.
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Creates the service.
     *
     * @param inputDataValidator the validator run at step :9
     */
    public ValidationService(InputDataValidator inputDataValidator) {
        this.inputDataValidator = inputDataValidator;
    }

    /**
     * Implements flow {@code choice-error-handlingFlow1} [choice-error-handling.xml:4-48]. The steps
     * run in the order of the flow:
     * <ol>
     *   <li>:8 {@code json-to-object-transformer}: the body is parsed into a {@code java.util.HashMap}
     *       whose values are {@code Integer}, {@code Long}, {@code BigInteger}, {@code Double},
     *       {@code String}, {@code Boolean}, {@code LinkedHashMap}, {@code ArrayList} or
     *       {@code null}. A body that is empty, malformed, or a JSON array or scalar raises
     *       {@link UncheckedIOException}; the JSON document {@code null} raises
     *       {@link ClassCastException} before the validator runs.</li>
     *   <li>:9 {@code custom-filter}: {@link InputDataValidator#validate(Map)} runs on the map. An
     *       {@link IllegalArgumentException}, a {@link NumberFormatException} included, is rethrown
     *       as {@link InvalidInputDataException} (branch :16-30). A {@link NullPointerException} is
     *       rethrown as {@link MissingInputDataException} (branch :31-45). Each carries the map and
     *       has the validator's exception as its cause.</li>
     *   <li>:10 {@code set-variable order}: the map is held as {@code order}.</li>
     *   <li>:12 {@code set-payload}: the value of the {@code email} key is taken.</li>
     *   <li>:13 {@code regex-filter}: a {@code null} value, or a value whose string form holds no
     *       case-sensitive match of the pattern anywhere ({@link java.util.regex.Matcher#find()}),
     *       ends the flow with {@link Optional#empty()}. {@code x aaa@aaa.aa y} passes;
     *       {@code bad} and {@code AAA@AAA.AA} are rejected.</li>
     *   <li>:14 {@code set-payload}: the reply {@code Input data validation passed.} is
     *       returned.</li>
     * </ol>
     *
     * @param body the raw request body; {@code null} is parsed as an empty body
     * @return {@code Input data validation passed.}, or {@link Optional#empty()} when the regex filter
     *         rejects the {@code email} value
     * @throws InvalidInputDataException when the validator raises an {@link IllegalArgumentException};
     *         {@link InvalidInputDataException#payload()} is the parsed map
     * @throws MissingInputDataException when the validator raises a {@link NullPointerException};
     *         {@link MissingInputDataException#payload()} is the parsed map
     * @throws UncheckedIOException when the body is neither a JSON object nor the JSON document
     *         {@code null}; its cause is the Jackson {@link IOException}
     * @throws ClassCastException when the body is the JSON document {@code null}
     */
    @SuppressWarnings("unchecked")
    public Optional<String> choiceErrorHandlingFlow1(byte[] body) {
        // json-to-object-transformer "Convert JSON to HashMap" (:8), returnClass java.util.HashMap.
        // A null body is parsed as an empty body (D-394).
        Map<String, Object> map;
        try {
            map = objectMapper.readValue(body == null ? EMPTY_BODY : body, HashMap.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (map == null) {
            throw new ClassCastException("null payload cannot be cast to java.util.Map");
        }

        // custom-filter "Perform custom validation" (:9); exception branches :16-30 and :31-45.
        try {
            inputDataValidator.validate(map);
        } catch (IllegalArgumentException e) {
            throw new InvalidInputDataException(map, e);
        } catch (NullPointerException e) {
            throw new MissingInputDataException(map, e);
        }

        // set-variable "Set order variable" (:10): #[payload].
        Map<String, Object> order = map;

        // set-payload "Set payload to Email" (:12): #[payload['email']].
        Object email = map.get("email");

        // regex-filter "Validate email" (:13): a null value or a value without a match is rejected.
        if (email == null || !EMAIL_PATTERN.matcher(String.valueOf(email)).find()) {
            return Optional.empty();
        }

        // set-payload "Set Payload" (:14).
        return Optional.of("Input data validation passed.");
    }
}
