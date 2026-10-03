package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link PriceService}: flow {@code priceService}
 * ({@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:144-149}) and the
 * price resource method {@code getProductPrice} of the original class
 * {@code com.mulesoft.se.orders.ProductPrice}.
 *
 * <p>The price resource has one method, {@code GET /prices/{productId}}, which answers {@code "159"}
 * as {@code text/plain} for every product id. The flow's listener accepts {@code POST /api} only,
 * and that request matches no resource method (D-067). Each test constructs the service with
 * {@code new PriceService()}, with no Spring application context and no mock, and asserts
 * <ul>
 *   <li>{@code POST} or {@code GET} with an empty resource path ({@code /api}) matches no resource
 *       method;</li>
 *   <li>{@code POST} on {@code /prices/AX02} matches no resource method;</li>
 *   <li>{@code GET} on {@code /prices/AX02}, with or without the leading and trailing slash, returns
 *       {@code "159"};</li>
 *   <li>{@code GET} on {@code /prices} or {@code /prices/AX02/extra}, and a {@code null} method or
 *       resource path, match no resource method;</li>
 *   <li>{@link PriceService#getProductPrice(String)} returns {@code "159"} for every product id.</li>
 * </ul>
 * The tests cover every line of {@link PriceService} (D-049).
 */
public class PriceServiceTest {

    /** Price text of the price resource method for every product id. */
    private static final String PRICE = "159";

    /** Product id of the original integration test's order item. */
    private static final String PRODUCT_ID = "AX02";

    /** Resource path of {@code GET /prices/{productId}} for {@link #PRODUCT_ID}, relative to {@code /api}. */
    private static final String PRICE_PATH = "/prices/" + PRODUCT_ID;

    /** Resource path of the {@code /api} listener path itself. */
    private static final String EMPTY_PATH = "";

    /** The unit under test, constructed directly. */
    private final PriceService service = new PriceService();

    /**
     * {@code POST /api} matches no resource (D-067): {@code priceService("POST", "")} is empty.
     */
    @Test
    @DisplayName("POST with an empty resource path matches no price resource method")
    public void postWithEmptyPathMatchesNoResource() {
        Optional<String> result = service.priceService("POST", EMPTY_PATH);

        assertThat(result).isEmpty();
    }

    /**
     * {@code GET /api} matches no resource (D-067): {@code priceService("GET", "")} is empty.
     */
    @Test
    @DisplayName("GET with an empty resource path matches no price resource method")
    public void getWithEmptyPathMatchesNoResource() {
        Optional<String> result = service.priceService("GET", EMPTY_PATH);

        assertThat(result).isEmpty();
    }

    /**
     * {@code POST /api/prices/AX02} matches no resource (D-067): the price resource method answers
     * {@code GET} only, and {@code priceService("POST", "/prices/AX02")} is empty.
     */
    @Test
    @DisplayName("POST on the product price path matches no price resource method")
    public void postOnPricePathMatchesNoResource() {
        Optional<String> result = service.priceService("POST", PRICE_PATH);

        assertThat(result).isEmpty();
    }

    /**
     * {@code GET /api/prices/AX02} matches {@code GET /prices/{productId}}:
     * {@code priceService("GET", "/prices/AX02")} equals {@code Optional.of("159")}.
     */
    @Test
    @DisplayName("GET on the product price path returns the price 159")
    public void getOnPricePathReturnsPrice() {
        Optional<String> result = service.priceService("GET", PRICE_PATH);

        assertThat(result).isEqualTo(Optional.of(PRICE));
    }

    /**
     * The price resource method returns {@code "159"} for every product id:
     * {@code getProductPrice("AX02")} and {@code getProductPrice("any")} both return {@code "159"}.
     */
    @Test
    @DisplayName("getProductPrice returns the fixed price 159 for every product id")
    public void getProductPriceReturnsFixedPrice() {
        assertThat(service.getProductPrice(PRODUCT_ID)).isEqualTo(PRICE);
        assertThat(service.getProductPrice("any")).isEqualTo(PRICE);
    }

    /**
     * {@code GET /api/prices}, without a product id, matches no resource method:
     * {@code priceService("GET", "/prices")} is empty.
     */
    @Test
    @DisplayName("GET on the price collection path without a product id matches no price resource method")
    public void getOnPriceCollectionPathMatchesNoResource() {
        Optional<String> result = service.priceService("GET", "/prices");

        assertThat(result).isEmpty();
    }

    /**
     * {@code GET /api/prices/AX02/extra}, one segment below a product id, matches no resource method:
     * {@code priceService("GET", "/prices/AX02/extra")} is empty.
     */
    @Test
    @DisplayName("GET on a path below the product price path matches no price resource method")
    public void getOnNestedPricePathMatchesNoResource() {
        Optional<String> result = service.priceService("GET", PRICE_PATH + "/extra");

        assertThat(result).isEmpty();
    }

    /**
     * A {@code null} method or a {@code null} resource path matches no resource method and raises no
     * exception: {@code priceService(null, "/prices/AX02")}, {@code priceService("GET", null)} and
     * {@code priceService(null, null)} are empty.
     */
    @Test
    @DisplayName("A null method or resource path matches no price resource method")
    public void nullMethodOrPathMatchesNoResource() {
        assertThat(service.priceService(null, PRICE_PATH)).isEmpty();
        assertThat(service.priceService("GET", null)).isEmpty();
        assertThat(service.priceService(null, null)).isEmpty();
    }

    /**
     * The product price path matches without its leading slash and with a trailing slash:
     * {@code priceService("GET", "prices/AX02")}, {@code priceService("GET", "/prices/AX02/")} and
     * {@code priceService("GET", "prices/AX02/")} each equal {@code Optional.of("159")}.
     */
    @Test
    @DisplayName("GET on the product price path with or without its leading and trailing slash returns the price 159")
    public void getOnPricePathSlashVariantsReturnsPrice() {
        assertThat(service.priceService("GET", "prices/" + PRODUCT_ID)).isEqualTo(Optional.of(PRICE));
        assertThat(service.priceService("GET", PRICE_PATH + "/")).isEqualTo(Optional.of(PRICE));
        assertThat(service.priceService("GET", "prices/" + PRODUCT_ID + "/")).isEqualTo(Optional.of(PRICE));
    }
}
