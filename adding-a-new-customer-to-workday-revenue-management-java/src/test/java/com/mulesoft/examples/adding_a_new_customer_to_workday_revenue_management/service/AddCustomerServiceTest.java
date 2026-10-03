package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.WorkdayRevenueClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamRateLimitException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamUnavailableException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper.PutCustomerRequestMapper;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper.PutCustomerResponseMapper;
import com.workday.bsvc.BusinessEntityStatusValueObjectIDType;
import com.workday.bsvc.CustomerCategoryObjectIDType;
import com.workday.bsvc.CustomerObjectType;
import com.workday.bsvc.CustomerStatusDataType;
import com.workday.bsvc.CustomerWWSDataType;
import com.workday.bsvc.PutCustomerRequestType;
import com.workday.bsvc.PutCustomerResponseType;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.xml.sax.SAXException;

/**
 * Unit tests of {@link AddCustomerService#addCustomerFlow(byte[])}, the flow {@code add-customer-flow}
 * [adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:7-54].
 *
 * <p>Each test builds the service directly, with no Spring application context, over the real DW-01
 * {@link PutCustomerRequestMapper}, the real DW-02 {@link PutCustomerResponseMapper} and a Mockito mock of
 * {@link WorkdayRevenueClient}, and asserts
 * <ul>
 *   <li>the request fixture {@value #FIXTURE} reaches {@code Put_Customer} once, mapped by DW-01, and the
 *       reply is {@code Added customer: } followed by the returned descriptor, unchanged (D-578);</li>
 *   <li>a {@code null} answer, an answer without {@code Customer_Reference} and a reference without
 *       {@code Descriptor} each give the reply {@code Added customer: {NullPayload}} (D-578);</li>
 *   <li>an {@link UpstreamAuthenticationException}, {@link UpstreamRateLimitException} or
 *       {@link UpstreamUnavailableException} of the client reaches the caller as the same instance, after
 *       exactly one {@code Put_Customer} call;</li>
 *   <li>a malformed body, an empty or {@code null} body, and a body with a DOCTYPE declaration when the
 *       JAXP parser disallows DOCTYPE, each throw {@link IllegalArgumentException} with no client call
 *       (D-580);</li>
 *   <li>a body that declares an unsupported encoding throws {@link UncheckedIOException} with no client
 *       call (D-580).</li>
 * </ul>
 * The {@code :52} step is the return value of the service and writes no log line (D-579). The fixture is the
 * test-classpath resource {@value #FIXTURE}, the request document of the original suite, read and never
 * written. These tests cover the {@code service} package under the JaCoCo LINE covered ratio rule of at
 * least 0.80 (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
@ExtendWith(MockitoExtension.class)
public class AddCustomerServiceTest {

    /** Absolute test-classpath location of the original suite's request fixture. */
    private static final String FIXTURE = "/original/customer.xml";

    /** The {@code CustomerName} and {@code BusinessEntityName} of {@value #FIXTURE}. */
    private static final String FIXTURE_NAME = "NAME";

    /** The {@code Customer_Reference} descriptor returned by the mocked {@code Put_Customer}. */
    private static final String DESCRIPTOR = "John Doe";

    /** The {@code upstreamSystem} of every client exception of this project. */
    private static final String WORKDAY = "Workday";

    /** Xerces feature that rejects any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** A well-formed {@code root/Account} body that starts with a DOCTYPE declaration. */
    private static final String DOCTYPE_BODY = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<!DOCTYPE root>"
            + "<root><Account><CustomerName>John Doe</CustomerName></Account></root>";

    /** The {@code Put_Customer} client, mocked. */
    @Mock
    private WorkdayRevenueClient workdayRevenueClient;

    /** The service under test. */
    private AddCustomerService service;

    /**
     * Builds the service over the real DW-01 and DW-02 mappers and the mocked client.
     */
    @BeforeEach
    void createService() {
        service = new AddCustomerService(
                new PutCustomerRequestMapper(), workdayRevenueClient, new PutCustomerResponseMapper());
    }

    /**
     * Asserts the fixture body reaches {@code Put_Customer} exactly once as the DW-01 request of
     * {@value #FIXTURE}: {@code NAME} in {@code Customer_ID}, {@code Customer_Reference_ID},
     * {@code Customer_Name} and {@code Business_Entity_Name}, category {@code ID} of type
     * {@code Customer_Category_ID} with value {@code CUSTOMER_CATEGORY-5}, and status {@code ID} of type
     * {@code Business_Entity_Status_Value_ID} with value {@code ACTIVE}; that the descriptor
     * {@code John Doe} gives the reply {@code Added customer: John Doe}; and that an empty and a
     * space-padded descriptor are appended unchanged (D-578).
     *
     * @throws IOException when the fixture cannot be read
     */
    @Test
    @DisplayName("Put_Customer descriptor is appended to the Added customer reply")
    public void descriptorOfPutCustomerAnswerIsAppendedToReply() throws IOException {
        byte[] body = fixture();
        when(workdayRevenueClient.putCustomer(any())).thenReturn(
                responseWithDescriptor(DESCRIPTOR), responseWithDescriptor(""), responseWithDescriptor("  John  Doe "));

        assertThat(service.addCustomerFlow(body)).isEqualTo("Added customer: John Doe");

        ArgumentCaptor<PutCustomerRequestType> request = ArgumentCaptor.forClass(PutCustomerRequestType.class);
        verify(workdayRevenueClient, times(1)).putCustomer(request.capture());
        assertFixtureRequest(request.getValue());

        assertThat(service.addCustomerFlow(body)).isEqualTo("Added customer: ");
        assertThat(service.addCustomerFlow(body)).isEqualTo("Added customer:   John  Doe ");
        verify(workdayRevenueClient, times(3)).putCustomer(any());
        verifyNoMoreInteractions(workdayRevenueClient);
    }

    /**
     * Asserts a {@code null} {@code Put_Customer} answer, an answer without {@code Customer_Reference} and a
     * {@code Customer_Reference} without {@code Descriptor} each give the reply
     * {@code Added customer: {NullPayload}}, one {@code Put_Customer} call per request (D-578).
     *
     * @throws IOException when the fixture cannot be read
     */
    @Test
    @DisplayName("Absent descriptor yields the Added customer {NullPayload} reply")
    public void absentDescriptorYieldsNullPayloadReply() throws IOException {
        byte[] body = fixture();
        when(workdayRevenueClient.putCustomer(any())).thenReturn(
                null, new PutCustomerResponseType(), responseWithDescriptor(null));

        assertThat(service.addCustomerFlow(body)).isEqualTo("Added customer: {NullPayload}");
        assertThat(service.addCustomerFlow(body)).isEqualTo("Added customer: {NullPayload}");
        assertThat(service.addCustomerFlow(body)).isEqualTo("Added customer: {NullPayload}");

        verify(workdayRevenueClient, times(3)).putCustomer(any());
        verifyNoMoreInteractions(workdayRevenueClient);
    }

    /**
     * Asserts an {@link UpstreamAuthenticationException} of {@code Put_Customer} reaches the caller as the
     * same instance after exactly one client call.
     *
     * @throws IOException when the fixture cannot be read
     */
    @Test
    @DisplayName("Upstream authentication failure propagates unchanged after one Put_Customer call")
    public void upstreamAuthenticationExceptionPropagatesUnchanged() throws IOException {
        assertPropagatesUnchangedAfterOneCall(new UpstreamAuthenticationException(
                WORKDAY, "Workday authentication failed for Put_Customer", new IllegalStateException("401")));
    }

    /**
     * Asserts an {@link UpstreamRateLimitException} of {@code Put_Customer}, carrying the {@code Retry-After}
     * value {@code 30}, reaches the caller as the same instance after exactly one client call.
     *
     * @throws IOException when the fixture cannot be read
     */
    @Test
    @DisplayName("Upstream rate limit propagates unchanged after one Put_Customer call")
    public void upstreamRateLimitExceptionPropagatesUnchanged() throws IOException {
        assertPropagatesUnchangedAfterOneCall(new UpstreamRateLimitException(
                WORKDAY, "Workday rate limit exceeded for Put_Customer", "30", new IllegalStateException("429")));
    }

    /**
     * Asserts an {@link UpstreamUnavailableException} of {@code Put_Customer} reaches the caller as the same
     * instance after exactly one client call: the write is not re-sent.
     *
     * @throws IOException when the fixture cannot be read
     */
    @Test
    @DisplayName("Upstream unavailability propagates unchanged after one Put_Customer call")
    public void upstreamUnavailableExceptionPropagatesUnchanged() throws IOException {
        assertPropagatesUnchangedAfterOneCall(new UpstreamUnavailableException(
                WORKDAY, "Workday unavailable for Put_Customer: Read timed out",
                new SocketTimeoutException("Read timed out")));
    }

    /**
     * Asserts an unclosed {@code root/Account} body and a body that is not XML each throw
     * {@link IllegalArgumentException} with the parser's message and its {@link SAXException} as cause, and
     * that {@code Put_Customer} is never called (D-580).
     */
    @Test
    @DisplayName("Malformed XML body is rejected before Put_Customer is called")
    public void malformedXmlIsRejectedBeforePutCustomer() {
        for (String body : List.of("<root><Account><CustomerName>John Doe</CustomerName>", "not xml")) {
            Throwable thrown = catchThrowable(() -> service.addCustomerFlow(body.getBytes(StandardCharsets.UTF_8)));

            assertThat(thrown).as("failure for %s", body)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasCauseInstanceOf(SAXException.class);
            assertThat(thrown.getMessage()).as("message for %s", body).isEqualTo(thrown.getCause().getMessage());
        }
        verifyNoInteractions(workdayRevenueClient);
    }

    /**
     * Asserts a zero-length body and a {@code null} body each throw {@link IllegalArgumentException} with the
     * message {@code Request body is empty} and no cause, and that {@code Put_Customer} is never called
     * (D-580).
     */
    @Test
    @DisplayName("Empty request body is rejected before Put_Customer is called")
    public void emptyBodyIsRejectedBeforePutCustomer() {
        for (byte[] body : new byte[][] {new byte[0], null}) {
            Throwable thrown = catchThrowable(() -> service.addCustomerFlow(body));

            assertThat(thrown).as("failure for a %s body", body == null ? "null" : "zero-length")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Request body is empty")
                    .hasNoCause();
        }
        verifyNoInteractions(workdayRevenueClient);
    }

    /**
     * Asserts a body whose XML declaration names the unsupported encoding {@code X-UNKNOWN} throws
     * {@link UncheckedIOException} with the parser's {@link UnsupportedEncodingException} as cause, and that
     * {@code Put_Customer} is never called (D-580).
     */
    @Test
    @DisplayName("Body with an unsupported declared encoding is rejected before Put_Customer is called")
    public void unsupportedDeclaredEncodingIsRejectedBeforePutCustomer() {
        byte[] body = "<?xml version=\"1.0\" encoding=\"X-UNKNOWN\"?><root/>".getBytes(StandardCharsets.UTF_8);

        Throwable thrown = catchThrowable(() -> service.addCustomerFlow(body));

        assertThat(thrown).isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(UnsupportedEncodingException.class);
        verifyNoInteractions(workdayRevenueClient);
    }

    /**
     * Asserts a well-formed body with a DOCTYPE declaration is rejected exactly when the JAXP parser
     * disallows DOCTYPE declarations: it then throws {@link IllegalArgumentException} with a
     * {@link SAXException} cause and {@code Put_Customer} is never called (D-580); otherwise it reaches
     * {@code Put_Customer} once and gives the reply {@code Added customer: John Doe}.
     */
    @Test
    @DisplayName("DOCTYPE body is rejected when the parser disallows DOCTYPE declarations")
    public void doctypeBodyIsRejectedWhenParserDisallowsDoctype() {
        byte[] body = DOCTYPE_BODY.getBytes(StandardCharsets.UTF_8);

        if (parserDisallowsDoctype()) {
            Throwable thrown = catchThrowable(() -> service.addCustomerFlow(body));

            assertThat(thrown).isInstanceOf(IllegalArgumentException.class).hasCauseInstanceOf(SAXException.class);
            verifyNoInteractions(workdayRevenueClient);
        } else {
            when(workdayRevenueClient.putCustomer(any())).thenReturn(responseWithDescriptor(DESCRIPTOR));

            assertThat(service.addCustomerFlow(body)).isEqualTo("Added customer: John Doe");
            verify(workdayRevenueClient, times(1)).putCustomer(any());
            verifyNoMoreInteractions(workdayRevenueClient);
        }
    }

    /**
     * Stubs {@code Put_Customer} to throw {@code failure}, runs the flow on the fixture, and asserts the
     * caller receives that same instance after exactly one client call.
     *
     * @param failure the client exception
     * @throws IOException when the fixture cannot be read
     */
    private void assertPropagatesUnchangedAfterOneCall(RuntimeException failure) throws IOException {
        byte[] body = fixture();
        when(workdayRevenueClient.putCustomer(any())).thenThrow(failure);

        Throwable thrown = catchThrowable(() -> service.addCustomerFlow(body));

        assertThat(thrown).isSameAs(failure);
        assertThat(thrown).hasFieldOrPropertyWithValue("upstreamSystem", WORKDAY);
        verify(workdayRevenueClient, times(1)).putCustomer(any());
        verifyNoMoreInteractions(workdayRevenueClient);
    }

    /**
     * Asserts {@code request} is the DW-01 mapping of {@value #FIXTURE}.
     *
     * @param request the request the client received
     */
    private static void assertFixtureRequest(PutCustomerRequestType request) {
        CustomerWWSDataType data = request.getCustomerData();
        assertThat(data.getCustomerID()).isEqualTo(FIXTURE_NAME);
        assertThat(data.getCustomerReferenceID()).isEqualTo(FIXTURE_NAME);
        assertThat(data.getCustomerName()).isEqualTo(FIXTURE_NAME);
        assertThat(data.getBusinessEntityData().getBusinessEntityName()).isEqualTo(FIXTURE_NAME);

        List<CustomerCategoryObjectIDType> categoryIds = data.getCustomerCategoryReference().getID();
        assertThat(categoryIds).hasSize(1);
        assertThat(categoryIds.get(0).getType()).isEqualTo("Customer_Category_ID");
        assertThat(categoryIds.get(0).getValue()).isEqualTo("CUSTOMER_CATEGORY-5");

        List<CustomerStatusDataType> statusData = data.getCustomerStatusData();
        assertThat(statusData).hasSize(1);
        List<BusinessEntityStatusValueObjectIDType> statusIds =
                statusData.get(0).getCustomerStatusValueReference().getID();
        assertThat(statusIds).hasSize(1);
        assertThat(statusIds.get(0).getType()).isEqualTo("Business_Entity_Status_Value_ID");
        assertThat(statusIds.get(0).getValue()).isEqualTo("ACTIVE");
    }

    /**
     * Builds a {@code Put_Customer} answer whose {@code Customer_Reference} carries {@code descriptor}.
     *
     * @param descriptor the {@code Descriptor} value, or {@code null} for none
     * @return the answer
     */
    private static PutCustomerResponseType responseWithDescriptor(String descriptor) {
        CustomerObjectType customerReference = new CustomerObjectType();
        customerReference.setDescriptor(descriptor);
        PutCustomerResponseType response = new PutCustomerResponseType();
        response.setCustomerReference(customerReference);
        return response;
    }

    /**
     * Reads every byte of the test-classpath resource {@value #FIXTURE}.
     *
     * @return the fixture bytes
     * @throws IOException when the resource is absent or cannot be read
     */
    private static byte[] fixture() throws IOException {
        try (InputStream in = AddCustomerServiceTest.class.getResourceAsStream(FIXTURE)) {
            if (in == null) {
                throw new IOException("Test resource not found: " + FIXTURE);
            }
            return in.readAllBytes();
        }
    }

    /**
     * Tells whether a JAXP {@link DocumentBuilderFactory} of this runtime accepts the feature that rejects
     * DOCTYPE declarations and reports it as set.
     *
     * @return {@code true} when the parser disallows DOCTYPE declarations
     */
    private static boolean parserDisallowsDoctype() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
            return factory.getFeature(DISALLOW_DOCTYPE_DECL);
        } catch (ParserConfigurationException e) {
            return false;
        }
    }
}
