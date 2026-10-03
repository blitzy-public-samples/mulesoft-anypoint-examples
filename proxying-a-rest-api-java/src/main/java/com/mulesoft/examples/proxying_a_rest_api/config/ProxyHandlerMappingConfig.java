package com.mulesoft.examples.proxying_a_rest_api.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcRegistrations;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Registers the application's {@code requestMappingHandlerMapping} bean as a
 * {@link PassThroughCorsRequestMappingHandlerMapping}, which returns the handler chain unchanged, so CORS preflight
 * requests reach the mapped handler (D-314, D-059).
 *
 * <p>Flow {@code rest-api-proxy} receives every request on the listener path {@code /*} and forwards it upstream with
 * the same method, path, query, headers and body, a CORS preflight included
 * [proxying-a-rest-api/src/main/app/proxying-a-rest-api.xml:12,14]. A CORS preflight is an {@code OPTIONS} request
 * that carries an {@code Origin} and an {@code Access-Control-Request-Method} header. With this bean, a preflight
 * resolves to the handler method mapped with no declared HTTP method, and the response carries only the status,
 * headers and body that handler writes: Spring adds no {@code Vary} or {@code Access-Control-*} header and answers no
 * preflight with {@code 403 Invalid CORS request}.
 *
 * <p>Boot's {@code WebMvcAutoConfiguration.EnableWebMvcConfiguration} takes the handler mapping from the single
 * {@link WebMvcRegistrations} bean and applies its order, interceptors, path matching, content negotiation and CORS
 * configurations to it as to its own {@link RequestMappingHandlerMapping}. This class sets none of them. The request
 * mapping handler adapter and the exception handler exception resolver stay Boot's defaults.
 *
 * <p>The class holds no state; the handler mapping it creates is safe for concurrent requests, as
 * {@link RequestMappingHandlerMapping} is. This project carries its own copy of the class (D-004).
 */
@Configuration(proxyBeanMethods = false)
public class ProxyHandlerMappingConfig {

    /**
     * Creates the {@code proxyWebMvcRegistrations} bean: a {@link WebMvcRegistrations} whose
     * {@link WebMvcRegistrations#getRequestMappingHandlerMapping()} returns a new
     * {@link PassThroughCorsRequestMappingHandlerMapping} (D-314).
     *
     * <p>{@link WebMvcRegistrations#getRequestMappingHandlerAdapter()} and
     * {@link WebMvcRegistrations#getExceptionHandlerExceptionResolver()} keep their default {@code null}, and Boot
     * creates those two components itself.
     *
     * @return the registrations Boot reads when it creates the {@code requestMappingHandlerMapping} bean
     */
    @Bean
    public WebMvcRegistrations proxyWebMvcRegistrations() {
        return new WebMvcRegistrations() {

            /**
             * Returns a new {@link PassThroughCorsRequestMappingHandlerMapping} for Boot to configure and register as
             * {@code requestMappingHandlerMapping}.
             *
             * @return a new, unconfigured handler mapping
             */
            @Override
            public RequestMappingHandlerMapping getRequestMappingHandlerMapping() {
                return new PassThroughCorsRequestMappingHandlerMapping();
            }
        };
    }

    /**
     * Request mapping handler mapping that returns the handler execution chain unchanged for CORS requests, CORS
     * preflight requests included (D-314, D-059).
     *
     * <p>{@link #getCorsHandlerExecutionChain} returns the chain it receives: no {@code PreFlightHandler} replaces the
     * handler of a preflight and no {@code CorsInterceptor} joins the chain of any request. Handler lookup, request
     * mapping conditions and every other behaviour are those of {@link RequestMappingHandlerMapping}.
     *
     * <p>Example: {@code OPTIONS /2.0/folders/0} with {@code Origin: https://example.com} and
     * {@code Access-Control-Request-Method: GET} resolves to the handler method mapped to {@code /**} with no declared
     * HTTP method. When that handler sets status 200 and the header {@code Allow: GET, OPTIONS}, the response is status
     * 200 with {@code Allow: GET, OPTIONS} and no {@code Vary} or {@code Access-Control-Allow-*} header.
     */
    public static class PassThroughCorsRequestMappingHandlerMapping extends RequestMappingHandlerMapping {

        /**
         * Returns {@code chain} unchanged; {@code request} and {@code config} are not read.
         *
         * <p>{@code config} may be {@code null}; the parameter declares no {@code org.springframework.lang.Nullable}
         * annotation (D-314).
         *
         * @param request the current request
         * @param chain the handler execution chain resolved for the request
         * @param config the CORS configuration resolved for the handler, or {@code null} when none is configured
         * @return {@code chain}, the same instance
         */
        @Override
        protected HandlerExecutionChain getCorsHandlerExecutionChain(HttpServletRequest request,
                HandlerExecutionChain chain, CorsConfiguration config) {
            return chain;
        }
    }
}
