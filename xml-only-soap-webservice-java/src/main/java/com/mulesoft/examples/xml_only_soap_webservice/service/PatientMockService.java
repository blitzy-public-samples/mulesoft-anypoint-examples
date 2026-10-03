package com.mulesoft.examples.xml_only_soap_webservice.service;

import com.mulesoft.examples.xml_only_soap_webservice.mapper.PatientMockMapper;
import java.io.StringReader;
import java.time.Clock;
import java.time.OffsetDateTime;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

/**
 * Implements the Mule mock flow {@code PatientService} of
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml} (lines 4-44): answers {@code upsertPatient} with an
 * {@code upsertPatientResponse} and every other operation with a {@code getPatientResponse}. The response XML
 * is written by {@link PatientMockMapper} (D-034).
 *
 * <p>{@code endpoint/PatientServiceMockEndpoint.invoke} passes the SOAP Body child element posted to
 * {@code /PatientService} to {@link #patientService(Element)} as a DOM {@link Element} (D-029). The flow has no
 * exception strategy and no logger [mocks.xml:4-44]: this class catches nothing and logs nothing, and an
 * exception it raises reaches the caller unchanged.
 *
 * <p>The class holds the injected {@link Clock} and one stateless {@link PatientMockMapper}, both final.
 * Concurrent calls do not affect each other.
 *
 * <pre>{@code
 * PatientMockService service = new PatientMockService(Clock.systemDefaultZone());
 * Source upsert = service.patientService(upsertPatientElement); // ns0:upsertPatientResponse document
 * Source other = service.patientService(getPatientElement);     // ns0:getPatientResponse document
 * Source empty = service.patientService(null);                  // ns0:getPatientResponse document
 * }</pre>
 */
@Service
public class PatientMockService {

    /** The source of the script value {@code now}, read once per call (D-400). */
    private final Clock clock;

    /** Writes the DW-42 and DW-43 documents. */
    private final PatientMockMapper mapper;

    /**
     * Creates the service over the given clock and a new {@link PatientMockMapper}.
     *
     * @param clock the clock {@link #patientService(Element)} reads {@code now} from: the {@code clock} bean,
     *     {@code Clock.systemDefaultZone()}, at runtime, and a {@code Clock.fixed(...)} in tests
     */
    public PatientMockService(Clock clock) {
        this.clock = clock;
        this.mapper = new PatientMockMapper();
    }

    /**
     * Implements flow {@code PatientService} [mocks.xml:4-44] for one request.
     *
     * <ol>
     *   <li>The {@code operation} variable [mocks.xml:7], MEL {@code xpath('fn:local-name(/*)')}, is the local
     *       name of {@code request}: {@link Element#getLocalName()}, or for an element built without namespace
     *       awareness the part of {@link Element#getNodeName()} after its last {@code ':'}; it is the empty
     *       string when {@code request} is {@code null}. The namespace of {@code request} is not read
     *       (D-430).</li>
     *   <li>{@code now} is read once per call from the injected {@link Clock} as
     *       {@code OffsetDateTime.now(clock)}, before the branch (D-400).</li>
     *   <li>The {@code when} [mocks.xml:10] compares the operation with {@code upsertPatient} exactly and
     *       case-sensitively; on a match the response is DW-42 [mocks.xml:12-19],
     *       {@link PatientMockMapper#upsertPatientResponse(OffsetDateTime)} of {@code now}.</li>
     *   <li>The {@code otherwise} [mocks.xml:22] covers every other operation, {@code getPatient} of
     *       {@code PatientService.wsdl}, the empty operation and any other local name included; the response
     *       is DW-43 [mocks.xml:24-40], {@link PatientMockMapper#getPatientResponse(OffsetDateTime)} of
     *       {@code now}.</li>
     * </ol>
     *
     * @param request the SOAP Body child element of the request, {@code upsertPatient} or {@code getPatient} in
     *     namespace {@code http://www.mule-health.com/SOA/message/1.0}, or {@code null} for an empty Body
     * @return a {@link StreamSource} whose {@link StreamSource#getReader() reader} yields the mapper's XML text
     *     for the chosen branch
     */
    public Source patientService(Element request) {
        String operation = operationOf(request);
        OffsetDateTime now = OffsetDateTime.now(clock);
        String text;
        if ("upsertPatient".equals(operation)) {
            text = mapper.upsertPatientResponse(now);
        } else {
            text = mapper.getPatientResponse(now);
        }
        return new StreamSource(new StringReader(text));
    }

    /**
     * Returns the XPath {@code fn:local-name} of {@code request}: {@code ""} for {@code null}, otherwise
     * {@link Element#getLocalName()} when it is not {@code null}, otherwise the part of
     * {@link Element#getNodeName()} after the last {@code ':'}, or the whole node name when it holds none.
     * The {@code ""} of a {@code null} request selects the {@code otherwise} branch (D-430).
     */
    private static String operationOf(Element request) {
        if (request == null) {
            return "";
        }
        String localName = request.getLocalName();
        String nodeName = request.getNodeName();
        return localName != null ? localName : nodeName.substring(nodeName.lastIndexOf(':') + 1);
    }
}
