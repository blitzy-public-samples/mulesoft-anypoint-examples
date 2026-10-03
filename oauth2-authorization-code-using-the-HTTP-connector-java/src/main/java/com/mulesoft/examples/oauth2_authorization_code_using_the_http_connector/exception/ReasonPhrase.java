package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the current Undertow exchange (D-010).
 * Does nothing outside a request.
 *
 * <p>This is the project's own copy of the helper (D-004). The class holds no state and is not
 * instantiable. A response for which no phrase is set carries Undertow's standard phrase for its
 * status code, for example {@code HTTP/1.1 302 Found}.
 *
 * <p>Usage in a handler, before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Writes {@code phrase} as the reason phrase of the status line of the response to the request
     * handled on the calling thread, through {@code HttpServerExchange.setReasonPhrase} (D-010).
     * Leaves the status code unchanged. Does nothing outside a request, that is when the calling
     * thread has no Undertow servlet request context, for example in a plain unit test or a MockMvc
     * request (D-218).
     *
     * <p>Call before the response is committed; a call after commit leaves the status line already
     * sent unchanged. The status code may be set before or after the call, and setting the status
     * leaves the phrase unchanged (D-218).
     *
     * @param phrase the text written verbatim after the status code on the status line, for example
     *     {@code "Invalid input data"}; {@code null} restores Undertow's standard phrase for the
     *     status code
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context == null) {
            return;
        }
        context.getExchange().setReasonPhrase(phrase);
    }
}
