package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link ManufacturerService}, the {@code manufacturers} flow
 * (mule-config.xml:43-46), with no Spring application context and no mocks (D-416).
 *
 * <p>The expected value is the text that the flow's expression transformer at mule-config.xml:45
 * produces: {@code ["Samsung","Philips","Sony"]}, with no spaces and no line break.
 */
final class ManufacturerServiceTest {

    /** The manufacturer list text of mule-config.xml:45, as a Java string literal. */
    private static final String EXPECTED = "[\"Samsung\",\"Philips\",\"Sony\"]";

    /** The service under test, instantiated directly. */
    private final ManufacturerService service = new ManufacturerService();

    /**
     * {@link ManufacturerService#manufacturers()} returns exactly
     * {@code ["Samsung","Philips","Sony"]}, and its UTF-8 bytes equal the bytes of that text.
     */
    @Test
    @DisplayName("manufacturers returns the exact manufacturer list text")
    void manufacturersReturnsExactLiteral() {
        String result = service.manufacturers();

        assertThat(result).isEqualTo(EXPECTED);
        assertThat(result.getBytes(StandardCharsets.UTF_8))
                .isEqualTo(EXPECTED.getBytes(StandardCharsets.UTF_8));
    }

    /** {@link ManufacturerService#manufacturers()} returns {@link ManufacturerService#MANUFACTURERS}. */
    @Test
    @DisplayName("manufacturers returns the MANUFACTURERS constant")
    void manufacturersEqualsConstant() {
        assertThat(service.manufacturers()).isEqualTo(ManufacturerService.MANUFACTURERS);
    }

    /**
     * The text {@link ManufacturerService#manufacturers()} returns is a JSON array of exactly the
     * strings {@code Samsung}, {@code Philips} and {@code Sony}, in that order.
     *
     * @throws Exception when the text is not a JSON array of strings
     */
    @Test
    @DisplayName("manufacturers parses as the ordered list Samsung, Philips, Sony")
    void manufacturersParsesAsOrderedList() throws Exception {
        String result = service.manufacturers();

        List<String> manufacturers = new ObjectMapper().readValue(result, new TypeReference<List<String>>() { });

        assertThat(manufacturers).containsExactly("Samsung", "Philips", "Sony");
    }
}
