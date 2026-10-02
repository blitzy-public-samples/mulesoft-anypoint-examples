/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.exception;

/**
 * Signals that the stock service has no data for the requested symbol.
 */
public class InvalidStockSymbol extends Exception {

    private static final long serialVersionUID = 2222417175418465035L;

    /**
     * Creates the exception with no message and no cause.
     */
    public InvalidStockSymbol() {
        super();
    }

    /**
     * Creates the exception with a message and a cause.
     *
     * @param message the detail message
     * @param cause the underlying cause
     */
    public InvalidStockSymbol(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates the exception with a message, for example the requested stock symbol.
     *
     * @param message the detail message
     */
    public InvalidStockSymbol(String message) {
        super(message);
    }

    /**
     * Creates the exception with a cause.
     *
     * @param cause the underlying cause
     */
    public InvalidStockSymbol(Throwable cause) {
        super(cause);
    }

}
