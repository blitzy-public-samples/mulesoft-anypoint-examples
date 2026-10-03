package com.mulesoft.examples.xml_only_soap_webservice.endpoint;

import com.mulesoft.examples.xml_only_soap_webservice.service.PatientMockService;
import javax.xml.transform.Source;
import org.springframework.stereotype.Component;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.w3c.dom.Element;

/**
 * Spring WS DOM payload endpoint of the Mule mock flow {@code PatientService} of
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml} (lines 4-44), whose message source is the
 * {@code http:listener} on path {@code PatientService} (:5) and the
 * {@code cxf:proxy-service payload="body"} of {@code service/PatientService.wsdl} (:6).
 *
 * <p>Binds the SOAP Body child element of a request posted to {@code /PatientService} as a DOM {@link Element}
 * and delegates it to {@link PatientMockService#patientService(Element)}; the element is {@code upsertPatient}
 * or {@code getPatient} in namespace {@code http://www.mule-health.com/SOA/message/1.0}. Both operations of
 * {@code PatientService.wsdl} reach {@link #invoke(Element)}, and the service selects the branch (D-430).
 *
 * <p>The class is a plain Spring {@code @Component}, not a Spring WS {@code @Endpoint}, and its method carries
 * no {@code @PayloadRoot}, {@code @SoapAction} or {@code @Action} mapping. {@code config/WsConfig} registers
 * {@code new MethodEndpoint(bean, "invoke", Element.class)} for {@code /PatientService} on its
 * {@code UriEndpointMapping} (D-029, D-599).
 *
 * <p>The endpoint catches nothing: an exception raised by the service reaches
 * {@code exception/SoapFaultMappingExceptionResolver}, which answers with a SOAP 1.1 Server fault (D-167).
 * It holds only the final service reference and is safe for concurrent requests.
 *
 * <pre>{@code
 * PatientServiceMockEndpoint endpoint = new PatientServiceMockEndpoint(patientMockService);
 * Source upsert = endpoint.invoke(upsertPatientElement); // ns0:upsertPatientResponse document
 * Source other = endpoint.invoke(getPatientElement);     // ns0:getPatientResponse document
 * }</pre>
 */
@Component
public class PatientServiceMockEndpoint {

    /** The service that implements flow {@code PatientService} and receives every bound request. */
    private final PatientMockService patientMockService;

    /**
     * Creates the endpoint over the given service.
     *
     * @param patientMockService the {@code PatientService} flow service that every request is delegated to
     */
    public PatientServiceMockEndpoint(PatientMockService patientMockService) {
        this.patientMockService = patientMockService;
    }

    /**
     * Passes the SOAP Body child element of one request to {@code /PatientService} to
     * {@link PatientMockService#patientService(Element)} and returns its result as the SOAP Body content of
     * the response.
     *
     * @param request the SOAP Body child element, {@code upsertPatient} or {@code getPatient} in namespace
     *     {@code http://www.mule-health.com/SOA/message/1.0}, or {@code null} for an empty Body, passed on
     *     unchanged
     * @return the service's response document, unchanged: {@code upsertPatientResponse} for
     *     {@code upsertPatient} (DW-42) and {@code getPatientResponse} for every other operation (DW-43)
     */
    @ResponsePayload
    public Source invoke(@RequestPayload Element request) {
        return patientMockService.patientService(request);
    }
}
