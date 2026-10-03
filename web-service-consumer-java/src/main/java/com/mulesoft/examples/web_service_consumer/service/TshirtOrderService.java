package com.mulesoft.examples.web_service_consumer.service;

import java.io.IOException;
import java.util.Objects;

import javax.xml.transform.Source;

import com.mulesoft.examples.web_service_consumer.client.TshirtServiceClient;
import com.mulesoft.examples.web_service_consumer.config.TshirtWsConfig;
import com.mulesoft.examples.web_service_consumer.mapper.TshirtMapper;
import org.mulesoft.tshirt_service.AuthenticationHeader;
import org.springframework.stereotype.Service;
import org.w3c.dom.Element;

/**
 * Runs the two flows of {@code web-service-consumer/src/main/app/tshirt-service-consumer.xml} against the t-shirt
 * SOAP service:
 *
 * <ul>
 *   <li>{@link #orderTshirt(String)}: flow {@code orderTshirt} [:6-33], called by {@code controller/OrdersController}
 *       for the path {@code /orders};</li>
 *   <li>{@link #listInventory()}: flow {@code listInventory} [:34-47], called by
 *       {@code controller/InventoryController} for the path {@code /inventory}.</li>
 * </ul>
 *
 * <p>Each method runs the steps of its flow in flow order: the request shaping of {@link TshirtMapper} where the flow
 * has one, exactly one call of {@link TshirtServiceClient}, and the JSON rendering of {@link TshirtMapper}. The class
 * holds no transformation, SOAP or HTTP code of its own.
 *
 * <p>Neither flow defines an exception strategy. No method catches, retries, wraps or replaces an exception: every
 * exception of the mapper or the client reaches the caller unchanged, and an HTTP caller receives the 500 answer of
 * {@code exception/GlobalExceptionHandler.unexpected}.
 *
 * <p>The three collaborators are final and set once by the constructor; the class holds no other state, logs
 * nothing and is safe for concurrent use.
 *
 * <p>Usage:
 * <pre>{@code
 * TshirtOrderService service = new TshirtOrderService(client, new TshirtMapper(), properties);
 * String order = service.orderTshirt("{\"email\":\"a@b.c\",\"size\":\"L\"}");
 * // order: the DW-37 JSON of OrderTshirtResponse, holding orderId
 * String inventory = service.listInventory();
 * // inventory: the DW-38 JSON of the ListInventoryResponse content, one "inventory" member per item
 * }</pre>
 */
@Service
public class TshirtOrderService {

    /** Sends the {@code OrderTshirt} and {@code ListInventory} SOAP requests. */
    private final TshirtServiceClient client;

    /** Implements DW-35, DW-36, DW-37 and DW-38. */
    private final TshirtMapper mapper;

    /** The bound {@code tshirt.*} keys; {@code tshirt.api-key} is the API key of the SOAP header (D-012). */
    private final TshirtWsConfig.Properties properties;

    /**
     * Creates the service over its three collaborators.
     *
     * @param client     the t-shirt SOAP service client
     * @param mapper     the mapper of DW-35 … DW-38
     * @param properties the bound {@code tshirt.*} keys, read for {@code tshirt.api-key}
     * @throws NullPointerException when {@code client}, {@code mapper} or {@code properties} is {@code null}
     */
    public TshirtOrderService(TshirtServiceClient client, TshirtMapper mapper, TshirtWsConfig.Properties properties) {
        this.client = Objects.requireNonNull(client, "client");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /**
     * Flow {@code orderTshirt} [tshirt-service-consumer.xml:6-33]: orders a t-shirt and returns the order response
     * as JSON. The steps, in order:
     *
     * <ol>
     *   <li>DW-35 [:12-16]: {@link TshirtMapper#toOrderTshirt(String)} writes {@code json} as the content of the
     *       {@code ns0:OrderTshirt} element of namespace {@code http://mulesoft.org/tshirt-service};</li>
     *   <li>DW-36 [:17-23]: {@link TshirtMapper#authenticationHeader(String)} builds the {@code AuthenticationHeader}
     *       SOAP header from the value of {@code tshirt.api-key} (D-012);</li>
     *   <li>{@code ws:consumer operation="OrderTshirt"} [:25]:
     *       {@link TshirtServiceClient#orderTshirt(Source, AuthenticationHeader)} sends one request with that payload
     *       and header;</li>
     *   <li>DW-37 [:28-31]: {@link TshirtMapper#toOrderJson(org.w3c.dom.Node)} renders the response element as JSON,
     *       which is returned unchanged.</li>
     * </ol>
     *
     * <p>A failure in DW-35 or DW-36 ends the call before any request is sent.
     *
     * @param json the HTTP request body, passed to DW-35 unchanged, {@code null} included
     * @return the DW-37 JSON of the {@code OrderTshirtResponse} element
     * @throws IOException a Jackson parse exception of DW-35 for malformed JSON, unwrapped
     * @throws IllegalArgumentException from DW-35 for a {@code null}, empty or whitespace-only body or content after
     *     the JSON root value; from DW-37 when the response SOAP Body holds no element
     * @throws org.w3c.dom.DOMException from DW-35 when a JSON key is not a valid XML element name
     * @throws IllegalStateException from the client when the response carries no SOAP message
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the service answers with a SOAP fault
     * @throws org.springframework.ws.client.WebServiceTransportException when the service answers with an HTTP error
     *     status and no SOAP fault
     * @throws org.springframework.ws.client.WebServiceIOException when the connection, the exchange or a timeout fails
     */
    public String orderTshirt(String json) throws IOException {
        Source source = mapper.toOrderTshirt(json);
        AuthenticationHeader header = mapper.authenticationHeader(properties.apiKey());
        Element response = client.orderTshirt(source, header);
        return mapper.toOrderJson(response);
    }

    /**
     * Flow {@code listInventory} [tshirt-service-consumer.xml:34-47]: lists the t-shirt inventory as JSON. The steps,
     * in order:
     *
     * <ol>
     *   <li>{@code ws:consumer operation="ListInventory"} [:38]: {@link TshirtServiceClient#listInventory()} sends one
     *       request;</li>
     *   <li>DW-38 [:41-45]: {@link TshirtMapper#toInventoryJson(org.w3c.dom.Node)} renders
     *       {@code payload.ns0#ListInventoryResponse} as JSON, which is returned unchanged.</li>
     * </ol>
     *
     * @return the DW-38 JSON of the content of the {@code ListInventoryResponse} element
     * @throws IllegalArgumentException from DW-38 when the response SOAP Body holds no element
     * @throws IllegalStateException from the client when the response carries no SOAP message
     * @throws org.springframework.ws.soap.client.SoapFaultClientException when the service answers with a SOAP fault
     * @throws org.springframework.ws.client.WebServiceTransportException when the service answers with an HTTP error
     *     status and no SOAP fault
     * @throws org.springframework.ws.client.WebServiceIOException when the connection, the exchange or a timeout fails
     */
    public String listInventory() {
        Element response = client.listInventory();
        return mapper.toInventoryJson(response);
    }
}
