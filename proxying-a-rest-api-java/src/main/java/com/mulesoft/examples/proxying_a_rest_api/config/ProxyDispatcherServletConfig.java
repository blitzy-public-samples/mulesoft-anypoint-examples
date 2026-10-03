package com.mulesoft.examples.proxying_a_rest_api.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers the application's {@code dispatcherServlet} bean as a {@link ProxyDispatcherServlet}, which dispatches
 * OPTIONS and TRACE to the handler without adding an {@code Allow} header or echoing the request (D-314, D-059).
 *
 * <p>Flow {@code rest-api-proxy} receives every HTTP method on the listener path {@code /*} and forwards it upstream
 * with the same method [proxying-a-rest-api/src/main/app/proxying-a-rest-api.xml:12,14]. With this bean, every
 * method, OPTIONS and TRACE included, reaches the handler mappings, and the response to an OPTIONS or TRACE request
 * carries only what the handler writes.
 *
 * <p>The bean is named {@link DispatcherServletAutoConfiguration#DEFAULT_DISPATCHER_SERVLET_BEAN_NAME}.
 * {@link DispatcherServletAutoConfiguration} then creates no dispatcher servlet of its own, and its
 * {@code DispatcherServletRegistrationBean} registers this bean at {@code spring.mvc.servlet.path} ({@code /}) with
 * the {@code spring.mvc.servlet.*} settings. The class declares no servlet registration bean.
 *
 * <p>The settings read from {@link WebMvcProperties} and applied to the servlet:
 * <ul>
 *   <li>{@code spring.mvc.dispatch-options-request} (Boot default {@code true});</li>
 *   <li>{@code spring.mvc.dispatch-trace-request} ({@code true} in {@code application.yml});</li>
 *   <li>{@code spring.mvc.publish-request-handled-events} (Boot default {@code true});</li>
 *   <li>{@code spring.mvc.log-request-details} (Boot default {@code false}).</li>
 * </ul>
 *
 * <p>The class holds no state; the servlet it creates is safe for concurrent requests, as {@link DispatcherServlet}
 * is. This project carries its own copy of the class (D-004).
 */
@Configuration(proxyBeanMethods = false)
public class ProxyDispatcherServletConfig {

    /**
     * Creates the {@code dispatcherServlet} bean: a {@link ProxyDispatcherServlet} with the {@code spring.mvc}
     * dispatch, event and logging settings applied as {@link DispatcherServletAutoConfiguration} applies them to its
     * own dispatcher servlet (D-314).
     *
     * <p>Request details, the forwarded {@code Authorization} header among them, are logged only when
     * {@code spring.mvc.log-request-details} is {@code true}.
     *
     * @param webMvcProperties the bound {@code spring.mvc} properties; must not be {@code null}
     * @return the dispatcher servlet that Boot registers at {@code /}
     * @throws NullPointerException when {@code webMvcProperties} is {@code null}
     */
    @Bean(name = DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)
    public DispatcherServlet dispatcherServlet(WebMvcProperties webMvcProperties) {
        Objects.requireNonNull(webMvcProperties, "webMvcProperties");
        ProxyDispatcherServlet servlet = new ProxyDispatcherServlet();
        servlet.setDispatchOptionsRequest(webMvcProperties.isDispatchOptionsRequest());
        servlet.setDispatchTraceRequest(webMvcProperties.isDispatchTraceRequest());
        // setThrowExceptionIfNoHandlerFound is not called: the servlet keeps its default true (D-314).
        servlet.setPublishEvents(webMvcProperties.isPublishRequestHandledEvents());
        servlet.setEnableLoggingRequestDetails(webMvcProperties.isLogRequestDetails());
        return servlet;
    }

    /**
     * Dispatcher servlet that dispatches OPTIONS and TRACE to the handler without adding an {@code Allow} header or
     * echoing the request (D-314).
     *
     * <p>{@link #doOptions} and {@link #doTrace} pass the request to {@code processRequest}, the path every other
     * method takes, and write nothing after it: an OPTIONS response carries no {@code Allow} header the handler did
     * not set, and a TRACE response carries no {@code message/http} copy of the request. Both methods behave this way
     * whatever {@code dispatchOptionsRequest} and {@code dispatchTraceRequest} hold, and for CORS preflight requests
     * too. Every other method is processed exactly as by {@link DispatcherServlet}.
     *
     * <p>Example: for {@code OPTIONS /2.0/folders/0}, a handler that sets status 200, sets no {@code Allow} header and
     * writes the body {@code ok} yields status 200, body {@code ok} and no {@code Allow} header; for
     * {@code TRACE /2.0/folders/0}, the same handler yields status 200 and body {@code ok} with no
     * {@code message/http} content.
     */
    public static class ProxyDispatcherServlet extends DispatcherServlet {

        /** Serialization version of this servlet class. */
        private static final long serialVersionUID = 1L;

        /**
         * Processes an OPTIONS request through the handler mappings, adding nothing to the handler's response.
         *
         * @param request  the current request
         * @param response the current response
         * @throws ServletException if request processing fails
         * @throws IOException      if reading the request or writing the response fails
         */
        @Override
        protected void doOptions(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            processRequest(request, response);
        }

        /**
         * Processes a TRACE request through the handler mappings, adding nothing to the handler's response.
         *
         * @param request  the current request
         * @param response the current response
         * @throws ServletException if request processing fails
         * @throws IOException      if reading the request or writing the response fails
         */
        @Override
        protected void doTrace(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            processRequest(request, response);
        }
    }
}
