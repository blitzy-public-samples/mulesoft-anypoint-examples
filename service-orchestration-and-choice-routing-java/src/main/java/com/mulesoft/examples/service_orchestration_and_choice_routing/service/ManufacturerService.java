package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import org.springframework.stereotype.Service;

/**
 * Replaces the {@code manufacturers} flow (mule-config.xml:43-46), whose AJAX channel
 * {@code /orders/manufacturers} answers every call with a fixed manufacturer list (D-027).
 *
 * <p>The class is stateless: the method takes no argument, reads nothing and returns a constant.
 *
 * <pre>{@code
 * ManufacturerService service = new ManufacturerService();
 * service.manufacturers(); // ["Samsung","Philips","Sony"]
 * }</pre>
 */
@Service
public class ManufacturerService {

    /** The manufacturer list text {@code ["Samsung","Philips","Sony"]}, without spaces or a line break. */
    public static final String MANUFACTURERS = "[\"Samsung\",\"Philips\",\"Sony\"]";

    /**
     * Returns the manufacturer list of the {@code manufacturers} flow (mule-config.xml:43-46).
     *
     * @return the text {@value #MANUFACTURERS}
     */
    public String manufacturers() {
        return MANUFACTURERS;
    }
}
