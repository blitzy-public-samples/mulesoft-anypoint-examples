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
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.ws.client.WebServiceIOException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import com.mulesoft.examples.xml_only_soap_webservice.client.EhrServiceClient;
import com.mulesoft.examples.xml_only_soap_webservice.client.PatientServiceClient;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.AdmissionMapper;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.EhrMockMapper;

/**
 * Unit tests of {@link AdmissionService}: the flow {@code admitPatientService} and its sub-flows
 * {@code upsertPatient}, {@code invokePatientService}, {@code createEpisode} and {@code invokeEHRService} of
 * {@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml} (lines 19-88, D-417).
 *
 * <p>{@link PatientServiceClient} and {@link EhrServiceClient} are Mockito mocks created in the field
 * declarations, the service runs its own {@link AdmissionMapper}, and no Spring application context starts;
 * {@code @ActiveProfiles} only names the {@code test} profile. The request is the {@code ns:admitSubject} Body
 * child of {@code original/message.xml}. The client answers are an {@code ns0:upsertPatientResponse} holding
 * {@code ns1:PatientId} {@code P123}, the DW-44 {@code ns0:createEpisodeResponse} that {@link EhrMockMapper}
 * writes for {@link #NOW}, and an {@code ns0:createEpisodeResponse} without {@code startDate} and
 * {@code endDate}.
 *
 * <p>The tests assert the order of the two client calls, the DW-40 and DW-41 elements each client receives,
 * the DW-39 {@code ns0:admitSubjectResponse} the flow returns, the identity of the elements each sub-flow
 * passes on and returns, and the propagation of client, mapper and parser exceptions. Both sides of every
 * document comparison pass through one identity transformer and are compared by XMLUnit with whitespace
 * ignored and namespace URIs and local names compared (D-049, D-050, D-556).
 */
@ActiveProfiles("test")
public class AdmissionServiceTest {

    /** Namespace of the SOA message elements ({@code ns0}, {@code ns}). */
    private static final String MSG_NS = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the SOA model elements ({@code ns1}). */
    private static final String MODEL_NS = "http://www.mule-health.com/SOA/model/1.0";

    /** Namespace of the SOAP 1.1 envelope of {@code original/message.xml}. */
    private static final String SOAP_NS = "http://schemas.xmlsoap.org/soap/envelope/";

    /** Fixed clock of the EHRService stub answer. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2015-03-01T10:15:30.123Z"), ZoneOffset.UTC);

    /** The instant {@link EhrMockMapper} writes as {@code episodeId}, {@code startDate} and {@code endDate}. */
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    /** Feature that makes the parser reject any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** The {@code ns1:PatientId} of the PatientService stub answer. */
    private static final String PATIENT_ID = "P123";

    /** Local names of the {@code ns1:Subject} children of {@code original/message.xml}, in document order. */
    private static final List<String> SUBJECT_FIELDS = List.of("nationalId", "firstName", "lastName", "address1",
            "address2", "address3", "nationality", "gender", "dateOfBirth");

    /** Texts of the {@code ns1:Subject} children of {@code original/message.xml}, in document order. */
    private static final List<String> SUBJECT_VALUES = List.of("1234", "Nial", "Darbey", "Buenos Aires", "", "",
            "Irish", "Male", "1970-08-07");

    /** Local names of the DW-39 {@code ns1:Episode} children when the EHRService Episode has both dates. */
    private static final List<String> EPISODE_FIELDS_WITH_DATES =
            List.of("episodeId", "PatientId", "admission", "startDate", "endDate", "care");

    /** Local names of the DW-39 {@code ns1:Episode} children when the EHRService Episode has no date. */
    private static final List<String> EPISODE_FIELDS_WITHOUT_DATES =
            List.of("episodeId", "PatientId", "admission", "care");

    /** Mock of the PatientService client (sub-flow {@code invokePatientService}). */
    private final PatientServiceClient patientClient = Mockito.mock(PatientServiceClient.class);

    /** Mock of the EHRService client (sub-flow {@code invokeEHRService}). */
    private final EhrServiceClient ehrClient = Mockito.mock(EhrServiceClient.class);

    /** The service under test. */
    private final AdmissionService service = new AdmissionService(patientClient, ehrClient);

    /**
     * Asserts one admission of {@code original/message.xml}: the DW-40 {@code ns0:upsertPatient} is sent to the
     * PatientService, then the DW-41 {@code ns0:createEpisode} with the returned {@code ns1:PatientId} to the
     * EHRService, and the returned document is the DW-39 {@code ns0:admitSubjectResponse} of the EHRService
     * answer.
     *
     * @throws Exception when a document cannot be read, parsed or serialised
     */
    @Test
    @DisplayName("admitPatientService sends upsertPatient, then createEpisode, and returns admitSubjectResponse (DW-39, DW-40, DW-41)")
    public void admitPatientServiceCallsPatientThenEhrAndReturnsAdmitSubjectResponse() throws Exception {
        when(patientClient.invoke(any(Element.class))).thenReturn(patientResponse());
        when(ehrClient.invoke(any(Element.class))).thenReturn(ehrResponse());

        Source result = service.admitPatientService(admitSubject());

        ArgumentCaptor<Element> patientCaptor = ArgumentCaptor.forClass(Element.class);
        ArgumentCaptor<Element> ehrCaptor = ArgumentCaptor.forClass(Element.class);
        InOrder inOrder = Mockito.inOrder(patientClient, ehrClient);
        inOrder.verify(patientClient).invoke(patientCaptor.capture());
        inOrder.verify(ehrClient).invoke(ehrCaptor.capture());
        verifyNoMoreInteractions(patientClient, ehrClient);

        Element upsertPatient = patientCaptor.getValue();
        assertUpsertPatientOfMessageSubject(upsertPatient);
        assertXmlEquals(normalise(new AdmissionMapper().toUpsertPatient(admitSubject())), toXml(upsertPatient));

        Element createEpisode = ehrCaptor.getValue();
        assertCreateEpisode(createEpisode, PATIENT_ID);
        assertXmlEquals(normalise(new AdmissionMapper().toCreateEpisode(patientResponse())), toXml(createEpisode));

        String response = toXml(result);
        assertXmlEquals(normalise(new AdmissionMapper().toAdmitSubjectResponse(ehrResponse())), response);
        assertAdmitSubjectResponse(parse(response), EPISODE_FIELDS_WITH_DATES, PATIENT_ID);
    }

    /**
     * Asserts the returned {@code ns0:admitSubjectResponse} is the DW-39 output for an EHRService
     * {@code ns1:Episode} without {@code startDate} and {@code endDate}, and holds neither element.
     *
     * @throws Exception when a document cannot be read, parsed or serialised
     */
    @Test
    @DisplayName("admitPatientService omits startDate and endDate when the EHRService Episode has neither (DW-39)")
    public void admitPatientServiceWithoutEpisodeDatesReturnsMapperOutput() throws Exception {
        when(patientClient.invoke(any(Element.class))).thenReturn(patientResponse());
        when(ehrClient.invoke(any(Element.class))).thenReturn(ehrResponseWithoutDates());

        Source result = service.admitPatientService(admitSubject());

        ArgumentCaptor<Element> patientCaptor = ArgumentCaptor.forClass(Element.class);
        ArgumentCaptor<Element> ehrCaptor = ArgumentCaptor.forClass(Element.class);
        InOrder inOrder = Mockito.inOrder(patientClient, ehrClient);
        inOrder.verify(patientClient).invoke(patientCaptor.capture());
        inOrder.verify(ehrClient).invoke(ehrCaptor.capture());
        verifyNoMoreInteractions(patientClient, ehrClient);
        assertXmlEquals(normalise(new AdmissionMapper().toUpsertPatient(admitSubject())),
                toXml(patientCaptor.getValue()));
        assertXmlEquals(normalise(new AdmissionMapper().toCreateEpisode(patientResponse())),
                toXml(ehrCaptor.getValue()));

        String response = toXml(result);
        assertXmlEquals(normalise(new AdmissionMapper().toAdmitSubjectResponse(ehrResponseWithoutDates())), response);
        Element admitSubjectResponse = parse(response);
        assertAdmitSubjectResponse(admitSubjectResponse, EPISODE_FIELDS_WITHOUT_DATES, PATIENT_ID);
        assertEquals(0, admitSubjectResponse.getElementsByTagNameNS("*", "startDate").getLength(), response);
        assertEquals(0, admitSubjectResponse.getElementsByTagNameNS("*", "endDate").getLength(), response);
    }

    /**
     * Asserts a PatientService failure reaches the caller as the same exception instance and the EHRService is
     * not called.
     *
     * @throws Exception when the request cannot be read or parsed
     */
    @Test
    @DisplayName("admitPatientService rethrows the PatientService failure unchanged and does not call the EHRService")
    public void admitPatientServicePropagatesPatientServiceFailure() throws Exception {
        WebServiceIOException failure = new WebServiceIOException("PatientService unreachable");
        when(patientClient.invoke(any(Element.class))).thenThrow(failure);

        WebServiceIOException thrown =
                assertThrows(WebServiceIOException.class, () -> service.admitPatientService(admitSubject()));

        assertSame(failure, thrown);
        verify(patientClient, times(1)).invoke(any(Element.class));
        verifyNoMoreInteractions(patientClient);
        verifyNoInteractions(ehrClient);
    }

    /**
     * Asserts an EHRService failure reaches the caller as the same exception instance after exactly one
     * PatientService call.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    @DisplayName("admitPatientService rethrows the EHRService failure unchanged after one PatientService call")
    public void admitPatientServicePropagatesEhrServiceFailure() throws Exception {
        WebServiceIOException failure = new WebServiceIOException("EHRService unreachable");
        when(patientClient.invoke(any(Element.class))).thenReturn(patientResponse());
        when(ehrClient.invoke(any(Element.class))).thenThrow(failure);

        WebServiceIOException thrown =
                assertThrows(WebServiceIOException.class, () -> service.admitPatientService(admitSubject()));

        assertSame(failure, thrown);
        verify(patientClient, times(1)).invoke(any());
        verify(ehrClient, times(1)).invoke(any());
        verifyNoMoreInteractions(patientClient, ehrClient);
    }

    /**
     * Asserts {@code upsertPatient} sends the DW-40 {@code ns0:upsertPatient} of {@code original/message.xml} to
     * the PatientService as the document element of its own document, whitespace text nodes kept, and returns
     * the PatientService answer as the same element.
     *
     * @throws Exception when a document cannot be read, parsed or serialised
     */
    @Test
    @DisplayName("upsertPatient sends the DW-40 upsertPatient element to the PatientService and returns its answer")
    public void upsertPatientSendsUpsertPatientRequestAndReturnsResponse() throws Exception {
        Element stub = patientResponse();
        when(patientClient.invoke(any(Element.class))).thenReturn(stub);

        Element out = service.upsertPatient(admitSubject());

        assertSame(stub, out);
        ArgumentCaptor<Element> captor = ArgumentCaptor.forClass(Element.class);
        verify(patientClient).invoke(captor.capture());
        verifyNoMoreInteractions(patientClient);
        verifyNoInteractions(ehrClient);
        Element upsertPatient = captor.getValue();
        assertUpsertPatientOfMessageSubject(upsertPatient);
        assertXmlEquals(normalise(new AdmissionMapper().toUpsertPatient(admitSubject())), toXml(upsertPatient));
        assertSame(upsertPatient, upsertPatient.getOwnerDocument().getDocumentElement());
        Node first = upsertPatient.getFirstChild();
        assertEquals(Node.TEXT_NODE, first.getNodeType());
        assertEquals("\n  ", first.getNodeValue());
    }

    /**
     * Asserts {@code invokePatientService} hands its element to {@link PatientServiceClient#invoke(Element)} as
     * the same instance and returns the client's element as the same instance.
     *
     * @throws Exception when a document cannot be parsed
     */
    @Test
    @DisplayName("invokePatientService passes its element to the PatientService client and returns the client's element")
    public void invokePatientServicePassesRequestToClient() throws Exception {
        Element req = upsertPatientRequest();
        Element stub = patientResponse();
        when(patientClient.invoke(same(req))).thenReturn(stub);

        assertSame(stub, service.invokePatientService(req));

        verify(patientClient).invoke(same(req));
        verifyNoMoreInteractions(patientClient);
        verifyNoInteractions(ehrClient);
    }

    /**
     * Asserts {@code invokePatientService} returns {@code null} when the PatientService client returns
     * {@code null}, its result for an empty response Body.
     *
     * @throws Exception when a document cannot be parsed
     */
    @Test
    @DisplayName("invokePatientService returns null when the PatientService client returns null for an empty response Body")
    public void invokePatientServiceReturnsNullForEmptyBody() throws Exception {
        Element req = upsertPatientRequest();
        when(patientClient.invoke(same(req))).thenReturn(null);

        assertNull(service.invokePatientService(req));

        verify(patientClient).invoke(same(req));
        verifyNoMoreInteractions(patientClient);
        verifyNoInteractions(ehrClient);
    }

    /**
     * Asserts {@code createEpisode} sends the DW-41 {@code ns0:createEpisode} holding the {@code ns1:PatientId}
     * of the given {@code ns0:upsertPatientResponse} to the EHRService, and returns the EHRService answer as the
     * same element.
     *
     * @throws Exception when a document cannot be read, parsed or serialised
     */
    @Test
    @DisplayName("createEpisode sends the DW-41 createEpisode element to the EHRService and returns its answer")
    public void createEpisodeSendsCreateEpisodeRequestAndReturnsResponse() throws Exception {
        Element stub = ehrResponse();
        when(ehrClient.invoke(any(Element.class))).thenReturn(stub);

        Element out = service.createEpisode(patientResponse());

        assertSame(stub, out);
        ArgumentCaptor<Element> captor = ArgumentCaptor.forClass(Element.class);
        verify(ehrClient).invoke(captor.capture());
        verifyNoMoreInteractions(ehrClient);
        verifyNoInteractions(patientClient);
        Element createEpisode = captor.getValue();
        assertCreateEpisode(createEpisode, PATIENT_ID);
        assertXmlEquals(normalise(new AdmissionMapper().toCreateEpisode(patientResponse())), toXml(createEpisode));
    }

    /**
     * Asserts {@code invokeEhrService} hands its element to {@link EhrServiceClient#invoke(Element)} as the same
     * instance and returns the client's element as the same instance.
     *
     * @throws Exception when a document cannot be parsed
     */
    @Test
    @DisplayName("invokeEhrService passes its element to the EHRService client and returns the client's element")
    public void invokeEhrServicePassesRequestToClient() throws Exception {
        Element req = createEpisodeRequest();
        Element stub = ehrResponse();
        when(ehrClient.invoke(same(req))).thenReturn(stub);

        assertSame(stub, service.invokeEhrService(req));

        verify(ehrClient).invoke(same(req));
        verifyNoMoreInteractions(ehrClient);
        verifyNoInteractions(patientClient);
    }


    // The four tests below assert the D-417 branches of AdmissionService beyond the nine admission and
    // sub-flow tests above (D-556).

    /**
     * Asserts a {@code null} {@code admitSubject}, the result of an empty request Body, sends a DW-40
     * {@code ns0:upsertPatient} holding an empty {@code ns1:Subject}, and the admission continues with the
     * PatientService answer.
     *
     * @throws Exception when a document cannot be read, parsed or serialised
     */
    @Test
    @DisplayName("admitPatientService sends upsertPatient with an empty Subject for a null admitSubject and completes")
    public void admitPatientServiceSendsEmptySubjectForNullAdmitSubject() throws Exception {
        when(patientClient.invoke(any(Element.class))).thenReturn(patientResponse());
        when(ehrClient.invoke(any(Element.class))).thenReturn(ehrResponse());

        Source result = service.admitPatientService(null);

        ArgumentCaptor<Element> patientCaptor = ArgumentCaptor.forClass(Element.class);
        ArgumentCaptor<Element> ehrCaptor = ArgumentCaptor.forClass(Element.class);
        InOrder inOrder = Mockito.inOrder(patientClient, ehrClient);
        inOrder.verify(patientClient).invoke(patientCaptor.capture());
        inOrder.verify(ehrClient).invoke(ehrCaptor.capture());
        verifyNoMoreInteractions(patientClient, ehrClient);
        Element upsertPatient = patientCaptor.getValue();
        assertQualifiedName(MSG_NS, "upsertPatient", upsertPatient);
        List<Element> children = childElements(upsertPatient);
        assertEquals(1, children.size());
        assertQualifiedName(MODEL_NS, "Subject", children.get(0));
        assertFalse(children.get(0).hasChildNodes(), "ns1:Subject has no child nodes");
        assertXmlEquals(normalise(new AdmissionMapper().toUpsertPatient(null)), toXml(upsertPatient));
        assertCreateEpisode(ehrCaptor.getValue(), PATIENT_ID);
        assertXmlEquals(normalise(new AdmissionMapper().toAdmitSubjectResponse(ehrResponse())), toXml(result));
    }

    /**
     * Asserts {@code null} client results reach the next mapper call: a {@code null} PatientService answer sends a
     * DW-41 {@code ns0:createEpisode} with an empty {@code ns1:PatientId}, and a {@code null} EHRService answer
     * returns a DW-39 {@code ns1:Episode} of empty elements beside the {@code Bill } element.
     *
     * @throws Exception when a document cannot be read, parsed or serialised
     */
    @Test
    @DisplayName("admitPatientService passes null client results to the next mapper and returns an empty Episode")
    public void admitPatientServicePassesNullClientResultsToTheNextMapper() throws Exception {
        when(patientClient.invoke(any(Element.class))).thenReturn(null);
        when(ehrClient.invoke(any(Element.class))).thenReturn(null);

        Source result = service.admitPatientService(admitSubject());

        ArgumentCaptor<Element> ehrCaptor = ArgumentCaptor.forClass(Element.class);
        InOrder inOrder = Mockito.inOrder(patientClient, ehrClient);
        inOrder.verify(patientClient).invoke(any(Element.class));
        inOrder.verify(ehrClient).invoke(ehrCaptor.capture());
        verifyNoMoreInteractions(patientClient, ehrClient);
        Element createEpisode = ehrCaptor.getValue();
        assertQualifiedName(MSG_NS, "createEpisode", createEpisode);
        List<Element> children = childElements(createEpisode);
        assertEquals(1, children.size());
        assertQualifiedName(MODEL_NS, "PatientId", children.get(0));
        assertFalse(children.get(0).hasChildNodes(), "ns1:PatientId has no child nodes");
        assertXmlEquals(normalise(new AdmissionMapper().toCreateEpisode(null)), toXml(createEpisode));

        String response = toXml(result);
        assertXmlEquals(normalise(new AdmissionMapper().toAdmitSubjectResponse(null)), response);
        Element admitSubjectResponse = parse(response);
        assertAdmitSubjectResponse(admitSubjectResponse, EPISODE_FIELDS_WITHOUT_DATES, "");
        for (Element field : childElements(childElements(admitSubjectResponse).get(0))) {
            assertFalse(field.hasChildNodes(), field.getLocalName() + " has no child nodes");
        }
    }

    /**
     * Asserts a {@code startDate} of the EHRService answer that does not begin with an ISO date raises the DW-39
     * {@link DateTimeParseException} to the caller after both client calls.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    @DisplayName("admitPatientService rethrows the DW-39 DateTimeParseException for a startDate without an ISO date")
    public void admitPatientServicePropagatesDw39DateTimeParseException() throws Exception {
        when(patientClient.invoke(any(Element.class))).thenReturn(patientResponse());
        when(ehrClient.invoke(any(Element.class))).thenReturn(parse("<ns0:createEpisodeResponse xmlns:ns0='"
                + MSG_NS + "' xmlns:ns1='" + MODEL_NS + "'><ns1:Episode><episodeId>E1</episodeId>"
                + "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId><admission>Elective</admission>"
                + "<startDate>not-a-date</startDate><care>Private</care></ns1:Episode>"
                + "</ns0:createEpisodeResponse>"));

        DateTimeParseException thrown =
                assertThrows(DateTimeParseException.class, () -> service.admitPatientService(admitSubject()));

        assertEquals("not-a-date", thrown.getParsedString());
        verify(patientClient, times(1)).invoke(any(Element.class));
        verify(ehrClient, times(1)).invoke(any(Element.class));
        verifyNoMoreInteractions(patientClient, ehrClient);
    }

    /**
     * Asserts DW-40 text that the namespace-aware parser rejects raises {@link IllegalStateException} carrying
     * the parser's message and the parser exception as its cause, before any client call. The
     * {@code admitSubject} is parsed without namespace awareness and holds the {@code Subject} child
     * {@code x:y:nationalId}, which DW-40 writes as {@code y:nationalId} with the prefix {@code y} unbound.
     *
     * @throws Exception when the request cannot be parsed
     */
    @Test
    @DisplayName("admitPatientService raises IllegalStateException with the parser's message for unparsable DW-40 text")
    public void admitPatientServiceRaisesIllegalStateExceptionWhenDw40TextCannotBeParsed() throws Exception {
        Element admitSubject = secureFactory(false).newDocumentBuilder()
                .parse(new InputSource(new StringReader("<ns:admitSubject xmlns:ns='" + MSG_NS + "' xmlns:ns1='"
                        + MODEL_NS + "'><ns1:Subject><x:y:nationalId>1234</x:y:nationalId></ns1:Subject>"
                        + "</ns:admitSubject>")))
                .getDocumentElement();

        IllegalStateException thrown =
                assertThrows(IllegalStateException.class, () -> service.admitPatientService(admitSubject));

        SAXException cause = assertInstanceOf(SAXException.class, thrown.getCause());
        assertEquals(cause.getMessage(), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("y:nationalId"), thrown.getMessage());
        verifyNoInteractions(patientClient, ehrClient);
    }


    /**
     * Returns the PatientService stub answer: {@code ns0:upsertPatientResponse} holding {@code ns1:PatientId}
     * {@code P123} in the model namespace, the element DW-41 selects as {@code ns1#PatientId}.
     *
     * @return the document element of a new document
     * @throws Exception when the text cannot be parsed
     */
    private static Element patientResponse() throws Exception {
        return parse("<ns0:upsertPatientResponse xmlns:ns0='" + MSG_NS + "' xmlns:ns1='" + MODEL_NS + "'>"
                + "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId></ns0:upsertPatientResponse>");
    }

    /**
     * Returns the EHRService stub answer: the DW-44 {@code ns0:createEpisodeResponse/ns1:Episode} that
     * {@link EhrMockMapper#createEpisodeResponse(Element, OffsetDateTime)} writes for
     * {@link #createEpisodeRequest()} and {@link #NOW}, with {@code startDate} and {@code endDate}.
     *
     * @return the document element of a new document
     * @throws Exception when the text cannot be parsed
     */
    private static Element ehrResponse() throws Exception {
        return parse(new EhrMockMapper().createEpisodeResponse(createEpisodeRequest(), NOW));
    }

    /**
     * Returns an EHRService stub answer {@code ns0:createEpisodeResponse/ns1:Episode} with {@code episodeId}
     * {@code E1}, {@code ns1:PatientId} {@code P123}, {@code admission} {@code Elective} and {@code care}
     * {@code Private}, and no {@code startDate} and no {@code endDate}.
     *
     * @return the document element of a new document
     * @throws Exception when the text cannot be parsed
     */
    private static Element ehrResponseWithoutDates() throws Exception {
        return parse("<ns0:createEpisodeResponse xmlns:ns0='" + MSG_NS + "' xmlns:ns1='" + MODEL_NS + "'>"
                + "<ns1:Episode><episodeId>E1</episodeId><ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId>"
                + "<admission>Elective</admission><care>Private</care></ns1:Episode>"
                + "</ns0:createEpisodeResponse>");
    }

    /**
     * Returns an {@code ns0:upsertPatient} request holding an {@code ns1:Subject} with one {@code nationalId}.
     *
     * @return the document element of a new document
     * @throws Exception when the text cannot be parsed
     */
    private static Element upsertPatientRequest() throws Exception {
        return parse("<ns0:upsertPatient xmlns:ns0='" + MSG_NS + "' xmlns:ns1='" + MODEL_NS + "'>"
                + "<ns1:Subject><nationalId>1234</nationalId></ns1:Subject></ns0:upsertPatient>");
    }

    /**
     * Returns an {@code ns0:createEpisode} request holding {@code ns1:PatientId} {@code P123}.
     *
     * @return the document element of a new document
     * @throws Exception when the text cannot be parsed
     */
    private static Element createEpisodeRequest() throws Exception {
        return parse("<ns0:createEpisode xmlns:ns0='" + MSG_NS + "' xmlns:ns1='" + MODEL_NS + "'>"
                + "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId></ns0:createEpisode>");
    }

    /**
     * Asserts {@code element} is {@code ns0:upsertPatient} holding one {@code ns1:Subject} whose element children
     * are the nine unqualified children of the {@code ns1:Subject} of {@code original/message.xml}, with their
     * texts, in document order.
     *
     * @param element the element sent to the PatientService
     */
    private static void assertUpsertPatientOfMessageSubject(Element element) {
        assertQualifiedName(MSG_NS, "upsertPatient", element);
        List<Element> children = childElements(element);
        assertEquals(1, children.size(), "element children of ns0:upsertPatient");
        Element subject = children.get(0);
        assertQualifiedName(MODEL_NS, "Subject", subject);
        List<Element> fields = childElements(subject);
        List<String> values = new ArrayList<>();
        for (Element field : fields) {
            assertNull(field.getNamespaceURI(), "namespace of " + field.getLocalName());
            values.add(field.getTextContent());
        }
        assertEquals(SUBJECT_FIELDS, localNames(fields));
        assertEquals(SUBJECT_VALUES, values);
    }

    /**
     * Asserts {@code element} is {@code ns0:createEpisode} holding one {@code ns1:PatientId} with the given text.
     *
     * @param element the element sent to the EHRService
     * @param patientId the expected {@code ns1:PatientId} text
     */
    private static void assertCreateEpisode(Element element, String patientId) {
        assertQualifiedName(MSG_NS, "createEpisode", element);
        List<Element> children = childElements(element);
        assertEquals(1, children.size(), "element children of ns0:createEpisode");
        assertQualifiedName(MODEL_NS, "PatientId", children.get(0));
        assertEquals(patientId, children.get(0).getTextContent());
    }

    /**
     * Asserts {@code response} is {@code ns0:admitSubjectResponse} holding {@code ns1:Episode} and
     * {@code ns1:Bill}: the Episode children have the given local names in order and its {@code ns1:PatientId}
     * has the given text, and the Bill holds {@code costPerNight} {@code 100}, {@code initialStateEstimate}
     * {@code 5}, {@code runningTotal} {@code 500} and {@code status} {@code ADMITTED}.
     *
     * @param response the returned document element
     * @param episodeFields the expected local names of the Episode children
     * @param patientId the expected {@code ns1:PatientId} text
     */
    private static void assertAdmitSubjectResponse(Element response, List<String> episodeFields, String patientId) {
        assertQualifiedName(MSG_NS, "admitSubjectResponse", response);
        List<Element> children = childElements(response);
        assertEquals(2, children.size(), "element children of ns0:admitSubjectResponse");
        Element episode = children.get(0);
        assertQualifiedName(MODEL_NS, "Episode", episode);
        List<Element> episodeChildren = childElements(episode);
        assertEquals(episodeFields, localNames(episodeChildren));
        Element episodePatientId = episodeChildren.get(episodeFields.indexOf("PatientId"));
        assertQualifiedName(MODEL_NS, "PatientId", episodePatientId);
        assertEquals(patientId, episodePatientId.getTextContent());

        Element bill = children.get(1);
        assertQualifiedName(MODEL_NS, "Bill", bill);
        List<Element> billChildren = childElements(bill);
        List<String> billValues = new ArrayList<>();
        for (Element field : billChildren) {
            billValues.add(field.getTextContent());
        }
        assertEquals(List.of("costPerNight", "initialStateEstimate", "runningTotal", "status"),
                localNames(billChildren));
        assertEquals(List.of("100", "5", "500", "ADMITTED"), billValues);
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
     * Asserts two XML documents are similar under XMLUnit: whitespace-only text is ignored, a prefix-only
     * difference is similar, and any difference of namespace URI, local name, attribute or text fails.
     *
     * @param expected the expected document text
     * @param actual the actual document text
     */
    private static void assertXmlEquals(String expected, String actual) {
        Diff d = DiffBuilder.compare(Input.fromString(expected))
                .withTest(Input.fromString(actual))
                .ignoreWhitespace()
                .checkForSimilar()
                .build();
        assertFalse(d.hasDifferences(), d.toString());
    }

    /**
     * Reads classpath {@code original/message.xml} with the namespace-aware parser and returns the first element
     * child of its {@code {SOAP_NS}Body}, asserted to be {@code admitSubject} in {@link #MSG_NS}.
     *
     * @return the {@code ns:admitSubject} element of the original request
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the resource is not well-formed
     * @throws IOException when the resource cannot be read
     */
    private static Element admitSubject() throws ParserConfigurationException, SAXException, IOException {
        try (InputStream message =
                AdmissionServiceTest.class.getClassLoader().getResourceAsStream("original/message.xml")) {
            assertNotNull(message, "classpath resource original/message.xml");
            Element envelope = secureFactory(true).newDocumentBuilder().parse(message).getDocumentElement();
            Element body = (Element) envelope.getElementsByTagNameNS(SOAP_NS, "Body").item(0);
            assertNotNull(body, "{" + SOAP_NS + "}Body of original/message.xml");
            List<Element> bodyChildren = childElements(body);
            assertFalse(bodyChildren.isEmpty(), "element child of the SOAP Body");
            Element admitSubject = bodyChildren.get(0);
            assertEquals(MSG_NS, admitSubject.getNamespaceURI(), "namespace of the SOAP Body child");
            assertEquals("admitSubject", admitSubject.getLocalName(), "local name of the SOAP Body child");
            return admitSubject;
        }
    }

    /**
     * Parses {@code xml} with the namespace-aware parser and returns its document element.
     *
     * @param xml the document text
     * @return the document element of a new document
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed or declares a document type
     * @throws IOException when the text cannot be read
     */
    private static Element parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        return secureFactory(true).newDocumentBuilder()
                .parse(new InputSource(new StringReader(xml)))
                .getDocumentElement();
    }

    /**
     * Creates a {@link DocumentBuilderFactory} that rejects document type declarations and expands neither
     * XInclude nor entity references.
     *
     * @param namespaceAware whether the parser is namespace-aware
     * @return a new factory
     * @throws ParserConfigurationException when the feature cannot be set
     */
    private static DocumentBuilderFactory secureFactory(boolean namespaceAware) throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    /**
     * Serialises {@code source} with the JDK identity transformer, external DTD and stylesheet access disabled.
     * A {@link StreamSource} is read once.
     *
     * @param source the document to serialise
     * @return the serialised document text
     * @throws TransformerException when the source cannot be read or serialised
     */
    private static String toXml(Source source) throws TransformerException {
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        StringWriter xml = new StringWriter();
        factory.newTransformer().transform(source, new StreamResult(xml));
        return xml.toString();
    }

    /**
     * Serialises {@code element} and its subtree with {@link #toXml(Source)}.
     *
     * @param element the element to serialise
     * @return the serialised element text
     * @throws TransformerException when the element cannot be serialised
     */
    private static String toXml(Element element) throws TransformerException {
        return toXml(new DOMSource(element));
    }

    /**
     * Serialises mapper text with {@link #toXml(Source)}, the form every comparison applies to both sides.
     *
     * @param mapperText the XML text written by a mapper
     * @return the serialised document text
     * @throws TransformerException when the text cannot be parsed or serialised
     */
    private static String normalise(String mapperText) throws TransformerException {
        return toXml(new StreamSource(new StringReader(mapperText)));
    }

    /**
     * Returns the element children of {@code parent} in document order.
     *
     * @param parent the parent element
     * @return its child elements
     */
    private static List<Element> childElements(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) child);
            }
        }
        return children;
    }

    /**
     * Returns the local names of {@code elements}, in order.
     *
     * @param elements the elements
     * @return their local names
     */
    private static List<String> localNames(List<Element> elements) {
        List<String> names = new ArrayList<>();
        for (Element element : elements) {
            names.add(element.getLocalName());
        }
        return names;
    }
}
