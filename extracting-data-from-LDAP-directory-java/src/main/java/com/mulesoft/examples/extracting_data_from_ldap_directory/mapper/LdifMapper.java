package com.mulesoft.examples.extracting_data_from_ldap_directory.mapper;

import java.util.Base64;
import java.util.List;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import org.springframework.ldap.support.LdapUtils;
import org.springframework.stereotype.Component;

/**
 * Renders LDAP directory entries as LDIF text and joins the rendered entries into one list text
 * (D-063, D-206).
 *
 * <p>Source: flow {@code ldapFlow1} [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:7-16],
 * whose {@code object-to-string-transformer} [extracting-data-from-LDAP-directory/src/main/app/ldap.xml:15]
 * returns the aggregated entries as text, in the format of
 * [extracting-data-from-LDAP-directory/README.md:66-96].
 *
 * <p>The mapper holds no state, performs no I/O and writes no log. Every line it writes ends with
 * {@code \n} (LF, never CRLF). It writes no {@code version:} line, folds no line and sorts neither
 * attributes nor values.
 *
 * <pre>{@code
 * BasicAttributes attributes = new BasicAttributes(true);
 * attributes.put("uid", "mmc");
 * attributes.put("userPassword", "mmc123".getBytes(StandardCharsets.UTF_8));
 * String entry = mapper.toLdif("cn=mmc,ou=people", attributes);
 * // entry is "dn: cn=mmc,ou=people\nuid: mmc\nuserPassword:: bW1jMTIz\n\n"
 * // (the uid and userPassword lines follow the enumeration order of the attributes)
 * String body = mapper.toListString(List.of(entry, "dn: cn=admin,ou=people\n\n"));
 * // body is "[dn: cn=mmc,ou=people\n...\n\n, dn: cn=admin,ou=people\n\n]"
 * }</pre>
 */
@Component
public class LdifMapper {

    /**
     * Renders one directory entry as an LDIF block.
     *
     * <p>The block is, in this order:
     *
     * <ol>
     *   <li>{@code dn: <dn>\n}, with {@code dn} written unchanged;</li>
     *   <li>for each attribute in the enumeration order of {@link Attributes#getAll()}, and for each
     *       of its values in the enumeration order of {@link Attribute#getAll()}, one line:
     *       <ul>
     *         <li>{@code <id>:: <base64>\n} for a {@code byte[]} value, the value encoded with
     *             {@link Base64#getEncoder()} (for example {@code userPassword:: bW1jMTIz} for the
     *             bytes of {@code mmc123});</li>
     *         <li>{@code <id>: <value>\n} for any other value, written with
     *             {@link String#valueOf(Object)};</li>
     *       </ul>
     *   </li>
     *   <li>one empty line, {@code \n}.</li>
     * </ol>
     *
     * <p>Every block therefore ends with {@code \n\n}; an entry without attributes gives
     * {@code dn: <dn>\n\n}. Every enumeration that is opened is closed before the method returns or
     * throws.
     *
     * @param dn the distinguished name of the entry, relative to the base of the LDAP context
     *     source, for example {@code cn=mmc,ou=people}
     * @param attributes the attributes of the entry
     * @return the LDIF block of the entry
     * @throws org.springframework.ldap.NamingException the {@link LdapUtils#convertLdapException}
     *     translation of a {@link NamingException} raised while enumerating attributes or values or
     *     while closing an enumeration
     */
    public String toLdif(String dn, Attributes attributes) {
        StringBuilder text = new StringBuilder("dn: ").append(dn).append('\n');
        try {
            appendAttributes(text, attributes);
        } catch (NamingException e) {
            throw LdapUtils.convertLdapException(e);
        }
        return text.append('\n').toString();
    }

    /**
     * Joins rendered entries into one list text: {@code [}, the entries in list order separated by
     * {@code ", "}, then {@code ]}. One entry gives {@code [<entry>]}; an empty list gives
     * {@code []}.
     *
     * @param entries the rendered entries, each usually a {@link #toLdif(String, Attributes)} block
     * @return the list text of the entries
     */
    public String toListString(List<String> entries) {
        return "[" + String.join(", ", entries) + "]";
    }

    /**
     * Appends the value lines of every attribute of {@code attributes} to {@code text}, then closes
     * the attribute enumeration.
     */
    private static void appendAttributes(StringBuilder text, Attributes attributes) throws NamingException {
        NamingEnumeration<? extends Attribute> all = attributes.getAll();
        try {
            while (all.hasMore()) {
                appendValues(text, all.next());
            }
        } finally {
            all.close();
        }
    }

    /**
     * Appends one line per value of {@code attribute} to {@code text}, then closes the value
     * enumeration.
     */
    private static void appendValues(StringBuilder text, Attribute attribute) throws NamingException {
        String id = attribute.getID();
        NamingEnumeration<?> values = attribute.getAll();
        try {
            while (values.hasMore()) {
                appendLine(text, id, values.next());
            }
        } finally {
            values.close();
        }
    }

    /**
     * Appends {@code <id>:: <base64>\n} for a {@code byte[]} value and {@code <id>: <value>\n} for
     * any other value.
     */
    private static void appendLine(StringBuilder text, String id, Object value) {
        if (value instanceof byte[] bytes) {
            text.append(id).append(":: ").append(Base64.getEncoder().encodeToString(bytes)).append('\n');
        } else {
            text.append(id).append(": ").append(String.valueOf(value)).append('\n');
        }
    }
}
