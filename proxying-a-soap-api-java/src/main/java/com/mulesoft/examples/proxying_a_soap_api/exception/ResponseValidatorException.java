package com.mulesoft.examples.proxying_a_soap_api.exception;

/**
 * Upstream HTTP status outside 0–399 returned to the {@code http:request} that posts the SOAP
 * envelope to {@code shop/ShopService} on {@code www.predic8.com:8080}
 * [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:10; soap-api-proxy.xml:4]; the exception
 * carries that status (D-259).
 *
 * <p>The message is the response-validator text
 * {@code Response code <status> mapped as failure. Message payload is of type: BufferInputStream}.
 * Its wording is pinned once the Tier 2A fixture {@code proxying-a-soap-api_proxy-soap-fault}
 * exists (D-023).
 *
 * <p>The {@code ?wsdl} fetch of {@code http://www.predic8.com:8080/shop/ShopService?wsdl}
 * [soap-api-proxy.xml:7] and the envelope forward [soap-api-proxy.xml:10] raise it for an upstream
 * status outside 0–399. The failure is answered with HTTP 500, as flow {@code main} answers it
 * [soap-api-proxy.xml:5-12] (D-259).
 *
 * <pre>{@code
 * ResponseValidatorException failure = new ResponseValidatorException(500);
 * failure.getMessage(); // "Response code 500 mapped as failure. Message payload is of type: BufferInputStream"
 * failure.getStatus();  // 500
 * }</pre>
 */
public class ResponseValidatorException extends RuntimeException {

    /** Serialization version of this exception type. */
    private static final long serialVersionUID = 1L;

    /** Upstream HTTP status mapped as failure [soap-api-proxy.xml:10]. */
    private final int status;

    /**
     * Creates the failure for the upstream HTTP status {@code status}, with the message
     * {@code Response code <status> mapped as failure. Message payload is of type: BufferInputStream}.
     *
     * @param status upstream HTTP status outside 0–399
     */
    public ResponseValidatorException(int status) {
        super("Response code " + status + " mapped as failure. Message payload is of type: BufferInputStream");
        this.status = status;
    }

    /**
     * Returns the upstream HTTP status this failure carries.
     *
     * @return upstream HTTP status outside 0–399
     */
    public int getStatus() {
        return status;
    }
}
