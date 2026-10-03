package com.mulesoft.examples.extracting_data_from_ldap_directory.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mulesoft.examples.extracting_data_from_ldap_directory.config.LdapConfig;
import com.mulesoft.examples.extracting_data_from_ldap_directory.mapper.LdifMapper;
import java.util.List;
import javax.naming.NamingException;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.SearchControls;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.ldap.core.ContextMapper;
import org.springframework.ldap.core.DirContextAdapter;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.support.LdapUtils;

/**
 * Verifies the one-level search, per-entry logging and list rendering of
 * {@link LdapSearchService#ldapFlow1()}, run on a Mockito {@link LdapTemplate}, a real
 * {@link LdifMapper} and no Spring application context. The tests cover the {@code service}
 * package under the JaCoCo LINE rule (D-049).
 *
 * <p>The log lines are read from the console output of the test, captured by
 * {@link OutputCaptureExtension}.
 */
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class LdapSearchServiceTest {

    /** The template mock that answers the directory search. */
    @Mock
    private LdapTemplate template;

    /** Captures the search base passed to {@link LdapTemplate}. */
    @Captor
    private ArgumentCaptor<String> baseCaptor;

    /** Captures the search filter passed to {@link LdapTemplate}. */
    @Captor
    private ArgumentCaptor<String> filterCaptor;

    /** Captures the search controls passed to {@link LdapTemplate}. */
    @Captor
    private ArgumentCaptor<SearchControls> controlsCaptor;

    /** Captures the context mapper passed to {@link LdapTemplate}. */
    @Captor
    private ArgumentCaptor<ContextMapper<String>> mapperCaptor;

    /** The mapper that renders each entry and the entry list. */
    private final LdifMapper mapper = new LdifMapper();

    /** The bound {@code ldap.*} keys, with search base {@code ou=people} and filter {@code (objectClass=*)}. */
    private final LdapConfig.LdapProperties properties = new LdapConfig.LdapProperties(
            "cn=x", "p", "1", "ldap://localhost:1/dc=my-domain,dc=com",
            new LdapConfig.LdapProperties.Search("ou=people", "(objectClass=*)"));

    /** The service under test. */
    private LdapSearchService service;

    /** Builds the service over the template mock, the mapper and the properties. */
    @BeforeEach
    void setUp() {
        service = new LdapSearchService(template, mapper, properties);
    }

    /**
     * Asserts one search at base {@code ou=people} with filter {@code (objectClass=*)}, scope
     * {@link SearchControls#ONELEVEL_SCOPE} and no list of returning attributes.
     */
    @Test
    @DisplayName("ldapFlow1 searches ou=people one level with (objectClass=*)")
    void ldapFlow1SearchesBaseOneLevelWithFilter() {
        when(template.search(anyString(), anyString(), any(SearchControls.class),
                ArgumentMatchers.<ContextMapper<String>>any())).thenReturn(List.of("a"));

        service.ldapFlow1();

        verify(template).search(baseCaptor.capture(), filterCaptor.capture(), controlsCaptor.capture(),
                mapperCaptor.capture());
        assertEquals("ou=people", baseCaptor.getValue());
        assertEquals("(objectClass=*)", filterCaptor.getValue());
        assertEquals(SearchControls.ONELEVEL_SCOPE, controlsCaptor.getValue().getSearchScope());
        assertNull(controlsCaptor.getValue().getReturningAttributes());
    }

    /**
     * Asserts the context mapper passed to the search renders the entry {@code cn=mmc,ou=people} as
     * an LDIF block that starts with its {@code dn} line and holds its {@code cn} value line.
     *
     * @throws NamingException declared by {@link ContextMapper#mapFromContext(Object)}
     */
    @Test
    @DisplayName("ldapFlow1 renders each search result as an LDIF block with its relative DN")
    void ldapFlow1MapperRendersEntryAsLdif() throws NamingException {
        when(template.search(anyString(), anyString(), any(SearchControls.class),
                ArgumentMatchers.<ContextMapper<String>>any())).thenReturn(List.of("a"));

        service.ldapFlow1();

        verify(template).search(anyString(), anyString(), any(SearchControls.class), mapperCaptor.capture());
        BasicAttributes attrs = new BasicAttributes(true);
        attrs.put("cn", "mmc");
        attrs.put("sn", "mmc");
        attrs.put("objectClass", "person");
        String entry = mapperCaptor.getValue().mapFromContext(
                new DirContextAdapter(attrs, LdapUtils.newLdapName("cn=mmc,ou=people")));
        assertTrue(entry.startsWith("dn: cn=mmc,ou=people\n"));
        assertTrue(entry.contains("cn: mmc\n"));
    }

    /**
     * Asserts two entries give the list text {@code [a, b]}, one {@code LDAP user:} line per entry
     * and one {@code all users:} line holding the list text.
     *
     * @param output the console output of the test
     */
    @Test
    @DisplayName("ldapFlow1 logs each user and the list and returns the list text")
    void ldapFlow1LogsEachEntryAndReturnsListText(CapturedOutput output) {
        when(template.search(anyString(), anyString(), any(SearchControls.class),
                ArgumentMatchers.<ContextMapper<String>>any())).thenReturn(List.of("a", "b"));

        assertEquals("[a, b]", service.ldapFlow1());

        assertTrue(output.getAll().contains("LDAP user: a"));
        assertTrue(output.getAll().contains("LDAP user: b"));
        assertTrue(output.getAll().contains("all users: [a, b]"));
    }

    /**
     * Asserts a search without entries returns {@code ""} and logs neither an {@code LDAP user:}
     * line nor an {@code all users:} line (D-023).
     *
     * @param output the console output of the test
     */
    @Test
    @DisplayName("ldapFlow1 returns an empty body and logs nothing when the search finds no entry")
    void ldapFlow1WithoutEntriesReturnsEmptyTextAndLogsNothing(CapturedOutput output) {
        when(template.search(anyString(), anyString(), any(SearchControls.class),
                ArgumentMatchers.<ContextMapper<String>>any())).thenReturn(List.of());

        assertEquals("", service.ldapFlow1());

        assertFalse(output.getAll().contains("LDAP user:"));
        assertFalse(output.getAll().contains("all users:"));
    }

    /** Asserts the Spring LDAP exception raised by the search is the exception the method throws. */
    @Test
    @DisplayName("ldapFlow1 propagates the Spring LDAP exception of the search unchanged")
    void ldapFlow1PropagatesSearchException() {
        org.springframework.ldap.CommunicationException failure = new org.springframework.ldap.CommunicationException(
                new javax.naming.CommunicationException("down"));
        when(template.search(anyString(), anyString(), any(SearchControls.class),
                ArgumentMatchers.<ContextMapper<String>>any())).thenThrow(failure);

        org.springframework.ldap.CommunicationException thrown = assertThrows(
                org.springframework.ldap.CommunicationException.class, service::ldapFlow1);

        assertSame(failure, thrown);
    }
}
