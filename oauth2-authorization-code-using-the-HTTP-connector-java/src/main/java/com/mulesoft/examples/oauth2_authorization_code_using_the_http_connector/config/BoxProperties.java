package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code box.*} keys of {@code application.yml}: the request configuration
 * {@code HTTP_Request_Configuration_HTTPS} and its {@code oauth2:authorization-code-grant-type}
 * [oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml:5-19], the
 * {@code Location} header of {@code boxUserLoginFlow} (:29-30) and the search request of
 * {@code userLoginDoneFlow} (:40-42).
 *
 * <p>{@code @ConfigurationPropertiesScan} on {@code Oauth2AuthorizationCodeUsingTheHttpConnectorApplication}
 * registers the record; it is not a component. The record declares no Java default value: every value comes
 * from the bound keys. An absent {@code String} key binds {@code null}, an absent {@code box.request.port} or
 * {@code box.request.response-timeout} binds {@code 0}, and an absent {@code box.web}, {@code box.request} or
 * {@code box.search} group binds a {@code null} nested record. {@link #customParameters()} is the one
 * component that is never {@code null}. The committed {@code application.yml} holds no real {@code box.id} or
 * {@code box.secret}; the {@code local} and {@code test} profiles supply the values (D-012).
 * The generated {@code equals}, {@code hashCode} and {@code toString} cover every component, and
 * {@code toString} prints {@code secret} (D-495).
 *
 * <p>Every URL component is used as bound, with any scheme, host and port, plain {@code http} included.
 * {@link #localListenerAddresses()} derives the HTTPS listener addresses of the local authorization URL and
 * the redirection URL (D-011, D-495).
 *
 * <pre>{@code
 * String authorize = boxProperties.authorizationUrl();
 * for (BoxProperties.NameValue parameter : boxProperties.customParameters()) {
 *     // box_device_id first, then box_device_name, as application.yml lists them
 * }
 * List<InetSocketAddress> listeners = boxProperties.localListenerAddresses();
 * }</pre>
 *
 * @param id                    {@code box.id}: the client id, the {@code clientId} of the grant type
 *                              [http-authorization-code-web.xml:6] (D-012)
 * @param secret                {@code box.secret}: the client secret, the {@code clientSecret} of the grant
 *                              type [http-authorization-code-web.xml:6] (D-012)
 * @param authorizationUrl      {@code box.authorization-url}: the Box authorization endpoint, the
 *                              {@code authorizationUrl} of {@code oauth2:authorization-request}
 *                              [http-authorization-code-web.xml:7]
 * @param localAuthorizationUrl {@code box.local-authorization-url}: the local URL that starts the
 *                              authorization, the {@code localAuthorizationUrl} of
 *                              {@code oauth2:authorization-request} [http-authorization-code-web.xml:7]
 * @param redirectionUrl        {@code box.redirection-url}: the local URL Box redirects to with the
 *                              authorization code, the {@code redirectionUrl} of the grant type
 *                              [http-authorization-code-web.xml:6]
 * @param tokenUrl              {@code box.token-url}: the Box token endpoint, the {@code tokenUrl} of
 *                              {@code oauth2:token-request} [http-authorization-code-web.xml:13]
 * @param customParameters      {@code box.custom-parameters}: the {@code oauth2:custom-parameter} entries of
 *                              the authorization request [http-authorization-code-web.xml:8-11], in the order
 *                              {@code application.yml} lists them: {@code box_device_id} (:9), then
 *                              {@code box_device_name} (:10). An absent key binds an empty list; the list is
 *                              unmodifiable
 * @param web                   {@code box.web.*}: the login redirect of {@code boxUserLoginFlow}
 *                              [http-authorization-code-web.xml:29-30]
 * @param request               {@code box.request.*}: the Box API address of
 *                              {@code HTTP_Request_Configuration_HTTPS} [http-authorization-code-web.xml:5]
 * @param search                {@code box.search.*}: the search request of {@code userLoginDoneFlow}
 *                              [http-authorization-code-web.xml:40-42]
 */
@ConfigurationProperties("box")
public record BoxProperties(
        String id,
        String secret,
        String authorizationUrl,
        String localAuthorizationUrl,
        String redirectionUrl,
        String tokenUrl,
        List<NameValue> customParameters,
        Web web,
        Request request,
        Search search) {

    /** Key of {@link #localAuthorizationUrl()}, named in the exceptions of {@link #localListenerAddresses()}. */
    private static final String LOCAL_AUTHORIZATION_URL_KEY = "box.local-authorization-url";

    /** Key of {@link #redirectionUrl()}, named in the exceptions of {@link #localListenerAddresses()}. */
    private static final String REDIRECTION_URL_KEY = "box.redirection-url";

    /** Port of an {@code https} URL that names none. */
    private static final int DEFAULT_HTTPS_PORT = 443;

    /** Port of an {@code http} URL that names none. */
    private static final int DEFAULT_HTTP_PORT = 80;

    /** Highest TCP port number. */
    private static final int MAX_PORT = 65535;

    /**
     * Creates the bound properties. A {@code null} {@code customParameters} becomes an empty list, and any other
     * list is held as an unmodifiable copy in its original order. Every other component is held as given.
     *
     * @param id                    the value of {@code box.id}
     * @param secret                the value of {@code box.secret}
     * @param authorizationUrl      the value of {@code box.authorization-url}
     * @param localAuthorizationUrl the value of {@code box.local-authorization-url}
     * @param redirectionUrl        the value of {@code box.redirection-url}
     * @param tokenUrl              the value of {@code box.token-url}
     * @param customParameters      the entries of {@code box.custom-parameters}, or {@code null}
     * @param web                   the {@code box.web.*} group, or {@code null}
     * @param request               the {@code box.request.*} group, or {@code null}
     * @param search                the {@code box.search.*} group, or {@code null}
     * @throws NullPointerException if {@code customParameters} contains a {@code null} entry
     */
    public BoxProperties {
        customParameters = customParameters == null ? List.of() : List.copyOf(customParameters);
    }

    /**
     * Returns the addresses of the HTTPS listeners that serve the local authorization URL and the redirection
     * URL: one {@link InetSocketAddress#createUnresolved unresolved} host and port pair per distinct pair, first
     * that of {@link #localAuthorizationUrl()}, then that of {@link #redirectionUrl()} (D-011, D-495).
     * {@code AdditionalPortsConfig} opens one HTTPS listener per entry, and {@code PortPathGuardFilter} reads
     * their ports.
     *
     * <p>Derived value, not a bound property: the binder sets only the record components. Each call parses the
     * two URLs again with {@link URI#create(String)}. The host is {@link URI#getHost()} as written in the URL;
     * the port is {@link URI#getPort()}, or 443 for scheme {@code https} and
     * 80 for scheme {@code http} (scheme compared case-insensitively) when the URL
     * names no port. A {@code null} URL contributes no entry. With the committed {@code application.yml} both
     * URLs name host {@code localhost} and port {@code 8082}, and the result is that single entry; two URLs
     * with different ports give two entries.
     *
     * @return the distinct listener addresses in that order, as an unmodifiable list; empty when both URLs are
     *         {@code null}
     * @throws IllegalStateException naming {@code box.local-authorization-url} or {@code box.redirection-url}
     *                               when that URL does not parse, names no host, names a port outside
     *                               0-65535, or names no port and has a scheme other than
     *                               {@code http} or {@code https}
     */
    public List<InetSocketAddress> localListenerAddresses() {
        Set<InetSocketAddress> addresses = new LinkedHashSet<>();
        addListenerAddress(addresses, LOCAL_AUTHORIZATION_URL_KEY, localAuthorizationUrl);
        addListenerAddress(addresses, REDIRECTION_URL_KEY, redirectionUrl);
        return List.copyOf(addresses);
    }

    /**
     * Adds the listener address of {@code url} to {@code addresses}; a {@code null} url adds nothing.
     *
     * @param addresses the addresses collected so far, in insertion order
     * @param key       the key {@code url} is bound from
     * @param url       the bound URL, or {@code null}
     */
    private static void addListenerAddress(Set<InetSocketAddress> addresses, String key, String url) {
        if (url != null) {
            addresses.add(listenerAddress(key, url));
        }
    }

    /**
     * Parses {@code url} into an unresolved host and port pair.
     *
     * @param key the key {@code url} is bound from
     * @param url the bound URL
     * @return the unresolved address of {@code url}
     * @throws IllegalStateException naming {@code key} when {@code url} does not parse, names no host, names a
     *                               port outside 0-65535, or names no port and has a scheme other
     *                               than {@code http} or {@code https}
     */
    private static InetSocketAddress listenerAddress(String key, String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(key + " is not a parsable URL: " + url, e);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalStateException(key + " names no host: " + url);
        }
        int port = uri.getPort() == -1 ? defaultPort(key, url, uri.getScheme()) : uri.getPort();
        if (port < 0 || port > MAX_PORT) {
            throw new IllegalStateException(key + " names port " + port + ", outside 0-" + MAX_PORT + ": " + url);
        }
        return InetSocketAddress.createUnresolved(host, port);
    }

    /**
     * Returns the port of a URL that names none: 443 for {@code https} and
     * 80 for {@code http}, compared case-insensitively.
     *
     * @param key    the key the URL is bound from
     * @param url    the bound URL
     * @param scheme the URL's scheme, or {@code null} when it has none
     * @return the scheme's port
     * @throws IllegalStateException naming {@code key} when the scheme is neither {@code http} nor {@code https}
     */
    private static int defaultPort(String key, String url, String scheme) {
        String normalized = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "https" -> DEFAULT_HTTPS_PORT;
            case "http" -> DEFAULT_HTTP_PORT;
            default -> throw new IllegalStateException(
                    key + " names no port and its scheme is neither http nor https: " + url);
        };
    }

    /**
     * One custom parameter of the authorization request: the {@code paramName} and {@code value} of an
     * {@code oauth2:custom-parameter} [http-authorization-code-web.xml:9-10]. Bound from one entry of
     * {@code box.custom-parameters}.
     *
     * @param name  {@code box.custom-parameters[n].name}: the parameter name, for example {@code box_device_id}
     * @param value {@code box.custom-parameters[n].value}: the parameter value, for example {@code 1}
     */
    public record NameValue(String name, String value) {
    }

    /**
     * The login redirect of {@code boxUserLoginFlow} [http-authorization-code-web.xml:29-30].
     *
     * @param loginLocation {@code box.web.login-location}: the {@code Location} header value of the
     *                      {@code 302} response builder [http-authorization-code-web.xml:30]
     */
    public record Web(String loginLocation) {
    }

    /**
     * The Box API address of {@code HTTP_Request_Configuration_HTTPS} [http-authorization-code-web.xml:5].
     *
     * @param protocol        {@code box.request.protocol}: the {@code protocol} attribute, for example
     *                        {@code HTTPS} [http-authorization-code-web.xml:5]
     * @param host            {@code box.request.host}: the {@code host} attribute
     *                        [http-authorization-code-web.xml:5]
     * @param port            {@code box.request.port}: the {@code port} attribute
     *                        [http-authorization-code-web.xml:5]
     * @param basePath        {@code box.request.base-path}: the {@code basePath} attribute, for example
     *                        {@code /2.0} [http-authorization-code-web.xml:5]
     * @param responseTimeout {@code box.request.response-timeout}: the response timeout of the Box API calls, in
     *                        milliseconds; neither the request configuration (:5) nor the search request
     *                        (:40) sets a {@code responseTimeout} attribute
     */
    public record Request(String protocol, String host, int port, String basePath, long responseTimeout) {
    }

    /**
     * The search request of {@code userLoginDoneFlow} [http-authorization-code-web.xml:40-42].
     *
     * @param path  {@code box.search.path}: the {@code path} of the {@code GET} request, for example
     *              {@code /search} [http-authorization-code-web.xml:40]
     * @param query {@code box.search.query}: the value of the {@code query} query parameter, for example
     *              {@code mule} [http-authorization-code-web.xml:42]
     */
    public record Search(String path, String query) {
    }
}
