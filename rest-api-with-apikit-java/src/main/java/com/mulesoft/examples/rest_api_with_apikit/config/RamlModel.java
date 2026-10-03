package com.mulesoft.examples.rest_api_with_apikit.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Parsed RAML 0.8 contract of the APIkit router {@code leagues-config}: the classpath RAML named by
 * {@code apikit.leagues-config.raml}, its {@code !include} files, the resource tree, the console model
 * served as {@code api.json} and the raw contract files served under the console (D-009).
 *
 * <p>The constructor reads and parses everything once; the instance is immutable afterwards and safe
 * for concurrent use. Each {@code !include <path>} is resolved relative to the RAML folder and replaced
 * by the included file's text, kept raw: schemas and examples are never parsed here, and a file that is
 * not strict JSON (for example {@code examples/match-get-example.json}) loads unchanged.
 *
 * <p>Usage outside Spring:
 *
 * <pre>{@code
 * RamlModel model = new RamlModel("api/leagues.raml");
 * model.basePath();                                   // "/api"
 * model.resource("/teams/{teamId}")
 *      .flatMap(resource -> resource.method("get"))
 *      .flatMap(method -> method.response(200))
 *      .flatMap(response -> response.body().stream().findFirst())
 *      .map(Body::exampleFile);                       // Optional["examples/teamid-get-example.json"]
 * model.file("schemas/match-schema-input.json");      // the classpath file's bytes
 * }</pre>
 *
 * <p>Root {@code documentation}, {@code schemas}, {@code resourceTypes} and {@code traits} are not
 * modelled.
 */
@Component
public class RamlModel {

    private static final Logger LOG = LoggerFactory.getLogger(RamlModel.class);

    /** Local YAML tag that inlines another contract file. */
    private static final String INCLUDE_TAG = "!include";

    /** RAML 0.8 method keys of a resource. */
    private static final Set<String> METHOD_KEYS =
            Set.of("get", "post", "put", "delete", "patch", "head", "options", "trace", "connect");

    /** Matches the {@code scheme://authority} prefix of a URI or URI template such as {@code https://{host}/api}. */
    private static final Pattern SCHEME_AND_AUTHORITY = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*://[^/]*");

    /** One {@code {name}} URI parameter of a resource path template. */
    private static final Pattern URI_PARAMETER = Pattern.compile("\\{([^{}/]+)}");

    /** RAML 0.8 default type of a named parameter. */
    private static final String DEFAULT_TYPE = "string";

    private final String title;
    private final String version;
    private final String baseUri;
    private final String basePath;
    private final String ramlFile;
    private final List<Resource> resources;
    private final Map<String, Object> consoleModel;
    private final Map<String, byte[]> files;

    /**
     * Loads and parses the RAML at {@code ramlLocation} and every file it includes.
     *
     * <p>The RAML folder is the part of the location before its last {@code /} ({@code api}); the RAML
     * file name is the part after it ({@code leagues.raml}).
     *
     * @param ramlLocation classpath location of the RAML file, {@code api/leagues.raml} by default
     * @throws IllegalStateException if the location is blank, the RAML or an included file is not on the
     *     classpath, the YAML does not parse, or a RAML node has an unsupported shape; the message names
     *     the classpath path or the RAML node
     */
    public RamlModel(@Value("${apikit.leagues-config.raml}") String ramlLocation) {
        if (ramlLocation == null || ramlLocation.isBlank()) {
            throw new IllegalStateException("RAML location apikit.leagues-config.raml is not set");
        }
        String location = ramlLocation.trim();
        int slash = location.lastIndexOf('/');
        String ramlFolder = slash < 0 ? "" : location.substring(0, slash);
        this.ramlFile = location.substring(slash + 1);
        if (ramlFile.isEmpty()) {
            throw new IllegalStateException("RAML location " + location + " names no file");
        }
        byte[] ramlBytes = readClasspath(location);

        LoaderOptions options = new LoaderOptions();
        options.setTagInspector(tag -> INCLUDE_TAG.equals(tag.getValue()));
        IncludeConstructor constructor = new IncludeConstructor(options, ramlFolder);
        Object root;
        try {
            root = new Yaml(constructor).load(new String(ramlBytes, StandardCharsets.UTF_8));
        } catch (YAMLException e) {
            throw new IllegalStateException("Cannot parse RAML " + location + ": " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> raml)) {
            throw new IllegalStateException("RAML " + location + " is not a YAML mapping");
        }

        this.title = text(raml.get("title"));
        this.version = text(raml.get("version"));
        this.baseUri = text(raml.get("baseUri"));
        this.basePath = pathOf(baseUri);

        List<Resource> walked = new ArrayList<>();
        for (Map.Entry<?, ?> entry : raml.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("/")) {
                parseResource("", key, entry.getValue(), List.of(), walked);
            }
        }
        this.resources = List.copyOf(walked);

        Map<String, byte[]> contractFiles = new LinkedHashMap<>(constructor.includedFiles);
        contractFiles.put(ramlFile, ramlBytes);
        this.files = Collections.unmodifiableMap(contractFiles);

        this.consoleModel = buildConsoleModel();

        int methodCount = resources.stream().mapToInt(resource -> resource.methods().size()).sum();
        LOG.debug("Loaded RAML {}: {} resources, {} methods, {} included files",
                location, resources.size(), methodCount, constructor.includedFiles.size());
    }

    /**
     * Returns the RAML {@code title}.
     *
     * @return the title, {@code La Liga} for {@code leagues.raml}; {@code null} when absent
     */
    public String title() {
        return title;
    }

    /**
     * Returns the RAML {@code version} as text; a YAML number such as {@code 1.0} reads {@code "1.0"}.
     *
     * @return the version, or {@code null} when absent
     */
    public String version() {
        return version;
    }

    /**
     * Returns the RAML {@code baseUri} as written.
     *
     * @return the base URI, {@code http://localhost:8080/api} for {@code leagues.raml}; {@code null} when
     *     absent
     */
    public String baseUri() {
        return baseUri;
    }

    /**
     * Returns the path part of {@code baseUri}: {@code scheme://authority} removed, trailing {@code /}
     * removed, leading {@code /} ensured.
     *
     * @return {@code /api} for {@code leagues.raml}; an empty string when {@code baseUri} is absent or has
     *     no path
     */
    public String basePath() {
        return basePath;
    }

    /**
     * Returns every resource, depth-first in RAML order, each with its full path template.
     *
     * @return an unmodifiable list: {@code /teams}, {@code /teams/{teamId}}, {@code /positions},
     *     {@code /fixture}, {@code /fixture/{homeTeamId}/{awayTeamId}} for {@code leagues.raml}
     */
    public List<Resource> resources() {
        return resources;
    }

    /**
     * Finds the resource whose full path template equals {@code path}.
     *
     * @param path a full template relative to the base URI, for example {@code /teams/{teamId}}
     * @return the resource, or empty when no resource has exactly that template
     */
    public Optional<Resource> resource(String path) {
        if (path == null) {
            return Optional.empty();
        }
        return resources.stream().filter(resource -> resource.path().equals(path)).findFirst();
    }

    /**
     * Returns the console model served as {@code api.json} (D-009).
     *
     * <p>Nested unmodifiable maps and lists with keys in this order: root {@code title}, {@code version},
     * {@code basePath}, {@code ramlFile}, {@code resources}; resource {@code path}, {@code displayName},
     * {@code uriParameters}, {@code methods}; method {@code method}, {@code description},
     * {@code queryParameters}, {@code body}, {@code responses}; body {@code mediaType}, {@code schema},
     * {@code schemaFile}, {@code example}, {@code exampleFile}; response {@code status} (an integer),
     * {@code description}, {@code headers}, {@code body}; parameter {@code name}, {@code displayName},
     * {@code description}, {@code type}, {@code required}, {@code example}, {@code minLength},
     * {@code maxLength}, {@code enum}, {@code default}. Absent scalars are {@code null}; lists are always
     * present.
     *
     * @return the console model
     */
    public Map<String, Object> consoleModel() {
        return consoleModel;
    }

    /**
     * Returns a copy of the raw bytes of a contract file: the RAML file itself ({@code leagues.raml}) or
     * one of its include paths as written in the RAML, trimmed ({@code schemas/teams-schema-input.json},
     * {@code examples/teams-example.json}, ...).
     *
     * @param path the RAML file name or an include path
     * @return the bytes as read from the classpath at construction; empty for {@code null}, an absolute
     *     path, a path with a {@code ..} segment or a backslash, and any other unknown path
     */
    public Optional<byte[]> file(String path) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.indexOf('\\') >= 0
                || Arrays.asList(path.split("/", -1)).contains("..")) {
            return Optional.empty();
        }
        byte[] bytes = files.get(path);
        return bytes == null ? Optional.empty() : Optional.of(bytes.clone());
    }

    /**
     * A RAML 0.8 named parameter: a URI parameter, a query parameter or a response header.
     *
     * <p>Defaults: {@code displayName} is the name, {@code type} is {@code string}, {@code required} is
     * {@code true} for URI parameters and {@code false} for query parameters and headers,
     * {@code enumValues} (the RAML {@code enum}) is empty. {@code example} and {@code defaultValue} (the
     * RAML {@code default}) are the YAML values as text.
     *
     * @param name the parameter name as declared, for example {@code teamId}
     * @param displayName the RAML {@code displayName}, or the name
     * @param description the RAML {@code description}, or {@code null}
     * @param type the RAML {@code type}: {@code string}, {@code number}, {@code integer}, {@code date},
     *     {@code boolean} or {@code file}
     * @param required whether the parameter must be present
     * @param example the RAML {@code example} as text, or {@code null}
     * @param minLength the RAML {@code minLength}, or {@code null}
     * @param maxLength the RAML {@code maxLength}, or {@code null}
     * @param enumValues the RAML {@code enum} values as text; never {@code null}
     * @param defaultValue the RAML {@code default} as text, or {@code null}
     */
    public record Parameter(
            String name,
            String displayName,
            String description,
            String type,
            boolean required,
            String example,
            Integer minLength,
            Integer maxLength,
            List<String> enumValues,
            String defaultValue) {

        /**
         * Creates the parameter; {@code enumValues} is stored as an unmodifiable list, empty for {@code null}.
         *
         * @param name the parameter name
         * @param displayName the display name
         * @param description the description, or {@code null}
         * @param type the RAML type
         * @param required whether the parameter must be present
         * @param example the example, or {@code null}
         * @param minLength the minimum length, or {@code null}
         * @param maxLength the maximum length, or {@code null}
         * @param enumValues the enum values
         * @param defaultValue the default value, or {@code null}
         */
        public Parameter {
            enumValues = copy(enumValues);
        }
    }

    /**
     * One media-type entry of a request or response {@code body}.
     *
     * @param mediaType the media type key as written, for example {@code application/json}
     * @param schema the schema text, the included file's raw text or the inline value; {@code null} when
     *     absent
     * @param schemaFile the trimmed include path of the schema, for example
     *     {@code schemas/teams-schema-input.json}; {@code null} when inline or absent
     * @param example the example text, the included file's raw text or the inline value; {@code null} when
     *     absent
     * @param exampleFile the trimmed include path of the example, for example
     *     {@code examples/teams-example.json}; {@code null} when inline or absent
     */
    public record Body(String mediaType, String schema, String schemaFile, String example, String exampleFile) {
    }

    /**
     * One declared response of a method.
     *
     * @param status the HTTP status code key, for example {@code 201}
     * @param description the RAML {@code description}, or {@code null}
     * @param headers the declared response headers in RAML order; never {@code null}
     * @param body the declared response bodies in RAML order; never {@code null}
     */
    public record Response(int status, String description, List<Parameter> headers, List<Body> body) {

        /**
         * Creates the response; {@code headers} and {@code body} are stored as unmodifiable lists, empty for
         * {@code null}.
         *
         * @param status the status code
         * @param description the description, or {@code null}
         * @param headers the response headers
         * @param body the response bodies
         */
        public Response {
            headers = copy(headers);
            body = copy(body);
        }
    }

    /**
     * One method of a resource.
     *
     * @param verb the lower-case RAML method key: {@code get}, {@code post}, {@code put},
     *     {@code delete}, ...
     * @param description the RAML {@code description}, or {@code null}
     * @param queryParameters the declared query parameters in RAML order; never {@code null}
     * @param body the declared request bodies in RAML order; never {@code null}
     * @param responses the declared responses in RAML order; never {@code null}
     */
    public record Method(
            String verb,
            String description,
            List<Parameter> queryParameters,
            List<Body> body,
            List<Response> responses) {

        /**
         * Creates the method; the three lists are stored as unmodifiable lists, empty for {@code null}.
         *
         * @param verb the lower-case method key
         * @param description the description, or {@code null}
         * @param queryParameters the query parameters
         * @param body the request bodies
         * @param responses the responses
         */
        public Method {
            queryParameters = copy(queryParameters);
            body = copy(body);
            responses = copy(responses);
        }

        /**
         * Finds the request body declared for {@code mediaType}, comparing the media-type strings
         * case-insensitively.
         *
         * @param mediaType a media type such as {@code application/json}
         * @return the body, or empty when none is declared for that media type
         */
        public Optional<Body> body(String mediaType) {
            if (mediaType == null) {
                return Optional.empty();
            }
            return body.stream().filter(entry -> entry.mediaType().equalsIgnoreCase(mediaType)).findFirst();
        }

        /**
         * Finds the response declared for {@code status}.
         *
         * @param status an HTTP status code
         * @return the response, or empty when the method declares no such status
         */
        public Optional<Response> response(int status) {
            return responses.stream().filter(entry -> entry.status() == status).findFirst();
        }
    }

    /**
     * One resource with its full path template.
     *
     * <p>{@code uriParameters} lists one parameter per {@code {name}} of the path, in path order: the
     * declaration of the nearest level that declares it (the resource itself, then its ancestors), or an
     * implicit required {@code string} parameter. Declared parameters that do not occur in the path follow
     * in declaration order.
     *
     * @param path the full path template relative to the base URI, for example {@code /teams/{teamId}}
     * @param displayName the RAML {@code displayName}, or the resource's relative key such as
     *     {@code /{teamId}}
     * @param description the RAML {@code description}, or {@code null}
     * @param uriParameters the URI parameters of the full path; never {@code null}
     * @param methods the methods in RAML order; never {@code null}
     */
    public record Resource(
            String path,
            String displayName,
            String description,
            List<Parameter> uriParameters,
            List<Method> methods) {

        /**
         * Creates the resource; {@code uriParameters} and {@code methods} are stored as unmodifiable lists,
         * empty for {@code null}.
         *
         * @param path the full path template
         * @param displayName the display name
         * @param description the description, or {@code null}
         * @param uriParameters the URI parameters
         * @param methods the methods
         */
        public Resource {
            uriParameters = copy(uriParameters);
            methods = copy(methods);
        }

        /**
         * Finds the method with RAML key {@code verb}, compared case-insensitively.
         *
         * @param verb an HTTP method such as {@code get} or {@code GET}
         * @return the method, or empty when the resource does not declare it
         */
        public Optional<Method> method(String verb) {
            if (verb == null) {
                return Optional.empty();
            }
            return methods.stream().filter(entry -> entry.verb().equalsIgnoreCase(verb)).findFirst();
        }
    }

    /**
     * The value of one {@code !include}: the trimmed include path and the included file's UTF-8 text.
     *
     * @param path the include path as written after the tag, trimmed
     * @param text the included file's content, unparsed
     */
    private record Included(String path, String text) {
    }

    /**
     * SnakeYAML safe constructor that resolves {@code !include <path>} relative to the RAML folder and
     * records each included file's bytes by its include path.
     */
    private static final class IncludeConstructor extends SafeConstructor {

        private final String ramlFolder;

        /** Bytes of every included file, keyed by trimmed include path, in first-include order. */
        private final Map<String, byte[]> includedFiles = new LinkedHashMap<>();

        /**
         * Creates the constructor and registers the {@code !include} construct.
         *
         * @param options the loader options of the parse
         * @param ramlFolder classpath folder of the RAML file, empty for the classpath root
         */
        private IncludeConstructor(LoaderOptions options, String ramlFolder) {
            super(options);
            this.ramlFolder = ramlFolder;
            this.yamlConstructors.put(new Tag(INCLUDE_TAG), new IncludeConstruct());
        }

        /** Builds an {@link Included} from an {@code !include} scalar. */
        private final class IncludeConstruct extends AbstractConstruct {

            /**
             * Loads the classpath file {@code <ramlFolder>/<path>} named by the scalar and returns its text.
             *
             * @param node the {@code !include} node
             * @return the include path and the file's UTF-8 text
             * @throws IllegalStateException if the node is not a non-empty scalar or the file is not on the
             *     classpath
             */
            @Override
            public Object construct(Node node) {
                if (!(node instanceof ScalarNode scalar)) {
                    throw new IllegalStateException(INCLUDE_TAG + " " + position(node) + " does not name a file");
                }
                String path = constructScalar(scalar).trim();
                if (path.isEmpty()) {
                    throw new IllegalStateException(INCLUDE_TAG + " " + position(node) + " does not name a file");
                }
                String location = ramlFolder.isEmpty() ? path : ramlFolder + "/" + path;
                byte[] bytes = includedFiles.computeIfAbsent(path, key -> readClasspath(location));
                return new Included(path, new String(bytes, StandardCharsets.UTF_8));
            }
        }
    }


    /**
     * Adds the resource at {@code parentPath + relativePath} and then its child resources, depth-first in
     * RAML order, to {@code out}.
     *
     * @param parentPath full path of the parent resource, empty for a root resource
     * @param relativePath the resource key, for example {@code /{teamId}}
     * @param value the resource mapping; {@code null} counts as an empty mapping
     * @param inherited URI parameters declared by the ancestors, nearest last
     * @param out the resources walked so far
     */
    private static void parseResource(
            String parentPath, String relativePath, Object value, List<Parameter> inherited, List<Resource> out) {
        String path = parentPath + relativePath;
        Map<?, ?> properties = mapping(value, path);

        List<Parameter> declared = new ArrayList<>(inherited);
        for (Parameter parameter : parseParameters(properties.get("uriParameters"), true, path + " uriParameters")) {
            declared.removeIf(existing -> existing.name().equals(parameter.name()));
            declared.add(parameter);
        }

        List<Method> methods = new ArrayList<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (METHOD_KEYS.contains(key)) {
                methods.add(parseMethod(key, entry.getValue(), path));
            }
        }

        String displayName = text(properties.get("displayName"));
        out.add(new Resource(path, displayName == null ? relativePath : displayName,
                text(properties.get("description")), uriParameters(path, declared), methods));

        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (key.startsWith("/")) {
                parseResource(path, key, entry.getValue(), declared, out);
            }
        }
    }

    /**
     * Orders the URI parameters of {@code path}: one per {@code {name}} in path order, taken from
     * {@code declared} or implicit, then the declared parameters the path does not contain.
     *
     * @param path a full resource path template
     * @param declared the parameters declared by the resource and its ancestors, nearest last
     * @return the URI parameters of the resource
     */
    private static List<Parameter> uriParameters(String path, List<Parameter> declared) {
        Map<String, Parameter> byName = new LinkedHashMap<>();
        for (Parameter parameter : declared) {
            byName.put(parameter.name(), parameter);
        }
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = URI_PARAMETER.matcher(path);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        names.addAll(byName.keySet());

        List<Parameter> ordered = new ArrayList<>();
        for (String name : names) {
            Parameter parameter = byName.get(name);
            ordered.add(parameter != null ? parameter
                    : new Parameter(name, name, null, DEFAULT_TYPE, true, null, null, null, List.of(), null));
        }
        return ordered;
    }

    /**
     * Parses one method mapping.
     *
     * @param verb the lower-case method key
     * @param value the method mapping; {@code null} counts as an empty mapping
     * @param path full path of the owning resource
     * @return the method
     */
    private static Method parseMethod(String verb, Object value, String path) {
        String context = verb.toUpperCase(Locale.ROOT) + " " + path;
        Map<?, ?> properties = mapping(value, context);
        return new Method(verb, text(properties.get("description")),
                parseParameters(properties.get("queryParameters"), false, context + " queryParameters"),
                parseBodies(properties.get("body"), context + " body"),
                parseResponses(properties.get("responses"), context + " responses"));
    }

    /**
     * Parses a {@code responses} mapping of status code to response properties.
     *
     * @param value the mapping; {@code null} counts as an empty mapping
     * @param context the RAML node named in error messages
     * @return the responses in RAML order
     */
    private static List<Response> parseResponses(Object value, String context) {
        List<Response> responses = new ArrayList<>();
        for (Map.Entry<?, ?> entry : mapping(value, context).entrySet()) {
            String key = String.valueOf(entry.getKey()).trim();
            int status;
            try {
                status = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("RAML " + context + " key " + key + " is not a status code", e);
            }
            String responseContext = context + " " + status;
            Map<?, ?> properties = mapping(entry.getValue(), responseContext);
            responses.add(new Response(status, text(properties.get("description")),
                    parseParameters(properties.get("headers"), false, responseContext + " headers"),
                    parseBodies(properties.get("body"), responseContext + " body")));
        }
        return responses;
    }

    /**
     * Parses a {@code body} mapping of media type to {@code schema} and {@code example}.
     *
     * @param value the mapping; {@code null} counts as an empty mapping
     * @param context the RAML node named in error messages
     * @return the bodies in RAML order
     */
    private static List<Body> parseBodies(Object value, String context) {
        List<Body> bodies = new ArrayList<>();
        for (Map.Entry<?, ?> entry : mapping(value, context).entrySet()) {
            String mediaType = String.valueOf(entry.getKey());
            Map<?, ?> properties = mapping(entry.getValue(), context + " " + mediaType);
            Object schema = properties.get("schema");
            Object example = properties.get("example");
            bodies.add(new Body(mediaType, text(schema), includePath(schema), text(example), includePath(example)));
        }
        return bodies;
    }

    /**
     * Parses a mapping of parameter name to named-parameter properties.
     *
     * @param value the mapping; {@code null} counts as an empty mapping
     * @param requiredByDefault the {@code required} value of a parameter that does not declare it
     * @param context the RAML node named in error messages
     * @return the parameters in RAML order
     */
    private static List<Parameter> parseParameters(Object value, boolean requiredByDefault, String context) {
        List<Parameter> parameters = new ArrayList<>();
        for (Map.Entry<?, ?> entry : mapping(value, context).entrySet()) {
            String name = String.valueOf(entry.getKey());
            String parameterContext = context + " " + name;
            Map<?, ?> properties = mapping(entry.getValue(), parameterContext);
            String displayName = text(properties.get("displayName"));
            String type = text(properties.get("type"));
            parameters.add(new Parameter(
                    name,
                    displayName == null ? name : displayName,
                    text(properties.get("description")),
                    type == null ? DEFAULT_TYPE : type,
                    bool(properties.get("required"), requiredByDefault, parameterContext + " required"),
                    text(properties.get("example")),
                    integer(properties.get("minLength"), parameterContext + " minLength"),
                    integer(properties.get("maxLength"), parameterContext + " maxLength"),
                    texts(properties.get("enum")),
                    text(properties.get("default"))));
        }
        return parameters;
    }

    /** Builds the {@code api.json} console model from the parsed contract. */
    private Map<String, Object> buildConsoleModel() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", title);
        root.put("version", version);
        root.put("basePath", basePath);
        root.put("ramlFile", ramlFile);
        root.put("resources", resources.stream().map(RamlModel::consoleResource).toList());
        return Collections.unmodifiableMap(root);
    }

    private static Map<String, Object> consoleResource(Resource resource) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("path", resource.path());
        map.put("displayName", resource.displayName());
        map.put("uriParameters", resource.uriParameters().stream().map(RamlModel::consoleParameter).toList());
        map.put("methods", resource.methods().stream().map(RamlModel::consoleMethod).toList());
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> consoleMethod(Method method) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("method", method.verb());
        map.put("description", method.description());
        map.put("queryParameters", method.queryParameters().stream().map(RamlModel::consoleParameter).toList());
        map.put("body", method.body().stream().map(RamlModel::consoleBody).toList());
        map.put("responses", method.responses().stream().map(RamlModel::consoleResponse).toList());
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> consoleBody(Body body) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("mediaType", body.mediaType());
        map.put("schema", body.schema());
        map.put("schemaFile", body.schemaFile());
        map.put("example", body.example());
        map.put("exampleFile", body.exampleFile());
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> consoleResponse(Response response) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", response.status());
        map.put("description", response.description());
        map.put("headers", response.headers().stream().map(RamlModel::consoleParameter).toList());
        map.put("body", response.body().stream().map(RamlModel::consoleBody).toList());
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, Object> consoleParameter(Parameter parameter) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", parameter.name());
        map.put("displayName", parameter.displayName());
        map.put("description", parameter.description());
        map.put("type", parameter.type());
        map.put("required", parameter.required());
        map.put("example", parameter.example());
        map.put("minLength", parameter.minLength());
        map.put("maxLength", parameter.maxLength());
        map.put("enum", parameter.enumValues());
        map.put("default", parameter.defaultValue());
        return Collections.unmodifiableMap(map);
    }

    /**
     * Returns the path part of {@code uri}: {@code scheme://authority} removed, trailing {@code /}
     * removed, leading {@code /} ensured.
     *
     * @param uri an absolute or relative URI, possibly holding {@code {name}} templates; may be {@code null}
     * @return the path, empty when there is none
     */
    private static String pathOf(String uri) {
        if (uri == null) {
            return "";
        }
        String path = SCHEME_AND_AUTHORITY.matcher(uri.trim()).replaceFirst("");
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.isEmpty() || path.startsWith("/") ? path : "/" + path;
    }

    /**
     * Reads a classpath resource completely.
     *
     * @param location the classpath location
     * @return the resource bytes
     * @throws IllegalStateException naming {@code location} if the resource is missing or unreadable
     */
    private static byte[] readClasspath(String location) {
        try (InputStream in = new ClassPathResource(location).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read classpath resource " + location + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns {@code value} as a mapping.
     *
     * @param value a YAML value; {@code null} counts as an empty mapping
     * @param context the RAML node named in the error message
     * @return the mapping
     * @throws IllegalStateException if {@code value} is neither {@code null} nor a mapping
     */
    private static Map<?, ?> mapping(Object value, String context) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalStateException("RAML node " + context + " is not a mapping");
    }

    /**
     * Returns the text of a YAML value: an include's file text, any other value through
     * {@link String#valueOf(Object)}, {@code null} for {@code null}.
     */
    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Included included) {
            return included.text();
        }
        return String.valueOf(value);
    }

    /** Returns the trimmed include path of an {@code !include} value, {@code null} for an inline value. */
    private static String includePath(Object value) {
        return value instanceof Included included ? included.path() : null;
    }

    /**
     * Returns a YAML integer value as an {@link Integer}.
     *
     * @throws IllegalStateException if the value is neither {@code null}, a number nor a decimal integer text
     */
    private static Integer integer(Object value, String context) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String digits = text(value).trim();
        try {
            return Integer.valueOf(digits);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("RAML " + context + " value " + digits + " is not an integer", e);
        }
    }

    /**
     * Returns a YAML boolean value, {@code defaultValue} for {@code null}.
     *
     * @throws IllegalStateException if the value is neither a boolean nor the text {@code true} or {@code false}
     */
    private static boolean bool(Object value, boolean defaultValue, String context) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        String literal = text(value).trim();
        if ("true".equalsIgnoreCase(literal) || "false".equalsIgnoreCase(literal)) {
            return Boolean.parseBoolean(literal);
        }
        throw new IllegalStateException("RAML " + context + " value " + literal + " is not a boolean");
    }

    /** Returns a YAML sequence as texts in order, a scalar as one text, {@code null} as an empty list. */
    private static List<String> texts(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object element : list) {
                String item = text(element);
                if (item != null) {
                    values.add(item);
                }
            }
            return values;
        }
        return List.of(text(value));
    }

    /** Returns an unmodifiable copy of {@code list}, an empty list for {@code null}. */
    private static <T> List<T> copy(List<T> list) {
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Returns the 1-based line and column of {@code node} for error messages. */
    private static String position(Node node) {
        Mark mark = node == null ? null : node.getStartMark();
        return mark == null ? "in the RAML" : "at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
    }
}

