package com.mulesoft.examples.soap_webservice_security.mapper;

import com.mulesoft.mule.example.security.Greet;
import com.mulesoft.mule.example.security.GreetResponse;
import com.mulesoft.mule.example.security.ObjectFactory;
import jakarta.xml.bind.JAXBElement;
import org.springframework.stereotype.Component;

/**
 * Converts names to and from the {@code greet} and {@code greetResponse} payloads of the Greeter contract
 * [src/main/resources/wsdl/Greeter.wsdl] (D-028).
 *
 * <p>The payload types {@link Greet} and {@link GreetResponse} are the JAXB classes generated from
 * {@code Greeter.wsdl} into {@code target/generated-sources/jaxb} (D-028). Each type is the named complex type of
 * its global element and carries no {@code @XmlRootElement}. Every payload this class builds is a
 * {@link JAXBElement} with the element's qualified name:
 *
 * <ul>
 *   <li>request element {@code {http://security.example.mule.mulesoft.com/}greet};</li>
 *   <li>response element {@code {http://security.example.mule.mulesoft.com/}greetResponse}.</li>
 * </ul>
 *
 * <p>Each element holds one optional, unqualified {@code name} child of type {@code xs:string}. A name passes
 * through unchanged in both directions: it is neither validated, trimmed nor concatenated, and a {@code null} name
 * leaves the {@code name} child absent. The greeting text itself is built by
 * {@code service.GreeterService}.
 *
 * <p>Instances hold no mutable state, perform no I/O and are safe for concurrent use;
 * {@code new GreetMessageMapper()} needs no Spring context.
 *
 * <pre>{@code
 * GreetMessageMapper mapper = new GreetMessageMapper();
 * JAXBElement<Greet> request = mapper.toGreet("Mule");          // greet element with <name>Mule</name>
 * mapper.nameOf(request);                                        // "Mule"
 * mapper.nameOf(mapper.toGreet(null));                           // null; the greet element has no name child
 * JAXBElement<GreetResponse> reply = mapper.toGreetResponse(greeting);
 * mapper.nameOf(reply.getValue());                               // greeting, unchanged
 * Object unmarshalled = reply;                                   // result of marshalSendAndReceive
 * mapper.nameOf(unmarshalled);                                   // greeting, unchanged
 * mapper.nameOf("text");                                         // IllegalArgumentException
 * }</pre>
 */
@Component
public class GreetMessageMapper {

    /** Factory of the generated Greeter payload types and their qualified elements. */
    private final ObjectFactory objectFactory = new ObjectFactory();

    /**
     * Wraps a name in the {@code greet} request element; a null name leaves the {@code name} element absent.
     *
     * @param name the name to send, passed through unchanged; may be {@code null}
     * @return the {@code {http://security.example.mule.mulesoft.com/}greet} element holding the name
     */
    public JAXBElement<Greet> toGreet(String name) {
        Greet greet = objectFactory.createGreet();
        greet.setName(name);
        return objectFactory.createGreet(greet);
    }

    /**
     * Returns the name carried by a {@code greet} request element.
     *
     * @param request the bound {@code greet} request payload
     * @return the {@code name} value, or {@code null} when the element has no {@code name} child
     */
    public String nameOf(JAXBElement<Greet> request) {
        return request.getValue().getName();
    }

    /**
     * Wraps a name in the {@code greetResponse} element; a null name leaves the {@code name} element absent.
     *
     * @param name the name to return, passed through unchanged; may be {@code null}
     * @return the {@code {http://security.example.mule.mulesoft.com/}greetResponse} element holding the name
     */
    public JAXBElement<GreetResponse> toGreetResponse(String name) {
        GreetResponse response = objectFactory.createGreetResponse();
        response.setName(name);
        return objectFactory.createGreetResponse(response);
    }

    /**
     * Returns the name carried by a {@code greetResponse} payload.
     *
     * @param response the {@code greetResponse} payload
     * @return the {@code name} value, or {@code null} when the payload has no {@code name} child
     */
    public String nameOf(GreetResponse response) {
        return response.getName();
    }

    /**
     * Returns the name carried by an untyped {@code greetResponse} payload, such as the result of
     * {@code WebServiceTemplate.marshalSendAndReceive}.
     *
     * <p>Accepted payloads are a bare {@link GreetResponse} and a {@link JAXBElement} whose value is a
     * {@link GreetResponse}; both resolve through {@link #nameOf(GreetResponse)}.
     *
     * @param response the unmarshalled response payload
     * @return the {@code name} value, or {@code null} when the payload has no {@code name} child
     * @throws IllegalArgumentException when the payload is {@code null} or of any other type; the message names
     *     the received type
     */
    public String nameOf(Object response) {
        if (response instanceof GreetResponse greetResponse) {
            return nameOf(greetResponse);
        }
        if (response instanceof JAXBElement<?> element && element.getValue() instanceof GreetResponse value) {
            return nameOf(value);
        }
        throw new IllegalArgumentException("Unexpected greet response payload: "
                + (response == null ? "null" : response.getClass().getName()));
    }
}
