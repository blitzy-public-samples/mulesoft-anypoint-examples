package com.mulesoft.examples.implementing_a_choice_exception_strategy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.exception.InvalidInputDataException;
import com.mulesoft.examples.implementing_a_choice_exception_strategy.exception.MissingInputDataException;

/** Unit tests for {@link ValidationService#choiceErrorHandlingFlow1} (choice-error-handling.xml:8-14; D-049, D-053). */
@ExtendWith(MockitoExtension.class)
class ValidationServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PASSED = "Input data validation passed.";

    @Mock
    InputDataValidator validator;

    @Captor
    ArgumentCaptor<Map<String, Object>> payloadCaptor;

    private ValidationService service;

    @BeforeEach
    void setUp() {
        service = new ValidationService(validator);
    }

    private static byte[] bodyWithEmail(String emailJson) {
        return ("{\"email\": " + emailJson
                + ", \"item name\": \"aa\", \"item units\": 10, \"item price per unit\": 1, \"membership\": \"free\"}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] validBody() {
        return bodyWithEmail("\"aaa@aaa.aa\"");
    }

    private static HashMap<String, Object> parsed(byte[] body) throws IOException {
        return MAPPER.readValue(body, new TypeReference<HashMap<String, Object>>() { });
    }

    @Test
    void validBodyReturnsValidationPassed() throws Exception {
        when(validator.validate(anyMap())).thenReturn(true);

        Optional<String> result = service.choiceErrorHandlingFlow1(validBody());

        assertThat(result).isEqualTo(Optional.of(PASSED));
        verify(validator).validate(payloadCaptor.capture());
        assertThat(payloadCaptor.getValue())
                .isInstanceOf(HashMap.class)
                .isEqualTo(parsed(validBody()));
    }

    @Test
    void emailWithoutAtSignIsFiltered() {
        when(validator.validate(anyMap())).thenReturn(true);

        Optional<String> result = service.choiceErrorHandlingFlow1(bodyWithEmail("\"not-an-email\""));

        assertThat(result).isEmpty();
    }

    @Test
    void nullEmailIsFiltered() {
        when(validator.validate(anyMap())).thenReturn(true);

        Optional<String> result = service.choiceErrorHandlingFlow1(bodyWithEmail("null"));

        assertThat(result).isEmpty();
    }

    @Test
    void upperCaseEmailIsFiltered() {
        when(validator.validate(anyMap())).thenReturn(true);

        Optional<String> result = service.choiceErrorHandlingFlow1(bodyWithEmail("\"AAA@AAA.AA\""));

        assertThat(result).isEmpty();
    }

    @Test
    void emailFoundInsideLongerTextPasses() {
        when(validator.validate(anyMap())).thenReturn(true);

        Optional<String> result = service.choiceErrorHandlingFlow1(bodyWithEmail("\"x aaa@aaa.aa y\""));

        assertThat(result).isEqualTo(Optional.of(PASSED));
    }

    @Test
    void validatorIllegalArgumentBecomesInvalidInputDataException() throws Exception {
        IllegalArgumentException thrown = new IllegalArgumentException("x");
        when(validator.validate(anyMap())).thenThrow(thrown);

        InvalidInputDataException e = assertThrows(InvalidInputDataException.class,
                () -> service.choiceErrorHandlingFlow1(validBody()));

        assertThat(e).isInstanceOf(IllegalArgumentException.class);
        assertThat(e.payload()).isEqualTo(parsed(validBody()));
        assertThat(e.getCause()).isSameAs(thrown);
    }

    @Test
    void validatorNumberFormatBecomesInvalidInputDataException() throws Exception {
        NumberFormatException thrown = new NumberFormatException("y");
        when(validator.validate(anyMap())).thenThrow(thrown);

        InvalidInputDataException e = assertThrows(InvalidInputDataException.class,
                () -> service.choiceErrorHandlingFlow1(validBody()));

        assertThat(e).isInstanceOf(IllegalArgumentException.class);
        assertThat(e.payload()).isEqualTo(parsed(validBody()));
        assertThat(e.getCause()).isSameAs(thrown);
    }

    @Test
    void validatorNullPointerBecomesMissingInputDataException() throws Exception {
        NullPointerException thrown = new NullPointerException("z");
        when(validator.validate(anyMap())).thenThrow(thrown);

        MissingInputDataException e = assertThrows(MissingInputDataException.class,
                () -> service.choiceErrorHandlingFlow1(validBody()));

        assertThat(e).isInstanceOf(NullPointerException.class);
        assertThat(e.payload()).isEqualTo(parsed(validBody()));
        assertThat(e.getCause()).isSameAs(thrown);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "", "[]", "5"})
    void malformedOrNonObjectBodyFailsBeforeValidation(String body) {
        Throwable thrown = catchThrowable(() -> service.choiceErrorHandlingFlow1(body.getBytes(StandardCharsets.UTF_8)));

        assertThat(thrown)
                .isNotNull()
                .isNotInstanceOf(IllegalArgumentException.class)
                .isNotInstanceOf(NullPointerException.class)
                .isInstanceOf(UncheckedIOException.class);
        verifyNoInteractions(validator);
    }

    @Test
    void jsonNullDocumentThrowsClassCastException() {
        byte[] body = "null".getBytes(StandardCharsets.UTF_8);

        assertThrows(ClassCastException.class, () -> service.choiceErrorHandlingFlow1(body));

        verifyNoInteractions(validator);
    }
}
