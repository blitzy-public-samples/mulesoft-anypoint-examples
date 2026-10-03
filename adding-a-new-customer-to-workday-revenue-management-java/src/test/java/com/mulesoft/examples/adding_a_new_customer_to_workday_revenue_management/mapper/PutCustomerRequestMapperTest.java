package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.tuple;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import com.workday.bsvc.BusinessEntityStatusValueObjectIDType;
import com.workday.bsvc.CustomerCategoryObjectIDType;
import com.workday.bsvc.CustomerStatusDataType;
import com.workday.bsvc.CustomerWWSDataType;
import com.workday.bsvc.ObjectFactory;
import com.workday.bsvc.PutCustomerRequestType;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xmlunit.builder.Input;
import org.xmlunit.xpath.JAXPXPathEngine;

/**
 * Unit tests of {@link PutCustomerRequestMapper#toPutCustomerRequest(Document)}, the DW-01 transform of
 * {@code add-customer-flow}: {@code root/Account} to {@code Put_Customer_Request/Customer_Data}.
 *
 * <p>Each test calls the mapper directly, with no Spring application context, and asserts
 * <ul>
 *   <li>the generated {@code com.workday.bsvc} object the mapper returns for the original fixture, the README
 *       request body and a namespaced copy of the fixture: {@code CustomerName} in {@code Customer_ID},
 *       {@code Customer_Reference_ID} and {@code Customer_Name}, {@code BusinessEntityName} in
 *       {@code Business_Entity_Data/Business_Entity_Name}, and the category and status
 *       {@code *_Type}/{@code *_Value} pairs in the {@code type} attribute and value of exactly one {@code ID}
 *       each;</li>
 *   <li>the XML JAXB marshals from it: the {@code urn:com.workday/bsvc} root element, the {@code Customer_Data}
 *       child order of the v35.0 schema, the namespace-qualified {@code type} attributes, and no
 *       {@code Customer_Reference}, {@code Add_Only} or {@code version};</li>
 *   <li>the empty string for each value and {@code type} whose input is absent, one {@code Account} leaf at a
 *       time and all at once, with every container still created (D-323);</li>
 *   <li>input matching by local name in any namespace, from namespace-aware and non-namespace-aware DOMs, on the
 *       first matching element, with its text content unchanged.</li>
 * </ul>
 * The fixture is the test-classpath resource {@value #FIXTURE}, the request document of the original suite,
 * read and never written. These tests cover the {@code mapper} package under the JaCoCo LINE covered ratio
 * rule of at least 0.80 (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
public class PutCustomerRequestMapperTest {

    /** Namespace of the generated Workday elements and of their qualified attributes. */
    private static final String BSVC = "urn:com.workday/bsvc";

    /** Absolute test-classpath location of the original suite's request fixture. */
    private static final String FIXTURE = "/original/customer.xml";

    /** The {@code CustomerName} and {@code BusinessEntityName} of {@value #FIXTURE}. */
    private static final String FIXTURE_NAME = "NAME";

    /** The {@code CustomerName} and {@code BusinessEntityName} of the README request body. */
    private static final String README_NAME = "John Doe";

    /** Xerces feature that rejects any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** XPath of the marshalled {@code Customer_Data} element, with {@code bsvc} bound to {@value #BSVC}. */
    private static final String CUSTOMER_DATA = "/bsvc:Put_Customer_Request/bsvc:Customer_Data";

    /** XPath of the marshalled {@code Customer_ID} element. */
    private static final String CUSTOMER_ID = CUSTOMER_DATA + "/bsvc:Customer_ID";

    /** XPath of the marshalled {@code Customer_Reference_ID} element. */
    private static final String CUSTOMER_REFERENCE_ID = CUSTOMER_DATA + "/bsvc:Customer_Reference_ID";

    /** XPath of the marshalled {@code Customer_Name} element. */
    private static final String CUSTOMER_NAME = CUSTOMER_DATA + "/bsvc:Customer_Name";

    /** XPath of the marshalled {@code Business_Entity_Name} element. */
    private static final String BUSINESS_ENTITY_NAME =
            CUSTOMER_DATA + "/bsvc:Business_Entity_Data/bsvc:Business_Entity_Name";

    /** XPath of the marshalled category {@code ID} element. */
    private static final String CATEGORY_ID = CUSTOMER_DATA + "/bsvc:Customer_Category_Reference/bsvc:ID";

    /** XPath of the namespace-qualified {@code type} attribute of the marshalled category {@code ID}. */
    private static final String CATEGORY_ID_TYPE = CATEGORY_ID + "/@bsvc:type";

    /** XPath of the marshalled status {@code ID} element. */
    private static final String STATUS_ID =
            CUSTOMER_DATA + "/bsvc:Customer_Status_Data/bsvc:Customer_Status_Value_Reference/bsvc:ID";

    /** XPath of the namespace-qualified {@code type} attribute of the marshalled status {@code ID}. */
    private static final String STATUS_ID_TYPE = STATUS_ID + "/@bsvc:type";

    /**
     * The request body of step 5 of the original README
     * [adding-a-new-customer-to-workday-revenue-management/README.md:32-42], with its nesting.
     */
    private static final String README_REQUEST_BODY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <root>
                <Account>
                    <CustomerName>John Doe</CustomerName>
                    <BusinessEntityName>John Doe</BusinessEntityName>
                    <Customer_Category_Reference_Type>Customer_Category_ID</Customer_Category_Reference_Type>
                    <Customer_Category_Reference_Value>CUSTOMER_CATEGORY-5</Customer_Category_Reference_Value>
                    <Customer_Status_Reference_Type>Business_Entity_Status_Value_ID</Customer_Status_Reference_Type>
                    <Customer_Status_Reference_Value>ACTIVE</Customer_Status_Reference_Value>
                </Account>
            </root>
            """;

    /** The six leaves and values of {@value #FIXTURE}, with every element in namespace {@code urn:test:account}. */
    private static final String NAMESPACED_ACCOUNT = """
            <a:root xmlns:a="urn:test:account">
                <a:Account>
                    <a:CustomerName>NAME</a:CustomerName>
                    <a:BusinessEntityName>NAME</a:BusinessEntityName>
                    <a:Customer_Category_Reference_Type>Customer_Category_ID</a:Customer_Category_Reference_Type>
                    <a:Customer_Category_Reference_Value>CUSTOMER_CATEGORY-5</a:Customer_Category_Reference_Value>
                    <a:Customer_Status_Reference_Type>Business_Entity_Status_Value_ID</a:Customer_Status_Reference_Type>
                    <a:Customer_Status_Reference_Value>ACTIVE</a:Customer_Status_Reference_Value>
                </a:Account>
            </a:root>
            """;

    /** An {@code Account} with six distinct values, one per input element. */
    private static final String DISTINCT_ACCOUNT = "<root><Account>"
            + "<CustomerName>John Doe</CustomerName>"
            + "<BusinessEntityName>Doe Holdings</BusinessEntityName>"
            + "<Customer_Category_Reference_Type>Customer_Category_ID</Customer_Category_Reference_Type>"
            + "<Customer_Category_Reference_Value>CUSTOMER_CATEGORY-5</Customer_Category_Reference_Value>"
            + "<Customer_Status_Reference_Type>Business_Entity_Status_Value_ID</Customer_Status_Reference_Type>"
            + "<Customer_Status_Reference_Value>ACTIVE</Customer_Status_Reference_Value>"
            + "</Account></root>";

    /** JAXB context of {@link PutCustomerRequestType}, created on first use. */
    private static JAXBContext requestJaxbContext;

    /** The mapper under test. */
    private final PutCustomerRequestMapper mapper = new PutCustomerRequestMapper();

    /**
     * Asserts the original {@value #FIXTURE} gives {@code NAME} in {@code Customer_ID},
     * {@code Customer_Reference_ID}, {@code Customer_Name} and {@code Business_Entity_Name}, one category
     * {@code ID} of type {@code Customer_Category_ID} and value {@code CUSTOMER_CATEGORY-5}, and one status
     * {@code ID} of type {@code Business_Entity_Status_Value_ID} and value {@code ACTIVE}, and leaves
     * {@code Customer_Reference}, {@code Add_Only} and {@code version} unset.
     *
     * @throws IOException when the fixture cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the fixture is not well-formed XML
     */
    @Test
    @DisplayName("Original customer.xml fixture fills every Put_Customer request field from its Account")
    public void mapsOriginalCustomerFixture() throws IOException, ParserConfigurationException, SAXException {
        PutCustomerRequestType request = mapper.toPutCustomerRequest(fixture());

        assertThat(leaves(request)).containsExactlyEntriesOf(expectedLeaves(FIXTURE_NAME, FIXTURE_NAME));
        assertThat(request.getCustomerReference()).isNull();
        assertThat(request.isAddOnly()).isNull();
        assertThat(request.getVersion()).isNull();
    }

    /**
     * Asserts the request body of the original README gives {@code John Doe} in {@code Customer_ID},
     * {@code Customer_Reference_ID}, {@code Customer_Name} and {@code Business_Entity_Name}, and the fixture's
     * category and status {@code ID}s.
     *
     * @throws IOException when the body cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the body is not well-formed XML
     */
    @Test
    @DisplayName("README request body fills every Put_Customer request field with John Doe")
    public void mapsReadmeRequestBody() throws IOException, ParserConfigurationException, SAXException {
        PutCustomerRequestType request = mapper.toPutCustomerRequest(parse(README_REQUEST_BODY, true));

        assertThat(leaves(request)).containsExactlyEntriesOf(expectedLeaves(README_NAME, README_NAME));
    }

    /**
     * Asserts the fixture's request, wrapped by {@link ObjectFactory#createPutCustomerRequest} and marshalled to
     * text, is one {@code bsvc:Put_Customer_Request} whose leaves carry the fixture values and whose two
     * {@code ID} elements carry a {@code bsvc:type} attribute and no unqualified {@code type}.
     *
     * @throws IOException when the fixture cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the fixture is not well-formed XML
     * @throws JAXBException when the request cannot be marshalled
     */
    @Test
    @DisplayName("Mapped fixture marshals as a Put_Customer request with namespace-qualified type attributes")
    public void marshalsQualifiedPutCustomerRequest()
            throws IOException, ParserConfigurationException, SAXException, JAXBException {
        String xml = marshal(mapper.toPutCustomerRequest(fixture()));

        assertThat(xpath(xml, "count(/bsvc:Put_Customer_Request)")).isEqualTo("1");
        assertThat(xpath(xml, CUSTOMER_ID)).isEqualTo(FIXTURE_NAME);
        assertThat(xpath(xml, CUSTOMER_REFERENCE_ID)).isEqualTo(FIXTURE_NAME);
        assertThat(xpath(xml, CUSTOMER_NAME)).isEqualTo(FIXTURE_NAME);
        assertThat(xpath(xml, BUSINESS_ENTITY_NAME)).isEqualTo(FIXTURE_NAME);
        assertThat(xpath(xml, CATEGORY_ID)).isEqualTo("CUSTOMER_CATEGORY-5");
        assertThat(xpath(xml, CATEGORY_ID_TYPE)).isEqualTo("Customer_Category_ID");
        assertThat(xpath(xml, "count(" + CATEGORY_ID + "/@type)")).isEqualTo("0");
        assertThat(xpath(xml, STATUS_ID)).isEqualTo("ACTIVE");
        assertThat(xpath(xml, STATUS_ID_TYPE)).isEqualTo("Business_Entity_Status_Value_ID");
        assertThat(xpath(xml, "count(" + STATUS_ID + "/@type)")).isEqualTo("0");
    }

    /**
     * Asserts the fixture's six leaves and values, with every element in a namespace, give the same request
     * values as the fixture itself.
     *
     * @throws IOException when the input cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the input is not well-formed XML
     */
    @Test
    @DisplayName("Namespaced Account input fills the same request fields as the fixture")
    public void mapsNamespacedInputIdentically() throws IOException, ParserConfigurationException, SAXException {
        Map<String, String> namespaced = leaves(mapper.toPutCustomerRequest(parse(NAMESPACED_ACCOUNT, true)));

        assertThat(namespaced).containsExactlyEntriesOf(leaves(mapper.toPutCustomerRequest(fixture())));
        assertThat(namespaced).containsExactlyEntriesOf(expectedLeaves(FIXTURE_NAME, FIXTURE_NAME));
    }

    /**
     * Asserts the fixture without its {@code localName} element gives the empty string for each request leaf
     * that element fills, and the fixture value for every other leaf, and that each leaf marshals exactly once
     * with that value: an empty element for a value, an empty {@code bsvc:type} attribute for a {@code type}.
     *
     * @param localName the local name of the {@code Account} element removed from the fixture
     * @throws IOException when the fixture cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the fixture is not well-formed XML
     * @throws JAXBException when the request cannot be marshalled
     */
    @ParameterizedTest(name = "Absent {0} yields an empty field")
    @ValueSource(strings = {"CustomerName", "BusinessEntityName", "Customer_Category_Reference_Type",
            "Customer_Category_Reference_Value", "Customer_Status_Reference_Type", "Customer_Status_Reference_Value"})
    @DisplayName("Absent Account leaf yields an empty request field and an empty marshalled value")
    public void absentLeafYieldsEmptyField(String localName)
            throws IOException, ParserConfigurationException, SAXException, JAXBException {
        Document document = fixture();
        Node leaf = document.getElementsByTagNameNS("*", localName).item(0);
        assertThat(leaf).as("fixture element %s", localName).isNotNull();
        leaf.getParentNode().removeChild(leaf);
        assertThat(document.getElementsByTagNameNS("*", localName).getLength()).isZero();

        List<String> affected = switch (localName) {
            case "CustomerName" -> List.of(CUSTOMER_ID, CUSTOMER_REFERENCE_ID, CUSTOMER_NAME);
            case "BusinessEntityName" -> List.of(BUSINESS_ENTITY_NAME);
            case "Customer_Category_Reference_Type" -> List.of(CATEGORY_ID_TYPE);
            case "Customer_Category_Reference_Value" -> List.of(CATEGORY_ID);
            case "Customer_Status_Reference_Type" -> List.of(STATUS_ID_TYPE);
            case "Customer_Status_Reference_Value" -> List.of(STATUS_ID);
            default -> throw new IllegalArgumentException("No request leaf listed for Account element " + localName);
        };
        Map<String, String> expected = expectedLeaves(FIXTURE_NAME, FIXTURE_NAME);
        for (String path : affected) {
            expected.put(path, "");
        }

        PutCustomerRequestType request = mapper.toPutCustomerRequest(document);
        // An absent Account leaf maps to the empty string, never null, and marshals as an empty value (D-323).
        assertThat(leaves(request)).containsExactlyEntriesOf(expected);
        assertMarshalledLeaves(marshal(request), expected);
    }

    /**
     * Asserts {@code <root/>}, a {@code root} without an {@code Account} child, maps without an exception to the
     * empty string for every value and {@code type}, with every container created.
     *
     * @throws IOException when the input cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the input is not well-formed XML
     */
    @Test
    @DisplayName("Document without an Account yields empty request fields")
    public void documentWithoutAccountYieldsEmptyFields()
            throws IOException, ParserConfigurationException, SAXException {
        Document document = parse("<root/>", true);

        assertThatCode(() -> mapper.toPutCustomerRequest(document)).doesNotThrowAnyException();
        assertEveryFieldEmpty(mapper.toPutCustomerRequest(document));
    }

    /**
     * Asserts each of six distinct {@code Account} values reaches its own request field: {@code CustomerName}
     * the three customer identifiers, {@code BusinessEntityName} the business entity name, and each
     * {@code *_Type}/{@code *_Value} pair its own {@code ID}.
     *
     * @throws IOException when the input cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the input is not well-formed XML
     */
    @Test
    @DisplayName("Each Account element fills its own Put_Customer request field")
    public void eachAccountElementFillsItsOwnField() throws IOException, ParserConfigurationException, SAXException {
        CustomerWWSDataType data = mapper.toPutCustomerRequest(parse(DISTINCT_ACCOUNT, true)).getCustomerData();

        assertThat(data.getCustomerID()).isEqualTo("John Doe");
        assertThat(data.getCustomerReferenceID()).isEqualTo("John Doe");
        assertThat(data.getCustomerName()).isEqualTo("John Doe");
        assertThat(data.getBusinessEntityData().getBusinessEntityName()).isEqualTo("Doe Holdings");
        assertThat(data.getCustomerCategoryReference().getID())
                .extracting(CustomerCategoryObjectIDType::getType, CustomerCategoryObjectIDType::getValue)
                .containsExactly(tuple("Customer_Category_ID", "CUSTOMER_CATEGORY-5"));
        assertThat(data.getCustomerStatusData())
                .extracting(CustomerStatusDataType::getCustomerStatusValueReference)
                .singleElement()
                .satisfies(reference -> assertThat(reference.getID())
                        .extracting(BusinessEntityStatusValueObjectIDType::getType,
                                BusinessEntityStatusValueObjectIDType::getValue)
                        .containsExactly(tuple("Business_Entity_Status_Value_ID", "ACTIVE")));
    }

    /**
     * Asserts the marshalled request is a {@code bsvc:Put_Customer_Request} whose {@code Customer_Data} children
     * follow the v35.0 schema sequence, whose two {@code ID} elements carry their value and a
     * {@code bsvc:type} attribute and no unqualified {@code type}, and which holds no {@code Customer_Reference},
     * {@code Add_Only} or {@code version}.
     *
     * @throws IOException when the input cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the input is not well-formed XML
     * @throws JAXBException when the request cannot be marshalled
     */
    @Test
    @DisplayName("Marshalled Put_Customer request follows the v35.0 schema sequence with qualified type attributes")
    public void marshalledRequestFollowsSchemaSequenceWithQualifiedTypeAttributes()
            throws IOException, ParserConfigurationException, SAXException, JAXBException {
        String xml = marshal(mapper.toPutCustomerRequest(parse(DISTINCT_ACCOUNT, true)));

        assertThat(xpath(xml, "namespace-uri(/*)")).isEqualTo(BSVC);
        assertThat(xpath(xml, "local-name(/*)")).isEqualTo("Put_Customer_Request");
        // Customer_Category_Reference precedes Business_Entity_Data in the v35.0 schema sequence (D-323).
        assertThat(customerDataChildNames(xml)).containsExactly("Customer_ID", "Customer_Reference_ID",
                "Customer_Name", "Customer_Category_Reference", "Business_Entity_Data", "Customer_Status_Data");
        assertThat(xpath(xml, CUSTOMER_DATA + "/bsvc:Customer_ID")).isEqualTo("John Doe");
        assertThat(xpath(xml, CUSTOMER_DATA + "/bsvc:Customer_Reference_ID")).isEqualTo("John Doe");
        assertThat(xpath(xml, CUSTOMER_DATA + "/bsvc:Customer_Name")).isEqualTo("John Doe");
        assertThat(xpath(xml, CUSTOMER_DATA + "/bsvc:Business_Entity_Data/bsvc:Business_Entity_Name"))
                .isEqualTo("Doe Holdings");
        assertThat(xpath(xml, "count(" + CATEGORY_ID + ")")).isEqualTo("1");
        assertThat(xpath(xml, CATEGORY_ID)).isEqualTo("CUSTOMER_CATEGORY-5");
        assertThat(xpath(xml, CATEGORY_ID + "/@bsvc:type")).isEqualTo("Customer_Category_ID");
        assertThat(xpath(xml, "count(" + CATEGORY_ID + "/@type)")).isEqualTo("0");
        assertThat(xpath(xml, "count(" + STATUS_ID + ")")).isEqualTo("1");
        assertThat(xpath(xml, STATUS_ID)).isEqualTo("ACTIVE");
        assertThat(xpath(xml, STATUS_ID + "/@bsvc:type")).isEqualTo("Business_Entity_Status_Value_ID");
        assertThat(xpath(xml, "count(" + STATUS_ID + "/@type)")).isEqualTo("0");
        assertThat(xpath(xml, "count(/bsvc:Put_Customer_Request/bsvc:Customer_Reference)")).isEqualTo("0");
        assertThat(xpath(xml, "count(/bsvc:Put_Customer_Request/@bsvc:Add_Only)")).isEqualTo("0");
        assertThat(xpath(xml, "count(/bsvc:Put_Customer_Request/@bsvc:version)")).isEqualTo("0");
    }

    /**
     * Asserts a document element other than {@code root} gives the empty string for every value and
     * {@code type}, with every container created.
     *
     * @throws IOException when the input cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the input is not well-formed XML
     */
    @Test
    @DisplayName("Document element other than root yields empty request fields")
    public void documentElementOtherThanRootYieldsEmptyFields()
            throws IOException, ParserConfigurationException, SAXException {
        assertEveryFieldEmpty(mapper.toPutCustomerRequest(parse(
                "<customers><Account><CustomerName>John Doe</CustomerName></Account></customers>", true)));
    }

    /**
     * Asserts a document with no document element gives the empty string for every value and {@code type},
     * with every container created.
     *
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     */
    @Test
    @DisplayName("Document without a document element yields empty request fields")
    public void documentWithoutDocumentElementYieldsEmptyFields() throws ParserConfigurationException {
        assertEveryFieldEmpty(mapper.toPutCustomerRequest(builderFactory(true).newDocumentBuilder().newDocument()));
    }

    /**
     * Asserts a {@code null} document gives a request, never {@code null}, with the empty string for every value
     * and {@code type} and every container created.
     */
    @Test
    @DisplayName("Null document yields empty request fields")
    public void nullDocumentYieldsEmptyFields() {
        assertEveryFieldEmpty(mapper.toPutCustomerRequest(null));
    }

    /**
     * Asserts a namespace-aware DOM whose {@code root}, {@code Account} and leaf elements sit in a default
     * namespace and under a prefix is read by local name.
     *
     * @throws IOException when the input cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the input is not well-formed XML
     */
    @Test
    @DisplayName("Namespace-aware input is matched by local name in any namespace")
    public void namespaceAwareInputIsMatchedByLocalName()
            throws IOException, ParserConfigurationException, SAXException {
        CustomerWWSDataType data = mapper.toPutCustomerRequest(parse("<root xmlns=\"urn:example:accounts\""
                + " xmlns:a=\"urn:example:other\"><a:Account>"
                + "<CustomerName>John Doe</CustomerName>"
                + "<a:BusinessEntityName>Doe Holdings</a:BusinessEntityName>"
                + "</a:Account></root>", true)).getCustomerData();

        assertThat(data.getCustomerName()).isEqualTo("John Doe");
        assertThat(data.getBusinessEntityData().getBusinessEntityName()).isEqualTo("Doe Holdings");
    }

    /**
     * Asserts a non-namespace-aware DOM is read by element name without any {@code prefix:}, for prefixed and
     * unprefixed elements alike.
     *
     * @throws IOException when the input cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the input is not well-formed XML
     */
    @Test
    @DisplayName("Non-namespace-aware input is matched by element name without its prefix")
    public void nonNamespaceAwareInputIsMatchedByNameWithoutPrefix()
            throws IOException, ParserConfigurationException, SAXException {
        CustomerWWSDataType data = mapper.toPutCustomerRequest(parse("<a:root xmlns:a=\"urn:example:accounts\">"
                + "<a:Account>"
                + "<a:CustomerName>John Doe</a:CustomerName>"
                + "<BusinessEntityName>Doe Holdings</BusinessEntityName>"
                + "</a:Account></a:root>", false)).getCustomerData();

        assertThat(data.getCustomerName()).isEqualTo("John Doe");
        assertThat(data.getBusinessEntityData().getBusinessEntityName()).isEqualTo("Doe Holdings");
    }

    /**
     * Asserts the first {@code Account} and the first matching leaf are read past comments, text and other
     * elements, and that the leaf's text content is used unchanged, surrounding spaces included.
     *
     * @throws IOException when the input cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the input is not well-formed XML
     */
    @Test
    @DisplayName("First matching element is read with its text content unchanged")
    public void firstMatchingElementIsReadUnchanged() throws IOException, ParserConfigurationException, SAXException {
        CustomerWWSDataType data = mapper.toPutCustomerRequest(parse("<root>\n"
                + "  <!-- accounts -->\n"
                + "  <Contact><CustomerName>Not An Account</CustomerName></Contact>\n"
                + "  <Account>\n"
                + "    <Note>first account</Note>\n"
                + "    <CustomerName>  John  Doe </CustomerName>\n"
                + "    <CustomerName>Jane Roe</CustomerName>\n"
                + "  </Account>\n"
                + "  <Account><CustomerName>Second Account</CustomerName>"
                + "<BusinessEntityName>Second Holdings</BusinessEntityName></Account>\n"
                + "</root>", true)).getCustomerData();

        assertThat(data.getCustomerID()).isEqualTo("  John  Doe ");
        assertThat(data.getCustomerReferenceID()).isEqualTo("  John  Doe ");
        assertThat(data.getCustomerName()).isEqualTo("  John  Doe ");
        // The second Account is not read: Business_Entity_Name stays empty (D-323).
        assertThat(data.getBusinessEntityData().getBusinessEntityName()).isEqualTo("");
    }

    /**
     * Asserts every value and {@code type} of {@code request} is the empty string and every container exists,
     * with exactly one category {@code ID}, one {@code Customer_Status_Data} and one status {@code ID} (D-323).
     *
     * @param request the mapped request
     */
    private static void assertEveryFieldEmpty(PutCustomerRequestType request) {
        assertThat(request).isNotNull();
        CustomerWWSDataType data = request.getCustomerData();
        assertThat(data).isNotNull();
        assertThat(data.getCustomerID()).isEqualTo("");
        assertThat(data.getCustomerReferenceID()).isEqualTo("");
        assertThat(data.getCustomerName()).isEqualTo("");
        assertThat(data.getBusinessEntityData()).isNotNull();
        assertThat(data.getBusinessEntityData().getBusinessEntityName()).isEqualTo("");
        assertThat(data.getCustomerCategoryReference()).isNotNull();
        assertThat(data.getCustomerCategoryReference().getID())
                .extracting(CustomerCategoryObjectIDType::getType, CustomerCategoryObjectIDType::getValue)
                .containsExactly(tuple("", ""));
        assertThat(data.getCustomerStatusData()).hasSize(1);
        assertThat(data.getCustomerStatusData().get(0).getCustomerStatusValueReference()).isNotNull();
        assertThat(data.getCustomerStatusData().get(0).getCustomerStatusValueReference().getID())
                .extracting(BusinessEntityStatusValueObjectIDType::getType,
                        BusinessEntityStatusValueObjectIDType::getValue)
                .containsExactly(tuple("", ""));
    }

    /**
     * Lists the eight leaf values of {@code request}, keyed by the XPath at which each marshals: the
     * {@code Customer_ID}, {@code Customer_Reference_ID}, {@code Customer_Name} and {@code Business_Entity_Name}
     * values, then the {@code type} and value of the category {@code ID} and of the status {@code ID}. Asserts the
     * request holds exactly one category {@code ID}, one {@code Customer_Status_Data} and one status {@code ID}.
     *
     * @param request the mapped request
     * @return the leaf values in that order, {@code null} where the request holds {@code null}
     */
    private static Map<String, String> leaves(PutCustomerRequestType request) {
        assertThat(request).isNotNull();
        CustomerWWSDataType data = request.getCustomerData();
        assertThat(data).isNotNull();
        List<CustomerCategoryObjectIDType> categoryIds = data.getCustomerCategoryReference().getID();
        assertThat(categoryIds).hasSize(1);
        assertThat(data.getCustomerStatusData()).hasSize(1);
        List<BusinessEntityStatusValueObjectIDType> statusIds =
                data.getCustomerStatusData().get(0).getCustomerStatusValueReference().getID();
        assertThat(statusIds).hasSize(1);

        Map<String, String> leaves = new LinkedHashMap<>();
        leaves.put(CUSTOMER_ID, data.getCustomerID());
        leaves.put(CUSTOMER_REFERENCE_ID, data.getCustomerReferenceID());
        leaves.put(CUSTOMER_NAME, data.getCustomerName());
        leaves.put(BUSINESS_ENTITY_NAME, data.getBusinessEntityData().getBusinessEntityName());
        leaves.put(CATEGORY_ID_TYPE, categoryIds.get(0).getType());
        leaves.put(CATEGORY_ID, categoryIds.get(0).getValue());
        leaves.put(STATUS_ID_TYPE, statusIds.get(0).getType());
        leaves.put(STATUS_ID, statusIds.get(0).getValue());
        return leaves;
    }

    /**
     * Returns the leaf values, in the order and with the keys of {@link #leaves}, of an {@code Account} with
     * {@code customerName}, {@code businessEntityName} and the category and status of {@value #FIXTURE}:
     * {@code Customer_Category_ID}/{@code CUSTOMER_CATEGORY-5} and
     * {@code Business_Entity_Status_Value_ID}/{@code ACTIVE}.
     *
     * @param customerName the expected {@code Customer_ID}, {@code Customer_Reference_ID} and {@code Customer_Name}
     * @param businessEntityName the expected {@code Business_Entity_Name}
     * @return a new modifiable map of the expected leaf values
     */
    private static Map<String, String> expectedLeaves(String customerName, String businessEntityName) {
        Map<String, String> leaves = new LinkedHashMap<>();
        leaves.put(CUSTOMER_ID, customerName);
        leaves.put(CUSTOMER_REFERENCE_ID, customerName);
        leaves.put(CUSTOMER_NAME, customerName);
        leaves.put(BUSINESS_ENTITY_NAME, businessEntityName);
        leaves.put(CATEGORY_ID_TYPE, "Customer_Category_ID");
        leaves.put(CATEGORY_ID, "CUSTOMER_CATEGORY-5");
        leaves.put(STATUS_ID_TYPE, "Business_Entity_Status_Value_ID");
        leaves.put(STATUS_ID, "ACTIVE");
        return leaves;
    }

    /**
     * Asserts each XPath key of {@code expected} selects exactly one node of {@code xml} whose string value is the
     * mapped value.
     *
     * @param xml the marshalled {@code Put_Customer_Request}
     * @param expected the expected string value per XPath
     */
    private static void assertMarshalledLeaves(String xml, Map<String, String> expected) {
        for (Map.Entry<String, String> leaf : expected.entrySet()) {
            assertThat(xpath(xml, "count(" + leaf.getKey() + ")")).as("count(%s)", leaf.getKey()).isEqualTo("1");
            assertThat(xpath(xml, leaf.getKey())).as(leaf.getKey()).isEqualTo(leaf.getValue());
        }
    }

    /**
     * Reads and parses {@value #FIXTURE} through this class into a namespace-aware DOM.
     *
     * @return the fixture document
     * @throws IOException when the resource cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the resource is not well-formed XML
     */
    private static Document fixture() throws IOException, ParserConfigurationException, SAXException {
        try (InputStream in = PutCustomerRequestMapperTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("test-classpath resource %s", FIXTURE).isNotNull();
            return builderFactory(true).newDocumentBuilder().parse(in);
        }
    }

    /**
     * Parses {@code xml} into a DOM.
     *
     * @param xml the document text
     * @param namespaceAware whether the parser is namespace-aware
     * @return the parsed document
     * @throws IOException when the text cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the text is not well-formed XML
     */
    private static Document parse(String xml, boolean namespaceAware)
            throws IOException, ParserConfigurationException, SAXException {
        return builderFactory(namespaceAware).newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Returns a DOM parser factory with secure processing on, document type declarations rejected, and entity
     * reference expansion and XInclude off.
     *
     * @param namespaceAware whether the parsers it creates are namespace-aware
     * @return the configured factory
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     */
    private static DocumentBuilderFactory builderFactory(boolean namespaceAware) throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        return factory;
    }

    /**
     * Marshals {@code request}, wrapped by {@link ObjectFactory#createPutCustomerRequest}, to XML text.
     *
     * @param request the mapped request
     * @return the marshalled {@code Put_Customer_Request} document
     * @throws JAXBException when the request cannot be marshalled
     */
    private static String marshal(PutCustomerRequestType request) throws JAXBException {
        StringWriter xml = new StringWriter();
        requestJaxbContext().createMarshaller().marshal(new ObjectFactory().createPutCustomerRequest(request), xml);
        return xml.toString();
    }

    /**
     * Returns the JAXB context of {@link PutCustomerRequestType}, creating it on the first call.
     *
     * @return the shared context
     * @throws JAXBException when the context cannot be created
     */
    private static synchronized JAXBContext requestJaxbContext() throws JAXBException {
        if (requestJaxbContext == null) {
            requestJaxbContext = JAXBContext.newInstance(PutCustomerRequestType.class);
        }
        return requestJaxbContext;
    }

    /**
     * Returns an XPath 1.0 engine with {@code bsvc} bound to {@value #BSVC}.
     *
     * @return the engine
     */
    private static JAXPXPathEngine xpathEngine() {
        JAXPXPathEngine engine = new JAXPXPathEngine();
        engine.setNamespaceContext(Map.of("bsvc", BSVC));
        return engine;
    }

    /**
     * Evaluates an XPath 1.0 {@code expression} against the XML text {@code xml} with {@code bsvc} bound to
     * {@value #BSVC}.
     *
     * @param xml the marshalled document
     * @param expression the XPath expression
     * @return the string value of the result
     */
    private static String xpath(String xml, String expression) {
        return xpathEngine().evaluate(expression, Input.fromString(xml).build());
    }

    /**
     * Lists the local names of the child elements of the marshalled {@code Customer_Data}, in document order.
     *
     * @param xml the marshalled document
     * @return the child element local names, empty when there is no {@code Customer_Data}
     */
    private static List<String> customerDataChildNames(String xml) {
        List<String> names = new ArrayList<>();
        for (Node child : xpathEngine().selectNodes(CUSTOMER_DATA + "/*", Input.fromString(xml).build())) {
            names.add(child.getLocalName());
        }
        return names;
    }
}
