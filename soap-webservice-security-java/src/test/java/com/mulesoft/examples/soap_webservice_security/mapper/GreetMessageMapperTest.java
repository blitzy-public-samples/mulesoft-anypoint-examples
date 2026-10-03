package com.mulesoft.examples.soap_webservice_security.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mulesoft.mule.example.security.Greet;
import com.mulesoft.mule.example.security.GreetResponse;
import com.mulesoft.mule.example.security.ObjectFactory;
import jakarta.xml.bind.JAXBElement;
import javax.xml.namespace.QName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link GreetMessageMapper} over the JAXB types generated from {@code Greeter.wsdl} (D-028);
 * counted by the mapper coverage rule (D-049).
 *
 * <p>The mapper is instantiated directly, with no Spring context and no mocks. Input payloads are built through
 * the generated {@link ObjectFactory}.
 */
public class GreetMessageMapperTest {

    private static final String NS = "http://security.example.mule.mulesoft.com/";

    private static final QName GREET = new QName(NS, "greet");

    private static final QName GREET_RESPONSE = new QName(NS, "greetResponse");

    private final GreetMessageMapper mapper = new GreetMessageMapper();

    private final ObjectFactory objectFactory = new ObjectFactory();

    @Test
    public void toGreetSetsQNameAndName() {
        JAXBElement<Greet> element = mapper.toGreet("Mule");

        assertEquals(GREET, element.getName());
        assertNotNull(element.getValue());
        assertEquals("Mule", element.getValue().getName());
    }

    @Test
    public void toGreetWithNullNameLeavesNameNull() {
        JAXBElement<Greet> element = mapper.toGreet(null);

        assertEquals(GREET, element.getName());
        assertNull(element.getValue().getName());
    }

    @Test
    public void toGreetResponseSetsQNameAndName() {
        JAXBElement<GreetResponse> element = mapper.toGreetResponse("Mule");

        assertEquals(GREET_RESPONSE, element.getName());
        assertEquals("Mule", element.getValue().getName());
    }

    @Test
    public void toGreetResponseWithNullNameLeavesNameNull() {
        JAXBElement<GreetResponse> element = mapper.toGreetResponse(null);

        assertEquals(GREET_RESPONSE, element.getName());
        assertNull(element.getValue().getName());
    }

    @Test
    public void nameOfGreetElementReturnsName() {
        assertEquals("Mule", mapper.nameOf(greetElement("Mule")));
    }

    @Test
    public void nameOfGreetElementReturnsNullName() {
        assertNull(mapper.nameOf(greetElement(null)));
    }

    @Test
    public void nameOfGreetResponseReturnsName() {
        assertEquals("Hello Mule", mapper.nameOf(greetResponse("Hello Mule")));
    }

    @Test
    public void nameOfGreetResponseReturnsNullName() {
        assertNull(mapper.nameOf(greetResponse(null)));
    }

    @Test
    public void nameOfObjectAcceptsGreetResponse() {
        assertEquals("Hello Mule", mapper.nameOf((Object) greetResponse("Hello Mule")));
    }

    @Test
    public void nameOfObjectAcceptsGreetResponseElement() {
        assertEquals("Hello Mule", mapper.nameOf((Object) greetResponseElement("Hello Mule")));
    }

    @Test
    public void nameOfObjectRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> mapper.nameOf((Object) null));
    }

    @Test
    public void nameOfObjectRejectsString() {
        assertThrows(IllegalArgumentException.class, () -> mapper.nameOf((Object) "Hello Mule"));
    }

    @Test
    public void nameOfObjectRejectsGreetElement() {
        assertThrows(IllegalArgumentException.class, () -> mapper.nameOf((Object) greetElement("Mule")));
    }

    private JAXBElement<Greet> greetElement(String name) {
        Greet greet = objectFactory.createGreet();
        greet.setName(name);
        return objectFactory.createGreet(greet);
    }

    private GreetResponse greetResponse(String name) {
        GreetResponse response = objectFactory.createGreetResponse();
        response.setName(name);
        return response;
    }

    private JAXBElement<GreetResponse> greetResponseElement(String name) {
        return objectFactory.createGreetResponse(greetResponse(name));
    }
}
