package com.mulesoft.examples.extracting_data_from_ldap_directory.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link LdifMapper} output text, run on {@code new LdifMapper()} with no Spring
 * application context. They cover the {@code mapper} package under the JaCoCo LINE rule (D-049).
 *
 * <p>The {@code toLdif} tests render the entry {@code cn=mmc,ou=people} of the seed
 * {@code src/test/resources/original/ldap-add.ldif}. They assert the first line, the presence of each
 * value line, the value order inside {@code objectClass}, the line count and the block ending, and
 * never the relative order of different attributes.
 */
class LdifMapperTest {

    /** The distinguished name of the seed entry {@code mmc}, relative to {@code dc=my-domain,dc=com}. */
    private static final String MMC_DN = "cn=mmc,ou=people";

    /** The mapper under test. */
    private final LdifMapper mapper = new LdifMapper();

    /**
     * Asserts the first line is {@code dn: cn=mmc,ou=people} and the text starts with that line and
     * its line feed.
     */
    @Test
    void toLdifWritesDnLineFirst() {
        String text = mapper.toLdif(MMC_DN, mmcAttributes());

        assertEquals("dn: cn=mmc,ou=people", lines(text).get(0));
        assertTrue(text.startsWith("dn: cn=mmc,ou=people\n"));
    }

    /** Asserts each {@code String} value is written as {@code <id>: <value>}. */
    @Test
    void toLdifWritesStringValueLines() {
        List<String> lines = lines(mapper.toLdif(MMC_DN, mmcAttributes()));

        assertTrue(lines.contains("sn: mmc"));
        assertTrue(lines.contains("cn: mmc"));
        assertTrue(lines.contains("uid: mmc"));
    }

    /**
     * Asserts the {@code byte[]} value of {@code userPassword} is written as
     * {@code userPassword:: bW1jMTIz}, the Base64 of {@code mmc123}, and never as
     * {@code userPassword: <value>}.
     */
    @Test
    void toLdifWritesByteArrayValueAsBase64() {
        List<String> lines = lines(mapper.toLdif(MMC_DN, mmcAttributes()));

        assertTrue(lines.contains("userPassword:: bW1jMTIz"));
        assertTrue(lines.stream().noneMatch(line -> line.startsWith("userPassword: ")));
    }

    /**
     * Asserts the four {@code objectClass} values are written as four lines in the order the
     * attribute holds them.
     */
    @Test
    void toLdifWritesMultiValuedAttributeValuesInOrder() {
        List<String> objectClassLines = lines(mapper.toLdif(MMC_DN, mmcAttributes())).stream()
                .filter(line -> line.startsWith("objectClass: "))
                .toList();

        assertEquals(
                List.of(
                        "objectClass: top",
                        "objectClass: person",
                        "objectClass: organizationalPerson",
                        "objectClass: inetOrgPerson"),
                objectClassLines);
    }

    /**
     * Asserts the entry gives exactly 9 non-empty lines: {@code dn}, four {@code objectClass},
     * {@code cn}, {@code sn}, {@code uid} and {@code userPassword}.
     */
    @Test
    void toLdifWritesOneLinePerValue() {
        assertEquals(9, lines(mapper.toLdif(MMC_DN, mmcAttributes())).size());
    }

    /** Asserts the block ends with the line feed of its last line followed by exactly one empty line. */
    @Test
    void toLdifEndsBlockWithOneEmptyLine() {
        String text = mapper.toLdif(MMC_DN, mmcAttributes());

        assertTrue(text.endsWith("\n\n"));
        assertFalse(text.endsWith("\n\n\n"));
    }

    /** Asserts an entry without attributes gives the {@code dn} line followed by one empty line. */
    @Test
    void toLdifWithoutAttributesWritesDnLineAndEmptyLine() {
        assertEquals("dn: cn=empty,ou=people\n\n", mapper.toLdif("cn=empty,ou=people", new BasicAttributes(true)));
    }

    /**
     * Asserts a {@link NamingException} raised by the attribute enumeration is thrown as
     * {@link org.springframework.ldap.NamingException} holding the original exception as its cause.
     *
     * @throws NamingException declared by the stubbed calls {@link NamingEnumeration#hasMore()} and
     *     {@link NamingEnumeration#next()}
     */
    @Test
    void toLdifConvertsNamingExceptionToUncheckedLdapException() throws NamingException {
        NamingException cause = new NamingException("enumeration failed");
        NamingEnumeration<?> enumeration = mock(NamingEnumeration.class);
        when(enumeration.hasMore()).thenThrow(cause);
        when(enumeration.next()).thenThrow(cause);
        Attributes attributes = mock(Attributes.class);
        doReturn(enumeration).when(attributes).getAll();
        doReturn(enumeration).when(attributes).getIDs();

        org.springframework.ldap.NamingException thrown = assertThrows(
                org.springframework.ldap.NamingException.class, () -> mapper.toLdif(MMC_DN, attributes));

        assertSame(cause, thrown.getCause());
    }

    /** Asserts one entry is written as {@code [<entry>]}. */
    @Test
    void toListStringWrapsSingleEntryInBrackets() {
        assertEquals("[x]", mapper.toListString(List.of("x")));
    }

    /** Asserts two entries are written in list order, separated by {@code ", "}, inside brackets. */
    @Test
    void toListStringJoinsEntriesWithCommaAndSpace() {
        assertEquals("[x, y]", mapper.toListString(List.of("x", "y")));
    }

    /**
     * Returns case-insensitive attributes holding the values of the seed entry {@code cn=mmc,ou=people}:
     * {@code objectClass} {@code top}, {@code person}, {@code organizationalPerson} and
     * {@code inetOrgPerson} in that order; {@code cn}, {@code sn} and {@code uid} {@code mmc}; and
     * {@code userPassword} the UTF-8 bytes of {@code mmc123}.
     */
    private static Attributes mmcAttributes() {
        BasicAttributes attributes = new BasicAttributes(true);
        BasicAttribute objectClass = new BasicAttribute("objectClass");
        objectClass.add("top");
        objectClass.add("person");
        objectClass.add("organizationalPerson");
        objectClass.add("inetOrgPerson");
        attributes.put(objectClass);
        attributes.put("cn", "mmc");
        attributes.put("sn", "mmc");
        attributes.put("uid", "mmc");
        attributes.put("userPassword", "mmc123".getBytes(StandardCharsets.UTF_8));
        return attributes;
    }

    /** Splits {@code text} at each line feed; trailing empty lines are dropped. */
    private static List<String> lines(String text) {
        return List.of(text.split("\n"));
    }
}
