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
 * Sends a SOAP Body payload to the PatientService endpoint on the configured request host and port
 * and returns the response Body child.
 *
 * <p>Replaces the {@code invokePatientService} sub-flow: its {@code cxf:proxy-client payload="body"}
 * wraps the given Body child in a SOAP 1.1 envelope, and its {@code http:request} on
 * {@code HTTP_Request_Configuration} posts that envelope to {@code /PatientService}. The
 * {@code patientServiceTemplate} bean supplies the message factory, the message sender and the
 * destination {@code http://<request host>:<request port>/PatientService}, which it resolves on every
 * call. The request carries no SOAPAction beyond the empty default value. The Spring WS client stack
 * is recorded in D-028.
 *
 * <p>Usage:
 * <pre>{@code
 * Element upsertPatientResponse = patientServiceClient.invoke(upsertPatientRequest);
 * }</pre>
 */
@Component
public class PatientServiceClient {

    private final WebServiceTemplate template;

    /**
     * Creates the client over the PatientService template.
     *
     * @param template the {@code patientServiceTemplate} bean, which holds the PatientService destination
     */
    public PatientServiceClient(@Qualifier("patientServiceTemplate") WebServiceTemplate template) {
        this.template = template;
    }

    /**
     * Posts {@code body} as the SOAP Body of a request to the PatientService endpoint and returns the
     * first element of the response Body.
     *
     * <p>The destination is read from the template on each call. {@code body} is read as given and is
     * neither modified nor copied into another document. Failures propagate unchanged: no retry,
     * wrapping or classification is applied, and the connector failure modes of D-020 are not used.
     *
     * @param body the request Body child element, for example {@code upsertPatient}
     * @return the response Body child element, for example {@code upsertPatientResponse}, or
     *         {@code null} when the response Body is empty
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the PatientService
     *         endpoint answers with a SOAP fault
     * @throws org.springframework.ws.client.WebServiceIOException when the connection or the message
     *         exchange fails with an I/O error
     * @throws org.springframework.ws.client.WebServiceTransportException when the endpoint answers
     *         with an HTTP error that carries no SOAP fault
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
