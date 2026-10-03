package com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Reason phrase on the HTTP/1.1 status line (D-010).
 *
 * <p>{@link #set(String)} stores a reason phrase on the Undertow exchange of the request that the
 * calling thread is handling. Undertow writes that phrase after the status code when the response
 * is committed, for example {@code HTTP/1.1 400 Invalid input data}. A later change of the status
 * code keeps the phrase.
 *
 * <p>Usage in a handler that answers 400:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 */
public final class ReasonPhrase {

    /** No instances; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the current HTTP response's status line (D-010). Leaves the status
     * code unchanged.
     *
     * <p>Precondition: the calling thread handles an Undertow servlet request, and the response is
     * not yet committed. A phrase set after the response is committed does not reach the status
     * line.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Invalid input data"}; {@code null} restores the standard phrase of the status code
     * @throws IllegalStateException when the calling thread handles no Undertow servlet request
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
