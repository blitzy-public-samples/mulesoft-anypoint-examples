/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.implementing_a_choice_exception_strategy.service;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Validates the order map parsed from the request body. Ported from the
 * {@code InputDataValidator} custom filter of the implementing-a-choice-exception-strategy
 * example, {@code choice-error-handling.xml:9} (D-048).
 */
@Component
public class InputDataValidator {

    /**
     * Checks the parsed order in this order: the {@code email} key is present,
     * {@code item units} is at least 1, and {@code item price per unit} is at least 0.
     * An {@code email} key mapped to {@code null} passes the first check.
     *
     * @param payload the order parsed from the request JSON
     * @return {@code true} when every check passes
     * @throws NullPointerException with message {@code email is missing} when the
     *         {@code email} key is absent; also, without a fixed message, when
     *         {@code item units} or {@code item price per unit} is absent or {@code null}
     * @throws IllegalArgumentException with message {@code item units is negative}
     *         when {@code item units} is below 1, or {@code item price per unit is negative}
     *         when {@code item price per unit} is below 0
     * @throws NumberFormatException when the string form of {@code item units} or
     *         {@code item price per unit} is not an {@code int}
     */
    public boolean validate(Map<String, Object> payload) {
        if (!payload.containsKey("email"))
            throw new NullPointerException("email is missing");
        if (Integer.parseInt(payload.get("item units").toString()) < 1)
            throw new IllegalArgumentException("item units is negative");
        if (Integer.parseInt(payload.get("item price per unit").toString()) < 0)
            throw new IllegalArgumentException("item price per unit is negative");

        return true;
    }

}
