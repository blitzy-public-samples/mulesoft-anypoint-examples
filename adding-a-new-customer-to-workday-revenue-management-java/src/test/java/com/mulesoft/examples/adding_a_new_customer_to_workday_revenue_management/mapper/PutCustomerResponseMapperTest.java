package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.StringReader;

import javax.xml.transform.stream.StreamSource;

import com.workday.bsvc.CustomerObjectType;
import com.workday.bsvc.PutCustomerResponseType;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link PutCustomerResponseMapper#toDescriptor(PutCustomerResponseType)}, the DW-02 transform of
 * {@code add-customer-flow}: {@code Put_Customer_Response/Customer_Reference/@Descriptor} to a string.
 *
 * <p>Each test calls the mapper directly, with no Spring application context, on a generated
 * {@code com.workday.bsvc} response that is either built in code or unmarshalled by JAXB from the text of a
 * {@code Put_Customer_Response} element, and asserts
 * <ul>
 *   <li>the {@code urn:com.workday/bsvc} {@code Descriptor} of the {@code Customer_Reference} is returned exactly
 *       as received, with no trimming;</li>
 *   <li>{@code null} is returned, with no exception thrown, for a {@code null} response, a response without
 *       {@code Customer_Reference}, a reference without {@code Descriptor} and a reference whose only
 *       {@code Descriptor} attribute is unqualified.</li>
 * </ul>
 * These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio rule of at least 0.80
 * (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
public class PutCustomerResponseMapperTest {

    /** Namespace of the generated Workday elements and of their qualified attributes. */
    private static final String BSVC = "urn:com.workday/bsvc";

    /** A {@code Put_Customer_Response} whose {@code Customer_Reference} has the {@code Descriptor} John Doe. */
    private static final String RESPONSE_WITH_DESCRIPTOR = "<bsvc:Put_Customer_Response xmlns:bsvc=\"" + BSVC + "\">"
            + "<bsvc:Customer_Reference bsvc:Descriptor=\"John Doe\">"
            + "<bsvc:ID bsvc:type=\"Customer_ID\">John Doe</bsvc:ID>"
            + "</bsvc:Customer_Reference>"
            + "</bsvc:Put_Customer_Response>";

    /** JAXB context of the generated {@code com.workday.bsvc} package, created on first use. */
    private static JAXBContext responseJaxbContext;

    /** The mapper under test. */
    private final PutCustomerResponseMapper mapper = new PutCustomerResponseMapper();

    /**
     * Asserts a {@code Put_Customer_Response} whose {@code Customer_Reference} holds the {@code Descriptor}
     * {@code John Doe} and one {@code ID} maps to exactly {@code John Doe}, and that a {@code Descriptor} with
     * leading, inner and trailing spaces and an empty {@code Descriptor} are each returned unchanged.
     *
     * @throws JAXBException when the input cannot be unmarshalled
     */
    @Test
    @DisplayName("Put_Customer response maps to the Descriptor of its Customer_Reference")
    public void returnsPresentDescriptor() throws JAXBException {
        PutCustomerResponseType response = unmarshal(RESPONSE_WITH_DESCRIPTOR);

        assertThat(mapper.toDescriptor(response)).isEqualTo("John Doe");
        assertThat(mapper.toDescriptor(responseWithDescriptor("  John  Doe "))).isEqualTo("  John  Doe ");
        assertThat(mapper.toDescriptor(responseWithDescriptor(""))).isEqualTo("");
    }

    /**
     * Asserts a response without {@code Customer_Reference}, built in code and unmarshalled, maps to
     * {@code null} without an exception.
     *
     * @throws JAXBException when the input cannot be unmarshalled
     */
    @Test
    @DisplayName("Response without a Customer_Reference yields a null descriptor")
    public void returnsNullWhenCustomerReferenceAbsent() throws JAXBException {
        PutCustomerResponseType built = new PutCustomerResponseType();
        PutCustomerResponseType unmarshalled =
                unmarshal("<bsvc:Put_Customer_Response xmlns:bsvc=\"" + BSVC + "\" bsvc:version=\"v35.0\"/>");

        assertThatCode(() -> mapper.toDescriptor(built)).doesNotThrowAnyException();
        assertThat(mapper.toDescriptor(built)).isNull();
        assertThatCode(() -> mapper.toDescriptor(unmarshalled)).doesNotThrowAnyException();
        assertThat(mapper.toDescriptor(unmarshalled)).isNull();
    }

    /**
     * Asserts a {@code Customer_Reference} without a {@code urn:com.workday/bsvc} {@code Descriptor} maps to
     * {@code null} without an exception: a reference built in code with no descriptor set, one unmarshalled
     * without the attribute, and one unmarshalled with only an unqualified {@code Descriptor} attribute, which
     * DW-02's {@code @ns0#Descriptor} does not select.
     *
     * @throws JAXBException when the input cannot be unmarshalled
     */
    @Test
    @DisplayName("Customer_Reference without a Descriptor yields a null descriptor")
    public void returnsNullWhenDescriptorAbsent() throws JAXBException {
        PutCustomerResponseType built = responseWithDescriptor(null);
        PutCustomerResponseType unmarshalled = unmarshal("<bsvc:Put_Customer_Response xmlns:bsvc=\"" + BSVC + "\">"
                + "<bsvc:Customer_Reference><bsvc:ID bsvc:type=\"Customer_ID\">John Doe</bsvc:ID>"
                + "</bsvc:Customer_Reference>"
                + "</bsvc:Put_Customer_Response>");
        PutCustomerResponseType unqualified = unmarshal("<bsvc:Put_Customer_Response xmlns:bsvc=\"" + BSVC + "\">"
                + "<bsvc:Customer_Reference Descriptor=\"John Doe\"/>"
                + "</bsvc:Put_Customer_Response>");

        assertThatCode(() -> mapper.toDescriptor(built)).doesNotThrowAnyException();
        assertThat(mapper.toDescriptor(built)).isNull();
        assertThatCode(() -> mapper.toDescriptor(unmarshalled)).doesNotThrowAnyException();
        assertThat(mapper.toDescriptor(unmarshalled)).isNull();
        assertThat(unqualified.getCustomerReference()).isNotNull();
        assertThatCode(() -> mapper.toDescriptor(unqualified)).doesNotThrowAnyException();
        assertThat(mapper.toDescriptor(unqualified)).isNull();
    }

    /**
     * Asserts a {@code null} response maps to {@code null} without an exception.
     */
    @Test
    @DisplayName("Null response yields a null descriptor")
    public void returnsNullForNullResponse() {
        assertThatCode(() -> mapper.toDescriptor(null)).doesNotThrowAnyException();
        assertThat(mapper.toDescriptor(null)).isNull();
    }

    /**
     * Builds a response whose {@code Customer_Reference} carries {@code descriptor}.
     *
     * @param descriptor the {@code Descriptor} value, or {@code null} for none
     * @return the response
     */
    private static PutCustomerResponseType responseWithDescriptor(String descriptor) {
        CustomerObjectType customerReference = new CustomerObjectType();
        customerReference.setDescriptor(descriptor);
        PutCustomerResponseType response = new PutCustomerResponseType();
        response.setCustomerReference(customerReference);
        return response;
    }

    /**
     * Unmarshals the XML text {@code xml}, read as a {@link StreamSource}, as a {@link PutCustomerResponseType}.
     *
     * @param xml a {@code Put_Customer_Response} element
     * @return the unmarshalled response
     * @throws JAXBException when the text cannot be unmarshalled
     */
    private static PutCustomerResponseType unmarshal(String xml) throws JAXBException {
        return responseJaxbContext().createUnmarshaller()
                .unmarshal(new StreamSource(new StringReader(xml)), PutCustomerResponseType.class).getValue();
    }

    /**
     * Returns the JAXB context of the generated {@code com.workday.bsvc} package, read through its
     * {@code ObjectFactory}, creating it on the first call.
     *
     * @return the shared context
     * @throws JAXBException when the context cannot be created
     */
    private static synchronized JAXBContext responseJaxbContext() throws JAXBException {
        if (responseJaxbContext == null) {
            responseJaxbContext = JAXBContext.newInstance("com.workday.bsvc");
        }
        return responseJaxbContext;
    }
}
