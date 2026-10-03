package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config.JmsConfig;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.exception.MyException;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper.OrderXmlMapper;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.invocation.Invocation;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit tests of {@link OrderTransactionService#transactionsFlow1(String)}, the body of the flow
 * {@code transactionsFlow1} [using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:6-22], of
 * {@link TestComponent#process(Order)}, its component step [:17], and of {@link MyException}. Decisions: D-025,
 * D-049, D-571.
 *
 * <ul>
 *   <li>The order read by {@link OrderXmlMapper} is logged at INFO as {@code XXXXXXX order: <order>} [:11] before the
 *       insert, inserted once with {@link #INSERT_SQL} and its three named parameters [:13-16], and passed to
 *       {@link TestComponent}; its {@link MyException} leaves the method unchanged and nothing is sent.</li>
 *   <li>A component result is sent to the outbound queue {@code out} [:18-20] after the insert and the component
 *       call.</li>
 *   <li>A mapper failure leaves the method unchanged and no insert, component call or send runs.</li>
 *   <li>The method carries {@code @Transactional} with {@code rollbackFor} {@link Exception} (D-025, D-571).</li>
 *   <li>The ported {@link TestComponent} always throws {@code MyException("exception customized")}.</li>
 *   <li>{@code new MyException()} reports the error {@code test}.</li>
 * </ul>
 *
 * <p>No Spring context starts. {@link OrderXmlMapper}, {@link NamedParameterJdbcTemplate}, {@link TestComponent} and
 * {@link JmsTemplate} are Mockito mocks with strict stubs; {@link JmsConfig.ActiveMqProperties} is the record with
 * the original literals {@code 2}, {@code in} and {@code out}. The INFO line is read from the console output that
 * {@link OutputCaptureExtension} captures during the test. The tests run under surefire in the {@code test} phase and
 * cover the {@code service} package for the JaCoCo LINE check (D-049).
 */
@ExtendWith(MockitoExtension.class)
public class OrderTransactionServiceTest {

    /** The order message of the original test, {@code src/test/resources/original/message.xml}, on one line. */
    private static final String XML =
            "<order><itemId>1</itemId><itemUnits>2</itemUnits><customerId>1</customerId></order>";

    /** The insert of transactions.xml:14, byte-identical to the statement the service runs. */
    private static final String INSERT_SQL =
            "insert into orders(item_id,item_units,customer_id) values (:itemId, :itemUnits, :customerId)";

    /** The INFO message logged for {@link #order()}. */
    private static final String ORDER_LOG_LINE = "XXXXXXX order: Order [item_id=1, item_units=2, customer_id=1]";

    /**
     * Message of the exception {@link TestComponent#process(Order)} throws
     * [using-transactional-scope-in-jms-to-database/src/main/java/org/mule/examples/TestComponent.java:20].
     */
    private static final String COMPONENT_MESSAGE = "exception customized";

    @Mock
    private OrderXmlMapper orderXmlMapper;

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Mock
    private TestComponent testComponent;

    @Mock
    private JmsTemplate jmsTemplate;

    /** The {@code active-mq.*} keys with the original literals [transactions.xml:3,7,18]. */
    private JmsConfig.ActiveMqProperties props;

    private OrderTransactionService service;

    /** Builds the properties record and the service on the four mocks; stubs nothing. */
    @BeforeEach
    void setUp() {
        props = new JmsConfig.ActiveMqProperties(2, "in", "out");
        service = new OrderTransactionService(orderXmlMapper, jdbcTemplate, testComponent, jmsTemplate, props);
    }

    /**
     * Asserts that the component's {@code MyException("exception customized")} is the exception the method throws,
     * after one INFO line {@code XXXXXXX order: Order [item_id=1, item_units=2, customer_id=1]} from the
     * {@code OrderTransactionService} logger and then exactly one insert of {@link #INSERT_SQL} with {@code itemId=1},
     * {@code itemUnits=2} and {@code customerId=1} bound in that order, that the component receives the mapped order,
     * and that nothing is sent [transactions.xml:11-21].
     *
     * @param output the console output captured during the test
     * @throws Exception never; the declared exception of {@link OrderTransactionService#transactionsFlow1(String)}
     */
    @Test
    @DisplayName("transactionsFlow1 rethrows MyException after the insert and sends nothing to out")
    @ExtendWith(OutputCaptureExtension.class)
    public void transactionsFlow1PropagatesMyExceptionAfterInsertAndSendsNothing(CapturedOutput output)
            throws Exception {
        Order order = order();
        MyException failure = new MyException(COMPONENT_MESSAGE);
        AtomicReference<String> outputAtInsert = new AtomicReference<>();
        when(orderXmlMapper.toOrder(XML)).thenReturn(order);
        doAnswer(invocation -> {
            outputAtInsert.set(output.getOut());
            return 1;
        }).when(jdbcTemplate).update(eq(INSERT_SQL), any(SqlParameterSource.class));
        when(testComponent.process(order)).thenThrow(failure);

        MyException thrown = assertThrows(MyException.class, () -> service.transactionsFlow1(XML));

        assertSame(failure, thrown);
        assertEquals(COMPONENT_MESSAGE, thrown.getMessage());
        List<Invocation> updates = updateInvocations();
        assertEquals(1, updates.size(), "update invocations");
        Invocation update = updates.get(0);
        assertEquals(INSERT_SQL, update.getArgument(0));
        Object params = update.getArgument(1);
        assertArrayEquals(new String[] {"itemId", "itemUnits", "customerId"},
                assertInstanceOf(SqlParameterSource.class, params).getParameterNames());
        assertEquals(1, paramValue(params, "itemId"), "itemId");
        assertEquals(2, paramValue(params, "itemUnits"), "itemUnits");
        assertEquals(1, paramValue(params, "customerId"), "customerId");
        verify(testComponent).process(order);
        verify(jmsTemplate, never()).convertAndSend(anyString(), any(Object.class));
        verifyNoMoreInteractions(jmsTemplate);

        assertNotNull(outputAtInsert.get(), "console output at the insert");
        assertEquals(1, outputAtInsert.get().lines()
                .filter(line -> line.contains(" INFO ") && line.contains(OrderTransactionService.class.getSimpleName())
                        && line.endsWith(ORDER_LOG_LINE))
                .count(), "INFO lines logged before the insert");
        assertEquals(1, output.getOut().lines().filter(line -> line.contains("XXXXXXX order: ")).count(),
                "order lines logged");
    }

    /**
     * Asserts that a component returning {@code RESULT} has that result sent to queue {@code out}, in this order:
     * the insert of {@link #INSERT_SQL}, the component call with the mapped order, the send; and that no other
     * template call runs [transactions.xml:13-20].
     *
     * @throws Exception never; the declared exception of {@link OrderTransactionService#transactionsFlow1(String)}
     */
    @Test
    @DisplayName("transactionsFlow1 inserts the order before it sends the component result to out")
    public void transactionsFlow1InsertsBeforeSendingResultToOutboundQueue() throws Exception {
        Order order = order();
        when(orderXmlMapper.toOrder(XML)).thenReturn(order);
        when(testComponent.process(order)).thenReturn("RESULT");

        service.transactionsFlow1(XML);

        InOrder steps = inOrder(jdbcTemplate, testComponent, jmsTemplate);
        steps.verify(jdbcTemplate).update(eq(INSERT_SQL), any(SqlParameterSource.class));
        steps.verify(testComponent).process(order);
        steps.verify(jmsTemplate).convertAndSend("out", "RESULT");
        verifyNoMoreInteractions(jdbcTemplate, testComponent, jmsTemplate);
    }

    /**
     * Asserts that the mapper's {@link IllegalArgumentException} is the exception the method throws, and that no
     * insert, component call or send runs [transactions.xml:8-10].
     *
     * @throws Exception never; the declared exception of {@link OrderTransactionService#transactionsFlow1(String)}
     */
    @Test
    @DisplayName("transactionsFlow1 rethrows a mapper failure without calling the database, the component or JMS")
    public void transactionsFlow1PropagatesMapperFailureWithoutTouchingCollaborators() throws Exception {
        String notAnOrder = "<orders><itemId>1</itemId></orders>";
        IllegalArgumentException failure = new IllegalArgumentException("not an order");
        when(orderXmlMapper.toOrder(notAnOrder)).thenThrow(failure);

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.transactionsFlow1(notAnOrder));

        assertSame(failure, thrown);
        verifyNoInteractions(jdbcTemplate, testComponent, jmsTemplate);
    }

    /**
     * Asserts that {@link OrderTransactionService#transactionsFlow1(String)} carries {@link Transactional} and that
     * its {@code rollbackFor} contains {@link Exception} (D-025, D-571).
     *
     * @throws NoSuchMethodException when the service has no public {@code transactionsFlow1(String)}
     */
    @Test
    @DisplayName("transactionsFlow1 is @Transactional with rollbackFor Exception")
    public void transactionsFlow1IsTransactionalWithRollbackForException() throws NoSuchMethodException {
        Method method = OrderTransactionService.class.getMethod("transactionsFlow1", String.class);

        Transactional transactional = method.getAnnotation(Transactional.class);

        assertNotNull(transactional, "@Transactional on transactionsFlow1(String)");
        assertTrue(List.of(transactional.rollbackFor()).contains(Exception.class), "rollbackFor contains Exception");
    }

    /**
     * Asserts that the ported {@link TestComponent} throws {@link MyException} whose message and
     * {@link MyException#getError()} are {@code exception customized}
     * [using-transactional-scope-in-jms-to-database/src/main/java/org/mule/examples/TestComponent.java:20].
     */
    @Test
    @DisplayName("TestComponent.process throws MyException with message and error exception customized")
    public void testComponentAlwaysThrowsMyException() {
        MyException thrown = assertThrows(MyException.class, () -> new TestComponent().process(order()));

        assertEquals(COMPONENT_MESSAGE, thrown.getMessage());
        assertEquals(COMPONENT_MESSAGE, thrown.getError());
    }

    /**
     * Asserts that {@link MyException#MyException()} sets {@link MyException#getError()} to {@code test}
     * [using-transactional-scope-in-jms-to-database/src/main/java/org/exceptions/MyException.java:18-21].
     */
    @Test
    @DisplayName("new MyException() reports the error test")
    public void myExceptionNoArgConstructorSetsErrorToTest() {
        assertEquals("test", new MyException().getError());
    }

    /**
     * Builds the order of {@link #XML} through its setters.
     *
     * @return a new order with {@code itemId} 1, {@code itemUnits} 2 and {@code customerId} 1
     */
    private static Order order() {
        Order order = new Order();
        order.setItemId(1);
        order.setItemUnits(2);
        order.setCustomerId(1);
        return order;
    }

    /**
     * Lists the calls of any {@code update} overload recorded on the mocked {@link NamedParameterJdbcTemplate}.
     *
     * @return the {@code update} invocations in call order
     */
    private List<Invocation> updateInvocations() {
        return mockingDetails(jdbcTemplate).getInvocations().stream()
                .filter(invocation -> "update".equals(invocation.getMethod().getName()))
                .toList();
    }

    /**
     * Reads one named value from the parameter argument of an {@code update} call.
     *
     * @param params the parameter argument; asserted to be an {@link SqlParameterSource} holding {@code name}
     * @param name   the parameter name
     * @return the value bound to {@code name}
     */
    private static Object paramValue(Object params, String name) {
        SqlParameterSource source = assertInstanceOf(SqlParameterSource.class, params);
        assertTrue(source.hasValue(name), "parameter " + name + " is bound");
        return source.getValue(name);
    }
}
