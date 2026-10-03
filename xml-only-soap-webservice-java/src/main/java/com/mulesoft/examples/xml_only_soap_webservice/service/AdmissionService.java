package com.mulesoft.examples.xml_only_soap_webservice.service;

import java.io.IOException;
import java.io.StringReader;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.springframework.stereotype.Service;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import com.mulesoft.examples.xml_only_soap_webservice.client.EhrServiceClient;
import com.mulesoft.examples.xml_only_soap_webservice.client.PatientServiceClient;
import com.mulesoft.examples.xml_only_soap_webservice.mapper.AdmissionMapper;

/**
 * Implements the Mule flow {@code admitPatientService} and its sub-flows {@code upsertPatient},
 * {@code invokePatientService}, {@code createEpisode} and {@code invokeEHRService} of
 * {@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml} (lines 19-88). The
 * request and response XML is written by {@link AdmissionMapper} (D-034), and the two downstream
 * SOAP calls go through {@link PatientServiceClient} and {@link EhrServiceClient}.
 *
 * <p>{@link #admitPatientService(Element)} is the entry method of the SOAP-triggered flow:
 * {@code AdmissionServiceEndpoint.admitSubject} passes it the {@code admitSubject} child of the
 * request SOAP Body and returns its {@link Source} as the response payload (D-029). The four
 * sub-flows are the package-private methods {@link #upsertPatient(Element)},
 * {@link #invokePatientService(Element)}, {@link #createEpisode(Element)} and
 * {@link #invokeEhrService(Element)}.
 *
 * <p>One admission runs these steps in this order, each on the result of the previous one:
 *
 * <ol>
 *   <li>DW-40 builds {@code ns0:upsertPatient}, which is posted to {@code /PatientService};</li>
 *   <li>DW-41 builds {@code ns0:createEpisode} from the PatientService response, which is posted to
 *       {@code /EHRService};</li>
 *   <li>DW-39 builds {@code ns0:admitSubjectResponse} from the EHRService response.</li>
 * </ol>
 *
 * <p>The DW-40 and DW-41 text is parsed into a DOM element before each client call; the DW-39 text
 * is returned unparsed. A value returned by a client, {@code null} included, reaches the next mapper
 * call unchanged. Client and mapper exceptions propagate unchanged: a failed PatientService call ends
 * the admission before the EHRService call, and {@code SoapFaultMappingExceptionResolver} answers the
 * request with a {@code soap:Server} fault. The flow has no logger and no exception strategy, and
 * this class logs nothing (D-417).
 *
 * <p>The class holds no state besides its final collaborators; concurrent admissions do not affect
 * each other.
 *
 * <pre>{@code
 * AdmissionService service = new AdmissionService(patientServiceClient, ehrServiceClient);
 * Source admitSubjectResponse = service.admitPatientService(admitSubject);
 * }</pre>
 */
@Service
public class AdmissionService {

    /** Client of the {@code /PatientService} SOAP endpoint (sub-flow {@code invokePatientService}). */
    private final PatientServiceClient patientServiceClient;

    /** Client of the {@code /EHRService} SOAP endpoint (sub-flow {@code invokeEHRService}). */
    private final EhrServiceClient ehrServiceClient;

    /** Writer of the DW-39, DW-40 and DW-41 documents (D-034, D-156). */
    private final AdmissionMapper mapper;

    /**
     * Creates the service over the two downstream SOAP clients and a new {@link AdmissionMapper}.
     *
     * @param patientServiceClient the client that posts to {@code /PatientService}
     * @param ehrServiceClient     the client that posts to {@code /EHRService}
     */
    public AdmissionService(PatientServiceClient patientServiceClient, EhrServiceClient ehrServiceClient) {
        this.patientServiceClient = patientServiceClient;
        this.ehrServiceClient = ehrServiceClient;
        this.mapper = new AdmissionMapper();
    }

    /**
     * Implements the flow {@code admitPatientService}
     * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:19-48]: runs
     * {@link #upsertPatient(Element)} (flow-ref :23), then {@link #createEpisode(Element)} (flow-ref
     * :24) on the PatientService response, then applies DW-39 (:26-46) through
     * {@link AdmissionMapper#toAdmitSubjectResponse(Element)} to the EHRService response.
     *
     * <p>The returned document is {@code ns0:admitSubjectResponse}, holding {@code ns1:Episode} and
     * the element named {@code Bill } with its trailing space, which carries {@code costPerNight}
     * {@code 100}, {@code initialStateEstimate} {@code 5}, {@code runningTotal} {@code 500} and
     * {@code status} {@code ADMITTED} (D-029, D-043).
     *
     * @param admitSubject the {@code ns:admitSubject} child of the request SOAP Body, or {@code null}
     *                     for an empty Body
     * @return the DW-39 {@code admitSubjectResponse} text as a {@link StreamSource}, readable once
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the PatientService or
     *         the EHRService answers with a SOAP fault
     * @throws org.springframework.ws.client.WebServiceIOException when a downstream call fails with
     *         an I/O error
     * @throws org.springframework.ws.client.WebServiceTransportException when a downstream service
     *         answers with an HTTP error that carries no SOAP fault
     * @throws java.time.format.DateTimeParseException when DW-39 cannot read a present
     *         {@code startDate} or {@code endDate} as an ISO date
     * @throws IllegalStateException when DW-40 or DW-41 text cannot be parsed
     */
    public Source admitPatientService(Element admitSubject) {
        Element upsertResponse = upsertPatient(admitSubject);
        Element episodeResponse = createEpisode(upsertResponse);
        return new StreamSource(new StringReader(mapper.toAdmitSubjectResponse(episodeResponse)));
    }

    /**
     * Implements the sub-flow {@code upsertPatient}
     * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:50-64]: applies DW-40
     * (:53-61) through {@link AdmissionMapper#toUpsertPatient(Element)}, which copies the
     * {@code Subject} of {@code admitSubject} into {@code ns0:upsertPatient}, parses that text and
     * passes the element to {@link #invokePatientService(Element)} (flow-ref :63).
     *
     * @param admitSubject the {@code admitSubject} child of the request SOAP Body, or {@code null}
     * @return the PatientService response Body child, for example {@code upsertPatientResponse}, or
     *         {@code null} for an empty response Body
     */
    Element upsertPatient(Element admitSubject) {
        return invokePatientService(parse(mapper.toUpsertPatient(admitSubject)));
    }

    /**
     * Implements the sub-flow {@code invokePatientService}
     * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:66-70]: posts {@code body}
     * as the SOAP Body child of a request to {@code /PatientService} through
     * {@link PatientServiceClient#invoke(Element)} ({@code cxf:proxy-client payload="body"} :67,
     * {@code http:request} :68) and returns the client's result unchanged.
     *
     * @param body the request Body child, for example {@code ns0:upsertPatient}
     * @return the PatientService response Body child, or {@code null} for an empty response Body
     */
    Element invokePatientService(Element body) {
        return patientServiceClient.invoke(body);
    }

    /**
     * Implements the sub-flow {@code createEpisode}
     * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:71-83]: applies DW-41
     * (:73-80) through {@link AdmissionMapper#toCreateEpisode(Element)}, which copies the
     * {@code ns1:PatientId} of {@code upsertPatientResponse} into {@code ns0:createEpisode}, parses
     * that text and passes the element to {@link #invokeEhrService(Element)} (flow-ref
     * {@code invokeEHRService} :82).
     *
     * @param upsertPatientResponse the PatientService response Body child, or {@code null}
     * @return the EHRService response Body child, for example {@code createEpisodeResponse}, or
     *         {@code null} for an empty response Body
     */
    Element createEpisode(Element upsertPatientResponse) {
        return invokeEhrService(parse(mapper.toCreateEpisode(upsertPatientResponse)));
    }

    /**
     * Implements the sub-flow {@code invokeEHRService}
     * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:85-88]: posts {@code body}
     * as the SOAP Body child of a request to {@code /EHRService} through
     * {@link EhrServiceClient#invoke(Element)} ({@code cxf:proxy-client payload="body"} :86,
     * {@code http:request} :87) and returns the client's result unchanged.
     *
     * @param body the request Body child, for example {@code ns0:createEpisode}
     * @return the EHRService response Body child, or {@code null} for an empty response Body
     */
    Element invokeEhrService(Element body) {
        return ehrServiceClient.invoke(body);
    }

    /**
     * Parses mapper text into a namespace-aware DOM element. Each call creates its own
     * {@link DocumentBuilderFactory} and builder with secure processing enabled; whitespace text
     * nodes are kept (D-417).
     *
     * @param xml the XML text written by {@link AdmissionMapper}
     * @return the document element of the parsed text
     * @throws IllegalStateException when the parser cannot be configured or cannot read the text; its
     *         message is the parser's message and its cause the parser exception
     */
    private static Element parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)))
                    .getDocumentElement();
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
