package com.mulesoft.examples.netsuite_data_retrieval.config;

import com.mulesoft.examples.netsuite_data_retrieval.exception.BadRequestException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UrlPathHelper;

/**
 * Query-parameter validation of the APIkit router {@code netsuite-api-config}
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:13, :22]: every request under the RAML
 * base path ({@code /api}) whose path and method match a method of {@code netsuite-api.raml} is
 * checked against that method's declared query parameters before its handler method runs (D-046).
 *
 * <p>Checks, applied to every value of a declared query parameter present in the request:
 * <ul>
 *   <li>{@code type: integer}: the whole value matches {@code -?\d+}; the empty value, a leading
 *       {@code +}, a fraction and whitespace fail;</li>
 *   <li>{@code enum}: the value equals one of the declared values, case-sensitive.</li>
 * </ul>
 *
 * <p>A violation raises {@link BadRequestException}, which {@code GlobalExceptionHandler.badRequest}
 * answers with 400, {@code Content-Type: application/json} and the body
 * {@code { "message": "Bad request" }} [netsuite-api.xml:128-132]; the exception message names the
 * parameter and is not written to the response.
 *
 * <p>Not checked (D-046, D-675): request bodies, {@code Content-Type}, absent parameters,
 * undeclared parameters and parameters of any other RAML type. The console paths
 * {@code <base>/console} and {@code <base>/console/**} are not intercepted (D-009). A request whose
 * path and method match no RAML method passes unchecked and keeps Spring's 404 or 405 answer. A
 * path that no handler maps is not intercepted and keeps the 404
 * {@code { "message": "Resource not found" }}.
 *
 * <p>The path looked up in {@link RamlModel} is the request path within the application,
 * percent-decoded and without {@code ;} path parameters, with the base path removed. Query values
 * are read decoded from {@link HttpServletRequest#getParameterMap()}, form-body parameters the
 * container adds to it included (D-675).
 *
 * <p>Usage, as seen by a client of the running application:
 *
 * <pre>{@code
 * GET /api/items?quantity=abc                  -> 400 { "message": "Bad request" }
 * GET /api/items?quantity=                     -> 400 { "message": "Bad request" }
 * GET /api/items?operator=FOO                  -> 400 { "message": "Bad request" }
 * GET /api/items?quantity=5&quantity=x         -> 400 { "message": "Bad request" }
 * GET /api/items?operator=EQUAL_TO&quantity=5  -> passed to the controller
 * GET /api/items?quantity=-3                   -> passed to the controller
 * GET /api/customers?name=anything             -> passed to the controller
 * GET /api/console/                            -> not intercepted
 * }</pre>
 *
 * <p>The instance holds only its injected, immutable fields and is safe for concurrent use.
 */
@Component
public class RamlRequestValidator implements HandlerInterceptor, WebMvcConfigurer {

    /** Logs the active base path at INFO and each rejected parameter name at DEBUG. */
    private static final Logger LOG = LoggerFactory.getLogger(RamlRequestValidator.class);

    /** RAML 0.8 named-parameter type whose values are checked against {@link #INTEGER}. */
    private static final String INTEGER_TYPE = "integer";

    /** Whole-value pattern of an {@code integer} query value (D-675). */
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");

    /** Console path below the base path [netsuite-api.xml:13, {@code consolePath="console"}]. */
    private static final String CONSOLE_SEGMENT = "/console";

    /**
     * Read-only shared helper: the RAML lookup path is the request URI without the context path,
     * percent-decoded and without {@code ;} content (D-675).
     */
    private static final UrlPathHelper PATH_HELPER = UrlPathHelper.defaultInstance;

    /** Parsed {@code netsuite-api.raml}, read through {@link RamlModel#findMethod(String, String)}. */
    private final RamlModel ramlModel;

    /** Normalised base path: empty, or a leading {@code /} and no trailing {@code /}. */
    private final String basePath;

    /**
     * Creates the validator for the RAML contract {@code ramlModel} under {@code basePath}.
     *
     * <p>The base path is normalised once: surrounding whitespace and every trailing {@code /} are
     * removed and a leading {@code /} is added when missing.
     *
     * <pre>{@code
     * "/api"  -> "/api"
     * "api"   -> "/api"
     * "/api/" -> "/api"
     * "/"     -> ""
     * }</pre>
     *
     * @param ramlModel the parsed {@code netsuite-api.raml}
     * @param basePath  the listener base path, bound with {@code @Value} from the key
     *                  {@code listener.netsuite-api-http-listener-config.base-path}, {@code /api}
     *                  by default [netsuite-api.xml:21] (D-675)
     * @throws NullPointerException if {@code ramlModel} is {@code null}
     */
    public RamlRequestValidator(
            RamlModel ramlModel,
            @Value("${listener.netsuite-api-http-listener-config.base-path:/api}") String basePath) {
        this.ramlModel = Objects.requireNonNull(ramlModel, "ramlModel");
        this.basePath = normaliseBasePath(basePath);
        LOG.info("RAML query-parameter validation active under {}/**, console {}{} excluded",
                this.basePath, this.basePath, CONSOLE_SEGMENT);
    }

    /**
     * Registers this validator for {@code <base>/**}, excluding {@code <base>/console} and
     * {@code <base>/console/**}.
     *
     * @param registry the interceptor registry of Spring MVC
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        String consolePath = basePath + CONSOLE_SEGMENT;
        registry.addInterceptor(this)
                .addPathPatterns(basePath + "/**")
                .excludePathPatterns(consolePath, consolePath + "/**");
    }

    /**
     * Checks the declared query parameters of the RAML method that the request's path and method
     * match.
     *
     * <p>Steps:
     * <ol>
     *   <li>the path within the application, outside {@code <base>}, passes unchecked;</li>
     *   <li>the path after {@code <base>} (for example {@code /api/items} gives {@code /items}) and
     *       the HTTP method are looked up with {@link RamlModel#findMethod(String, String)}; no
     *       match passes unchecked;</li>
     *   <li>every value of each declared query parameter present in the request is checked, in RAML
     *       order; the first value that fails raises {@link BadRequestException}.</li>
     * </ol>
     *
     * @param request  the current request
     * @param response the current response, not written
     * @param handler  the handler Spring MVC selected, not inspected
     * @return {@code true} when the request passes
     * @throws BadRequestException 400 for a value that is not an {@code integer} of
     *                             {@code -?\d+} or is outside the declared {@code enum}; the message
     *                             is {@code Invalid query parameter: <name>}
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String relativePath = relativePath(PATH_HELPER.getPathWithinApplication(request));
        if (relativePath == null) {
            return true;
        }
        Optional<RamlModel.Method> method = ramlModel.findMethod(relativePath, request.getMethod());
        if (method.isEmpty()) {
            return true;
        }
        Map<String, String[]> parameters = request.getParameterMap();
        for (RamlModel.QueryParameter declared : method.get().queryParameters()) {
            String[] values = parameters.get(declared.name());
            if (values == null) {
                continue;
            }
            for (String value : values) {
                if (!isValid(declared, value)) {
                    LOG.debug("Rejected with 400: query parameter {} of {} {} breaks its RAML declaration",
                            declared.name(), method.get().method(), relativePath);
                    throw new BadRequestException("Invalid query parameter: " + declared.name());
                }
            }
        }
        return true;
    }

    /**
     * Tells whether {@code value} meets the RAML declaration {@code declared}: an {@code integer}
     * value matches {@code -?\d+} as a whole, and a value of a parameter with an {@code enum} equals
     * one of its values. A {@code null} value is checked as the empty value.
     *
     * <pre>{@code
     * quantity (integer): "5", "-3", "007" -> true; "", "abc", "+5", "1.5", " 5" -> false
     * operator (enum):    "EQUAL_TO"       -> true; "FOO", "equal_to", ""       -> false
     * name     (string):  any value        -> true
     * }</pre>
     *
     * @param declared the declared query parameter
     * @param value    one value of that parameter in the request
     * @return {@code true} when the value meets every check of the declaration
     */
    static boolean isValid(RamlModel.QueryParameter declared, String value) {
        String candidate = value == null ? "" : value;
        if (INTEGER_TYPE.equals(declared.type()) && !INTEGER.matcher(candidate).matches()) {
            return false;
        }
        return declared.enumValues() == null || declared.enumValues().contains(candidate);
    }

    /**
     * Returns the part of {@code pathWithinApplication} after the base path.
     *
     * <pre>{@code
     * base "/api": "/api/items" -> "/items"; "/api" -> ""; "/apix/items" -> null; "/items" -> null
     * base "":     "/items"     -> "/items"
     * }</pre>
     *
     * @param pathWithinApplication the decoded request path without the context path
     * @return the path after the base path, or {@code null} when the path is outside it
     */
    String relativePath(String pathWithinApplication) {
        if (pathWithinApplication == null) {
            return null;
        }
        if (basePath.isEmpty()) {
            return pathWithinApplication;
        }
        if (pathWithinApplication.equals(basePath)) {
            return "";
        }
        if (pathWithinApplication.startsWith(basePath + "/")) {
            return pathWithinApplication.substring(basePath.length());
        }
        return null;
    }

    /**
     * Normalises a configured base path: {@code null} and blank give the empty path; otherwise
     * surrounding whitespace and every trailing {@code /} are removed and a leading {@code /} is
     * added when missing.
     *
     * @param basePath the configured base path, possibly {@code null}
     * @return the empty path, or a path with a leading {@code /} and no trailing {@code /}
     */
    static String normaliseBasePath(String basePath) {
        String normalised = basePath == null ? "" : basePath.trim();
        int end = normalised.length();
        while (end > 0 && normalised.charAt(end - 1) == '/') {
            end--;
        }
        normalised = normalised.substring(0, end);
        if (normalised.isEmpty()) {
            return "";
        }
        return normalised.startsWith("/") ? normalised : "/" + normalised;
    }
}
