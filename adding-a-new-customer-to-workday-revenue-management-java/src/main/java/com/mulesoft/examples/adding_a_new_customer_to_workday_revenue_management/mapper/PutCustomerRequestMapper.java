package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper;

import com.workday.bsvc.BusinessEntityStatusValueObjectIDType;
import com.workday.bsvc.BusinessEntityStatusValueObjectType;
import com.workday.bsvc.CustomerBusinessEntityWWSDataType;
import com.workday.bsvc.CustomerCategoryObjectIDType;
import com.workday.bsvc.CustomerCategoryObjectType;
import com.workday.bsvc.CustomerStatusDataType;
import com.workday.bsvc.CustomerWWSDataType;
import com.workday.bsvc.PutCustomerRequestType;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Maps a {@code root/Account} request document to a Workday {@code Put_Customer} request (DW-01).
 *
 * <p>The six {@code Account} elements of {@code root.xsd} fill the request as follows:
 * <ul>
 *   <li>{@code CustomerName} fills {@code Customer_ID}, {@code Customer_Reference_ID} and
 *       {@code Customer_Name};</li>
 *   <li>{@code BusinessEntityName} fills {@code Business_Entity_Data/Business_Entity_Name};</li>
 *   <li>{@code Customer_Category_Reference_Type} and {@code Customer_Category_Reference_Value} fill the
 *       {@code type} attribute and the value of {@code Customer_Category_Reference/ID};</li>
 *   <li>{@code Customer_Status_Reference_Type} and {@code Customer_Status_Reference_Value} fill the
 *       {@code type} attribute and the value of
 *       {@code Customer_Status_Data/Customer_Status_Value_Reference/ID}.</li>
 * </ul>
 * The request carries nothing else: no {@code Customer_Reference}, no {@code Add_Only} and no
 * {@code version}.
 *
 * <p>Input elements are matched by local name in any namespace, from a namespace-aware or a
 * non-namespace-aware DOM alike. Each step reads the first matching child element, skipping text,
 * comment and other non-element nodes, and the text content of the leaf is used unchanged. An absent
 * input element, a document element other than {@code root}, a missing {@code Account} or a
 * {@code null} document gives the empty string for the corresponding values, which marshals as an
 * empty element or an empty {@code type} attribute; no value or {@code type} is ever {@code null}.
 * Every container ({@code Customer_Data}, {@code Business_Entity_Data},
 * {@code Customer_Category_Reference}, {@code Customer_Status_Data},
 * {@code Customer_Status_Value_Reference} and both {@code ID}s) is always created. Marshalled through
 * JAXB, the {@code Customer_Data} children follow the Workday WWS v35.0 schema sequence (D-323).
 *
 * <p>The mapper holds no state, never throws for missing structure and is safe for concurrent use.
 *
 * <p>Usage:
 * <pre>{@code
 * Document account = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(new InputSource(
 *         new StringReader("<root><Account><CustomerName>John Doe</CustomerName></Account></root>")));
 * PutCustomerRequestType request = new PutCustomerRequestMapper().toPutCustomerRequest(account);
 * // request.getCustomerData().getCustomerName() is "John Doe";
 * // request.getCustomerData().getBusinessEntityData().getBusinessEntityName() is ""
 * JAXBElement<PutCustomerRequestType> element = new ObjectFactory().createPutCustomerRequest(request);
 * }</pre>
 */
@Component
public class PutCustomerRequestMapper {

    /**
     * Builds the {@code Put_Customer_Request} payload from {@code payload.root.Account} (DW-01).
     *
     * <p>Each of the six {@code Account} elements is read once. The returned object holds one
     * {@code Customer_Data} with one {@code Business_Entity_Data}, one {@code Customer_Category_Reference}
     * holding exactly one {@code ID}, and exactly one {@code Customer_Status_Data} whose
     * {@code Customer_Status_Value_Reference} holds exactly one {@code ID}. Values and {@code type}
     * attributes whose input element is absent are the empty string (D-323).
     *
     * @param document the request document, the DOM of {@code root.xsd}'s {@code root} element; may be
     *     {@code null}
     * @return a new request, never {@code null}; it is not wrapped in its {@code Put_Customer_Request}
     *     element, which {@code ObjectFactory.createPutCustomerRequest} supplies
     */
    public PutCustomerRequestType toPutCustomerRequest(Document document) {
        // An absent input value is written as "": an empty element or an empty type attribute (DW-01, D-323).
        String customerName = written(accountField(document, "CustomerName"));
        String businessEntityName = written(accountField(document, "BusinessEntityName"));
        String categoryType = written(accountField(document, "Customer_Category_Reference_Type"));
        String categoryValue = written(accountField(document, "Customer_Category_Reference_Value"));
        String statusType = written(accountField(document, "Customer_Status_Reference_Type"));
        String statusValue = written(accountField(document, "Customer_Status_Reference_Value"));

        // Customer_Data: Customer_ID, Customer_Reference_ID and Customer_Name all take CustomerName.
        CustomerWWSDataType data = new CustomerWWSDataType();
        data.setCustomerID(customerName);
        data.setCustomerReferenceID(customerName);
        data.setCustomerName(customerName);

        // Business_Entity_Data/Business_Entity_Name.
        CustomerBusinessEntityWWSDataType businessEntity = new CustomerBusinessEntityWWSDataType();
        businessEntity.setBusinessEntityName(businessEntityName);
        data.setBusinessEntityData(businessEntity);

        // Customer_Category_Reference/ID: value and namespace-qualified type attribute.
        CustomerCategoryObjectIDType categoryId = new CustomerCategoryObjectIDType();
        categoryId.setType(categoryType);
        categoryId.setValue(categoryValue);
        CustomerCategoryObjectType category = new CustomerCategoryObjectType();
        category.getID().add(categoryId);
        data.setCustomerCategoryReference(category);

        // Customer_Status_Data/Customer_Status_Value_Reference/ID: value and namespace-qualified type attribute.
        BusinessEntityStatusValueObjectIDType statusId = new BusinessEntityStatusValueObjectIDType();
        statusId.setType(statusType);
        statusId.setValue(statusValue);
        BusinessEntityStatusValueObjectType statusValueReference = new BusinessEntityStatusValueObjectType();
        statusValueReference.getID().add(statusId);
        CustomerStatusDataType status = new CustomerStatusDataType();
        status.setCustomerStatusValueReference(statusValueReference);
        data.getCustomerStatusData().add(status);

        // JAXB marshals these children in the v35.0 schema sequence (D-323).
        PutCustomerRequestType request = new PutCustomerRequestType();
        request.setCustomerData(data);
        return request;
    }

    /**
     * Returns the value DW-01 writes for a selector result: {@code value} itself, or the empty string
     * when it is {@code null}.
     */
    private static String written(String value) {
        return value == null ? "" : value;
    }

    /**
     * Returns the text content of {@code payload.root.Account.<field>}: the first {@code field} child
     * element of the first {@code Account} child element of the document element {@code root}, all
     * matched by local name in any namespace.
     *
     * @return the unchanged text content, or {@code null} when the document is {@code null}, the
     *     document element is absent or not {@code root}, or no {@code Account} or {@code field}
     *     element is found
     */
    private static String accountField(Document document, String field) {
        if (document == null) {
            return null;
        }
        Element root = document.getDocumentElement();
        if (root == null || !"root".equals(localName(root))) {
            return null;
        }
        Element account = firstChildElement(root, "Account");
        if (account == null) {
            return null;
        }
        Element leaf = firstChildElement(account, field);
        return leaf == null ? null : leaf.getTextContent();
    }

    /**
     * Returns the first child element of {@code parent} whose local name is {@code name}, or
     * {@code null} when there is none. Non-element children are skipped.
     */
    private static Element firstChildElement(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(child))) {
                return (Element) child;
            }
        }
        return null;
    }

    /**
     * Returns the local name of {@code node}: {@link Node#getLocalName()}, or, when a
     * non-namespace-aware DOM leaves it {@code null}, {@link Node#getNodeName()} without any
     * {@code prefix:}.
     */
    private static String localName(Node node) {
        String local = node.getLocalName();
        if (local != null) {
            return local;
        }
        String qualifiedName = node.getNodeName();
        int colon = qualifiedName.indexOf(':');
        return colon < 0 ? qualifiedName : qualifiedName.substring(colon + 1);
    }
}
