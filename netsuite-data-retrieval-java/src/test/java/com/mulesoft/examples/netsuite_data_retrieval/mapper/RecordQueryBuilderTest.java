package com.mulesoft.examples.netsuite_data_retrieval.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.mulesoft.examples.netsuite_data_retrieval.exception.BadRequestException;

/**
 * Unit tests of {@link RecordQueryBuilder}, the NetSuite REST record-collection conditions of the
 * {@code q} query parameter (D-016). Each test calls a new builder directly, with no Spring
 * application context.
 *
 * <ul>
 *   <li>{@link RecordQueryBuilder#customers(String)} replaces the DW-17 criterion
 *       {@code CustomerSearch.basic.companyName STARTS_WITH name}: no condition for a {@code null}
 *       name, otherwise {@code companyname START_WITH "<name>"} with the value unchanged.</li>
 *   <li>{@link RecordQueryBuilder#opportunities(String)} replaces the DW-21 criterion
 *       {@code OpportunitySearch.basic.title STARTS_WITH title}: no condition for a {@code null}
 *       title, otherwise {@code title START_WITH "<title>"} with the value unchanged.</li>
 *   <li>A value that contains a double quote {@code "} is refused by both methods with
 *       {@link BadRequestException} (FB-NS-06, D-016); a single quote {@code '} is kept.</li>
 * </ul>
 */
class RecordQueryBuilderTest {

    /** Detail message of the {@link BadRequestException} for a value holding {@code "}. */
    private static final String QUOTE_REFUSED_MESSAGE = "Quote character not supported in query value";

    /** The builder under test. */
    private final RecordQueryBuilder builder = new RecordQueryBuilder();

    @Test
    @DisplayName("Customers without a name give no condition")
    void customersWithoutNameGiveNoCondition() {
        assertThat(builder.customers(null)).isEmpty();
    }

    @Test
    @DisplayName("Customers with an empty name give a companyname condition with empty quotes")
    void customersWithEmptyNameGiveEmptyQuotedCondition() {
        assertThat(builder.customers("")).hasValue("companyname START_WITH \"\"");
    }

    @Test
    @DisplayName("Customers with a name give the companyname START_WITH condition of the REST documentation")
    void customersWithNameGiveCompanyNameCondition() {
        assertThat(builder.customers("Another Company"))
                .hasValue("companyname START_WITH \"Another Company\"");
    }

    @ParameterizedTest(name = "Customer name {0} is refused")
    @ValueSource(strings = {"Bob \"The\" Builder", "\""})
    @DisplayName("Customers with a name holding a double quote are refused as a bad request")
    void customersWithDoubleQuoteAreRefused(String name) {
        assertThatThrownBy(() -> builder.customers(name))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(QUOTE_REFUSED_MESSAGE);
    }

    @Test
    @DisplayName("Customers with a name holding a single quote keep the name unchanged")
    void customersWithSingleQuoteKeepName() {
        assertThat(builder.customers("O'Brien")).hasValue("companyname START_WITH \"O'Brien\"");
    }

    @Test
    @DisplayName("Opportunities without a title give no condition")
    void opportunitiesWithoutTitleGiveNoCondition() {
        assertThat(builder.opportunities(null)).isEmpty();
    }

    @Test
    @DisplayName("Opportunities with an empty title give a title condition with empty quotes")
    void opportunitiesWithEmptyTitleGiveEmptyQuotedCondition() {
        assertThat(builder.opportunities("")).hasValue("title START_WITH \"\"");
    }

    @Test
    @DisplayName("Opportunities with a title give the title START_WITH condition")
    void opportunitiesWithTitleGiveTitleCondition() {
        assertThat(builder.opportunities("Another Company"))
                .hasValue("title START_WITH \"Another Company\"");
        assertThat(builder.opportunities("Short wave")).hasValue("title START_WITH \"Short wave\"");
    }

    @ParameterizedTest(name = "Opportunity title {0} is refused")
    @ValueSource(strings = {"Bob \"The\" Builder", "\""})
    @DisplayName("Opportunities with a title holding a double quote are refused as a bad request")
    void opportunitiesWithDoubleQuoteAreRefused(String title) {
        assertThatThrownBy(() -> builder.opportunities(title))
                .isInstanceOf(BadRequestException.class)
                .hasMessage(QUOTE_REFUSED_MESSAGE);
    }

    @Test
    @DisplayName("Opportunities with a title holding a single quote keep the title unchanged")
    void opportunitiesWithSingleQuoteKeepTitle() {
        assertThat(builder.opportunities("O'Brien")).hasValue("title START_WITH \"O'Brien\"");
    }
}
