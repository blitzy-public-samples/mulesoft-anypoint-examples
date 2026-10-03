package com.mulesoft.examples.testing_apikit_with_munit.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.fge.jackson.JsonLoader;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import com.mulesoft.examples.testing_apikit_with_munit.exception.BadRequestException;
import com.mulesoft.examples.testing_apikit_with_munit.exception.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

/**
 * Validates every request under the APIkit router's listener base path against the RAML contract
 * that {@link RamlModel} reads, before Spring MVC invokes the resource controller. It reproduces
 * the request validation of the APIkit router {@code api-config}
 * [testing-apikit-with-munit/src/main/app/api.xml:3 ({@code apikit:config}), :41 ({@code apikit:router}
 * in flow {@code api-main}, listener {@code api/*} at :39)]. The class is project-local (D-004).
 *
 * <p>{@link #addInterceptors(InterceptorRegistry)} registers this instance for
 * {@code <base-path>/**} and excludes the console routes {@code <base-path>/<console-path>} and
 * {@code <base-path>/<console-path>/**} ({@code /api/console}, {@code /api/console/},
 * {@code /api/console/api.json}, {@code /api/console/<file>}) (D-009).
 *
 * <p>{@link #preHandle(HttpServletRequest, HttpServletResponse, Object)} matches the request path,
 * relative to the context path and the base path, against the RAML resources: a resource whose path
 * has no URI parameter and equals the request path is taken first, otherwise the first resource, in
 * RAML file order, whose path template matches (D-537). It then runs four checks, in this order, and
 * throws on the first violation:
 *
 * <table>
 *   <caption>Checks and the exceptions they raise</caption>
 *   <tr><th>Check</th><th>Violation raises</th><th>Answer of GlobalExceptionHandler</th></tr>
 *   <tr><td>No resource matches the path</td><td>{@link NotFoundException}</td><td>404</td></tr>
 *   <tr><td>1. The method is declared on the resource; {@code HEAD} and {@code OPTIONS} are
 *       rejected unless the RAML declares them (D-397)</td>
 *       <td>{@link HttpRequestMethodNotSupportedException}</td><td>405</td></tr>
 *   <tr><td>2. The {@code Content-Type} is one of the method's declared request media types (type
 *       and subtype, case-insensitive, parameters ignored), checked only when the method declares a
 *       request body</td>
 *       <td>{@link HttpMediaTypeNotSupportedException}</td><td>415</td></tr>
 *   <tr><td>3. Every declared URI parameter that the path captures and every declared query
 *       parameter satisfies its {@code required}, {@code type}, {@code enum}, {@code minLength} and
 *       {@code maxLength}</td><td>{@link BadRequestException}</td><td>400</td></tr>
 *   <tr><td>4. The request body satisfies the JSON schema of the matched request media type,
 *       draft-03 included, validated with json-schema-validator 2.2.14 (D-046)</td>
 *       <td>{@link BadRequestException}</td><td>400</td></tr>
 * </table>
 *
 * <p>The exceptions reach the project's {@code exception.GlobalExceptionHandler} through
 * {@code DispatcherServlet}; this class writes no response. A request that passes every check
 * continues to its controller. A request path that no handler maps raises
 * {@code NoHandlerFoundException} in Spring MVC before any interceptor runs.
 *
 * <p>The instance is stateless: the resource patterns and the schema factory are built once by the
 * constructor and never change, and every request is validated with local variables only.
 *
 * <p>Answers for the copied {@code api.raml}, which declares {@code get}, {@code post}, {@code put}
 * and {@code delete} on {@code /munit} with no request body, parameter or schema:
 *
 * <pre>{@code
 * GET     /api/munit          -> passes; MunitController answers 200
 * POST    /api/munit          -> passes for any Content-Type or none; MunitController answers 201
 * GET     /api/munit?foo=bar  -> passes (undeclared query parameters are not checked)
 * HEAD    /api/munit          -> HttpRequestMethodNotSupportedException -> 405 (D-397)
 * OPTIONS /api/munit          -> HttpRequestMethodNotSupportedException -> 405 (D-397)
 * GET     /api/console/       -> not intercepted (D-009)
 * }</pre>
 */
@Component
public class RamlRequestValidator implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(RamlRequestValidator.class);

    /** One URI parameter placeholder {@code {name}} of a RAML resource path; group 1 is the name. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^}/]+)\\}");

    /** Regular expression of one URI parameter value: one or more characters other than {@code /}. */
    private static final String PLACEHOLDER_VALUE = "([^/]+)";

    /** Listener base path of the router, without a trailing {@code /}; {@code ""} for the root. */
    private final String basePath;

    /** Console path below the base path, without a leading or trailing {@code /}. */
    private final String consolePath;

    /** Every RAML resource by its anchored path pattern, in RAML file order; unmodifiable. */
    private final Map<Pattern, RamlModel.RamlResource> resourcePatterns;

    /**
     * Every RAML resource whose path holds no opening brace, by that path, the first in RAML file
     * order for a path declared twice; unmodifiable.
     */
    private final Map<String, RamlModel.RamlResource> literalResources;

    /** Factory of the request-body JSON schemas; honours each schema's {@code $schema} (D-046). */
    private final JsonSchemaFactory jsonSchemaFactory;

    /**
     * Builds the resource patterns of the RAML contract.
     *
     * <p>Each resource path becomes an anchored pattern ({@code ^...$}): each literal part is
     * quoted with {@link Pattern#quote(String)} and each {@code {name}} placeholder matches
     * {@code ([^/]+)}. For example {@code /teams/{teamId}} becomes {@code ^\Q/teams/\E([^/]+)$}.
     *
     * @param ramlModel   the RAML contract; read through {@link RamlModel#consoleModel()} only
     * @param basePath    listener base path of the router ({@code apikit.api-config.base-path},
     *                    {@code /api}); trailing {@code /} characters are removed and a missing
     *                    leading {@code /} of a non-empty value is added
     * @param consolePath console path below the base path ({@code apikit.api-config.console-path},
     *                    {@code console}); leading and trailing {@code /} characters are removed
     * @throws NullPointerException if an argument is {@code null}
     */
    public RamlRequestValidator(RamlModel ramlModel,
                                @Value("${apikit.api-config.base-path}") String basePath,
                                @Value("${apikit.api-config.console-path}") String consolePath) {
        Objects.requireNonNull(ramlModel, "ramlModel");
        this.basePath = normaliseBasePath(Objects.requireNonNull(basePath, "basePath"));
        this.consolePath = normaliseConsolePath(Objects.requireNonNull(consolePath, "consolePath"));

        Map<Pattern, RamlModel.RamlResource> patterns = new LinkedHashMap<>();
        Map<String, RamlModel.RamlResource> literals = new LinkedHashMap<>();
        for (RamlModel.RamlResource resource : ramlModel.consoleModel().resources()) {
            patterns.put(pathPattern(resource.path()), resource);
            if (resource.path().indexOf('{') < 0) {
                literals.putIfAbsent(resource.path(), resource);
            }
        }
        this.resourcePatterns = Collections.unmodifiableMap(patterns);
        this.literalResources = Collections.unmodifiableMap(literals);
        this.jsonSchemaFactory = JsonSchemaFactory.byDefault();

        LOG.info("Validating requests under '{}' against {} RAML resource(s); console path '{}' excluded",
                this.basePath, this.resourcePatterns.size(), this.consolePath);
    }

    /**
     * Registers this instance as a Spring MVC interceptor for {@code <base-path>/**}, excluding
     * {@code <base-path>/<console-path>} and {@code <base-path>/<console-path>/**} (D-009). An empty
     * console path excludes nothing.
     *
     * @param registry the interceptor registry of the Spring MVC configuration
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        InterceptorRegistration registration = registry.addInterceptor(this).addPathPatterns(basePath + "/**");
        if (!consolePath.isEmpty()) {
            String console = basePath + "/" + consolePath;
            registration.excludePathPatterns(console, console + "/**");
        }
    }

    /**
     * Validates the request against the RAML contract: resource match, then checks 1 to 4 of the
     * class description, in order.
     *
     * <p>The path validated is {@link HttpServletRequest#getRequestURI()} without
     * {@link HttpServletRequest#getContextPath()} and without the base path, undecoded. A path
     * outside the base path is not validated. Values captured for URI parameters, and query
     * parameter names and values, are percent-decoded as UTF-8 with
     * {@link UriUtils#decode(String, Charset)}; a value that does not decode raises
     * {@link BadRequestException}. Query parameters are read from
     * {@link HttpServletRequest#getQueryString()} only, never from a form body (D-537).
     *
     * @param request  the current request
     * @param response the current response; not written
     * @param handler  the handler Spring MVC selected; not read
     * @return {@code true} when the request satisfies the contract or lies outside the base path
     * @throws NotFoundException                       if no RAML resource matches the path
     * @throws HttpRequestMethodNotSupportedException if the resource does not declare the method,
     *                                                 {@code HEAD} and {@code OPTIONS} included (D-397)
     * @throws HttpMediaTypeNotSupportedException     if the method declares a request body and the
     *                                                 {@code Content-Type} is missing, does not parse
     *                                                 or is not declared
     * @throws BadRequestException                     if a URI or query parameter, or the request body,
     *                                                 violates its declaration (D-046)
     * @throws IOException                             if the request body cannot be read
     * @throws IllegalStateException                   if a declared request media type or request
     *                                                 schema of the matched method is not a media type
     *                                                 or not JSON
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = apiPath(request);
        if (path == null) {
            return true;
        }
        Map<String, List<String>> uriValues = new LinkedHashMap<>();
        RamlModel.RamlResource resource = matchResource(path, uriValues);

        String method = request.getMethod().toUpperCase(Locale.ROOT);
        String where = method + " " + resource.path();
        RamlModel.RamlMethod ramlMethod = declaredMethod(resource, method);
        RamlModel.RamlBody body = declaredRequestBody(request, method, where, ramlMethod);
        validateUriParameters(where, resource, uriValues);
        validateQueryParameters(where, ramlMethod, request.getQueryString());
        if (body != null && body.schema() != null) {
            validateBody(request, where, body);
        }
        return true;
    }

    /**
     * {@return the request path relative to the context path and the base path, undecoded;
     * {@code null} when the path is not the base path or below it}
     */
    private String apiPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = StringUtils.hasLength(contextPath) && uri.startsWith(contextPath)
                ? uri.substring(contextPath.length())
                : uri;
        if (!path.startsWith(basePath)) {
            return null;
        }
        String relative = path.substring(basePath.length());
        if (!relative.isEmpty() && relative.charAt(0) != '/') {
            return null;
        }
        return relative;
    }

    /**
     * Finds the RAML resource of a request path: the resource whose path holds no opening brace and
     * equals {@code path}, otherwise the first resource, in RAML file order, whose pattern matches.
     * Adds the decoded value of each captured URI parameter to {@code uriValues}, under the
     * placeholder name, in placeholder order.
     *
     * @param path      request path relative to the base path, undecoded
     * @param uriValues receives the captured URI parameter values by name
     * @return the matched resource
     * @throws NotFoundException   naming {@code path} if no resource matches
     * @throws BadRequestException if a captured value does not percent-decode
     */
    private RamlModel.RamlResource matchResource(String path, Map<String, List<String>> uriValues) {
        RamlModel.RamlResource literal = literalResources.get(path);
        if (literal != null) {
            return literal;
        }
        for (Map.Entry<Pattern, RamlModel.RamlResource> entry : resourcePatterns.entrySet()) {
            Matcher matcher = entry.getKey().matcher(path);
            if (matcher.matches()) {
                List<String> names = placeholderNames(entry.getValue().path());
                for (int group = 1; group <= matcher.groupCount() && group <= names.size(); group++) {
                    String name = names.get(group - 1);
                    List<String> values = uriValues.get(name);
                    if (values == null) {
                        values = new ArrayList<>();
                        uriValues.put(name, values);
                    }
                    values.add(decode(matcher.group(group), "URI parameter " + name));
                }
                return entry.getValue();
            }
        }
        throw new NotFoundException("No RAML resource matches the path " + path);
    }

    /**
     * Check 1: rejects a method the RAML does not declare on the matched resource with
     * {@link HttpRequestMethodNotSupportedException}, listing the declared methods in RAML file
     * order. {@code HEAD} and {@code OPTIONS} on a resource that declares neither are rejected
     * (D-397).
     *
     * @param resource the matched resource
     * @param method   the request method, upper case
     * @return the declared method
     * @throws HttpRequestMethodNotSupportedException if the resource does not declare {@code method}
     */
    private static RamlModel.RamlMethod declaredMethod(RamlModel.RamlResource resource, String method)
            throws HttpRequestMethodNotSupportedException {
        List<String> declared = new ArrayList<>(resource.methods().size());
        for (RamlModel.RamlMethod candidate : resource.methods()) {
            if (candidate.method().equals(method)) {
                return candidate;
            }
            declared.add(candidate.method());
        }
        throw new HttpRequestMethodNotSupportedException(method, declared);
    }

    /**
     * Check 2: when the method declares request bodies, rejects a request whose {@code Content-Type}
     * is missing, does not parse or is not one of the declared media types with
     * {@link HttpMediaTypeNotSupportedException}. Media types are compared by type and subtype,
     * case-insensitively, with parameters ignored.
     *
     * @param request    the current request
     * @param method     the request method, upper case
     * @param where      the method and resource path, for messages
     * @param ramlMethod the declared method
     * @return the declared request body whose media type the {@code Content-Type} matches;
     *         {@code null} when the method declares no request body
     * @throws HttpMediaTypeNotSupportedException if the {@code Content-Type} is missing, does not
     *                                            parse or matches no declared media type
     * @throws IllegalStateException              if a declared media type does not parse
     */
    private static RamlModel.RamlBody declaredRequestBody(HttpServletRequest request, String method, String where,
                                                          RamlModel.RamlMethod ramlMethod)
            throws HttpMediaTypeNotSupportedException {
        List<RamlModel.RamlBody> declared = ramlMethod.request();
        if (declared.isEmpty()) {
            return null;
        }
        List<MediaType> supported = new ArrayList<>(declared.size());
        for (RamlModel.RamlBody body : declared) {
            try {
                supported.add(MediaType.parseMediaType(body.mediaType()));
            } catch (InvalidMediaTypeException ex) {
                // A RAML request media type that does not parse raises IllegalStateException (500, D-537).
                throw new IllegalStateException(
                        "RAML request media type of " + where + " is not a media type: " + body.mediaType(), ex);
            }
        }
        List<MediaType> supportedView = Collections.unmodifiableList(supported);

        String header = request.getContentType();
        if (!StringUtils.hasText(header)) {
            throw new HttpMediaTypeNotSupportedException((MediaType) null, supportedView, HttpMethod.valueOf(method));
        }
        MediaType contentType;
        try {
            contentType = MediaType.parseMediaType(header);
        } catch (InvalidMediaTypeException ex) {
            throw new HttpMediaTypeNotSupportedException(ex.getMessage());
        }
        for (int index = 0; index < supported.size(); index++) {
            if (supported.get(index).equalsTypeAndSubtype(contentType)) {
                return declared.get(index);
            }
        }
        throw new HttpMediaTypeNotSupportedException(contentType, supportedView, HttpMethod.valueOf(method));
    }

    /**
     * Check 3, URI parameters: validates each declared URI parameter of the resource that the path
     * captured, with its decoded value. A declared URI parameter that the path does not capture is
     * not checked.
     *
     * @param where     the method and resource path, for messages
     * @param resource  the matched resource
     * @param uriValues the decoded captured values by placeholder name
     * @throws BadRequestException if a captured value violates its declaration
     */
    private static void validateUriParameters(String where, RamlModel.RamlResource resource,
                                              Map<String, List<String>> uriValues) {
        for (RamlModel.RamlParameter parameter : resource.uriParameters()) {
            List<String> values = uriValues.get(parameter.name());
            if (values != null) {
                for (String value : values) {
                    validateValue("URI parameter", where, parameter, value);
                }
            }
        }
    }

    /**
     * Check 3, query parameters: validates each declared query parameter of the method against the
     * parameters of the query string. An absent required parameter raises
     * {@link BadRequestException}; an absent optional parameter is not checked; each value of a
     * present parameter is checked. A parameter without {@code =} has the value {@code ""}.
     * Undeclared query parameters are not checked.
     *
     * @param where       the method and resource path, for messages
     * @param ramlMethod  the declared method
     * @param queryString the raw query string of the request, or {@code null}
     * @throws BadRequestException if a required parameter is absent, or a name or value does not
     *                             percent-decode or violates its declaration
     */
    private static void validateQueryParameters(String where, RamlModel.RamlMethod ramlMethod, String queryString) {
        if (ramlMethod.queryParameters().isEmpty()) {
            return;
        }
        Map<String, List<String>> query = queryParameters(queryString);
        for (RamlModel.RamlParameter parameter : ramlMethod.queryParameters()) {
            List<String> values = query.get(parameter.name());
            if (values == null) {
                if (parameter.required()) {
                    throw new BadRequestException(
                            "Required query parameter " + parameter.name() + " of " + where + " is missing");
                }
                continue;
            }
            for (String value : values) {
                validateValue("Query parameter", where, parameter, value);
            }
        }
    }

    /**
     * {@return the parameters of a raw query string by decoded name, each with its decoded values in
     * query order; an empty map for a {@code null} or empty query string}
     *
     * @throws BadRequestException if a name or value does not percent-decode
     */
    private static Map<String, List<String>> queryParameters(String queryString) {
        if (!StringUtils.hasLength(queryString)) {
            return Map.of();
        }
        MultiValueMap<String, String> raw =
                UriComponentsBuilder.newInstance().query(queryString).build().getQueryParams();
        Map<String, List<String>> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            String name = decode(entry.getKey(), "Query parameter name");
            List<String> values = decoded.get(name);
            if (values == null) {
                values = new ArrayList<>();
                decoded.put(name, values);
            }
            for (String value : entry.getValue()) {
                values.add(value == null ? "" : decode(value, "Query parameter " + name));
            }
        }
        return decoded;
    }

    /**
     * Validates one decoded parameter value against its RAML declaration:
     * <ul>
     *   <li>{@code type}: {@code integer} parses as {@link BigInteger}, {@code number} as
     *       {@link BigDecimal}, {@code boolean} is exactly {@code true} or {@code false}, and
     *       {@code date} parses with {@link DateTimeFormatter#RFC_1123_DATE_TIME}; {@code string},
     *       {@code file} and any other type are not checked;</li>
     *   <li>{@code enum}, when declared, contains the value;</li>
     *   <li>{@code minLength} and {@code maxLength}, when declared, bound {@link String#length()}.</li>
     * </ul>
     *
     * @param kind      {@code URI parameter} or {@code Query parameter}, for messages
     * @param where     the method and resource path, for messages
     * @param parameter the declaration
     * @param value     the decoded value
     * @throws BadRequestException naming the parameter and the violated facet
     */
    private static void validateValue(String kind, String where, RamlModel.RamlParameter parameter, String value) {
        String label = kind + " " + parameter.name() + " of " + where;
        if (!hasType(parameter.type(), value)) {
            throw new BadRequestException(label + " is not of type " + parameter.type());
        }
        if (parameter.enumValues() != null && !parameter.enumValues().contains(value)) {
            throw new BadRequestException(label + " is not one of " + parameter.enumValues());
        }
        if (parameter.minLength() != null && value.length() < parameter.minLength()) {
            throw new BadRequestException(label + " is shorter than " + parameter.minLength() + " characters");
        }
        if (parameter.maxLength() != null && value.length() > parameter.maxLength()) {
            throw new BadRequestException(label + " is longer than " + parameter.maxLength() + " characters");
        }
    }

    /**
     * {@return whether {@code value} is of the RAML named-parameter {@code type}; {@code true} for
     * {@code string}, {@code file}, {@code null} and every other type}
     */
    private static boolean hasType(String type, String value) {
        if (type == null) {
            return true;
        }
        try {
            switch (type) {
                case "integer" -> new BigInteger(value);
                case "number" -> new BigDecimal(value);
                case "boolean" -> {
                    return "true".equals(value) || "false".equals(value);
                }
                case "date" -> DateTimeFormatter.RFC_1123_DATE_TIME.parse(value);
                default -> {
                    return true;
                }
            }
            return true;
        } catch (NumberFormatException | DateTimeParseException ex) {
            return false;
        }
    }

    /**
     * Check 4: validates the request body against the JSON schema of the matched request media type
     * with json-schema-validator 2.2.14, whose factory applies the draft named by the schema's
     * {@code $schema}, draft-03 included (D-046).
     *
     * <p>The body bytes are read from {@link HttpServletRequest#getInputStream()} and decoded with the
     * {@code charset} of the {@code Content-Type}, or UTF-8 when it names none. The body and the
     * schema are parsed with {@link JsonLoader#fromString(String)}.
     *
     * @param request the current request; its {@code Content-Type} parsed in check 2
     * @param where   the method and resource path, for messages
     * @param body    the matched request body declaration, with a schema
     * @throws BadRequestException   if the body is not JSON, the validator raises
     *                               {@link ProcessingException}, or the report is not a success
     * @throws IOException           if the request body cannot be read
     * @throws IllegalStateException if the schema text is not JSON
     */
    private void validateBody(HttpServletRequest request, String where, RamlModel.RamlBody body) throws IOException {
        JsonNode schemaNode;
        try {
            schemaNode = JsonLoader.fromString(body.schema());
        } catch (IOException ex) {
            // A RAML request schema that is not JSON raises IllegalStateException, answered with 500 (D-537).
            throw new IllegalStateException(
                    "RAML request schema of " + where + " " + body.mediaType() + " is not JSON", ex);
        }

        Charset declaredCharset = MediaType.parseMediaType(request.getContentType()).getCharset();
        Charset charset = declaredCharset == null ? StandardCharsets.UTF_8 : declaredCharset;
        byte[] bytes = StreamUtils.copyToByteArray(request.getInputStream());
        JsonNode instanceNode;
        try {
            instanceNode = JsonLoader.fromString(new String(bytes, charset));
        } catch (IOException ex) {
            throw new BadRequestException("Request body of " + where + " is not JSON");
        }

        ProcessingReport report;
        try {
            JsonSchema schema = jsonSchemaFactory.getJsonSchema(schemaNode);
            report = schema.validate(instanceNode);
        } catch (ProcessingException ex) {
            throw new BadRequestException(
                    "Request body of " + where + " cannot be validated against its schema: " + ex.getMessage());
        }
        if (!report.isSuccess()) {
            throw new BadRequestException("Request body of " + where + " does not match its schema");
        }
    }

    /**
     * {@return {@code raw} percent-decoded as UTF-8}
     *
     * @param what the decoded item, for the message
     * @throws BadRequestException if {@code raw} holds an invalid percent-encoded sequence
     */
    private static String decode(String raw, String what) {
        try {
            return UriUtils.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException(what + " is not validly percent-encoded");
        }
    }

    /**
     * {@return the names of the {@code {name}} placeholders of a RAML resource path, in path order}
     */
    private static List<String> placeholderNames(String resourcePath) {
        List<String> names = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(resourcePath);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /**
     * {@return the anchored pattern of a RAML resource path: each literal part quoted, each
     * {@code {name}} placeholder replaced by {@code ([^/]+)}}
     */
    private static Pattern pathPattern(String resourcePath) {
        StringBuilder regex = new StringBuilder("^");
        Matcher matcher = PLACEHOLDER.matcher(resourcePath);
        int literalStart = 0;
        while (matcher.find()) {
            appendLiteral(regex, resourcePath.substring(literalStart, matcher.start()));
            regex.append(PLACEHOLDER_VALUE);
            literalStart = matcher.end();
        }
        appendLiteral(regex, resourcePath.substring(literalStart));
        return Pattern.compile(regex.append('$').toString());
    }

    /** Appends {@code literal}, quoted with {@link Pattern#quote(String)}, when it is not empty. */
    private static void appendLiteral(StringBuilder regex, String literal) {
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal));
        }
    }

    /**
     * {@return {@code basePath} without trailing {@code /} characters, with a leading {@code /} when
     * the result is not empty and lacks one}
     */
    private static String normaliseBasePath(String basePath) {
        int end = basePath.length();
        while (end > 0 && basePath.charAt(end - 1) == '/') {
            end--;
        }
        String path = basePath.substring(0, end);
        return path.isEmpty() || path.startsWith("/") ? path : "/" + path;
    }

    /** {@return {@code consolePath} without leading or trailing {@code /} characters} */
    private static String normaliseConsolePath(String consolePath) {
        int start = 0;
        int end = consolePath.length();
        while (start < end && consolePath.charAt(start) == '/') {
            start++;
        }
        while (end > start && consolePath.charAt(end - 1) == '/') {
            end--;
        }
        return consolePath.substring(start, end);
    }
}
