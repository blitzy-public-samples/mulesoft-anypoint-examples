package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.exception;

/**
 * Signals that a received mail message has no attachment part to read.
 */
public class NoAttachmentException extends RuntimeException {

    /**
     * Creates the exception with the given detail message.
     *
     * @param message the detail message returned by {@link #getMessage()}
     */
    public NoAttachmentException(String message) {
        super(message);
    }
}
