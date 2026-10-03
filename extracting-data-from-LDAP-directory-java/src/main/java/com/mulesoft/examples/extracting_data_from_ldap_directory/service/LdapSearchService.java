package com.mulesoft.examples.extracting_data_from_ldap_directory.service;

import com.mulesoft.examples.extracting_data_from_ldap_directory.config.LdapConfig;
import com.mulesoft.examples.extracting_data_from_ldap_directory.mapper.LdifMapper;
import java.util.List;
import javax.naming.directory.SearchControls;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextOperations;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.stereotype.Service;

/**
 * Searches the directory for the users under {@code ou=people}, logs each user and the aggregated
 * list, and returns the list as LDIF text (D-063, D-510).
 *
 * <p>Source: the body of flow {@code ldapFlow1} [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:9-15];
 * its {@code http:listener} [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:8] is
 * {@code controller/LdapController.ldapFlow1}, which calls {@link #ldapFlow1()}. The flow's
 * elements map to the method's steps as follows:
 *
 * <ul>
 *   <li>{@code ldap:search baseDn="ou=people" filter="(objectClass=*)"} (:9): one
 *       {@link LdapTemplate#search(String, String, SearchControls, ContextMapper)} call with the
 *       keys {@code ldap.search.base-dn} and {@code ldap.search.filter}, scope
 *       {@link SearchControls#ONELEVEL_SCOPE} and every user attribute; each entry is rendered by
 *       {@link LdifMapper#toLdif(String, javax.naming.directory.Attributes)} with its DN relative to
 *       the context-source base {@code dc=my-domain,dc=com} (D-305);</li>
 *   <li>{@code byte-array-to-object-transformer} (:10): no step (D-510);</li>
 *   <li>{@code collection-splitter} and {@code logger "LDAP user: #[payload]"} (:11-12): one INFO
 *       line {@code LDAP user: <entry>} per entry, in directory order;</li>
 *   <li>{@code collection-aggregator failOnTimeout="true"} (:13): the list of rendered entries in
 *       directory order; no timeout applies;</li>
 *   <li>{@code logger "all users: #[payload]"} (:14): one INFO line {@code all users: <list>};</li>
 *   <li>{@code object-to-string-transformer} (:15): the return value
 *       {@link LdifMapper#toListString(List)}.</li>
 * </ul>
 *
 * <p>Spring LDAP exceptions raised by the search or by the mapper, for example
 * {@code org.springframework.ldap.CommunicationException} while no directory server is reachable,
 * leave the method unchanged and reach {@code exception/GlobalExceptionHandler.unexpected} (HTTP 500).
 *
 * <pre>{@code
 * // directory dc=my-domain,dc=com seeded with ldap.ldif, three users under ou=people
 * String body = service.ldapFlow1();
 * // log:  LDAP user: dn: cn=mmc,ou=people ...      (one line per user)
 * //       all users: [dn: cn=mmc,ou=people ...]
 * // body: "[dn: cn=mmc,ou=people\n...\n\n, dn: cn=testuser1,ou=people\n...\n\n, dn: cn=admin,ou=people\n...\n\n]"
 *
 * // no entry under ou=people
 * String empty = service.ldapFlow1();
 * // log:  nothing
 * // body: ""
 * }</pre>
 */
@Service
public class LdapSearchService {

    /** Receives the {@code LDAP user:} and {@code all users:} INFO lines of {@link #ldapFlow1()}. */
    private static final Logger log = LoggerFactory.getLogger(LdapSearchService.class);

    /** Runs the directory search against the context source of {@code config/LdapConfig}. */
    private final LdapTemplate ldapTemplate;

    /** Renders each entry as an LDIF block and the entries as one list text. */
    private final LdifMapper ldifMapper;

    /** Supplies the search base and filter, keys {@code ldap.search.base-dn} and {@code ldap.search.filter}. */
    private final LdapConfig.LdapProperties properties;

    /**
     * Creates the service over its collaborators.
     *
     * @param ldapTemplate the template that runs the directory search
     * @param ldifMapper the mapper that renders entries and the entry list
     * @param properties the bound {@code ldap.*} keys; {@code properties.search()} gives the base and
     *     the filter
     */
    public LdapSearchService(LdapTemplate ldapTemplate, LdifMapper ldifMapper,
            LdapConfig.LdapProperties properties) {
        this.ldapTemplate = ldapTemplate;
        this.ldifMapper = ldifMapper;
        this.properties = properties;
    }

    /**
     * Runs flow {@code ldapFlow1} [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:7-16]
     * and returns its reply text.
     *
     * <ol>
     *   <li>Searches {@code ldap.search.base-dn} with {@code ldap.search.filter}, one level below the
     *       base, returning every user attribute, and renders each entry as
     *       {@code ldifMapper.toLdif(<DN relative to the context-source base>, <attributes>)}.</li>
     *   <li>Logs {@code LDAP user: <entry>} at INFO for each rendered entry, in directory order.</li>
     *   <li>Returns {@code ""} when the search finds no entry, without the {@code all users:} line
     *       (D-023).</li>
     *   <li>Otherwise logs {@code all users: <list>} at INFO and returns {@code <list>}, the
     *       {@link LdifMapper#toListString(List)} text of the entries in directory order.</li>
     * </ol>
     *
     * @return the list text of the entries, for example {@code [dn: cn=mmc,ou=people\n...\n\n]}, or
     *     {@code ""} when the search finds no entry
     * @throws org.springframework.ldap.NamingException any Spring LDAP exception the search or the
     *     mapper raises, unchanged
     */
    public String ldapFlow1() {
        SearchControls controls = new SearchControls();
        controls.setSearchScope(SearchControls.ONELEVEL_SCOPE);
        controls.setReturningAttributes(null);

        List<String> entries = ldapTemplate.search(
                properties.search().baseDn(), properties.search().filter(), controls,
                (ContextMapper<String>) ctx -> {
                    DirContextOperations ops = (DirContextOperations) ctx;
                    return ldifMapper.toLdif(ops.getDn().toString(), ops.getAttributes());
                });

        for (String entry : entries) {
            log.info("LDAP user: {}", entry);
        }

        if (entries.isEmpty()) {
            return "";
        }

        String all = ldifMapper.toListString(entries);
        log.info("all users: {}", all);
        return all;
    }
}
