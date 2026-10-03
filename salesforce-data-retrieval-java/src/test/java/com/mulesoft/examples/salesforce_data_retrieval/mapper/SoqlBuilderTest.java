package com.mulesoft.examples.salesforce_data_retrieval.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link SoqlBuilder#build(Map)}, the SOQL text of the {@code sfdc:query-all} call of
 * {@code salesforceDataRetrievalFlow} [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:27]:
 *
 * <pre>{@code
 * "SELECT id, name " + (payload.field.isEmpty() ? "" : "," + payload.field) + " from " + payload.object
 *         + " where " + payload.searchKey + " like '%" + payload.searchValue + "%'"
 * }</pre>
 *
 * <p>Each test calls a {@code new SoqlBuilder()} directly, with no Spring application context and no mocks, and
 * asserts
 * <ul>
 *   <li>the exact text for an empty {@code field}, with two spaces between {@code name} and {@code from}, and for
 *       the {@code field=email} form of the original suite's {@code testGetData};</li>
 *   <li>quote-bearing and whitespace-bearing values inserted verbatim: not escaped, quoted or trimmed (D-044);</li>
 *   <li>a {@link NullPointerException} for a {@code null} form and for a form without {@code field};</li>
 *   <li>the text {@code null} for every other missing value.</li>
 * </ul>
 * These tests cover every line of {@link SoqlBuilder} under the JaCoCo LINE covered ratio rule of at least 0.80
 * on the {@code mapper} package (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
public class SoqlBuilderTest {

    /** The unit under test, created without a Spring context. */
    private final SoqlBuilder builder = new SoqlBuilder();

    /**
     * Empty {@code field}: the conditional part adds nothing, leaving two spaces between {@code name} and
     * {@code from}.
     */
    @Test
    public void buildWithEmptyFieldOmitsFieldList() {
        String soql = builder.build(form("user", "", "name", "mule"));

        assertEquals("SELECT id, name  from user where name like '%mule%'", soql);
    }

    /**
     * Non-empty {@code field}: a comma and the field follow {@code name }, with the form of the original suite's
     * {@code SalesforceIdRetrievalIT#testGetData}.
     */
    @Test
    public void buildWithFieldAppendsCommaAndField() {
        String soql = builder.build(form("user", "email", "name", "mule"));

        assertEquals("SELECT id, name ,email from user where name like '%mule%'", soql);
    }

    /**
     * Quote-bearing and whitespace-bearing values appear in the text exactly as submitted: no escaping, no
     * quoting and no trimming (D-044). A whitespace-only {@code field} is not empty and is appended after the
     * comma.
     */
    @Test
    public void buildInsertsQuoteBearingInputVerbatim() {
        assertEquals("SELECT id, name  from user where name like '%a'b%'",
                builder.build(form("user", "", "name", "a'b")));

        assertEquals("SELECT id, name ,email' OR '1'='1 from us\"er where name like '%mule%'",
                builder.build(form("us\"er", "email' OR '1'='1", "name", "mule")));

        assertEquals("SELECT id, name ,  from user where name like '% mu le %'",
                builder.build(form("user", " ", "name", " mu le ")));
    }

    /** A {@code null} form throws {@link NullPointerException}. */
    @Test
    public void buildThrowsNullPointerExceptionForNullMap() {
        assertThrows(NullPointerException.class, () -> builder.build(null));
    }

    /**
     * A form without {@code field} throws {@link NullPointerException}, as {@code payload.field.isEmpty()} does on
     * a {@code null} field.
     */
    @Test
    public void buildThrowsNullPointerExceptionForNullField() {
        Map<String, String> formWithoutField = new HashMap<>();
        formWithoutField.put("object", "user");
        formWithoutField.put("searchKey", "name");
        formWithoutField.put("searchValue", "mule");

        assertThrows(NullPointerException.class, () -> builder.build(formWithoutField));
    }

    /** A missing {@code object}, {@code searchKey} or {@code searchValue} appears as the text {@code null}. */
    @Test
    public void buildRendersOtherNullValuesAsNullText() {
        Map<String, String> fieldOnly = new HashMap<>();
        fieldOnly.put("field", "");

        assertEquals("SELECT id, name  from null where null like '%null%'", builder.build(fieldOnly));
    }

    /**
     * Returns an immutable retrieval form holding the four query keys {@link SoqlBuilder#build(Map)} reads.
     *
     * @param object      the {@code object} value
     * @param field       the {@code field} value
     * @param searchKey   the {@code searchKey} value
     * @param searchValue the {@code searchValue} value
     * @return the form keyed by {@code object}, {@code field}, {@code searchKey} and {@code searchValue}
     */
    private static Map<String, String> form(String object, String field, String searchKey, String searchValue) {
        return Map.of("object", object, "field", field, "searchKey", searchKey, "searchValue", searchValue);
    }
}
