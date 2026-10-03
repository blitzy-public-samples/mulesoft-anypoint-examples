package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import java.util.Optional;

import com.mulesoft.examples.netsuite_data_retrieval.exception.BadRequestException;
import org.springframework.stereotype.Component;

/**
 * Builds the NetSuite REST record-collection conditions that the search services send as the
 * {@code q} query parameter of {@code GET /services/rest/record/v1/customer} and
 * {@code GET /services/rest/record/v1/opportunity} (AAP 0.6.5, D-016).
 *
 * <p>Each condition has the form {@code <field> START_WITH "<value>"}: a lower-case body field, the
 * {@code START_WITH} operator and the request value between double quotes, separated by single
 * spaces.
 *
 * <ul>
 *   <li>{@link #customers(String)} replaces the DW-17 setter
 *       [DW-17 netsuite-api.xml:27, netsuite-data-retrieval/src/main/app/netsuite-api.xml:27-39],
 *       the {@code CustomerSearch.basic.companyName} {@code STARTS_WITH} criterion that exists only
 *       when the {@code name} query parameter is not {@code null}.</li>
 *   <li>{@link #opportunities(String)} replaces the DW-21 setter
 *       [DW-21 netsuite-api.xml:81, netsuite-data-retrieval/src/main/app/netsuite-api.xml:81-95],
 *       the {@code OpportunitySearch.basic.title} {@code STARTS_WITH} criterion that exists only
 *       when the {@code title} query parameter is not {@code null}.</li>
 * </ul>
 *
 * <p>An empty result means no condition: the caller sends no {@code q} parameter and the collection
 * query lists every record. The empty string is a value like any other and yields a condition.
 * The value is inserted as given: it is not trimmed, escaped, case-converted or URL-encoded; the
 * HTTP client URL-encodes the whole {@code q} parameter. A value that contains a double quote
 * {@code "} is refused with {@link BadRequestException} before any condition is built (FB-NS-06,
 * D-016), which {@code GlobalExceptionHandler} answers with status 400 and the body
 * {@code { "message": "Bad request" }}.
 *
 * <pre>{@code
 * Optional<String> q = builder.customers("Another Company");
 * // q.get() is: companyname START_WITH "Another Company"
 * List<String> ids = client.queryIds("customer", q.orElse(null));
 * }</pre>
 *
 * <p>The class holds no state; one instance serves concurrent callers.
 */
@Component
public class RecordQueryBuilder {

    /** Detail message of the {@link BadRequestException} raised for a value holding {@code "}. */
    private static final String QUOTE_REFUSED_MESSAGE = "Quote character not supported in query value";

    /** The double-quote character that delimits a condition value. */
    private static final char QUOTE = '"';

    /** Creates a builder. */
    public RecordQueryBuilder() {
        super();
    }

    /**
     * Returns the customer collection condition for the {@code name} query parameter of
     * {@code GET /api/customers} (DW-17 netsuite-api.xml:27, D-016).
     *
     * <ul>
     *   <li>{@code null} returns {@link Optional#empty()}: no condition.</li>
     *   <li>Any other value returns {@code companyname START_WITH "<name>"}, the value unchanged
     *       between the quotes; {@code ""} returns {@code companyname START_WITH ""}.</li>
     * </ul>
     *
     * @param name the {@code name} query parameter as received, or {@code null} when absent
     * @return the condition, or {@link Optional#empty()} when {@code name} is {@code null}
     * @throws BadRequestException if {@code name} contains a double quote {@code "} (FB-NS-06)
     */
    public Optional<String> customers(String name) {
        if (name == null) {
            return Optional.empty();
        }
        requireNoQuote(name);
        return Optional.of("companyname START_WITH \"" + name + "\"");
    }

    /**
     * Returns the opportunity collection condition for the {@code title} query parameter of
     * {@code GET /api/opportunities} (DW-21 netsuite-api.xml:81, D-016).
     *
     * <ul>
     *   <li>{@code null} returns {@link Optional#empty()}: no condition.</li>
     *   <li>Any other value returns {@code title START_WITH "<title>"}, the value unchanged between
     *       the quotes; {@code ""} returns {@code title START_WITH ""}.</li>
     * </ul>
     *
     * @param title the {@code title} query parameter as received, or {@code null} when absent
     * @return the condition, or {@link Optional#empty()} when {@code title} is {@code null}
     * @throws BadRequestException if {@code title} contains a double quote {@code "} (FB-NS-06)
     */
    public Optional<String> opportunities(String title) {
        if (title == null) {
            return Optional.empty();
        }
        requireNoQuote(title);
        return Optional.of("title START_WITH \"" + title + "\"");
    }

    /**
     * Refuses a condition value that contains a double quote (FB-NS-06, D-016).
     *
     * @param value the non-null condition value
     * @throws BadRequestException if {@code value} contains {@code "}; its detail message is
     *     {@code Quote character not supported in query value} and is never written to the
     *     response
     */
    private static void requireNoQuote(String value) {
        if (value.indexOf(QUOTE) >= 0) {
            throw new BadRequestException(QUOTE_REFUSED_MESSAGE);
        }
    }
}
