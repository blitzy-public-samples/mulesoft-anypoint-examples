package com.mulesoft.examples.xml_only_soap_webservice.endpoint;

import javax.xml.transform.Source;

import org.springframework.stereotype.Component;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.w3c.dom.Element;

import com.mulesoft.examples.xml_only_soap_webservice.service.AdmissionService;

/**
 * Spring WS DOM payload endpoint of the flow {@code admitPatientService}
 * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:19-48]: binds the SOAP Body child posted to
 * {@code /AdmissionService}, the {@code ns:admitSubject} element of the WSDL operation {@code admitSubject}, as a
 * DOM {@link Element} and delegates it unchanged to {@link AdmissionService#admitPatientService(Element)} (D-029).
 * It is a plain component without {@code @Endpoint} or any mapping annotation, reached only through the
 * {@code MethodEndpoint} that {@code config.WsConfig}'s {@code UriEndpointMapping} holds for
 * {@code /AdmissionService}, and it neither validates, logs nor catches (D-588).
 */
@Component
public class AdmissionServiceEndpoint {

    /** Implements the body of the flow {@code admitPatientService} and its four sub-flows. */
    private final AdmissionService admissionService;

    /**
     * Creates the endpoint over the admission service.
     *
     * @param admissionService the service implementing the flow {@code admitPatientService}
     */
    public AdmissionServiceEndpoint(AdmissionService admissionService) {
        this.admissionService = admissionService;
    }

    /**
     * Answers the WSDL operation {@code admitSubject}: passes the request payload unchanged to
     * {@link AdmissionService#admitPatientService(Element)} and returns that method's result unchanged as the
     * response payload. An exception of the service propagates unchanged to
     * {@code exception.SoapFaultMappingExceptionResolver}, which answers a {@code soap:Server} fault (D-167).
     *
     * @param admitSubject the {@code ns:admitSubject} child of the request SOAP Body, or {@code null} for an
     *                     empty Body
     * @return the {@code ns0:admitSubjectResponse} document that Spring WS writes as the response SOAP Body
     *         child
     */
    @ResponsePayload
    public Source admitSubject(@RequestPayload Element admitSubject) {
        return admissionService.admitPatientService(admitSubject);
    }
}
