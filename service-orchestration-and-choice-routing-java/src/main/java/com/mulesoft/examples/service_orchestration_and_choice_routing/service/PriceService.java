/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * Implements flow {@code priceService} and its price resource, the original class
 * {@code com.mulesoft.se.orders.ProductPrice}.
 *
 * <p>The price resource has one method, {@code GET /prices/{productId}}, which answers
 * {@link #PRODUCT_PRICE} for every product id. {@link #priceService(String, String)} matches a
 * request method and a resource path against that method and returns its result, or an empty
 * result when the request matches no resource method. The flow's listener accepts
 * {@code POST /api} only, and such a request matches no resource method: its result is empty and
 * the caller answers 404 (D-067).
 *
 * <p>The class holds no mutable state and is safe for concurrent use.
 *
 * <pre>{@code
 * PriceService service = new PriceService();
 * service.priceService("POST", "");            // Optional.empty()
 * service.priceService("GET", "/prices/AX02"); // Optional.of("159")
 * service.priceService("GET", "prices/AX02/"); // Optional.of("159")
 * service.priceService("GET", "/prices");      // Optional.empty()
 * service.getProductPrice("AX02");             // "159"
 * }</pre>
 */
@Service
public class PriceService {

    /** Price text that the price resource returns for every product id. */
    public static final String PRODUCT_PRICE = "159";

    /**
     * Resource path of the price resource method {@code GET /prices/{productId}}, relative to the
     * listener path {@code api}: an optional leading slash, {@code prices/}, one non-empty
     * {@code productId} segment captured as group 1, and an optional trailing slash.
     */
    private static final Pattern PRODUCT_PRICE_PATH = Pattern.compile("^/?prices/([^/]+)/?$");

    /**
     * Implements flow {@code priceService}: matches the request against the price resource method
     * {@code GET /prices/{productId}} and returns that method's result.
     *
     * <p>The request matches when {@code method} equals {@code GET}, ignoring case, and
     * {@code resourcePath} is {@code prices/<productId>} with an optional leading slash and an
     * optional trailing slash, where {@code <productId>} is one non-empty path segment. Any other
     * request, including every {@code POST /api} request of the flow's listener, matches no
     * resource method and yields an empty result (D-067). A {@code null} argument yields an empty
     * result. The method throws no exception.
     *
     * @param method       the HTTP method of the request, for example {@code "POST"}; may be
     *                     {@code null}
     * @param resourcePath the request path relative to the listener path {@code api}, for example
     *                     {@code ""} for {@code /api} or {@code "/prices/AX02"} for
     *                     {@code /api/prices/AX02}; may be {@code null}
     * @return the {@link #getProductPrice(String)} result for the captured product id when the
     *         request matches {@code GET /prices/{productId}}, otherwise {@link Optional#empty()}
     */
    public Optional<String> priceService(String method, String resourcePath) {
        if (!"GET".equalsIgnoreCase(method) || resourcePath == null) {
            return Optional.empty();
        }
        Matcher matcher = PRODUCT_PRICE_PATH.matcher(resourcePath);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        return Optional.of(getProductPrice(matcher.group(1)));
    }

    /**
     * Implements the price resource method {@code GET /prices/{productId}} of
     * {@code com.mulesoft.se.orders.ProductPrice}: returns {@link #PRODUCT_PRICE} for every
     * product id. The product id is not read.
     *
     * @param productId the {@code productId} path segment of the request; may be {@code null}
     * @return {@link #PRODUCT_PRICE}, {@code "159"}
     */
    public String getProductPrice(String productId) {
        return PRODUCT_PRICE;
    }
}
