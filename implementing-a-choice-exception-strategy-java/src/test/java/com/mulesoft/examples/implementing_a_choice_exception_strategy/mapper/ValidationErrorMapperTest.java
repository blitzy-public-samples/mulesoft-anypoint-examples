package com.mulesoft.examples.implementing_a_choice_exception_strategy.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link ValidationErrorMapper}: the DW-05 to DW-08 values of
 * {@code implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml}.
 *
 * <p>Each test calls one mapper method directly, with no Spring application context, and asserts
 * exact string equality with the literal of its {@code dw:set-variable} script (D-049).
 */
public class ValidationErrorMapperTest {

    /** The mapper under test. */
    private final ValidationErrorMapper mapper = new ValidationErrorMapper();

    /** DW-05: {@code statusCode} {@code "400"} of the {@code IllegalArgumentException} branch. */
    @Test
    public void invalidInputStatus() {
        assertThat(mapper.invalidInputStatus()).isEqualTo("400");
    }

    /** DW-06: {@code reason} {@code "Invalid input data"} of the {@code IllegalArgumentException} branch. */
    @Test
    public void invalidInputReason() {
        assertThat(mapper.invalidInputReason()).isEqualTo("Invalid input data");
    }

    /** DW-07: {@code statusCode} {@code "400"} of the {@code NullPointerException} branch. */
    @Test
    public void missingInputStatus() {
        assertThat(mapper.missingInputStatus()).isEqualTo("400");
    }

    /** DW-08: {@code reason} {@code "Missing input data"} of the {@code NullPointerException} branch. */
    @Test
    public void missingInputReason() {
        assertThat(mapper.missingInputReason()).isEqualTo("Missing input data");
    }
}
