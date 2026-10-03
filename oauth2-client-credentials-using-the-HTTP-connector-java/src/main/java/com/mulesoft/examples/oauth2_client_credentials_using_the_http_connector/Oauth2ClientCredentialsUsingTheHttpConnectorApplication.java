package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.reactive.ReactiveSecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.filter.OrderedFormContentFilter;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot entry point for the oauth2-client-credentials-using-the-HTTP-connector example.
 *
 * <p>Component scanning starts at this package and covers its subpackages. Spring Security's
 * servlet auto-configurations, its {@code ReactiveSecurityAutoConfiguration} and Boot's servlet
 * OAuth2 client auto-configuration are excluded: the application has no security filter chain,
 * no {@code springSecurityFilterChain} filter, no generated user, no bound
 * {@code spring.security.*} properties and no Boot-added authorized-client service, repository or
 * login chain (D-496). The form-content filter is registered disabled: form-urlencoded PUT, PATCH
 * and DELETE bodies stay unread on the request input stream (D-497).
 */
@SpringBootApplication(exclude = {
        SecurityAutoConfiguration.class,
        UserDetailsServiceAutoConfiguration.class,
        SecurityFilterAutoConfiguration.class,
        OAuth2ClientAutoConfiguration.class,
        ReactiveSecurityAutoConfiguration.class})
public class Oauth2ClientCredentialsUsingTheHttpConnectorApplication {

    /**
     * Form-content filter held by {@link #disabledFormContentFilterRegistration}. While this bean
     * exists, Boot creates no {@code formContentFilter} bean of its own (D-497).
     *
     * @return a new, unregistered form-content filter
     */
    @Bean
    public OrderedFormContentFilter disabledFormContentFilter() {
        return new OrderedFormContentFilter();
    }

    /**
     * Registration of {@link #disabledFormContentFilter()} with registration disabled. The servlet
     * container runs no form-content filter, and form-urlencoded PUT, PATCH and DELETE bodies are
     * not parsed into request parameters (D-497).
     *
     * @param disabledFormContentFilter the filter returned by {@link #disabledFormContentFilter()}
     * @return a disabled registration of that filter
     */
    @Bean
    public FilterRegistrationBean<OrderedFormContentFilter> disabledFormContentFilterRegistration(
            OrderedFormContentFilter disabledFormContentFilter) {
        FilterRegistrationBean<OrderedFormContentFilter> registration =
                new FilterRegistrationBean<>(disabledFormContentFilter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * Starts the application.
     *
     * @param args command-line arguments, passed to {@link SpringApplication}
     */
    public static void main(String[] args) {
        SpringApplication.run(Oauth2ClientCredentialsUsingTheHttpConnectorApplication.class, args);
    }
}
