package com.mulesoft.examples.http_request_response_with_logger.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.DispatcherServlet;

/**
 * Registers the dispatcher servlet bean with the five {@code spring.mvc} settings (D-108).
 *
 * <p>The bean is named {@code dispatcherServlet}
 * ({@link DispatcherServletAutoConfiguration#DEFAULT_DISPATCHER_SERVLET_BEAN_NAME}) and is an
 * {@link AllMethodsDispatcherServlet}. {@link DispatcherServletAutoConfiguration} then registers no
 * dispatcher servlet of its own, and its {@code DispatcherServletRegistrationBean} maps this bean at
 * {@code /} with the {@code spring.mvc.servlet.*} and multipart settings.
 *
 * <p>The settings, read from {@link WebMvcProperties}:
 * <ul>
 *   <li>{@code spring.mvc.dispatch-options-request} (Boot default {@code true});</li>
 *   <li>{@code spring.mvc.dispatch-trace-request} ({@code true} in {@code application.yml});</li>
 *   <li>{@code spring.mvc.throw-exception-if-no-handler-found};</li>
 *   <li>{@code spring.mvc.publish-request-handled-events};</li>
 *   <li>{@code spring.mvc.log-request-details}.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebMvcProperties.class)
public class DispatcherServletConfig {

    /**
     * Creates the {@code dispatcherServlet} bean: an {@link AllMethodsDispatcherServlet} configured with
     * the five {@code spring.mvc} settings that Boot applies to its own dispatcher servlet (D-108).
     *
     * <p>{@code spring.mvc.throw-exception-if-no-handler-found} is read and applied through the
     * deprecated {@link WebMvcProperties#isThrowExceptionIfNoHandlerFound()} and
     * {@link DispatcherServlet#setThrowExceptionIfNoHandlerFound(boolean)}, as Boot 3.2 does.
     *
     * @param properties the bound {@code spring.mvc} properties
     * @return the dispatcher servlet that Boot registers at {@code /}
     */
    @Bean(name = DispatcherServletAutoConfiguration.DEFAULT_DISPATCHER_SERVLET_BEAN_NAME)
    @SuppressWarnings({"deprecation", "removal"})
    public DispatcherServlet dispatcherServlet(WebMvcProperties properties) {
        AllMethodsDispatcherServlet servlet = new AllMethodsDispatcherServlet();
        servlet.setDispatchOptionsRequest(properties.isDispatchOptionsRequest());
        servlet.setDispatchTraceRequest(properties.isDispatchTraceRequest());
        servlet.setThrowExceptionIfNoHandlerFound(properties.isThrowExceptionIfNoHandlerFound());
        servlet.setPublishEvents(properties.isPublishRequestHandledEvents());
        servlet.setEnableLoggingRequestDetails(properties.isLogRequestDetails());
        return servlet;
    }

    /**
     * Dispatcher servlet that sends OPTIONS and TRACE requests to the handler mappings only (D-108).
     *
     * <p>With dispatching of the method enabled, an OPTIONS or TRACE response carries only what the
     * handler writes: no {@code Allow} header is added to an OPTIONS response, and no
     * {@code message/http} echo of the request is written for TRACE. With dispatching disabled, the
     * request is answered by {@link DispatcherServlet}'s own {@code doOptions} or {@code doTrace}.
     * Every other method is processed exactly as by {@link DispatcherServlet}.
     *
     * <p>A newly constructed instance dispatches OPTIONS and does not dispatch TRACE, the same defaults as
     * {@link DispatcherServlet}.
     */
    public static class AllMethodsDispatcherServlet extends DispatcherServlet {

        /** Serialization version of this servlet class. */
        private static final long serialVersionUID = 1L;

        /**
         * The value last passed to {@link #setDispatchOptionsRequest(boolean)}. The field has no
         * initializer; a new instance holds the {@code true} that the {@link DispatcherServlet}
         * constructor passes to that setter.
         */
        private boolean dispatchOptions;

        /**
         * The value last passed to {@link #setDispatchTraceRequest(boolean)}. The field has no
         * initializer; a new instance holds {@code false}.
         */
        private boolean dispatchTrace;

        /**
         * Sets whether OPTIONS requests are dispatched to the handler mappings, on
         * {@link DispatcherServlet} and on this instance.
         *
         * @param dispatchOptionsRequest {@code true} to dispatch OPTIONS requests
         */
        @Override
        public void setDispatchOptionsRequest(boolean dispatchOptionsRequest) {
            super.setDispatchOptionsRequest(dispatchOptionsRequest);
            this.dispatchOptions = dispatchOptionsRequest;
        }

        /**
         * Sets whether TRACE requests are dispatched to the handler mappings, on
         * {@link DispatcherServlet} and on this instance.
         *
         * @param dispatchTraceRequest {@code true} to dispatch TRACE requests
         */
        @Override
        public void setDispatchTraceRequest(boolean dispatchTraceRequest) {
            super.setDispatchTraceRequest(dispatchTraceRequest);
            this.dispatchTrace = dispatchTraceRequest;
        }

        /**
         * Processes an OPTIONS request through the handler mappings when OPTIONS dispatching is enabled,
         * adding nothing to the handler's response; otherwise answers it through
         * {@link DispatcherServlet}'s {@code doOptions}.
         *
         * @param request  the current request
         * @param response the current response
         * @throws ServletException if request processing fails
         * @throws IOException      if reading the request or writing the response fails
         */
        @Override
        protected void doOptions(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            if (this.dispatchOptions) {
                processRequest(request, response);
            } else {
                super.doOptions(request, response);
            }
        }

        /**
         * Processes a TRACE request through the handler mappings when TRACE dispatching is enabled,
         * adding nothing to the handler's response; otherwise answers it through
         * {@link DispatcherServlet}'s {@code doTrace}.
         *
         * @param request  the current request
         * @param response the current response
         * @throws ServletException if request processing fails
         * @throws IOException      if reading the request or writing the response fails
         */
        @Override
        protected void doTrace(HttpServletRequest request, HttpServletResponse response)
                throws ServletException, IOException {
            if (this.dispatchTrace) {
                processRequest(request, response);
            } else {
                super.doTrace(request, response);
            }
        }
    }
}
