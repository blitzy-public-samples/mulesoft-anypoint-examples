package com.mulesoft.examples.login_form_using_the_http_connector.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code login.username} and {@code login.password}: the credentials {@code DoLoginFlow} checks
 * [login-form-using-the-http-connector/src/main/app/login-form-using-the-http-connector.xml:21] and
 * {@code CallLoginFlowUsingRequester} posts
 * [login-form-using-the-http-connector/src/main/app/login-form-using-the-http-connector.xml:38-40] (D-012).
 *
 * <p>The committed {@code application.yml} sets both keys to {@code TODO}. The {@code local} profile
 * ({@code application-local.yml}) and the {@code test} profile ({@code application-test.yml}) supply the
 * values (D-012).
 *
 * <p>{@code @ConfigurationPropertiesScan} on {@code LoginFormUsingTheHttpConnectorApplication} registers the
 * record; it is not a component. It declares no default value and no validation: an absent key binds
 * {@code null}, which {@link #isConfigured()} reports. The generated {@code equals}, {@code hashCode} and
 * {@code toString} cover both components, and {@code toString} prints {@code password} (D-402).
 *
 * @param username {@code login.username}: the user name a submitted login form must carry, and the
 *                 {@code username} form field the requester flow posts
 * @param password {@code login.password}: the password a submitted login form must carry, and the
 *                 {@code password} form field the requester flow posts
 */
@ConfigurationProperties("login")
public record LoginProperties(String username, String password) {

    /** The value the committed {@code application.yml} gives both keys (D-012). */
    private static final String PLACEHOLDER = "TODO";

    /**
     * Reports whether both credentials hold a usable value.
     *
     * <p>Returns {@code false} when {@code username} or {@code password} is {@code null}, blank, or exactly
     * {@code TODO} (case-sensitive) after {@link String#strip()}. Returns {@code true} otherwise
     * (D-012, D-402).
     *
     * @return {@code true} when both values are set to something other than blank or {@code TODO}
     */
    public boolean isConfigured() {
        return isSet(username) && isSet(password);
    }

    /**
     * Reports whether one bound credential value is set.
     *
     * @param value the bound value of {@code login.username} or {@code login.password}
     * @return {@code false} when {@code value} is {@code null}, blank, or {@code TODO} after
     *         {@link String#strip()}; {@code true} otherwise
     */
    private static boolean isSet(String value) {
        return value != null && !value.isBlank() && !PLACEHOLDER.equals(value.strip());
    }
}
