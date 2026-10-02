/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.jms_message_rollback_and_redelivery.exception;

/**
 * Checked exception thrown by the redelivery flow for every delivery other than the fifth.
 *
 * <p>The listener rolls back the transacted session when this exception is in the cause chain,
 * and the broker then redelivers the message. {@link #getError()} returns the stored error text:
 * {@code "test"} for the no-argument constructor, otherwise the text passed to the constructor.
 */
public class MyException extends Exception {

    /** Serialization version identifier, {@code 1123544523432L} as in {@code org.exceptions.MyException}. */
    private static final long serialVersionUID = 1123544523432L;

    /** Error text returned by {@link #getError()}. */
    String mistake;

    /**
     * Creates the exception with no detail message and the error text {@code "test"}.
     */
    public MyException() {
        super();
        mistake = "test";
    }

    /**
     * Creates the exception with {@code err} as both the detail message and the error text.
     *
     * @param err the error text
     */
    public MyException(String err) {
        super(err);
        mistake = err;
    }

    /**
     * Returns the stored error text.
     *
     * @return {@code "test"} when created without arguments, otherwise the constructor argument
     */
    public String getError() {
        return mistake;
    }
}
