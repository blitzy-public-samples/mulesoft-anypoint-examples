package com.mulesoft.examples.http_oauth_provider.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Registers the resource owner from {@code oauth2-provider.resource-owner} (D-041, D-012).
 *
 * <p>Source: the Spring Security user service {@code resourceOwnerUserService} of the authentication
 * manager {@code resourceOwnerAuthenticationManager}, which holds one user with the authority
 * {@code RESOURCE_OWNER} [http-oauth-provider/src/main/app/http-oauth-provider.xml:13-19], delegated to
 * by the security provider {@code resourceOwnerSecurityProvider} of {@code mule-ss:security-manager}
 * [:22-24].
 *
 * <p>The user name, password and authority are read from configuration only:
 * <ul>
 *   <li>{@code oauth2-provider.resource-owner.username}: the user name;</li>
 *   <li>{@code oauth2-provider.resource-owner.password}: the password, stored as
 *       {@code {noop}<password>};</li>
 *   <li>{@code oauth2-provider.resource-owner.authority}: the single granted authority, default
 *       {@code RESOURCE_OWNER} in {@code application.yml}.</li>
 * </ul>
 * The committed {@code application.yml} holds {@code TODO} for the user name and the password; the
 * values come from {@code application-local.yml} at runtime and from {@code application-test.yml} in
 * tests (D-012).
 *
 * <p>Startup fails with an {@link IllegalStateException} whose message names the offending key, and
 * never contains its value, when {@code oauth2-provider.resource-owner.username} or
 * {@code oauth2-provider.resource-owner.password} is {@code null}, blank or {@code TODO} (ignoring case
 * and surrounding whitespace), or when {@code oauth2-provider.resource-owner.authority} is {@code null}
 * or blank. Accepted values are registered unchanged (D-666).
 *
 * <p>The class declares no {@code PasswordEncoder} and no {@code AuthenticationManager} bean.
 */
@Configuration
public class UsersConfig {

    /** Key of the resource-owner user name. */
    private static final String USERNAME_KEY = "oauth2-provider.resource-owner.username";

    /** Key of the resource-owner password. */
    private static final String PASSWORD_KEY = "oauth2-provider.resource-owner.password";

    /** Key of the resource-owner authority. */
    private static final String AUTHORITY_KEY = "oauth2-provider.resource-owner.authority";

    /** Placeholder value of an unset credential in {@code application.yml}, compared ignoring case. */
    private static final String PLACEHOLDER = "TODO";

    /** Prefix that marks a password stored as plain text for the delegating password encoder. */
    private static final String NOOP_PREFIX = "{noop}";

    /**
     * Registers the in-memory user details manager holding the resource owner as its only user
     * (D-041, D-012).
     *
     * <p>The user has the name {@code oauth2-provider.resource-owner.username}, the password
     * {@code {noop}} followed by {@code oauth2-provider.resource-owner.password}, and the single
     * authority {@code oauth2-provider.resource-owner.authority}; the account is enabled, unexpired,
     * unlocked and its credentials unexpired. {@code loadUserByUsername} of the returned manager
     * returns a copy of that user for its name, compared ignoring case, and throws
     * {@link org.springframework.security.core.userdetails.UsernameNotFoundException} for any other
     * name.
     *
     * <p>Example: the user name {@code owner}, the password {@code secret} and the authority
     * {@code RESOURCE_OWNER} register the user {@code owner} with the stored password
     * {@code {noop}secret} and the authority {@code RESOURCE_OWNER}.
     *
     * @param properties the bound {@code oauth2-provider.*} keys
     * @return the user details manager holding the resource owner
     * @throws IllegalStateException {@code "<key> must not be blank or TODO"} when the user name or
     *     the password is {@code null}, blank or {@code TODO}, or when no
     *     {@code oauth2-provider.resource-owner.*} key is set, and
     *     {@code "oauth2-provider.resource-owner.authority must not be blank"} when the authority is
     *     {@code null} or blank (D-666)
     */
    @Bean
    public InMemoryUserDetailsManager userDetailsService(OAuthProviderProperties properties) {
        // Startup check of the bound values: an absent, blank or placeholder user name or password
        // and an absent or blank authority fail startup before the user is registered (D-666).
        OAuthProviderProperties.ResourceOwner resourceOwner = requireResourceOwner(properties);
        requireNotBlankOrPlaceholder(USERNAME_KEY, resourceOwner.username());
        requireNotBlankOrPlaceholder(PASSWORD_KEY, resourceOwner.password());
        requireNotBlank(AUTHORITY_KEY, resourceOwner.authority());
        return new InMemoryUserDetailsManager(User.withUsername(resourceOwner.username())
                .password(NOOP_PREFIX + resourceOwner.password())
                .authorities(resourceOwner.authority())
                .build());
    }

    /**
     * Returns the bound resource owner of {@code oauth2-provider.resource-owner} (D-666).
     *
     * @param properties the bound {@code oauth2-provider.*} keys
     * @return {@code properties.resourceOwner()}
     * @throws IllegalStateException {@code "oauth2-provider.resource-owner.username must not be blank
     *     or TODO"} when {@code properties.resourceOwner()} is {@code null}, that is, when no
     *     {@code oauth2-provider.resource-owner.*} key is set
     */
    private static OAuthProviderProperties.ResourceOwner requireResourceOwner(
            OAuthProviderProperties properties) {
        OAuthProviderProperties.ResourceOwner resourceOwner = properties.resourceOwner();
        if (resourceOwner == null) {
            throw new IllegalStateException(USERNAME_KEY + " must not be blank or TODO");
        }
        return resourceOwner;
    }

    /**
     * Checks that a credential is neither {@code null}, blank nor the placeholder {@code TODO}, the
     * placeholder compared ignoring case after trimming (D-666).
     *
     * @param key   the configuration key of the credential
     * @param value the credential value
     * @throws IllegalStateException {@code "<key> must not be blank or TODO"} when the value is
     *     {@code null}, blank or {@code TODO}
     */
    private static void requireNotBlankOrPlaceholder(String key, String value) {
        if (value == null || value.isBlank() || PLACEHOLDER.equalsIgnoreCase(value.trim())) {
            throw new IllegalStateException(key + " must not be blank or TODO");
        }
    }

    /**
     * Checks that a value is neither {@code null} nor blank (D-666).
     *
     * @param key   the configuration key of the value
     * @param value the value
     * @throws IllegalStateException {@code "<key> must not be blank"} when the value is {@code null}
     *     or blank
     */
    private static void requireNotBlank(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " must not be blank");
        }
    }
}
