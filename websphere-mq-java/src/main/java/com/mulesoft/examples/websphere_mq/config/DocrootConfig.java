package com.mulesoft.examples.websphere_mq.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Serves the {@code docroot} classpath folder under the configured AJAX server path, redirects the bare path to
 * its trailing-slash form and answers that form with {@code index.html} (D-027).
 *
 * <p>For the path {@code P} read from {@code ajax-server.path}:
 * <ul>
 *   <li>{@code GET P} answers 302 with a {@code Location} ending in {@code P/};</li>
 *   <li>{@code GET P/} answers 200 with {@code docroot/index.html} as {@code text/html};</li>
 *   <li>{@code GET P/name} answers 200 with {@code docroot/name}, its media type taken from the file extension,
 *       or 404 when the file does not exist;</li>
 *   <li>a request outside {@code P} is not served by this class.</li>
 * </ul>
 *
 * <p>Controller handlers mapped under {@code P} take precedence over the view controllers and the resource
 * handler registered here.
 */
@Configuration
public class DocrootConfig implements WebMvcConfigurer {

    private final String path;

    /**
     * Creates the configuration for the given AJAX server path.
     *
     * @param path the AJAX server path, {@code ajax-server.path}, without a trailing slash
     */
    public DocrootConfig(@Value("${ajax-server.path}") String path) {
        this.path = path;
    }

    /**
     * Maps every request below the AJAX server path to the {@code docroot} classpath folder.
     *
     * @param registry the resource handler registry
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(path + "/**").addResourceLocations("classpath:/docroot/");
    }

    /**
     * Redirects the bare AJAX server path to its trailing-slash form and forwards that form to
     * {@code index.html}.
     *
     * @param registry the view controller registry
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController(path, path + "/");
        registry.addViewController(path + "/").setViewName("forward:" + path + "/index.html");
    }
}
