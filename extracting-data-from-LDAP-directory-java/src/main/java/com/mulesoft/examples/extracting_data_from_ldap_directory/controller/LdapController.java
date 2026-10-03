package com.mulesoft.examples.extracting_data_from_ldap_directory.controller;

import com.mulesoft.examples.extracting_data_from_ldap_directory.service.LdapSearchService;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * HTTP listener of flow {@code ldapFlow1} [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:6-8]:
 * answers every HTTP method on the listener path with the LDIF text of
 * {@link LdapSearchService#ldapFlow1()}, status 200 and no {@code Content-Type} (D-066).
 *
 * <p>The listener path is the value of {@code ldap-flow1.listener.path} in {@code application.yml},
 * default {@code /} (D-139), matched exactly. The port and host are {@code server.port} and
 * {@code server.address}, bound to {@code http.port} and
 * {@code listener.http-listener-configuration.host} (D-065). A request on any other path has no
 * mapping here and is answered by {@code exception/GlobalExceptionHandler.notFound}.
 *
 * <p>Example exchanges, with the directory seeded from {@code ldap.ldif}:
 *
 * <pre>{@code
 * GET  /    -> 200, no Content-Type, body "[dn: cn=mmc,ou=people\n...\n\n, dn: cn=testuser1,ou=people\n...\n\n]"
 * POST /    -> 200, the same body
 * GET  /    (no entry under ou=people) -> 200, Content-Length: 0, no body
 * GET  /x   -> 404 Not Found, body "No listener for endpoint: /x"
 * }</pre>
 */
@Controller
public class LdapController {

    /** Runs the body of flow {@code ldapFlow1}: the search, the per-user log and the list text. */
    private final LdapSearchService service;

    /**
     * Creates the listener over the flow service.
     *
     * @param service the service whose {@link LdapSearchService#ldapFlow1()} returns the reply text
     */
    public LdapController(LdapSearchService service) {
        this.service = service;
    }

    /**
     * Answers a request on the path held by {@code ldap-flow1.listener.path} with the text of
     * {@link LdapSearchService#ldapFlow1()} (D-139, D-642).
     *
     * <p>The mapping has no method restriction: GET, HEAD, POST, PUT, PATCH, DELETE, TRACE and
     * extension methods such as {@code PROPFIND} reach this method. OPTIONS reaches it through
     * {@code ldapFlow1OnOptions}.
     *
     * <p>The request line, headers, parameters and body are not read. The reply text is encoded as
     * UTF-8 and written with status 200 through {@link RawBody#write(HttpServletResponse, int, byte[])},
     * which sets {@code Content-Length} and no {@code Content-Type} (D-066); an empty text gives
     * {@code Content-Length: 0} and no body. An exception raised by the service is not caught here and
     * reaches {@code exception/GlobalExceptionHandler.unexpected}, which answers 500.
     *
     * @param response the HTTP response the reply is written to
     * @throws IOException if the reply cannot be written to the response
     */
    @RequestMapping(path = "${ldap-flow1.listener.path}")
    public void ldapFlow1(HttpServletResponse response) throws IOException {
        RawBody.write(response, 200, service.ldapFlow1().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Runs {@link #ldapFlow1(HttpServletResponse)} for OPTIONS on the path held by
     * {@code ldap-flow1.listener.path}: the client receives the same status, headers and body, and no
     * {@code Allow} header (D-642). A CORS preflight request (OPTIONS with {@code Origin} and
     * {@code Access-Control-Request-Method}) is answered by Spring MVC's CORS handling with 403 and
     * does not reach this method.
     *
     * @param response the HTTP response the reply is written to
     * @throws IOException if the reply cannot be written to the response
     */
    @RequestMapping(path = "${ldap-flow1.listener.path}", method = RequestMethod.OPTIONS)
    private void ldapFlow1OnOptions(HttpServletResponse response) throws IOException {
        ldapFlow1(response);
    }
}
