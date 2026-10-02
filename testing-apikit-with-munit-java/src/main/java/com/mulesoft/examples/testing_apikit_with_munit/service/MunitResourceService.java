package com.mulesoft.examples.testing_apikit_with_munit.service;

import org.springframework.stereotype.Service;

/**
 * Returns the fixed response text for each method of the {@code /munit} resource.
 *
 * <p>The class is stateless: every method takes no argument, reads nothing and returns a constant.
 *
 * <pre>{@code
 * MunitResourceService service = new MunitResourceService();
 * service.getMunit();    // "GET RESPONSE"
 * service.putMunit();    // "PUT RESPONSE"
 * service.deleteMunit(); // "DELETE RESPONSE"
 * service.postMunit();   // "POST RESPONSE"
 * }</pre>
 */
@Service
public class MunitResourceService {

    /** {@return the text {@code GET RESPONSE}} */
    public String getMunit() {
        return "GET RESPONSE";
    }

    /** {@return the text {@code PUT RESPONSE}} */
    public String putMunit() {
        return "PUT RESPONSE";
    }

    /** {@return the text {@code DELETE RESPONSE}} */
    public String deleteMunit() {
        return "DELETE RESPONSE";
    }

    /** {@return the text {@code POST RESPONSE}} */
    public String postMunit() {
        return "POST RESPONSE";
    }
}
