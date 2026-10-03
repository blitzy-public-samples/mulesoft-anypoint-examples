package com.mulesoft.examples.implementing_a_choice_exception_strategy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link InputDataValidator} (AAP 0.6.6; JaCoCo service rule D-049). */
class InputDataValidatorTest {

    private final InputDataValidator validator = new InputDataValidator();

    private static Map<String, Object> validPayload() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", "aaa@aaa.aa");
        payload.put("item name", "aa");
        payload.put("item units", Integer.valueOf(10));
        payload.put("item price per unit", Integer.valueOf(1));
        payload.put("membership", "free");
        return payload;
    }

    @Test
    void validPayloadIsAccepted() {
        Map<String, Object> payload = validPayload();

        assertThat(validator.validate(payload)).isTrue();
    }

    @Test
    void minimumUnitsAndZeroPriceAreAccepted() {
        Map<String, Object> payload = validPayload();
        payload.put("item units", Integer.valueOf(1));
        payload.put("item price per unit", Integer.valueOf(0));

        assertThat(validator.validate(payload)).isTrue();
    }

    @Test
    void missingEmailKeyThrowsNullPointerException() {
        Map<String, Object> payload = validPayload();
        payload.remove("email");

        assertThatThrownBy(() -> validator.validate(payload))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("email is missing");
    }

    @Test
    void nullEmailValueIsAccepted() {
        Map<String, Object> payload = validPayload();
        payload.put("email", null);

        assertThat(payload).containsKey("email");
        assertThat(validator.validate(payload)).isTrue();
    }

    @Test
    void zeroUnitsThrowsIllegalArgumentException() {
        Map<String, Object> payload = validPayload();
        payload.put("item units", Integer.valueOf(0));

        assertThatThrownBy(() -> validator.validate(payload))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("item units is negative");
    }

    @Test
    void negativePriceThrowsIllegalArgumentException() {
        Map<String, Object> payload = validPayload();
        payload.put("item price per unit", Integer.valueOf(-1));

        assertThatThrownBy(() -> validator.validate(payload))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("item price per unit is negative");
    }

    @Test
    void nonNumericUnitsThrowsNumberFormatException() {
        Map<String, Object> payload = validPayload();
        payload.put("item units", "abc");

        assertThatThrownBy(() -> validator.validate(payload))
                .isExactlyInstanceOf(NumberFormatException.class);
    }

    @Test
    void nonNumericPriceThrowsNumberFormatException() {
        Map<String, Object> payload = validPayload();
        payload.put("item price per unit", "abc");

        assertThatThrownBy(() -> validator.validate(payload))
                .isExactlyInstanceOf(NumberFormatException.class);
    }

    @Test
    void missingUnitsKeyThrowsNullPointerException() {
        Map<String, Object> payload = validPayload();
        payload.remove("item units");

        assertThatThrownBy(() -> validator.validate(payload))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void missingPriceKeyThrowsNullPointerException() {
        Map<String, Object> payload = validPayload();
        payload.remove("item price per unit");

        assertThatThrownBy(() -> validator.validate(payload))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void emailCheckRunsBeforeUnitsCheck() {
        Map<String, Object> payload = validPayload();
        payload.remove("email");
        payload.put("item units", Integer.valueOf(0));

        assertThatThrownBy(() -> validator.validate(payload))
                .isExactlyInstanceOf(NullPointerException.class)
                .hasMessage("email is missing");
    }

    @Test
    void unitsCheckRunsBeforePriceCheck() {
        Map<String, Object> payload = validPayload();
        payload.put("item units", Integer.valueOf(0));
        payload.put("item price per unit", Integer.valueOf(-1));

        assertThatThrownBy(() -> validator.validate(payload))
                .isExactlyInstanceOf(IllegalArgumentException.class)
                .hasMessage("item units is negative");
    }

    @Test
    void numericStringValuesAreAccepted() {
        Map<String, Object> payload = validPayload();
        payload.put("item units", "10");
        payload.put("item price per unit", "1");

        assertThat(validator.validate(payload)).isTrue();
    }
}
