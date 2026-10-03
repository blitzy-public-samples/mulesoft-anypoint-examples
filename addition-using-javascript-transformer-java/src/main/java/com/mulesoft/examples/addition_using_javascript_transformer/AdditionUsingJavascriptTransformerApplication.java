package com.mulesoft.examples.addition_using_javascript_transformer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;

/**
 * Starts the addition-using-javascript-transformer Spring Boot application, the Java form of flow
 * {@code javascript-calculatorFlow1}, on embedded Undertow (D-010). The class is named by the
 * project naming rule (D-003), and component scanning covers this package and its subpackages.
 *
 * <p>Boot's error MVC configuration is excluded: the context registers no error controller, no
 * container error page and no {@code /error} mapping, and {@code /error} is answered 404 with an
 * empty body and no {@code Content-Type}, like every other unmatched path (D-446).
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
public class AdditionUsingJavascriptTransformerApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to
     *             {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(AdditionUsingJavascriptTransformerApplication.class, args);
    }
}
