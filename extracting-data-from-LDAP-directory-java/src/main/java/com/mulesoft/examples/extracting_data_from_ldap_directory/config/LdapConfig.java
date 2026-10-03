package com.mulesoft.examples.extracting_data_from_ldap_directory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

/**
 * Replaces the Mule global element {@code ldap:config} named {@code LDAP} and its
 * {@code ldap:connection-pooling-profile} [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:3-5]
 * with a pooled {@link LdapContextSource} and the {@link LdapTemplate} built on it (D-063, D-305).
 *
 * <p>Binds the {@code ldap.*} keys of {@code application.yml} into {@link LdapProperties}. The bind DN,
 * the bind password and the connection URL with its base DN all come from those keys; the class holds
 * none of them (D-012). No {@code spring.ldap.*} key is read.
 *
 * <p>The two beans stand in for the context source and the template of Spring Boot's LDAP
 * auto-configuration, which defines its own only when the context holds none.
 *
 * <pre>{@code
 * ldap:
 *   dn: <bind DN>
 *   password: <bind password>
 *   url: ldap://<host>:<port>/dc=my-domain,dc=com
 *   search:
 *     base-dn: ou=people
 *     filter: "(objectClass=*)"
 *
 * // ldapContextSource: URL ldap://<host>:<port>, base dc=my-domain,dc=com, user DN <bind DN>, pooled
 * // ldapTemplate.search("ou=people", "(objectClass=*)", ...) yields entries whose DNs are relative
 * // to the base, for example cn=mmc,ou=people
 * }</pre>
 */
@Configuration
@EnableConfigurationProperties(LdapConfig.LdapProperties.class)
public class LdapConfig {

    /**
     * Binds the {@code ldap.*} keys of {@code application.yml}, the placeholders of the {@code ldap:config}
     * attributes [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:3] (D-012).
     *
     * @param dn the bind DN, key {@code ldap.dn} (Mule attribute {@code authDn})
     * @param password the bind password, key {@code ldap.password} (Mule attribute {@code authPassword})
     * @param port the LDAP server port as text, key {@code ldap.port}; {@code application.yml} may
     *     reference it from {@code ldap.url}, and no bean reads it directly
     * @param url the LDAP connection URL followed by the base DN, key {@code ldap.url} (Mule attribute
     *     {@code url})
     * @param search the settings of the {@code ldap:search} element, keys {@code ldap.search.*}
     */
    @ConfigurationProperties(prefix = "ldap")
    public record LdapProperties(String dn, String password, String port, String url, Search search) {

        /**
         * Binds the {@code ldap.search.*} keys, the attributes of the {@code ldap:search} element
         * [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:9].
         *
         * @param baseDn the search base, relative to the base DN of {@code ldap.url}, key
         *     {@code ldap.search.base-dn} (Mule attribute {@code baseDn})
         * @param filter the search filter, key {@code ldap.search.filter} (Mule attribute {@code filter})
         */
        public record Search(String baseDn, String filter) {
        }
    }

    /**
     * Builds the pooled context source from {@code ldap.url}, {@code ldap.dn} and {@code ldap.password}.
     *
     * <p>{@code ldap.url} is split at the first {@code /} after its {@code ://} separator: the text before
     * that {@code /} is the server URL and the text after it is the base DN. A value without such a
     * {@code /} is the server URL as a whole, with an empty base:
     *
     * <ul>
     *   <li>{@code ldap://localhost:10389/dc=my-domain,dc=com} gives the server URL
     *       {@code ldap://localhost:10389} and the base {@code dc=my-domain,dc=com};</li>
     *   <li>{@code ldap://h:1} gives the server URL {@code ldap://h:1} and an empty base;</li>
     *   <li>the placeholder committed in {@code application.yml} (D-012), which has no {@code ://}, is
     *       taken whole as the server URL, with an empty base.</li>
     * </ul>
     *
     * <p>Pooling is on; it is the counterpart of the {@code ldap:connection-pooling-profile}
     * [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:4] (D-063, D-305). Neither this method
     * nor the container's {@code afterPropertiesSet} call on the result opens a connection.
     *
     * @param properties the bound {@code ldap.*} keys
     * @return the context source, not yet connected
     * @throws IllegalStateException when {@code ldap.url} is not set
     */
    @Bean
    LdapContextSource ldapContextSource(LdapProperties properties) {
        String url = properties.url();
        if (url == null) {
            throw new IllegalStateException("Property 'ldap.url' must be set");
        }
        int scheme = url.indexOf("://");
        int slash = url.indexOf('/', scheme + 3);
        String serverUrl = slash < 0 ? url : url.substring(0, slash);
        String base = slash < 0 ? "" : url.substring(slash + 1);

        LdapContextSource contextSource = new LdapContextSource();
        contextSource.setUrl(serverUrl);
        contextSource.setBase(base);
        contextSource.setUserDn(properties.dn());
        contextSource.setPassword(properties.password());
        contextSource.setPooled(true);
        return contextSource;
    }

    /**
     * Builds the {@link LdapTemplate} over the context source of
     * {@link #ldapContextSource(LdapProperties)}.
     *
     * @param contextSource the pooled context source
     * @return the template that runs the directory searches
     */
    @Bean
    LdapTemplate ldapTemplate(LdapContextSource contextSource) {
        return new LdapTemplate(contextSource);
    }
}
