package com.mulesoft.examples.xml_only_soap_webservice.endpoint;

import javax.xml.transform.Source;

import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.w3c.dom.Element;

import com.mulesoft.examples.xml_only_soap_webservice.service.EhrMockService;

/**
 * Spring WS DOM payload endpoint of the mock SOAP service {@code EHRService}, served at {@code /EHRService}
 * (D-029). It replaces the message source of the Mule flow {@code EHRService}
 * [xml-only-soap-webservice/src/main/app/mocks.xml:45-84]:
 * <ul>
 *   <li>{@code http:listener path="EHRService"} on {@code HTTP_Listener_Configuration} (:46);</li>
 *   <li>{@code cxf:proxy-service payload="body"} with service {@code EHRService}, port {@code EHRPort}, namespace
 *       {@code http://www.mule-health.com/SOA/service/ehr/1.0} and {@code wsdlLocation="service/EHRService.wsdl"}
 *       (:47).</li>
 * </ul>
 *
 * <p>Both operations of {@code service/EHRService.wsdl}, {@code createEpisode} and {@code findEpisodes}, reach
 * {@link #invoke(Element)}. The SOAP Body child element is bound as a DOM {@link Element} and passed unchanged to
 * {@link EhrMockService#ehrService(Element)}, which selects the operation branch; the {@link Source} it returns is
 * written unchanged as the response payload. The endpoint does not transform, route, log or catch: an exception
 * raised by the service reaches {@code exception.SoapFaultMappingExceptionResolver}, which answers a
 * {@code soap:Server} fault (D-167).
 *
 * <p>The class carries Spring WS {@link Endpoint} and no {@code @PayloadRoot}, {@code @SoapAction} or
 * {@code @Action}. Its one route is the {@code /EHRService} entry of the {@code UriEndpointMapping} built by
 * {@code config.WsConfig} as {@code new MethodEndpoint(endpoint, "invoke", Element.class)} (D-565). The WSDL and the
 * schemas it imports are served from {@code classpath:service/} by {@code config.WsdlQueryFilter} (D-069).
 *
 * <p>Example request body, posted as SOAP 1.1 to {@code /EHRService}; {@code request} is the
 * {@code ns0:createEpisode} element:
 * <pre>{@code
 * <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
 *   <soap:Body>
 *     <ns0:createEpisode xmlns:ns0="http://www.mule-health.com/SOA/message/1.0"
 *                        xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
 *       <ns1:PatientId>P1001</ns1:PatientId>
 *     </ns0:createEpisode>
 *   </soap:Body>
 * </soap:Envelope>
 * }</pre>
 *
 * <p>The endpoint holds no mutable state and is safe for concurrent use.
 */
// Spring WS @Endpoint with no mapping annotation: the /EHRService UriEndpointMapping entry is its only route (D-565)
@Endpoint
public class EhrServiceMockEndpoint {

    /** The service implementing the {@code EHRService} mock flow; receives every bound request. */
    private final EhrMockService ehrMockService;

    /**
     * Creates the endpoint over the service that implements the {@code EHRService} mock flow.
     *
     * @param ehrMockService the service whose {@link EhrMockService#ehrService(Element)} answers each request
     */
    public EhrServiceMockEndpoint(EhrMockService ehrMockService) {
        this.ehrMockService = ehrMockService;
    }

    /**
     * Answers one SOAP request posted to {@code /EHRService} by delegating to
     * {@link EhrMockService#ehrService(Element)}.
     *
     * @param request the SOAP Body child element as a DOM element, for example {@code ns0:createEpisode} or
     *     {@code ns0:findEpisodes} in namespace {@code http://www.mule-health.com/SOA/message/1.0}, or {@code null}
     *     for an empty Body; passed to the service unchanged
     * @return the service's response payload, unchanged: {@code ns0:createEpisodeResponse} (DW-44) or
     *     {@code ns0:findEpisodesResponse} (DW-45)
     */
    @ResponsePayload
    public Source invoke(@RequestPayload Element request) {
        return ehrMockService.ehrService(request);
    }
}
