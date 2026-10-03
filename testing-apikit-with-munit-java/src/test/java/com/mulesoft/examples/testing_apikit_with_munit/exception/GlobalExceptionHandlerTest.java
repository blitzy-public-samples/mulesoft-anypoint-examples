package com.mulesoft.examples.testing_apikit_with_munit.exception;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Advice-level tests of {@link GlobalExceptionHandler}, the Java form of the APIkit mapping strategy
 * {@code api-apiKitGlobalExceptionMapping} [testing-apikit-with-munit/src/main/app/api.xml:10-36], with
 * no Spring application context (D-062).
 *
 * <p>A standalone {@link MockMvc} sends {@code GET /throw/*} requests to {@link ThrowingController},
 * each of whose route methods throws one exception, and resolves that exception through the advice
 * under test. The tests assert:
 *
 * <ul>
 *   <li>the 415 branch [api.xml:21-25] and the 400 branch [api.xml:31-35]: status, exactly
 *       {@code Content-Type: application/json} and the exact UTF-8 bytes of the literal body (D-062);</li>
 *   <li>the 404 branch [api.xml:11-15] for the project {@link NotFoundException}: the same three
 *       properties;</li>
 *   <li>the HTTP default rule for an unmapped exception: status 500, no {@code Content-Type} header and
 *       the exception message as the body (D-066).</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {

    /** MockMvc over {@link ThrowingController} with {@link GlobalExceptionHandler} as its only advice. */
    private MockMvc mockMvc;

    /** Builds a standalone MockMvc for each test: one {@link ThrowingController}, one advice. */
    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * {@code GET /throw/unsupported-media} ({@link HttpMediaTypeNotSupportedException}) answers 415, exactly
     * {@code Content-Type: application/json} and the bytes of {@code { "message": "Unsupported media type" }},
     * the {@code set-payload} value at api.xml:24 (D-062).
     */
    @Test
    @DisplayName("testing-apikit-with-munit_unsupported-media-415")
    void unsupportedMedia415() throws Exception {
        assertApikit("/throw/unsupported-media", 415, "{ \"message\": \"Unsupported media type\" }");
    }

    /**
     * Each of {@code GET /throw/bad-request} ({@link BadRequestException}), {@code GET /throw/missing-param}
     * ({@link MissingServletRequestParameterException}), {@code GET /throw/not-readable}
     * ({@link HttpMessageNotReadableException}) and {@code GET /throw/type-mismatch}
     * ({@link MethodArgumentTypeMismatchException}) answers 400, exactly {@code Content-Type: application/json}
     * and the bytes of {@code { "message": "Bad request" }}, the {@code set-payload} value at api.xml:34
     * (D-062). All four requests are asserted, and every failure is reported.
     */
    @Test
    @DisplayName("testing-apikit-with-munit_invalid-request-400")
    void invalidRequest400() {
        String body = "{ \"message\": \"Bad request\" }";
        assertAll(
                () -> assertApikit("/throw/bad-request", 400, body),
                () -> assertApikit("/throw/missing-param", 400, body),
                () -> assertApikit("/throw/not-readable", 400, body),
                () -> assertApikit("/throw/type-mismatch", 400, body));
    }

    /**
     * {@code GET /throw/not-found} (the project {@link NotFoundException}) answers 404, exactly
     * {@code Content-Type: application/json} and the bytes of {@code { "message": "Resource not found" }},
     * the {@code set-payload} value at api.xml:14.
     */
    @Test
    @DisplayName("NotFoundException maps to the APIkit 404 body")
    void notFoundException404() throws Exception {
        assertApikit("/throw/not-found", 404, "{ \"message\": \"Resource not found\" }");
    }

    /**
     * {@code GET /throw/runtime} ({@link RuntimeException} with the message {@code boom}, which no APIkit
     * branch lists) answers 500 with no {@code Content-Type}, neither as the response content type nor as a
     * header, and the UTF-8 bytes of {@code boom} as the body (D-066).
     */
    @Test
    @DisplayName("Unmapped exception maps to 500 with no Content-Type")
    void unexpectedException500() throws Exception {
        MvcResult result = mockMvc.perform(get("/throw/runtime")).andReturn();

        assertEquals(500, result.getResponse().getStatus());
        assertNull(result.getResponse().getContentType());
        assertNull(result.getResponse().getHeader("Content-Type"));
        assertArrayEquals("boom".getBytes(StandardCharsets.UTF_8), result.getResponse().getContentAsByteArray());
    }

    /**
     * Sends {@code GET path} and asserts the APIkit answer: the given status, a {@code Content-Type} header
     * equal to {@code application/json} with no parameter, and a body whose bytes equal the UTF-8 bytes of
     * {@code body}.
     *
     * @param path   the request path, one of the {@link ThrowingController} routes
     * @param status the expected HTTP status
     * @param body   the expected literal JSON body
     * @throws Exception if MockMvc fails to perform the request
     */
    private void assertApikit(String path, int status, String body) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andReturn();

        assertEquals(status, result.getResponse().getStatus(), () -> "status of GET " + path);
        assertEquals("application/json", result.getResponse().getHeader("Content-Type"),
                () -> "Content-Type of GET " + path);
        assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), result.getResponse().getContentAsByteArray(),
                () -> "body of GET " + path);
    }

    /**
     * Test-only controller registered by the standalone MockMvc of {@link GlobalExceptionHandlerTest}. Each
     * route method answers {@code GET} on its {@code /throw/*} path by throwing one exception (D-062).
     */
    @RestController
    static class ThrowingController {

        /** {@code GET /throw/unsupported-media}: throws {@link HttpMediaTypeNotSupportedException}. */
        @GetMapping("/throw/unsupported-media")
        void unsupportedMedia() throws Exception {
            throw new HttpMediaTypeNotSupportedException("x");
        }

        /** {@code GET /throw/bad-request}: throws the project {@link BadRequestException}. */
        @GetMapping("/throw/bad-request")
        void badRequest() throws Exception {
            throw new BadRequestException("x");
        }

        /**
         * {@code GET /throw/missing-param}: throws {@link MissingServletRequestParameterException} for the
         * {@code String} parameter {@code p}.
         */
        @GetMapping("/throw/missing-param")
        void missingParam() throws Exception {
            throw new MissingServletRequestParameterException("p", "String");
        }

        /**
         * {@code GET /throw/not-readable}: throws {@link HttpMessageNotReadableException} for an empty
         * request body.
         */
        @GetMapping("/throw/not-readable")
        void notReadable() throws Exception {
            throw new HttpMessageNotReadableException("x", new MockHttpInputMessage(new byte[0]));
        }

        /**
         * {@code GET /throw/type-mismatch}: throws {@link MethodArgumentTypeMismatchException} for the value
         * {@code abc} of the {@code Integer} parameter {@code p} of {@link #typed(Integer)}.
         */
        @GetMapping("/throw/type-mismatch")
        void typeMismatch() throws Exception {
            throw new MethodArgumentTypeMismatchException("abc", Integer.class, "p",
                    new MethodParameter(ThrowingController.class.getDeclaredMethod("typed", Integer.class), 0), null);
        }

        /** {@code GET /throw/not-found}: throws the project {@link NotFoundException}. */
        @GetMapping("/throw/not-found")
        void notFound() throws Exception {
            throw new NotFoundException("x");
        }

        /** {@code GET /throw/runtime}: throws {@link RuntimeException} with the message {@code boom}. */
        @GetMapping("/throw/runtime")
        void runtime() throws Exception {
            throw new RuntimeException("boom");
        }

        /**
         * Unmapped method with no behaviour; its {@code Integer} parameter {@code p} is the
         * {@link MethodParameter} of the exception {@link #typeMismatch()} throws.
         *
         * @param p the parameter the type mismatch names
         */
        void typed(Integer p) {
        }
    }
}
