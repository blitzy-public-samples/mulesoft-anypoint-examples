/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.mapper;

import org.springframework.stereotype.Component;

/**
 * Supplies the {@code statusCode} and {@code reason} values that the {@code choice-exception-strategy}
 * of {@code implementing-a-choice-exception-strategy/src/main/app/choice-error-handling.xml:15-47}
 * sets with four DataWeave {@code dw:set-variable} scripts (DW-05 to DW-08).
 *
 * <p>Each method returns the string literal of one script, unchanged:
 *
 * <ul>
 *   <li>{@link #invalidInputStatus()} and {@link #invalidInputReason()}: the
 *       {@code IllegalArgumentException} branch, {@code choice-error-handling.xml:16-30};</li>
 *   <li>{@link #missingInputStatus()} and {@link #missingInputReason()}: the
 *       {@code NullPointerException} branch, {@code choice-error-handling.xml:31-45}.</li>
 * </ul>
 *
 * <p>The status values are decimal HTTP status codes in text form, for example {@code "400"}. The
 * reason values are HTTP reason phrases for the response status line (D-010), with no trailing
 * punctuation or whitespace.
 *
 * <p>Instances hold no state; every method is side-effect free and safe for concurrent use.
 */
@Component
public class ValidationErrorMapper {

    /**
     * DW-05: status code for the {@code IllegalArgumentException} branch
     * ({@code choice-error-handling.xml:20}).
     *
     * @return {@code "400"}
     */
    public String invalidInputStatus() {
        return "400";
    }

    /**
     * DW-06: reason phrase for the {@code IllegalArgumentException} branch
     * ({@code choice-error-handling.xml:24}).
     *
     * @return {@code "Invalid input data"}
     */
    public String invalidInputReason() {
        return "Invalid input data";
    }

    /**
     * DW-07: status code for the {@code NullPointerException} branch
     * ({@code choice-error-handling.xml:35}).
     *
     * @return {@code "400"}
     */
    public String missingInputStatus() {
        return "400";
    }

    /**
     * DW-08: reason phrase for the {@code NullPointerException} branch
     * ({@code choice-error-handling.xml:39}).
     *
     * @return {@code "Missing input data"}
     */
    public String missingInputReason() {
        return "Missing input data";
    }
}
