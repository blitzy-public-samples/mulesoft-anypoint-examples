package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper;

import com.workday.bsvc.CustomerObjectType;
import com.workday.bsvc.PutCustomerResponseType;
import org.springframework.stereotype.Component;

/**
 * Maps a Workday {@code Put_Customer} response to the {@code Descriptor} of its
 * {@code Customer_Reference}: DW-02
 * [adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:44-48] of
 * {@code add-customer-flow}, which selects
 * {@code payload.ns0#Put_Customer_Response.ns0#Customer_Reference.@ns0#Descriptor} with {@code ns0}
 * bound to {@code urn:com.workday/bsvc}.
 *
 * <p>The input is the {@code Put_Customer_Response} element body that the Revenue_Management v35.0
 * {@code Put_Customer} operation returns, as the JAXB type {@link PutCustomerResponseType} generated
 * from {@code wsdl/Revenue_Management_v35.0.wsdl}. The output is the string value of the
 * {@code wd:Descriptor} attribute, for example {@code "John Doe"} for
 * {@code <wd:Customer_Reference wd:Descriptor="John Doe">}.
 *
 * <p>Instances hold no state; {@link #toDescriptor(PutCustomerResponseType)} has no side effects and
 * is safe for concurrent use.
 */
@Component
public class PutCustomerResponseMapper {

    /**
     * Maps a {@code Put_Customer} response to its {@code Customer_Reference} {@code Descriptor} (DW-02).
     *
     * <p>Returns {@code null} when the response is {@code null}, when it has no
     * {@code Customer_Reference}, or when the reference has no {@code Descriptor}. A present
     * {@code Descriptor} is returned exactly as received, with no trimming and no default value. The
     * method never throws.
     *
     * @param response the {@code Put_Customer_Response} returned by Workday, or {@code null}
     * @return the {@code Customer_Reference} {@code Descriptor}, or {@code null} when the response, the
     *         reference or the descriptor is absent
     */
    public String toDescriptor(PutCustomerResponseType response) {
        if (response == null) {
            return null;
        }
        CustomerObjectType customerReference = response.getCustomerReference();
        if (customerReference == null) {
            return null;
        }
        return customerReference.getDescriptor();
    }
}
