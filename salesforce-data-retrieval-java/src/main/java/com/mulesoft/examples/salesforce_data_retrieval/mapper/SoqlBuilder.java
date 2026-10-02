package com.mulesoft.examples.salesforce_data_retrieval.mapper;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Builds the SOQL text of the {@code query-all} call of {@code salesforceDataRetrievalFlow} from
 * the submitted retrieval form, with the composition of the MEL query expression
 * [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:27].
 *
 * <p>The form keys are the field names of {@code retrieval/index.html}: {@code object},
 * {@code field}, {@code searchKey} and {@code searchValue}. Every value is inserted unescaped and
 * untrimmed (D-044).
 *
 * <pre>{@code
 * builder.build(Map.of("object", "user", "field", "email",
 *         "searchKey", "name", "searchValue", "mule"));
 * // SELECT id, name ,email from user where name like '%mule%'
 * builder.build(Map.of("object", "user", "field", "",
 *         "searchKey", "name", "searchValue", "mule"));
 * // SELECT id, name  from user where name like '%mule%'
 * }</pre>
 */
@Component
public class SoqlBuilder {

    /**
     * Returns the SOQL text
     * {@code SELECT id, name [,<field>] from <object> where <searchKey> like '%<searchValue>%'}
     * [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:27].
     *
     * <p>An empty {@code field} adds nothing, which leaves two spaces between {@code name} and
     * {@code from}; any other {@code field} value is appended after a comma. The values of
     * {@code field}, {@code object}, {@code searchKey} and {@code searchValue} are inserted as
     * given: quotes, {@code %}, backslashes and whitespace are not escaped, trimmed or validated
     * (D-044). A missing {@code object}, {@code searchKey} or {@code searchValue} appears as the
     * text {@code null} at its position.
     *
     * @param form the submitted form values keyed by {@code object}, {@code field},
     *             {@code searchKey} and {@code searchValue}
     * @return the composed SOQL text
     * @throws NullPointerException if {@code form} is {@code null} or holds no {@code field} value
     */
    public String build(Map<String, String> form) {
        return "SELECT id, name "
                + (form.get("field").isEmpty() ? "" : "," + form.get("field"))
                + " from " + form.get("object")
                + " where " + form.get("searchKey")
                + " like '%" + form.get("searchValue") + "%'";
    }
}
