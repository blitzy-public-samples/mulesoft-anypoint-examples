package com.mulesoft.examples.xml_only_soap_webservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.mulesoft.examples.xml_only_soap_webservice.client.EhrServiceClient;
import com.mulesoft.examples.xml_only_soap_webservice.client.PatientServiceClient;

/**
 * Unit tests of {@link AdmissionService}, the flow {@code admitPatientService} and its sub-flows
 * {@code upsertPatient}, {@code invokePatientService}, {@code createEpisode} and {@code invokeEHRService} of
 * {@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml} (lines 19-88, D-417).
 *
 * <p>{@link PatientServiceClient} and {@link EhrServiceClient} are Mockito mocks, and the service runs its own
 * {@code AdmissionMapper}; no Spring application context starts. The request is the {@code ns:admitSubject} Body
 * child of {@code original/message.xml}. The client answers are {@code upsertPatientResponse} and
 * {@code createEpisodeResponse} documents in the shape of {@code PatientService.wsdl}, {@code EHRService.wsdl} and
 * {@code SOA-Message-1.0.xsd}, parsed with a namespace-aware parser.
 *
 * <p>The tests assert the DOM elements each client receives, the order of the two client calls, the returned
 * {@code admitSubjectResponse} text, and the propagation of client and mapper exceptions.
 */
@ExtendWith(MockitoExtension.class)
class AdmissionServiceTest {

    /** Namespace of the {@code ns0} message elements. */
    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the {@code ns1} model elements. */
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** Namespace of the SOAP 1.1 envelope of {@code original/message.xml}. */
    private static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";

    /** First line of every document the mapper returns. */
    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** Feature that makes the parser reject any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** The {@code PatientId} the PatientService stub answers with. */
    private static final String PATIENT_ID = "2015-03-01T10:15:30.123Z";

    /** The {@code episodeId}, {@code startDate} and {@code endDate} text the EHRService stub answers with. */
    private static final String EPISODE_NOW = "2015-03-01T10:15:31.456Z";

    /** The {@code Bill } element DW-39 writes after {@code ns1:Episode}, with its two leading spaces. */
    private static final String BILL = "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
            + "    <costPerNight>100</costPerNight>\n"
            + "    <initialStateEstimate>5</initialStateEstimate>\n"
            + "    <runningTotal>500</runningTotal>\n"
            + "    <status>ADMITTED</status>\n"
            + "  </ns1:Bill >\n";

    @Mock
    private PatientServiceClient patientServiceClient;

    @Mock
    private EhrServiceClient ehrServiceClient;

    @Captor
    private ArgumentCaptor<Element> patientRequest;

    @Captor
    private ArgumentCaptor<Element> ehrRequest;

    /** The service under test. */
    private AdmissionService service;

    @BeforeEach
    void createService() {
        service = new AdmissionService(patientServiceClient, ehrServiceClient);
    }

    /**
     * Asserts one admission of {@code original/message.xml}: {@code ns0:upsertPatient} with the request's
     * {@code ns1:Subject} is posted to the PatientService, then {@code ns0:createEpisode} with the returned
     * {@code ns1:PatientId} to the EHRService, and the returned {@code ns0:admitSubjectResponse} holds the
     * {@code ns1:Episode} of the EHRService answer with {@code startDate}, {@code endDate} plus five days and the
     * {@code Bill } element.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void admitPatientServicePostsUpsertPatientThenCreateEpisodeAndReturnsAdmitSubjectResponse() throws Exception {
        when(patientServiceClient.invoke(any())).thenReturn(parse(upsertPatientResponse(PATIENT_ID)));
        when(ehrServiceClient.invoke(any()))
                .thenReturn(parse(createEpisodeResponse(PATIENT_ID, EPISODE_NOW, EPISODE_NOW)));

        String response = textOf(service.admitPatientService(admitSubjectOfOriginalMessage()));

        InOrder order = inOrder(patientServiceClient, ehrServiceClient);
        order.verify(patientServiceClient).invoke(patientRequest.capture());
        order.verify(ehrServiceClient).invoke(ehrRequest.capture());
        verifyNoMoreInteractions(patientServiceClient, ehrServiceClient);
        assertUpsertPatientOfOriginalSubject(patientRequest.getValue());
        assertCreateEpisode(ehrRequest.getValue(), PATIENT_ID);
        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + EPISODE_NOW + "</episodeId>\n"
                + "    <ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>2015-03-01</startDate>\n"
                + "    <endDate>2015-03-06</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + BILL
                + "</ns0:admitSubjectResponse>", response);

        Element admitSubjectResponse = parse(response);
        assertQualifiedName(NS0, "admitSubjectResponse", admitSubjectResponse);
        List<Element> children = elementChildren(admitSubjectResponse);
        assertEquals(2, children.size());
        assertQualifiedName(NS1, "Episode", children.get(0));
        assertQualifiedName(NS1, "Bill", children.get(1));
    }

    /**
     * Asserts the returned {@code ns0:admitSubjectResponse} has no {@code startDate} and no {@code endDate} when
     * the EHRService {@code ns1:Episode} carries neither.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void admitPatientServiceOmitsStartDateAndEndDateWhenEpisodeHasNone() throws Exception {
        when(patientServiceClient.invoke(any())).thenReturn(parse(upsertPatientResponse(PATIENT_ID)));
        when(ehrServiceClient.invoke(any())).thenReturn(parse(createEpisodeResponse(PATIENT_ID, null, null)));

        String response = textOf(service.admitPatientService(admitSubjectOfOriginalMessage()));

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + EPISODE_NOW + "</episodeId>\n"
                + "    <ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + BILL
                + "</ns0:admitSubjectResponse>", response);
    }

    /**
     * Asserts a {@code null} {@code admitSubject} posts {@code ns0:upsertPatient} holding an empty
     * {@code ns1:Subject}, and the admission continues with the PatientService answer.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void admitPatientServicePostsEmptySubjectForNullAdmitSubject() throws Exception {
        when(patientServiceClient.invoke(any())).thenReturn(parse(upsertPatientResponse(PATIENT_ID)));
        when(ehrServiceClient.invoke(any()))
                .thenReturn(parse(createEpisodeResponse(PATIENT_ID, EPISODE_NOW, EPISODE_NOW)));

        String response = textOf(service.admitPatientService(null));

        verify(patientServiceClient).invoke(patientRequest.capture());
        verify(ehrServiceClient).invoke(ehrRequest.capture());
        Element upsertPatient = patientRequest.getValue();
        assertQualifiedName(NS0, "upsertPatient", upsertPatient);
        List<Element> children = elementChildren(upsertPatient);
        assertEquals(1, children.size());
        assertQualifiedName(NS1, "Subject", children.get(0));
        assertFalse(children.get(0).hasChildNodes(), "ns1:Subject has no child nodes");
        assertCreateEpisode(ehrRequest.getValue(), PATIENT_ID);
        assertTrue(response.contains("<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId>"), response);
    }

    /**
     * Asserts {@code null} client answers reach the next mapper call: a {@code null} PatientService answer posts
     * {@code ns0:createEpisode} with an empty {@code ns1:PatientId}, and a {@code null} EHRService answer returns
     * an {@code ns1:Episode} of empty elements beside the {@code Bill } element.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void admitPatientServicePassesNullClientAnswersToTheNextMapper() throws Exception {
        when(patientServiceClient.invoke(any())).thenReturn(null);
        when(ehrServiceClient.invoke(any())).thenReturn(null);

        String response = textOf(service.admitPatientService(admitSubjectOfOriginalMessage()));

        verify(ehrServiceClient).invoke(ehrRequest.capture());
        Element createEpisode = ehrRequest.getValue();
        assertQualifiedName(NS0, "createEpisode", createEpisode);
        List<Element> children = elementChildren(createEpisode);
        assertEquals(1, children.size());
        assertQualifiedName(NS1, "PatientId", children.get(0));
        assertFalse(children.get(0).hasChildNodes(), "ns1:PatientId has no child nodes");
        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId/>\n"
                + "    <ns1:PatientId/>\n"
                + "    <admission/>\n"
                + "    <care/>\n"
                + "  </ns1:Episode>\n"
                + BILL
                + "</ns0:admitSubjectResponse>", response);
    }

    /**
     * Asserts a SOAP fault from the PatientService reaches the caller as the same
     * {@link SoapFaultClientException}, and the EHRService is not called.
     *
     * @throws Exception when the request cannot be read or parsed
     */
    @Test
    void admitPatientServicePropagatesPatientServiceFaultWithoutCallingEhrService() throws Exception {
        SoapMessage faultMessage = mock(SoapMessage.class);
        when(faultMessage.getFaultReason()).thenReturn("Patient store unavailable");
        SoapFaultClientException fault = new SoapFaultClientException(faultMessage);
        when(patientServiceClient.invoke(any())).thenThrow(fault);
        Element admitSubject = admitSubjectOfOriginalMessage();

        SoapFaultClientException thrown =
                assertThrows(SoapFaultClientException.class, () -> service.admitPatientService(admitSubject));

        assertSame(fault, thrown);
        assertEquals("Patient store unavailable", thrown.getMessage());
        verifyNoInteractions(ehrServiceClient);
    }

    /**
     * Asserts an I/O failure of the PatientService call reaches the caller as the same
     * {@link WebServiceIOException}, and the EHRService is not called.
     *
     * @throws Exception when the request cannot be read or parsed
     */
    @Test
    void admitPatientServicePropagatesPatientServiceIoFailureWithoutCallingEhrService() throws Exception {
        WebServiceIOException failure = new WebServiceIOException("I/O error: Connection refused");
        when(patientServiceClient.invoke(any())).thenThrow(failure);
        Element admitSubject = admitSubjectOfOriginalMessage();

        WebServiceIOException thrown =
                assertThrows(WebServiceIOException.class, () -> service.admitPatientService(admitSubject));

        assertSame(failure, thrown);
        verifyNoInteractions(ehrServiceClient);
    }

    /**
     * Asserts an HTTP error of the EHRService call reaches the caller as the same
     * {@link WebServiceTransportException}, after one PatientService call.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void admitPatientServicePropagatesEhrServiceTransportFailure() throws Exception {
        WebServiceTransportException failure = new WebServiceTransportException("Internal Server Error [500]");
        when(patientServiceClient.invoke(any())).thenReturn(parse(upsertPatientResponse(PATIENT_ID)));
        when(ehrServiceClient.invoke(any())).thenThrow(failure);
        Element admitSubject = admitSubjectOfOriginalMessage();

        WebServiceTransportException thrown =
                assertThrows(WebServiceTransportException.class, () -> service.admitPatientService(admitSubject));

        assertSame(failure, thrown);
        verify(patientServiceClient).invoke(any());
        verify(ehrServiceClient).invoke(any());
        verifyNoMoreInteractions(patientServiceClient, ehrServiceClient);
    }

    /**
     * Asserts a {@code startDate} of the EHRService answer that does not begin with an ISO date raises the DW-39
     * {@link DateTimeParseException} to the caller.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void admitPatientServicePropagatesDateTimeParseExceptionForUnreadableStartDate() throws Exception {
        when(patientServiceClient.invoke(any())).thenReturn(parse(upsertPatientResponse(PATIENT_ID)));
        when(ehrServiceClient.invoke(any()))
                .thenReturn(parse(createEpisodeResponse(PATIENT_ID, "not-a-date", EPISODE_NOW)));
        Element admitSubject = admitSubjectOfOriginalMessage();

        DateTimeParseException thrown =
                assertThrows(DateTimeParseException.class, () -> service.admitPatientService(admitSubject));

        assertEquals("not-a-date", thrown.getParsedString());
    }

    /**
     * Asserts DW-40 text that the namespace-aware parser rejects raises {@link IllegalStateException} carrying the
     * parser's message and the parser exception as its cause, before any client call. The {@code admitSubject} is
     * parsed without namespace awareness and holds the {@code Subject} child {@code x:y:nationalId}, which DW-40
     * writes as {@code y:nationalId} with the prefix {@code y} unbound.
     *
     * @throws Exception when the request cannot be parsed
     */
    @Test
    void admitPatientServiceRaisesIllegalStateExceptionWhenDw40TextCannotBeParsed() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        Element admitSubject = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader("<ns:admitSubject xmlns:ns=\"" + NS0 + "\" xmlns:ns1=\""
                        + NS1 + "\"><ns1:Subject><x:y:nationalId>1234</x:y:nationalId></ns1:Subject>"
                        + "</ns:admitSubject>")))
                .getDocumentElement();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> service.admitPatientService(admitSubject));

        SAXException cause = assertInstanceOf(SAXException.class, thrown.getCause());
        assertEquals(cause.getMessage(), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("y:nationalId"), thrown.getMessage());
        verifyNoInteractions(patientServiceClient, ehrServiceClient);
    }

    /**
     * Asserts {@code upsertPatient} posts the DW-40 element as a namespace-aware DOM element with its whitespace
     * text nodes kept, and returns the PatientService answer unchanged.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void upsertPatientPostsDw40ElementWithWhitespaceKeptAndReturnsPatientServiceAnswer() throws Exception {
        Element answer = parse(upsertPatientResponse(PATIENT_ID));
        when(patientServiceClient.invoke(any())).thenReturn(answer);

        Element result = service.upsertPatient(admitSubjectOfOriginalMessage());

        assertSame(answer, result);
        verify(patientServiceClient).invoke(patientRequest.capture());
        verifyNoInteractions(ehrServiceClient);
        Element upsertPatient = patientRequest.getValue();
        assertUpsertPatientOfOriginalSubject(upsertPatient);
        Node first = upsertPatient.getFirstChild();
        assertEquals(Node.TEXT_NODE, first.getNodeType());
        assertEquals("\n  ", first.getNodeValue());
        assertSame(upsertPatient, upsertPatient.getOwnerDocument().getDocumentElement());
    }

    /**
     * Asserts {@code createEpisode} posts the DW-41 element {@code ns0:createEpisode} holding the
     * {@code ns1:PatientId} of the given {@code upsertPatientResponse}, and returns the EHRService answer
     * unchanged.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void createEpisodePostsDw41ElementAndReturnsEhrServiceAnswer() throws Exception {
        Element answer = parse(createEpisodeResponse(PATIENT_ID, EPISODE_NOW, EPISODE_NOW));
        when(ehrServiceClient.invoke(any())).thenReturn(answer);

        Element result = service.createEpisode(parse(upsertPatientResponse(PATIENT_ID)));

        assertSame(answer, result);
        verify(ehrServiceClient).invoke(ehrRequest.capture());
        verifyNoInteractions(patientServiceClient);
        assertCreateEpisode(ehrRequest.getValue(), PATIENT_ID);
    }

    /**
     * Asserts {@code invokePatientService} hands its body to {@link PatientServiceClient#invoke(Element)} as the
     * same element and returns the client's answer as the same element.
     *
     * @throws Exception when a document cannot be parsed
     */
    @Test
    void invokePatientServicePassesBodyUnchangedAndReturnsClientAnswer() throws Exception {
        Element body = parse("<ns0:getPatient xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId></ns0:getPatient>");
        Element answer = parse(upsertPatientResponse(PATIENT_ID));
        when(patientServiceClient.invoke(body)).thenReturn(answer);

        assertSame(answer, service.invokePatientService(body));

        verify(patientServiceClient).invoke(body);
        verifyNoMoreInteractions(patientServiceClient);
        verifyNoInteractions(ehrServiceClient);
    }

    /**
     * Asserts {@code invokeEhrService} hands its body to {@link EhrServiceClient#invoke(Element)} as the same
     * element and returns the client's {@code null} answer for an empty response Body.
     *
     * @throws Exception when a document cannot be parsed
     */
    @Test
    void invokeEhrServicePassesBodyUnchangedAndReturnsClientAnswer() throws Exception {
        Element body = parse("<ns0:findEpisodes xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId></ns0:findEpisodes>");
        when(ehrServiceClient.invoke(body)).thenReturn(null);

        assertNull(service.invokeEhrService(body));

        verify(ehrServiceClient).invoke(body);
        verifyNoMoreInteractions(ehrServiceClient);
        verifyNoInteractions(patientServiceClient);
    }

    /**
     * Asserts {@code element} is {@code ns0:upsertPatient} holding one {@code ns1:Subject} with the nine
     * unqualified children of the {@code ns1:Subject} of {@code original/message.xml}, in document order.
     *
     * @param element the element posted to the PatientService
     */
    private static void assertUpsertPatientOfOriginalSubject(Element element) {
        assertQualifiedName(NS0, "upsertPatient", element);
        List<Element> children = elementChildren(element);
        assertEquals(1, children.size());
        Element subject = children.get(0);
        assertQualifiedName(NS1, "Subject", subject);
        List<String> fields = new ArrayList<>();
        for (Element field : elementChildren(subject)) {
            assertNull(field.getNamespaceURI(), "namespace of " + field.getLocalName());
            fields.add(field.getLocalName() + "=" + field.getTextContent());
        }
        assertEquals(List.of("nationalId=1234", "firstName=Nial", "lastName=Darbey", "address1=Buenos Aires",
                "address2=", "address3=", "nationality=Irish", "gender=Male", "dateOfBirth=1970-08-07"), fields);
    }

    /**
     * Asserts {@code element} is {@code ns0:createEpisode} holding one {@code ns1:PatientId} with the given text.
     *
     * @param element the element posted to the EHRService
     * @param patientId the expected {@code ns1:PatientId} text
     */
    private static void assertCreateEpisode(Element element, String patientId) {
        assertQualifiedName(NS0, "createEpisode", element);
        List<Element> children = elementChildren(element);
        assertEquals(1, children.size());
        assertQualifiedName(NS1, "PatientId", children.get(0));
        assertEquals(patientId, children.get(0).getTextContent());
    }

    /**
     * Asserts the namespace URI and the local name of {@code element}.
     *
     * @param namespaceUri the expected namespace URI
     * @param localName the expected local name
     * @param element the element to check
     */
    private static void assertQualifiedName(String namespaceUri, String localName, Element element) {
        assertNotNull(element, "{" + namespaceUri + "}" + localName);
        assertEquals(namespaceUri, element.getNamespaceURI(), "namespace of " + element.getNodeName());
        assertEquals(localName, element.getLocalName(), "local name of " + element.getNodeName());
    }

    /**
     * Returns the text of the {@link StreamSource} reader that {@code source} is asserted to be.
     *
     * @param source the source returned by the service
     * @return the full text of its reader
     * @throws IOException when the reader fails
     */
    private static String textOf(Source source) throws IOException {
        StreamSource stream = assertInstanceOf(StreamSource.class, source);
        Reader reader = stream.getReader();
        assertNotNull(reader, "reader of the returned StreamSource");
        StringWriter text = new StringWriter();
        reader.transferTo(text);
        return text.toString();
    }

    /**
     * Builds an {@code ns0:upsertPatientResponse} document holding one {@code ns1:PatientId}.
     *
     * @param patientId the {@code ns1:PatientId} text
     * @return the document text
     */
    private static String upsertPatientResponse(String patientId) {
        return "<ns0:upsertPatientResponse xmlns:ns0=\"" + NS0 + "\">"
                + "<ns1:PatientId xmlns:ns1=\"" + NS1 + "\">" + patientId + "</ns1:PatientId>"
                + "</ns0:upsertPatientResponse>";
    }

    /**
     * Builds an {@code ns0:createEpisodeResponse/ns1:Episode} document with {@code episodeId}
     * {@link #EPISODE_NOW}, the given {@code ns1:PatientId}, {@code admission} {@code Elective} and {@code care}
     * {@code Private}.
     *
     * @param patientId the {@code ns1:PatientId} text
     * @param startDate the {@code startDate} text, or {@code null} to omit the element
     * @param endDate the {@code endDate} text, or {@code null} to omit the element
     * @return the document text
     */
    private static String createEpisodeResponse(String patientId, String startDate, String endDate) {
        return "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">"
                + "<ns1:Episode xmlns:ns1=\"" + NS1 + "\">"
                + "<episodeId>" + EPISODE_NOW + "</episodeId>"
                + "<ns1:PatientId>" + patientId + "</ns1:PatientId>"
                + "<admission>Elective</admission>"
                + (startDate == null ? "" : "<startDate>" + startDate + "</startDate>")
                + (endDate == null ? "" : "<endDate>" + endDate + "</endDate>")
                + "<care>Private</care>"
                + "</ns1:Episode>"
                + "</ns0:createEpisodeResponse>";
    }

    /**
     * Reads classpath {@code /original/message.xml} with the namespace-aware parser and returns the first element
     * child of its SOAP {@code Body}, asserted to be {@code admitSubject} in {@code NS0}.
     *
     * @return the {@code ns:admitSubject} element of the original request
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the resource is not well-formed
     * @throws IOException when the resource cannot be read
     */
    private static Element admitSubjectOfOriginalMessage()
            throws ParserConfigurationException, SAXException, IOException {
        try (InputStream message = AdmissionServiceTest.class.getResourceAsStream("/original/message.xml")) {
            assertNotNull(message, "classpath resource /original/message.xml");
            Element envelope = namespaceAwareFactory().newDocumentBuilder().parse(message).getDocumentElement();
            Element body = (Element) envelope.getElementsByTagNameNS(SOAP_ENV, "Body").item(0);
            assertNotNull(body, "SOAP Body of /original/message.xml");
            List<Element> bodyChildren = elementChildren(body);
            assertFalse(bodyChildren.isEmpty(), "element child of the SOAP Body");
            Element admitSubject = bodyChildren.get(0);
            assertQualifiedName(NS0, "admitSubject", admitSubject);
            return admitSubject;
        }
    }

    /**
     * Parses {@code xml} with a namespace-aware parser that rejects document type declarations.
     *
     * @param xml the document text
     * @return the document element
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed or declares a document type
     * @throws IOException when the text cannot be read
     */
    private static Element parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        return namespaceAwareFactory().newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)))
                .getDocumentElement();
    }

    /**
     * Creates a namespace-aware {@link DocumentBuilderFactory} with {@code disallow-doctype-decl} enabled.
     *
     * @return a new factory
     * @throws ParserConfigurationException when the feature cannot be set
     */
    private static DocumentBuilderFactory namespaceAwareFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        return factory;
    }

    /**
     * Returns the element children of {@code parent} in document order.
     *
     * @param parent the parent element
     * @return its child elements
     */
    private static List<Element> elementChildren(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) child);
            }
        }
        return children;
    }
}
