/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.controller;

import com.mulesoft.examples.service_orchestration_and_choice_routing.service.PriceService;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Answers {@code POST /api}, the listener of flow {@code priceService}, ported from the JAX-RS
 * resource {@code com.mulesoft.se.orders.ProductPrice} together with
 * {@link PriceService#getProductPrice(String)}. {@code config.PortPathGuardFilter} admits the
 * request on {@code listener.http-listener-configuration2.port} only (D-011).
 *
 * <p>The request body, query string and headers are not read, and any request {@code Content-Type}
 * or none is accepted. The controller passes the request method {@code POST} and the resource path
 * {@code ""}, the path of {@code /api} relative to the listener path {@code api}, to
 * {@link PriceService#priceService(String, String)} and renders the result:
 *
 * <pre>{@code
 * no result   404   empty body, Content-Length: 0, no Content-Type    (D-066, D-067)
 * result      200   Content-Type: text/plain;charset=UTF-8, the text  (D-441)
 * }</pre>
 *
 * <p>Every {@code POST /api} request yields no result: the answer on the wire is always the 404.
 * No other path or method is mapped: {@code GET /api/prices/{productId}} on the listener port is
 * answered 404 by {@code config.PortPathGuardFilter} (D-067). Exceptions are not caught; the
 * project's {@code exception.GlobalExceptionHandler} answers them with 500.
 *
 * <p>The controller holds no mutable state and is safe for concurrent use.
 */
@RestController
public class ProductPriceController {

    /** Request method passed to the price service for every request of this mapping. */
    private static final String REQUEST_METHOD = "POST";

    /** Path of {@code /api} relative to the listener path {@code api}. */
    private static final String RESOURCE_PATH = "";

    /** {@code Content-Type} of a price service result (D-441). */
    private static final String TEXT_PLAIN_UTF8 = "text/plain;charset=UTF-8";

    /** Matches the request against the price resource and supplies its result. */
    private final PriceService priceService;

    /**
     * Creates the controller over the price service.
     *
     * @param priceService the service that implements flow {@code priceService}
     */
    public ProductPriceController(PriceService priceService) {
        this.priceService = priceService;
    }

    /**
     * Implements the listener of flow {@code priceService}: answers {@code POST /api} with the
     * result of {@link PriceService#priceService(String, String)} for the method {@code POST} and
     * the resource path {@code ""}.
     *
     * <p>An empty result, which every {@code POST /api} request yields, is written through
     * {@link RawBody#write(HttpServletResponse, int, byte[])}: status 404 with the standard reason
     * phrase, {@code Content-Length: 0}, no body and no {@code Content-Type} (D-066, D-067, D-193).
     *
     * <p>A present result is written with status 200, {@code Content-Type: text/plain;charset=UTF-8},
     * {@code Content-Length} set to the byte length of the result and the UTF-8 bytes of the result
     * through the servlet output stream, which is flushed and left open for the container to close
     * (D-441).
     *
     * @param response the servlet response the answer is written to
     * @throws IOException if the servlet output stream cannot be obtained, written or flushed for a
     *                     present result; {@code exception.GlobalExceptionHandler} answers it with
     *                     500 (D-441)
     */
    @PostMapping("/api")
    public void priceService(HttpServletResponse response) throws IOException {
        Optional<String> price = priceService.priceService(REQUEST_METHOD, RESOURCE_PATH);
        if (price.isEmpty()) {
            RawBody.write(response, HttpServletResponse.SC_NOT_FOUND, new byte[0]);
            return;
        }
        // Present result: text/plain with UTF-8, explicit Content-Length, IOException propagated (D-441)
        byte[] body = price.get().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(TEXT_PLAIN_UTF8);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
