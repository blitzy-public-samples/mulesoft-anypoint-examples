package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the reason phrase of the HTTP/1.1 status line on the current Undertow exchange; see D-010.
 *
 * <p>The phrase is stored on the {@code HttpServerExchange} of the request served by the calling thread and is
 * written after the status code when the response is committed. A response for which no phrase is set carries
 * Undertow's standard phrase for its status code, for example {@code HTTP/1.1 200 OK}. This project's copy of the
 * class is its own (D-004).
 *
 * <p>Usage on the request thread, before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}. The class holds no state and is not
 * instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Sets {@code phrase} as the reason phrase of the current Undertow exchange (D-010).
     *
     * <p>The method takes the phrase only. The exchange is taken from the Undertow servlet request context bound
     * to the calling thread, and the phrase is handed to {@code HttpServerExchange.setReasonPhrase} unchanged; the
     * status code is left as it is.
     *
     * <p>Must be called before the response is committed; a phrase set after commit does not reach the status
     * line. Has no effect outside an Undertow request: when the calling thread has no Undertow request context (a
     * plain unit test, a MockMvc request or a thread that serves no Undertow request), the method returns without
     * throwing and without writing a log entry.
     *
     * @param phrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@code "Invalid input data"}
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context != null) {
            context.getExchange().setReasonPhrase(phrase);
        }
    }
}
