package com.mulesoft.examples.rest_api_with_apikit.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonschema.SchemaVersion;
import com.github.fge.jsonschema.cfg.ValidationConfiguration;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.LogLevel;
import com.github.fge.jsonschema.core.report.ProcessingMessage;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import com.mulesoft.examples.rest_api_with_apikit.exception.BadRequestException;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.util.StreamUtils;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.util.UriUtils;

/**
 * Request validation of the APIkit router {@code leagues-config}: every request under the RAML base path
 * ({@code /api}) that matches a RAML resource is checked against {@code leagues.raml} before its handler
 * method runs. The console under {@code /api/console} is not checked (D-009).
 *
 * <p>Checks and their answers, each raised as an exception that {@code GlobalExceptionHandler} renders
 * with the APIkit body:
 * <ul>
 *   <li>a method the RAML resource does not declare, {@code HEAD} and {@code OPTIONS} included:
 *       {@link HttpRequestMethodNotSupportedException}, 405 {@code { "message": "Method not allowed" }};</li>
 *   <li>a URI parameter that breaks its RAML type, {@code enum}, {@code minLength} or {@code maxLength}
 *       ({@code teamId}, {@code homeTeamId} and {@code awayTeamId} are exactly 3 characters), a required
 *       query parameter that is absent, a query value that breaks its declaration, or a malformed escape in
 *       the query string: {@link BadRequestException}, 400 {@code { "message": "Bad request" }};</li>
 *   <li>on a method that declares request bodies, a missing or unparsable {@code Content-Type}, or one whose
 *       type and subtype equal none of the declared media types (parameters such as {@code charset} are
 *       ignored): {@link HttpMediaTypeNotSupportedException}, 415
 *       {@code { "message": "Unsupported media type" }};</li>
 *   <li>on a method whose matched request body declares a schema, an absent or blank body, a body that is
 *       not exactly one JSON value (content after the root value included), or a body the draft-03 schema
 *       rejects: {@link BadRequestException}, 400 (D-046). Schema warnings, such as the unknown keyword
 *       {@code name}, do not reject. A {@code "required": true} at the schema root, as in
 *       {@code match-schema-input.json}, rejects only an absent body.</li>
 * </ul>
 *
 * <p>Query values are read from the raw query string; request parameters of the servlet request, which
 * can include a form body, are never read. Undeclared query parameters are ignored. A request whose path
 * matches no RAML resource passes unchecked and reaches Spring's 404 handling.
 *
 * <p>The draft-03 schemas of {@code leagues.raml} checked here are {@code teams-schema-output.json}
 * ({@code POST /teams}), {@code teamid-schema-input.json} ({@code PUT /teams/{teamId}}) and
 * {@code match-schema-input.json} ({@code PUT /fixture/{homeTeamId}/{awayTeamId}}); response schemas are
 * never checked (D-046). The body bytes passed to Jackson for binding are the bytes that were validated.
 *
 * <p>The instance is immutable after construction and safe for concurrent use; the compiled schema of a
 * request travels from {@link #preHandle} to {@link #beforeBodyRead} as a request attribute.
 */
@ControllerAdvice
public class RamlRequestValidator extends RequestBodyAdviceAdapter implements HandlerInterceptor, WebMvcConfigurer {

    private static final Logger LOG = LoggerFactory.getLogger(RamlRequestValidator.class);

    /** Request attribute holding the compiled {@link JsonSchema} of the matched RAML request body. */
    private static final String SCHEMA_ATTRIBUTE = RamlRequestValidator.class.getName() + ".schema";

    /** Path of Spring Boot's error controller. */
    private static final String ERROR_PATH = "/error";

    /** Reads schemas and request bodies; content after the root JSON value is a parse error. */
    private final ObjectMapper jsonReader = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final RamlModel model;
    private final String basePath;
    private final String consolePrefix;

    /** Parsed declared media type of each RAML request body, keyed by resource path, verb and media-type key. */
    private final Map<List<String>, MediaType> declaredMediaTypes;

    /** Compiled draft-03 schema of each RAML request body that declares one, keyed like {@link #declaredMediaTypes}. */
    private final Map<List<String>, JsonSchema> schemas;

    /**
     * Reads the RAML request bodies of {@code model} and compiles each declared schema as a draft-03 JSON
     * schema (D-046).
     *
     * @param model the parsed RAML contract
     * @param consolePath the console path below the base path, {@code console} by default (D-009)
     * @throws IllegalStateException if a request-body media type of the RAML does not parse, or a request
     *     schema is not one JSON value, is not a valid draft-03 schema or does not compile; the message
     *     names the resource, the method and the media type
     */
    public RamlRequestValidator(RamlModel model,
                                @Value("${apikit.leagues-config.console-path}") String consolePath) {
        this.model = model;
        this.basePath = model.basePath();
        this.consolePrefix = basePath + "/" + consolePath;

        JsonSchemaFactory factory = JsonSchemaFactory.newBuilder()
                .setValidationConfiguration(ValidationConfiguration.newBuilder()
                        .setDefaultVersion(SchemaVersion.DRAFTV3)
                        .freeze())
                .freeze();
        Map<List<String>, MediaType> mediaTypes = new HashMap<>();
        Map<List<String>, JsonSchema> compiled = new HashMap<>();
        for (RamlModel.Resource resource : model.resources()) {
            for (RamlModel.Method method : resource.methods()) {
                for (RamlModel.Body body : method.body()) {
                    String where = describe(resource, method, body);
                    MediaType declared = parseDeclaredMediaType(body.mediaType(), where);
                    List<String> key = bodyKey(resource, method, body);
                    mediaTypes.putIfAbsent(key, declared);
                    if (body.schema() != null && !body.schema().isBlank()) {
                        compiled.putIfAbsent(key, compileSchema(factory, body.schema(), where));
                    }
                }
            }
        }
        this.declaredMediaTypes = Collections.unmodifiableMap(mediaTypes);
        this.schemas = Collections.unmodifiableMap(compiled);
        LOG.info("RAML request validation active under {}: {} request bodies, {} draft-03 schemas",
                basePath.isEmpty() ? "/" : basePath, declaredMediaTypes.size(), schemas.size());
    }

    /**
     * Registers this validator for every path under the RAML base path except the console path and the
     * paths below it (D-009).
     *
     * @param registry the interceptor registry of Spring MVC
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this)
                .addPathPatterns(basePath + "/**")
                .excludePathPatterns(consolePrefix, consolePrefix + "/**");
    }

    /**
     * Checks a request against the RAML resource its path matches, in this order: the method, the URI
     * parameters, the query parameters and the request media type. When the matched request body declares
     * a schema, the compiled schema is stored for {@link #beforeBodyRead}.
     *
     * <p>Error dispatches, {@code /error}, the console path and the paths below it (D-009), paths outside
     * the base path and paths that match no RAML resource pass unchecked. A path matches a resource when it
     * has as many {@code /} segments as the resource's full path template, each literal segment equals the
     * template segment and each {@code {name}} segment is non-empty; of several matching templates, the one
     * with more literal segments wins, then the one first in RAML order. Segment values are percent-decoded
     * as UTF-8, and a {@code ;} path parameter of a segment is not part of its value.
     *
     * @param request the current request
     * @param response the current response, not written
     * @param handler the handler Spring MVC selected, not inspected
     * @return {@code true} when the request passes
     * @throws HttpRequestMethodNotSupportedException 405 for a method the matched resource does not
     *     declare, {@code HEAD} and {@code OPTIONS} included; the declared methods are listed upper-case
     * @throws BadRequestException 400 for a URI or query parameter that breaks its RAML declaration, a
     *     missing required query parameter or a malformed escape in a URI parameter or the query string
     * @throws HttpMediaTypeNotSupportedException 415 for a missing, unparsable or undeclared
     *     {@code Content-Type} on a method that declares request bodies
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            return true;
        }
        String path = pathWithinApplication(request);
        if (ERROR_PATH.equals(path) || isConsolePath(path) || !isUnderBasePath(path)) {
            return true;
        }
        Map<String, String> rawUriValues = new LinkedHashMap<>();
        RamlModel.Resource resource = matchResource(path.substring(basePath.length()), rawUriValues);
        if (resource == null) {
            return true;
        }

        RamlModel.Method method = resource.method(request.getMethod().toLowerCase(Locale.ROOT)).orElse(null);
        if (method == null) {
            List<String> declaredVerbs = resource.methods().stream()
                    .map(declared -> declared.verb().toUpperCase(Locale.ROOT))
                    .toList();
            LOG.debug("Rejected with 405: resource {} declares only {}", resource.path(), declaredVerbs);
            throw new HttpRequestMethodNotSupportedException(request.getMethod(), declaredVerbs);
        }

        checkUriParameters(resource, rawUriValues);
        checkQueryParameters(method, request.getQueryString());
        JsonSchema schema = checkMediaType(request, resource, method);
        if (schema != null) {
            request.setAttribute(SCHEMA_ATTRIBUTE, schema);
        }
        return true;
    }

    /**
     * Applies this advice to every {@code @RequestBody} argument; {@link #beforeBodyRead} and
     * {@link #handleEmptyBody} check only requests for which {@link #preHandle} stored a schema.
     *
     * @param methodParameter the handler method parameter
     * @param targetType the target type of the body
     * @param converterType the selected message converter
     * @return {@code true}
     */
    @Override
    public boolean supports(MethodParameter methodParameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    /**
     * Validates the raw request body against the draft-03 schema {@link #preHandle} stored for the
     * request, before Jackson binds it (D-046). A request without a stored schema is returned unchanged.
     *
     * @param inputMessage the request body and headers
     * @param parameter the handler method parameter
     * @param targetType the target type of the body
     * @param converterType the selected message converter
     * @return {@code inputMessage} when no schema is stored; otherwise a message with the same headers whose
     *     body is the validated bytes
     * @throws IOException if the request body cannot be read
     * @throws BadRequestException 400 for a blank body, a body that is not exactly one JSON value (content
     *     after the root value included) or a body the schema rejects; schema warnings do not reject
     */
    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage inputMessage, MethodParameter parameter,
                                           Type targetType, Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        JsonSchema schema = currentSchema();
        if (schema == null) {
            return inputMessage;
        }
        byte[] bytes = StreamUtils.copyToByteArray(inputMessage.getBody());
        validateBody(schema, bytes);
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(bytes);
            }

            @Override
            public HttpHeaders getHeaders() {
                return inputMessage.getHeaders();
            }
        };
    }

    /**
     * Rejects an absent request body when {@link #preHandle} stored a schema for the request; a schema's
     * root {@code "required": true}, as in {@code match-schema-input.json}, is enforced this way (D-046).
     *
     * @param body the body Spring MVC uses for an absent body, usually {@code null}
     * @param inputMessage the request body and headers
     * @param parameter the handler method parameter
     * @param targetType the target type of the body
     * @param converterType the selected message converter
     * @return {@code body} when no schema is stored
     * @throws BadRequestException 400 when a schema is stored
     */
    @Override
    public Object handleEmptyBody(Object body, HttpInputMessage inputMessage, MethodParameter parameter,
                                  Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        if (currentSchema() != null) {
            throw badRequest("Request body is missing");
        }
        return body;
    }

    /**
     * Finds the RAML resource whose full path template matches {@code relativePath}.
     *
     * @param relativePath the request path after the base path, for example {@code /teams/BAR}
     * @param rawUriValues receives the raw, still percent-encoded value of each {@code {name}} segment of
     *     the matched template under its RAML name
     * @return the matched resource, or {@code null} when none matches
     */
    private RamlModel.Resource matchResource(String relativePath, Map<String, String> rawUriValues) {
        String[] segments = relativePath.split("/", -1);
        RamlModel.Resource best = null;
        Map<String, String> bestValues = Map.of();
        int bestLiterals = -1;
        for (RamlModel.Resource resource : model.resources()) {
            String[] template = resource.path().split("/", -1);
            if (template.length != segments.length) {
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            int literals = 0;
            boolean matches = true;
            for (int i = 0; i < template.length && matches; i++) {
                String value = withoutPathParameters(segments[i]);
                String name = uriParameterName(template[i]);
                if (name == null) {
                    literals++;
                    matches = template[i].equals(decodeOrNull(value));
                } else if (value.isEmpty()) {
                    matches = false;
                } else {
                    values.put(name, value);
                }
            }
            if (matches && literals > bestLiterals) {
                best = resource;
                bestValues = values;
                bestLiterals = literals;
            }
        }
        rawUriValues.putAll(bestValues);
        return best;
    }

    /**
     * Checks each URI parameter of {@code resource} that has a value in the request path.
     *
     * @param resource the matched resource
     * @param rawUriValues the raw value of each URI parameter, by RAML name
     * @throws BadRequestException for a malformed escape or a value that breaks its declaration
     */
    private static void checkUriParameters(RamlModel.Resource resource, Map<String, String> rawUriValues) {
        for (RamlModel.Parameter parameter : resource.uriParameters()) {
            String raw = rawUriValues.get(parameter.name());
            if (raw == null) {
                continue;
            }
            String value = decodeOrNull(raw);
            if (value == null) {
                throw badRequest("URI parameter " + parameter.name() + " has a malformed percent escape");
            }
            checkValue("URI parameter", parameter, value);
        }
    }

    /**
     * Checks the declared query parameters of {@code method} against the raw query string.
     *
     * @param method the matched RAML method
     * @param queryString the raw query string, or {@code null}
     * @throws BadRequestException for a malformed escape, a missing required parameter or a value that
     *     breaks its declaration
     */
    private static void checkQueryParameters(RamlModel.Method method, String queryString) {
        Map<String, List<String>> query = parseQueryString(queryString);
        for (RamlModel.Parameter parameter : method.queryParameters()) {
            List<String> values = query.get(parameter.name());
            if (values == null || values.isEmpty()) {
                if (parameter.required()) {
                    throw badRequest("Query parameter " + parameter.name() + " is required");
                }
                continue;
            }
            for (String value : values) {
                checkValue("Query parameter", parameter, value);
            }
        }
    }

    /**
     * Splits a raw query string on {@code &} into {@code name[=value]} pairs, each part decoded as
     * {@code application/x-www-form-urlencoded} UTF-8; a pair without {@code =} has the value {@code ""}.
     *
     * @param queryString the raw query string, or {@code null}
     * @return the values of each name in query order; empty for a {@code null} or empty query string
     * @throws BadRequestException for a malformed percent escape
     */
    private static Map<String, List<String>> parseQueryString(String queryString) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        if (queryString == null || queryString.isEmpty()) {
            return query;
        }
        for (String pair : queryString.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            try {
                query.computeIfAbsent(URLDecoder.decode(rawName, StandardCharsets.UTF_8), name -> new ArrayList<>())
                        .add(URLDecoder.decode(rawValue, StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ex) {
                throw badRequest("Query string has a malformed percent escape");
            }
        }
        return query;
    }

    /**
     * Checks one parameter value against its RAML {@code type}, {@code enum}, {@code minLength} and
     * {@code maxLength}.
     *
     * @param kind {@code URI parameter} or {@code Query parameter}, used in the message
     * @param parameter the RAML declaration
     * @param value the decoded value
     * @throws BadRequestException naming the parameter and the failed check
     */
    private static void checkValue(String kind, RamlModel.Parameter parameter, String value) {
        String subject = kind + " " + parameter.name();
        String type = parameter.type() == null ? "string" : parameter.type();
        if (!matchesType(type, value)) {
            throw badRequest(subject + " is not a valid " + type);
        }
        if (!parameter.enumValues().isEmpty() && !parameter.enumValues().contains(value)) {
            throw badRequest(subject + " is not one of " + parameter.enumValues());
        }
        if (parameter.minLength() != null && value.length() < parameter.minLength()) {
            throw badRequest(subject + " is shorter than " + parameter.minLength() + " characters");
        }
        if (parameter.maxLength() != null && value.length() > parameter.maxLength()) {
            throw badRequest(subject + " is longer than " + parameter.maxLength() + " characters");
        }
    }

    /**
     * Tells whether {@code value} is a value of the RAML 0.8 named-parameter {@code type}: {@code integer}
     * parses as {@link BigInteger}, {@code number} as {@link BigDecimal}, {@code boolean} is {@code true}
     * or {@code false}, {@code date} parses as {@link DateTimeFormatter#RFC_1123_DATE_TIME}; {@code string}
     * and {@code file} accept every value (D-354).
     *
     * @param type the RAML type
     * @param value the decoded value
     * @return whether the value is of the type
     */
    private static boolean matchesType(String type, String value) {
        return switch (type) {
            case "integer" -> isInteger(value);
            case "number" -> isNumber(value);
            case "boolean" -> "true".equals(value) || "false".equals(value);
            case "date" -> isRfc1123Date(value);
            default -> true;
        };
    }

    private static boolean isInteger(String value) {
        try {
            new BigInteger(value);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean isNumber(String value) {
        try {
            new BigDecimal(value);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static boolean isRfc1123Date(String value) {
        try {
            DateTimeFormatter.RFC_1123_DATE_TIME.parse(value);
            return true;
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    /**
     * Checks the request {@code Content-Type} of a method that declares request bodies; type and subtype
     * are compared, parameters such as {@code charset} are ignored.
     *
     * @param request the current request
     * @param resource the matched resource
     * @param method the matched RAML method
     * @return the compiled schema of the matched request body; {@code null} when the method declares no
     *     request body or the matched body has no schema
     * @throws HttpMediaTypeNotSupportedException for a missing, unparsable or undeclared {@code Content-Type}
     */
    private JsonSchema checkMediaType(HttpServletRequest request, RamlModel.Resource resource,
                                      RamlModel.Method method) throws HttpMediaTypeNotSupportedException {
        if (method.body().isEmpty()) {
            return null;
        }
        String contentType = request.getContentType();
        if (contentType == null || contentType.isBlank()) {
            LOG.debug("Rejected with 415: no Content-Type on resource {}", resource.path());
            throw new HttpMediaTypeNotSupportedException("Content-Type is missing");
        }
        MediaType requested;
        try {
            requested = MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException ex) {
            LOG.debug("Rejected with 415: unparsable Content-Type on resource {}", resource.path());
            throw new HttpMediaTypeNotSupportedException(ex.getMessage());
        }
        List<MediaType> declared = new ArrayList<>();
        for (RamlModel.Body body : method.body()) {
            List<String> key = bodyKey(resource, method, body);
            MediaType mediaType = declaredMediaTypes.get(key);
            if (mediaType.equalsTypeAndSubtype(requested)) {
                return schemas.get(key);
            }
            declared.add(mediaType);
        }
        LOG.debug("Rejected with 415: resource {} accepts only {}", resource.path(), declared);
        throw new HttpMediaTypeNotSupportedException(requested, declared, HttpMethod.valueOf(request.getMethod()));
    }

    /**
     * Validates request body bytes against a compiled draft-03 schema (D-046).
     *
     * @param schema the compiled schema of the matched request body
     * @param bytes the raw request body
     * @throws BadRequestException for a blank body, a body that is not exactly one JSON value, a body the
     *     schema rejects, or a validation that fails to run
     */
    private void validateBody(JsonSchema schema, byte[] bytes) {
        if (bytes.length == 0 || new String(bytes, StandardCharsets.UTF_8).isBlank()) {
            throw badRequest("Request body is empty");
        }
        JsonNode node;
        try {
            node = jsonReader.readTree(bytes);
        } catch (IOException ex) {
            throw badRequest("Request body is not exactly one JSON value");
        }
        if (node == null || node.isMissingNode()) {
            throw badRequest("Request body is empty");
        }
        ProcessingReport report;
        try {
            report = schema.validate(node);
        } catch (ProcessingException ex) {
            throw badRequest("Request body could not be validated against its RAML schema");
        }
        if (!report.isSuccess()) {
            throw badRequest("Request body violates its RAML schema: " + describeErrors(report));
        }
    }

    /**
     * Returns the schema {@link #preHandle} stored for the current request.
     *
     * @return the compiled schema, or {@code null} outside a request or when none is stored
     */
    private static JsonSchema currentSchema() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        Object schema = attributes.getAttribute(SCHEMA_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return schema instanceof JsonSchema jsonSchema ? jsonSchema : null;
    }

    /**
     * Lists the keyword and schema pointer of each error or fatal message of a report, for example
     * {@code type at /properties/homeTeamScore}.
     *
     * @param report a validation or syntax report
     * @return the comma-separated errors
     */
    private static String describeErrors(ProcessingReport report) {
        List<String> errors = new ArrayList<>();
        for (ProcessingMessage message : report) {
            if (message.getLogLevel().compareTo(LogLevel.ERROR) >= 0) {
                JsonNode json = message.asJson();
                String keyword = json.path("keyword").asText(json.path("message").asText("error"));
                String pointer = json.path("schema").path("pointer").asText("");
                errors.add(keyword + " at " + (pointer.isEmpty() ? "/" : pointer));
            }
        }
        return String.join(", ", errors);
    }

    /**
     * Parses and compiles one RAML request schema as draft-03 (D-046).
     *
     * @param factory the draft-03 schema factory
     * @param text the raw schema text
     * @param where the resource, method and media type, used in the message
     * @return the compiled schema
     * @throws IllegalStateException if the text is not one JSON value, is not a valid draft-03 schema or
     *     does not compile
     */
    private JsonSchema compileSchema(JsonSchemaFactory factory, String text, String where) {
        JsonNode node;
        try {
            node = jsonReader.readTree(text);
        } catch (IOException ex) {
            throw new IllegalStateException("RAML request schema of " + where + " is not one JSON value", ex);
        }
        if (node == null || node.isMissingNode()) {
            throw new IllegalStateException("RAML request schema of " + where + " is empty");
        }
        ProcessingReport syntax = factory.getSyntaxValidator().validateSchema(node);
        if (!syntax.isSuccess()) {
            throw new IllegalStateException("RAML request schema of " + where
                    + " is not a valid draft-03 schema: " + describeErrors(syntax));
        }
        try {
            return factory.getJsonSchema(node);
        } catch (ProcessingException ex) {
            throw new IllegalStateException("RAML request schema of " + where + " does not compile", ex);
        }
    }

    /**
     * Parses the media-type key of a RAML request body.
     *
     * @param mediaType the media-type key as written in the RAML
     * @param where the resource, method and media type, used in the message
     * @return the parsed media type
     * @throws IllegalStateException if the key is blank or does not parse
     */
    private static MediaType parseDeclaredMediaType(String mediaType, String where) {
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalStateException("RAML request body of " + where + " has no media type");
        }
        try {
            return MediaType.parseMediaType(mediaType);
        } catch (InvalidMediaTypeException ex) {
            throw new IllegalStateException("RAML request body of " + where + " has an invalid media type", ex);
        }
    }

    private static List<String> bodyKey(RamlModel.Resource resource, RamlModel.Method method, RamlModel.Body body) {
        return List.of(resource.path(), method.verb(), body.mediaType());
    }

    private static String describe(RamlModel.Resource resource, RamlModel.Method method, RamlModel.Body body) {
        return method.verb().toUpperCase(Locale.ROOT) + " " + resource.path() + " " + body.mediaType();
    }

    /**
     * Returns the {@code name} of a template segment that is exactly {@code {name}}.
     *
     * @param segment one segment of a resource path template
     * @return the URI parameter name, or {@code null} for a literal segment
     */
    private static String uriParameterName(String segment) {
        if (segment.length() > 2 && segment.charAt(0) == '{' && segment.charAt(segment.length() - 1) == '}') {
            String name = segment.substring(1, segment.length() - 1);
            if (name.indexOf('{') < 0 && name.indexOf('}') < 0) {
                return name;
            }
        }
        return null;
    }

    /**
     * Removes the {@code ;} path parameters of one request path segment.
     *
     * @param segment one raw request path segment
     * @return the part before the first {@code ;}
     */
    private static String withoutPathParameters(String segment) {
        int semicolon = segment.indexOf(';');
        return semicolon < 0 ? segment : segment.substring(0, semicolon);
    }

    /**
     * Percent-decodes one raw path segment value as UTF-8.
     *
     * @param raw the raw value
     * @return the decoded value, or {@code null} for a malformed escape
     */
    private static String decodeOrNull(String raw) {
        try {
            return UriUtils.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String pathWithinApplication(HttpServletRequest request) {
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }

    private boolean isConsolePath(String path) {
        return path.equals(consolePrefix) || path.startsWith(consolePrefix + "/");
    }

    private boolean isUnderBasePath(String path) {
        return basePath.isEmpty() || path.equals(basePath) || path.startsWith(basePath + "/");
    }

    private static BadRequestException badRequest(String message) {
        LOG.debug("Rejected with 400: {}", message);
        return new BadRequestException(message);
    }
}
