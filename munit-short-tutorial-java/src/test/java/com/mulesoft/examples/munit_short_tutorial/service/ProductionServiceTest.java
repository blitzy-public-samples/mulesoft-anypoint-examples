package com.mulesoft.examples.munit_short_tutorial.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ProductionService}, ported from the MUnit suite
 * {@code munit-short-tutorial/src/test/munit/production-code-test-suite.xml} (D-057).
 *
 * <p>Each ported test carries the MUnit {@code munit:test} name as its display name. The unit
 * under test is a Mockito spy of a plain {@link ProductionService}, with no Spring context:
 * <ul>
 *   <li>{@code mock:verify-call messageProcessor="mule:sub-flow" times="1"} is
 *       {@code verify(service, times(1))} on the sub-flow method;</li>
 *   <li>{@code mock:when messageProcessor="mule:flow"} on {@code exampleFlow2}, returning the
 *       invocation property {@code my_variable}, is {@code doReturn(...)} on
 *       {@link ProductionService#exampleFlow2(String)};</li>
 *   <li>{@code mock:when} on the {@code Set Original Payload} processor, returning {@code #[]},
 *       is a {@code null} {@code urlKey};</li>
 *   <li>the inbound {@code url_key} query parameter is the {@code urlKey} argument;</li>
 *   <li>{@code munit:assert-payload-equals} is {@code assertEquals} with the MUnit message
 *       {@code "oops, wrong payload!"}.</li>
 * </ul>
 *
 * <p>The tests cover every line of {@link ProductionService} (D-049).
 */
class ProductionServiceTest {

    /** The {@code message} of every MUnit {@code assert-payload-equals} in the suite. */
    private static final String MESSAGE = "oops, wrong payload!";

    private ProductionService service;

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new ProductionService());
    }

    // ---------------------------------------------------------------- exampleFlow2 tests

    /**
     * MUnit {@code doc-test-exampleFlow2Test1}: payload {@code payload_1} through flow
     * {@code exampleFlow2} calls sub-flow {@code exampleSub_Flow1} once.
     */
    @Test
    @DisplayName("doc-test-exampleFlow2Test1")
    void docTestExampleFlow2Test1() {
        service.exampleFlow2("payload_1");

        verify(service, times(1)).exampleSubFlow1();
    }

    /**
     * MUnit {@code doc-test-exampleFlow2Test2}: payload {@code payload_2} through flow
     * {@code exampleFlow2} calls sub-flow {@code exampleSub_Flow2} once.
     */
    @Test
    @DisplayName("doc-test-exampleFlow2Test2")
    void docTestExampleFlow2Test2() {
        service.exampleFlow2("payload_2");

        verify(service, times(1)).exampleSubFlow2();
    }

    // ------------------------------------------------------------ exampleFlow unit tests

    /**
     * MUnit {@code doc-test-exampleFlow-unit-Test_1}: with {@code exampleFlow2} mocked to set
     * {@code my_variable} to {@code var_value_1}, flow {@code exampleFlow} returns
     * {@code response_payload_1}.
     */
    @Test
    @DisplayName("doc-test-exampleFlow-unit-Test_1")
    void docTestExampleFlowUnitTest1() {
        doReturn("var_value_1").when(service).exampleFlow2(any());

        assertEquals("response_payload_1", service.exampleFlow(null), MESSAGE);
    }

    /**
     * MUnit {@code doc-test-exampleFlow-unit-Test_2}: with {@code exampleFlow2} mocked to set
     * {@code my_variable} to {@code var_value_2}, flow {@code exampleFlow} returns
     * {@code response_payload_2}.
     */
    @Test
    @DisplayName("doc-test-exampleFlow-unit-Test_2")
    void docTestExampleFlowUnitTest2() {
        doReturn("var_value_2").when(service).exampleFlow2(any());

        assertEquals("response_payload_2", service.exampleFlow(null), MESSAGE);
    }

    // ------------------------------------------------------ exampleFlow functional tests

    /**
     * MUnit {@code doc-test-exampleFlow-functionalTest_1}: query parameter
     * {@code url_key=payload_1} through flow {@code exampleFlow}, with nothing mocked, returns
     * {@code response_payload_1}.
     */
    @Test
    @DisplayName("doc-test-exampleFlow-functionalTest_1")
    void docTestExampleFlowFunctionalTest1() {
        assertEquals("response_payload_1", service.exampleFlow("payload_1"), MESSAGE);
    }

    /**
     * MUnit {@code doc-test-exampleFlow-functionalTest_2}: query parameter
     * {@code url_key=payload_2} through flow {@code exampleFlow}, with nothing mocked, returns
     * {@code response_payload_2}.
     */
    @Test
    @DisplayName("doc-test-exampleFlow-functionalTest_2")
    void docTestExampleFlowFunctionalTest2() {
        assertEquals("response_payload_2", service.exampleFlow("payload_2"), MESSAGE);
    }

    // ---------------------------------------------------------------- null my_variable

    /**
     * Not part of the MUnit suite; it carries no MUnit or scenario display name.
     *
     * <p>With {@code exampleFlow2} returning {@code null}, flow {@code exampleFlow} throws
     * {@link NullPointerException}, as the choice expression
     * {@code flowVars['my_variable'].equals('var_value_1')} does on a {@code null}
     * {@code my_variable} [munit-short-tutorial/src/main/app/production-code.xml:14].
     */
    @Test
    void exampleFlow2ReturningNullThrows() {
        doReturn(null).when(service).exampleFlow2(any());

        assertThrows(NullPointerException.class, () -> service.exampleFlow("payload_1"));
    }
}
