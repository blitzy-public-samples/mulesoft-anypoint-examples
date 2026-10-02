package com.mulesoft.examples.xml_only_soap_webservice.client;

import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Sends a SOAP Body payload to the EHRService endpoint on the configured request host and port and returns the
 * response Body child.
 *
 * <p>Implements the Mule sub-flow {@code invokeEHRService}
 * ({@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml}, lines 85-88): the
 * {@code cxf:proxy-client payload="body"} envelope wrapping and the {@code http:request} POST to
 * {@code EHRService} on {@code HTTP_Request_Configuration}. The injected {@code ehrServiceTemplate} supplies the
 * SOAP 1.1 message factory and resolves the target URI {@code http://<request host>:<request port>/EHRService} on
 * every call. No SOAPAction value is set on the request; the SAAJ default empty value is sent and the endpoint is
 * selected by URI (D-029). Spring WS client stack: see D-028.
 *
 * <p>Usage:
 * <pre>{@code
 * Element createEpisodeResponse = ehrServiceClient.invoke(createEpisode);
 * }</pre>
 */
@Component
public class EhrServiceClient {

    private final WebServiceTemplate template;

    /**
     * Creates the client over the EHRService template.
     *
     * @param template the {@code ehrServiceTemplate} bean whose destination provider resolves the EHRService URI
     */
    public EhrServiceClient(@Qualifier("ehrServiceTemplate") WebServiceTemplate template) {
        this.template = template;
    }

    /**
     * Posts {@code body} as the SOAP Body child of a request envelope to the EHRService endpoint and returns the
     * child element of the response Body.
     *
     * <p>The target URI is read from the template's destination provider at call time. {@code body} is read as
     * given and is not modified. The returned element belongs to a new document.
     *
     * @param body the request Body child element, for example {@code ns0:createEpisode}
     * @return the response Body child element, for example {@code ns0:createEpisodeResponse}, or {@code null} when
     *     the response Body is empty
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the endpoint answers with a SOAP fault
     * @throws org.springframework.ws.client.WebServiceIOException when the connection to the endpoint or its I/O fails
     * @throws org.springframework.ws.client.WebServiceTransportException when the endpoint answers with an HTTP
     *     error status and no SOAP fault
     */
    public Element invoke(Element body) {
        DOMResult result = new DOMResult();
        template.sendSourceAndReceiveToResult(template.getDefaultUri(), new DOMSource(body), result);
        Node node = result.getNode();
        if (node instanceof Document) {
            return ((Document) node).getDocumentElement();
        }
        if (node instanceof Element) {
            return (Element) node;
        }
        return null;
    }
}
