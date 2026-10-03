package com.mulesoft.examples.content_based_routing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Spring Boot entry point of the content-based-routing port. It starts the application whose one
 * Mule configuration is listed as {@code config.resources=content-based-routing.xml} in
 * {@code content-based-routing/src/main/app/mule-deploy.properties} (line 5).
 *
 * <p>The application runs on embedded Undertow with one HTTP listener, the
 * {@code HTTP_Listener_Configuration} of {@code content-based-routing.xml} (line 3), opened on
 * {@code server.address}:{@code server.port} from {@code application.yml}: {@code 0.0.0.0:8081} by
 * default (D-065). Component scanning covers this package and its subpackages.
 *
 * <p>{@code @ConfigurationPropertiesScan} registers every {@code @ConfigurationProperties} record in
 * this package and its subpackages, among them {@code config.HttpListenerProperties}, bound to the
 * keys under {@code listener.http-listener-configuration}.
 *
 * <p>Boot's error MVC auto-configuration is excluded: the context registers no error controller, no
 * container error page and no {@code /error} mapping.
 *
 * <pre>{@code
 * java -jar target/content-based-routing-java-1.0.0.jar --http.port=8081
 * }</pre>
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
@ConfigurationPropertiesScan
public class ContentBasedRoutingApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(ContentBasedRoutingApplication.class, args);
    }
}
