/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.controller;

import com.mulesoft.examples.filtering_a_message.model.InboundHttpRequest;
import com.mulesoft.examples.filtering_a_message.service.DiscountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Controller;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * HTTP listener of flow {@code filteringFlow1}: binds the raw request on the listener path and hands it to
 * {@link DiscountService#filteringFlow1(InboundHttpRequest)}.
 */
@Controller
public class DiscountController {

    /** Runs flow {@code filteringFlow1}. */
    private final DiscountService discountService;

    /**
     * Creates the controller.
     *
     * @param discountService the flow service
     */
    DiscountController(DiscountService discountService) {
        this.discountService = discountService;
    }

    /**
     * Handles GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS and TRACE on the path held by
     * {@code filtering-flow1.listener.path} (D-139, D-591). Any method other than POST raises
     * {@link HttpRequestMethodNotSupportedException} before the body is read. For POST, reads the body
     * bytes from the input stream, passes them with the method, {@code Content-Length},
     * {@code Transfer-Encoding} and {@code Content-Type} to the service, and writes the service result
     * with status 200 and no Content-Type (D-066). An empty result writes status 200 with an empty body.
     *
     * @param request the HTTP request
     * @param response the HTTP response
     * @throws IOException if the request body cannot be read or the response cannot be written
     * @throws HttpRequestMethodNotSupportedException if the request method is not POST
     */
    @RequestMapping(path = "${filtering-flow1.listener.path}", method = {RequestMethod.GET, RequestMethod.HEAD,
            RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH, RequestMethod.DELETE, RequestMethod.OPTIONS,
            RequestMethod.TRACE})
    public void filteringFlow1(HttpServletRequest request, HttpServletResponse response)
            throws IOException, HttpRequestMethodNotSupportedException {
        if (!"POST".equals(request.getMethod())) {
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), List.of("POST"));
        }
        byte[] body = request.getInputStream().readAllBytes();
        InboundHttpRequest in = new InboundHttpRequest(request.getMethod(), request.getContentLengthLong(),
                request.getHeader("Transfer-Encoding"), request.getContentType(), body);
        Optional<String> result = discountService.filteringFlow1(in);
        if (result.isPresent()) {
            RawBody.write(response, 200, result.get().getBytes(StandardCharsets.UTF_8));
        } else {
            RawBody.write(response, 200, new byte[0]);
        }
    }
}
