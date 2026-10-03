package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the AJAX docroot under {@code ajax-server.path}, the static-resource part of the {@code ajaxServer}
 * connector (D-027).
 *
 * <p>Sources:
 * <ul>
 *   <li>{@code service-orchestration-and-choice-routing/src/main/app/mule-config.xml:3}:
 *       {@code ajax:connector name="ajaxServer" resourceBase="${app.home}/docroot"
 *       serverUrl="http://0.0.0.0:8090/orders"}; the connector serves the docroot folder from a context at the
 *       {@code serverUrl} path.</li>
 *   <li>{@code service-orchestration-and-choice-routing/src/main/app/docroot/index.html:21}: the page loads
 *       {@code mule-resource/js/mule.js} and its CSS and script assets by relative URL; it posts to
 *       {@code /orders/request}, {@code /orders/soap} and {@code /orders/manufacturers} (:119, :160, :166)
 *       through the committed shim {@code docroot/mule-resource/js/mule.js} (D-077).</li>
 * </ul>
 *
 * <p>With {@code P} the configured {@code ajax-server.path} ({@code /orders}) without trailing slashes:
 * <ul>
 *   <li>{@code GET P} answers {@code 302 Found} with {@code Location: P/}, the request's query string appended
 *       when present (D-659).</li>
 *   <li>{@code GET P/} answers {@code 200} with {@code classpath:/docroot/index.html} as {@code text/html}.</li>
 *   <li>{@code GET P/<name>} answers {@code 200} with {@code classpath:/docroot/<name>}, byte-identical and with
 *       the media type {@link org.springframework.http.MediaTypeFactory} assigns to the file name, or
 *       {@code 404} when no such file exists (D-027). Directory names other than {@code P/} answer
 *       {@code 404}.</li>
 *   <li>{@code GET} and {@code HEAD} are the methods served; another method on these paths answers
 *       {@code 405} unless a controller mapping owns the path (D-659).</li>
 *   <li>Nothing outside {@code P} is registered here: the absolute {@code /sh/scripts/shCore.js} that the
 *       {@code tests/} pages reference answers {@code 404} through {@link PortPathGuardFilter} (D-011, D-027).</li>
 * </ul>
 *
 * <p>Ports: this configurer is port-agnostic. {@link PortPathGuardFilter} admits {@code P} and {@code P/**}
 * on {@code ajax-server.port} (8090) only and answers {@code 404} or {@code 405} for them on the four
 * listener ports (D-011). The SOAP handler mapping, ordered {@code HIGHEST_PRECEDENCE}, takes
 * {@code POST /orders} on {@code listener.http-listener-configuration3.port} (1080) ahead of these handlers
 * (D-011, D-028).
 *
 * <p>Precedence on the AJAX port keeps Spring MVC's default handler-mapping orders: controller request
 * mappings (order {@code 0}, the {@code P/request}, {@code P/soap} and {@code P/manufacturers} channels), then
 * the view controllers registered here (order {@code 1}), then the resource handler registered here (lowest
 * precedence). A {@code POST P/request} therefore reaches its channel controller, never the docroot (D-027).
 *
 * <p>Usage: {@code http://localhost:8090/orders} is the entry URL; the browser follows the redirect to
 * {@code http://localhost:8090/orders/} and resolves the page's relative assets against it.
 */
@Configuration(proxyBeanMethods = false)
public class DocrootConfig implements WebMvcConfigurer {

    /** Property key of the docroot path; its value is shared with {@link PortPathGuardFilter}. */
    private static final String AJAX_PATH_KEY = "ajax-server.path";

    /** Classpath folder holding the byte-identical docroot copy and the committed shim (D-027, D-077). */
    private static final String DOCROOT_LOCATION = "classpath:/docroot/";

    /** Page answered for the trailing-slash entry path. */
    private static final String WELCOME_FILE = "index.html";

    /** Path separator and root path. */
    private static final String SLASH = "/";

    /** Logger of the handler registrations, at debug level. */
    private static final Logger log = LoggerFactory.getLogger(DocrootConfig.class);

    /** Docroot path without trailing slashes, e.g. {@code /orders}; {@code /} when the path is the root. */
    private final String root;

    /** Docroot path with exactly one trailing slash, e.g. {@code /orders/}. */
    private final String prefix;

    /**
     * Binds the docroot path.
     *
     * @param path value of {@code ajax-server.path}; trailing slashes are removed, as {@link PortPathGuardFilter}
     *             removes them
     * @throws IllegalStateException naming {@code ajax-server.path} when the value is {@code null}, does not start
     *                               with {@code /} or contains whitespace
     */
    public DocrootConfig(@Value("${" + AJAX_PATH_KEY + "}") String path) {
        this.root = root(path);
        this.prefix = SLASH.equals(root) ? SLASH : root + SLASH;
    }

    /**
     * Registers the entry-path view controllers (D-027).
     *
     * <p>{@code GET P} answers {@code 302 Found} with the context-relative {@code Location: P/}, written as set
     * and carrying the request's query string when present (D-659). {@code GET P/} forwards to
     * {@code P/index.html}, which the resource handler answers. A root path {@code /} has no redirect.
     *
     * @param registry the view controller registry of the MVC configuration
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        if (!SLASH.equals(root)) {
            registry.addRedirectViewController(root, prefix)
                    .setStatusCode(HttpStatus.FOUND)
                    .setKeepQueryParams(true);
        }
        registry.addViewController(prefix).setViewName("forward:" + prefix + WELCOME_FILE);
        log.debug("Docroot entry {} redirects to {}, which forwards to {}{}", root, prefix, prefix, WELCOME_FILE);
    }

    /**
     * Registers {@code P/**} on {@code classpath:/docroot/} with no resource chain, transformer, versioning or
     * cache-control setting, and no further location; files are answered byte-identical (D-027).
     *
     * @param registry the resource handler registry of the MVC configuration
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(prefix + "**").addResourceLocations(DOCROOT_LOCATION);
        log.debug("Docroot {} served under {}**", DOCROOT_LOCATION, prefix);
    }

    /**
     * Validates the configured path and removes its trailing slashes.
     *
     * @param path value of {@code ajax-server.path}
     * @return the path without trailing slashes; {@code /} for the root path
     * @throws IllegalStateException naming {@code ajax-server.path} when the value is {@code null}, does not start
     *                               with {@code /} or contains whitespace
     */
    private static String root(String path) {
        if (path == null || !path.startsWith(SLASH) || path.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException(AJAX_PATH_KEY + " must start with '/' and contain no whitespace, was '"
                    + path + "'");
        }
        String root = path;
        while (root.length() > 1 && root.endsWith(SLASH)) {
            root = root.substring(0, root.length() - 1);
        }
        return root;
    }
}
