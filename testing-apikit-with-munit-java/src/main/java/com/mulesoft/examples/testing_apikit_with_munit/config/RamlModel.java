package com.mulesoft.examples.testing_apikit_with_munit.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * In-memory model of the RAML 0.8 contract named by {@code apikit.api-config.raml}, the file the
 * APIkit router {@code api-config} serves (D-009).
 *
 * <p>The constructor reads the RAML from the classpath once and keeps its bytes unchanged. It then
 * parses the text with SnakeYAML, with duplicate keys rejected and every {@code !include} value
 * replaced by the text of the named file in the RAML's classpath folder, and builds one immutable
 * {@link ConsoleModel}. The model is the input of the interactive console's {@code api.json}
 * (D-009), of {@code RamlRequestValidator} (D-046) and of {@code RamlContractTest}. A missing,
 * unreadable or malformed RAML file, including any element whose shape differs from the type its
 * rule below reads, fails construction with an {@link IllegalStateException} that names the file
 * (D-283).
 *
 * <p>Model rules:
 * <ul>
 *   <li>Root {@code title} becomes {@link ConsoleModel#title()}; root {@code schemas}, as a map or as
 *       a list of single-entry maps, defines named schemas.</li>
 *   <li>Every key starting with {@code /} is a resource. Nested resources are flattened into one list
 *       in file order, each parent before its children, with the full path (parent path plus key).
 *       {@code displayName} defaults to the resource's own key.</li>
 *   <li>A resource's URI parameters are its parent's followed by its own; an own declaration replaces
 *       the parent's declaration of the same name. URI parameters default to required, query
 *       parameters to optional; the parameter type defaults to {@code string}.</li>
 *   <li>The keys {@code get}, {@code post}, {@code put}, {@code delete}, {@code patch}, {@code head}
 *       and {@code options} are methods, in file order, named in upper case. Each method carries its
 *       {@code description}, {@code queryParameters}, request {@code body} media types and
 *       {@code responses} by status.</li>
 *   <li>A body {@code schema} that names a root schema is replaced by that schema's text.</li>
 *   <li>Every other RAML key is ignored. Every list is unmodifiable and never {@code null}, except
 *       {@link RamlParameter#enumValues()}, which is {@code null} when no {@code enum} is declared;
 *       each record's canonical constructor stores copies of its lists (D-283).</li>
 * </ul>
 *
 * <pre>{@code
 * RamlModel model = new RamlModel("api/api.raml", "/api", new DefaultResourceLoader());
 * RamlModel.RamlResource munit = model.consoleModel().resources().get(0);
 * munit.path();                                           // "/munit"
 * munit.methods().get(0).method();                        // "GET"
 * munit.methods().get(0).responses().get(0).status();     // 200
 * }</pre>
 */
@Component
public class RamlModel {

    private static final Logger LOG = LoggerFactory.getLogger(RamlModel.class);

    private static final String CLASSPATH_PREFIX = "classpath:";

    private static final Tag INCLUDE_TAG = new Tag("!include");

    private static final Set<String> METHOD_KEYS =
            Set.of("get", "post", "put", "delete", "patch", "head", "options");

    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private static final String DEFAULT_PARAMETER_TYPE = "string";

    private final byte[] ramlBytes;

    private final ConsoleModel consoleModel;

    /**
     * Reads the RAML file {@code classpath:<raml>}, parses it and builds the console model.
     *
     * @param raml           classpath location of the RAML file ({@code apikit.api-config.raml},
     *                       {@code api/api.raml}); {@code !include} names resolve against the part
     *                       before its last {@code /}
     * @param basePath       listener base path of the router ({@code apikit.api-config.base-path},
     *                       {@code /api}), copied into {@link ConsoleModel#basePath()}
     * @param resourceLoader loader for the RAML file and its includes
     * @throws IllegalStateException if the RAML file or an included file is missing or unreadable,
     *                               the text is not valid YAML, a mapping holds a duplicate key, the
     *                               root is not a mapping, or a RAML element has an unexpected shape
     *                               (D-283); the message names the file
     */
    public RamlModel(@Value("${apikit.api-config.raml}") String raml,
                     @Value("${apikit.api-config.base-path}") String basePath,
                     ResourceLoader resourceLoader) {
        if (raml == null || raml.isBlank()) {
            throw new IllegalStateException("RAML location apikit.api-config.raml is empty");
        }
        Objects.requireNonNull(resourceLoader, "resourceLoader");
        String location = CLASSPATH_PREFIX + raml;
        int lastSlash = raml.lastIndexOf('/');
        String ramlFolder = lastSlash < 0 ? "" : raml.substring(0, lastSlash);

        this.ramlBytes = readClasspath(resourceLoader, location, "RAML file");
        Map<?, ?> root = parse(location, ramlFolder, resourceLoader, this.ramlBytes);
        this.consoleModel = buildConsoleModel(location, basePath, root);

        LOG.info("Loaded RAML {}: title '{}', {} resource(s)",
                location, consoleModel.title(), consoleModel.resources().size());
    }

    /**
     * {@return the console model built from the RAML file; the same immutable instance on every call}
     */
    public ConsoleModel consoleModel() {
        return consoleModel;
    }

    /**
     * {@return a copy of the RAML file's bytes, exactly as stored on the classpath}
     */
    public byte[] ramlBytes() {
        return ramlBytes.clone();
    }

    /**
     * Reads every byte of a classpath resource.
     *
     * @param loader   resource loader
     * @param location {@code classpath:} location
     * @param kind     label used in the error message ({@code RAML file} or {@code RAML include})
     * @return the resource bytes
     * @throws IllegalStateException naming {@code location} if the resource is missing or unreadable
     */
    private static byte[] readClasspath(ResourceLoader loader, String location, String kind) {
        Resource resource = loader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalStateException(kind + " not found: " + location);
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException(kind + " cannot be read: " + location, ex);
        }
    }

    /**
     * Parses the RAML text with duplicate keys rejected and {@code !include} resolved by
     * {@link IncludeConstructor} (D-009).
     *
     * @return the root mapping
     * @throws IllegalStateException naming {@code location} for invalid YAML, a duplicate key, a
     *                               failed {@code !include} (the message also names the included
     *                               file) or a root that is not a mapping (D-283)
     */
    private static Map<?, ?> parse(String location, String ramlFolder, ResourceLoader loader, byte[] bytes) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new IncludeConstructor(options, ramlFolder, loader));
        Object root;
        try {
            root = yaml.load(new String(bytes, StandardCharsets.UTF_8));
        } catch (YAMLException ex) {
            throw new IllegalStateException("RAML file " + location + " is not valid: " + ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new IllegalStateException("RAML file " + location + ": " + ex.getMessage(), ex);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException("RAML file " + location + " does not hold a mapping at its root");
        }
        return map;
    }

    /**
     * Builds the console model from the parsed root mapping.
     */
    private static ConsoleModel buildConsoleModel(String location, String basePath, Map<?, ?> root) {
        Map<String, String> schemas = schemas(location, root.get("schemas"));
        List<RamlResource> resources = new ArrayList<>();
        collectResources(location, schemas, root, "", Map.of(), resources);
        return new ConsoleModel(text(root.get("title")), basePath, resources);
    }

    /**
     * Reads the root {@code schemas} element: a map of name to schema text, or a list of
     * single-entry maps of the same form.
     *
     * @return unmodifiable map of schema name to schema text, in file order
     * @throws IllegalStateException naming {@code location} for another shape or a name declared twice
     *                               (D-283)
     */
    private static Map<String, String> schemas(String location, Object value) {
        if (value == null) {
            return Map.of();
        }
        Map<String, String> schemas = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            putSchemas(location, schemas, map);
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) {
                    throw new IllegalStateException(
                            "RAML file " + location + ": schemas list entry is not a mapping: " + item);
                }
                putSchemas(location, schemas, entry);
            }
        } else {
            throw new IllegalStateException("RAML file " + location + ": schemas is neither a mapping nor a list");
        }
        return Collections.unmodifiableMap(schemas);
    }

    /**
     * Adds each name-to-text entry of {@code declared} to {@code schemas}.
     *
     * @throws IllegalStateException naming {@code location} when a name is already present
     */
    private static void putSchemas(String location, Map<String, String> schemas, Map<?, ?> declared) {
        for (Map.Entry<?, ?> entry : declared.entrySet()) {
            String name = String.valueOf(entry.getKey());
            if (schemas.containsKey(name)) {
                throw new IllegalStateException("RAML file " + location + ": schema " + name + " is declared twice");
            }
            schemas.put(name, text(entry.getValue()));
        }
    }

    /**
     * Appends, in file order, every resource declared in {@code container} and, after each one, its
     * nested resources.
     *
     * @param container    the root mapping or a resource mapping
     * @param parentPath   full path of {@code container} ({@code ""} for the root)
     * @param inheritedUri URI parameters declared by the enclosing resources, by name
     * @param out          receives the resources
     */
    private static void collectResources(String location, Map<String, String> schemas, Map<?, ?> container,
                                         String parentPath, Map<String, RamlParameter> inheritedUri,
                                         List<RamlResource> out) {
        for (Map.Entry<?, ?> entry : container.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!key.startsWith("/")) {
                continue;
            }
            String path = parentPath + key;
            Map<?, ?> resource = mapping(location, "resource " + path, entry.getValue());

            String displayName = text(resource.get("displayName"));
            Map<String, RamlParameter> uriParameters = new LinkedHashMap<>(inheritedUri);
            for (RamlParameter parameter : parameters(location, "resource " + path + " uriParameters",
                    resource.get("uriParameters"), true)) {
                uriParameters.put(parameter.name(), parameter);
            }

            List<RamlMethod> methods = new ArrayList<>();
            for (Map.Entry<?, ?> member : resource.entrySet()) {
                String memberKey = String.valueOf(member.getKey());
                if (METHOD_KEYS.contains(memberKey)) {
                    methods.add(method(location, schemas, path, memberKey, member.getValue()));
                }
            }

            out.add(new RamlResource(path, displayName == null ? key : displayName,
                    new ArrayList<>(uriParameters.values()), methods));
            collectResources(location, schemas, resource, path, uriParameters, out);
        }
    }

    /**
     * Builds one method of a resource.
     */
    private static RamlMethod method(String location, Map<String, String> schemas, String path,
                                     String key, Object value) {
        String verb = key.toUpperCase(Locale.ROOT);
        String where = verb + " " + path;
        Map<?, ?> method = mapping(location, where, value);
        return new RamlMethod(
                verb,
                text(method.get("description")),
                parameters(location, where + " queryParameters", method.get("queryParameters"), false),
                bodies(location, schemas, where + " body", method.get("body")),
                responses(location, schemas, where + " responses", method.get("responses")));
    }

    /**
     * Builds the named parameters of a {@code uriParameters} or {@code queryParameters} mapping.
     *
     * @param defaultRequired {@code required} value of a parameter that does not declare it
     */
    private static List<RamlParameter> parameters(String location, String where, Object value,
                                                  boolean defaultRequired) {
        Map<?, ?> declared = mapping(location, where, value);
        List<RamlParameter> parameters = new ArrayList<>();
        for (Map.Entry<?, ?> entry : declared.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String parameterWhere = where + " " + name;
            Map<?, ?> properties = mapping(location, parameterWhere, entry.getValue());
            String type = text(properties.get("type"));
            parameters.add(new RamlParameter(
                    name,
                    type == null ? DEFAULT_PARAMETER_TYPE : type,
                    bool(location, parameterWhere + " required", properties.get("required"), defaultRequired),
                    strings(location, parameterWhere + " enum", properties.get("enum")),
                    integer(location, parameterWhere + " minLength", properties.get("minLength")),
                    integer(location, parameterWhere + " maxLength", properties.get("maxLength")),
                    text(properties.get("default")),
                    text(properties.get("example"))));
        }
        return parameters;
    }

    /**
     * Builds one body per media-type key of a {@code body} mapping.
     */
    private static List<RamlBody> bodies(String location, Map<String, String> schemas, String where,
                                         Object value) {
        Map<?, ?> declared = mapping(location, where, value);
        List<RamlBody> bodies = new ArrayList<>();
        for (Map.Entry<?, ?> entry : declared.entrySet()) {
            String mediaType = String.valueOf(entry.getKey());
            Map<?, ?> body = mapping(location, where + " " + mediaType, entry.getValue());
            String schema = text(body.get("schema"));
            if (schema != null && schemas.containsKey(schema)) {
                schema = schemas.get(schema);
            }
            bodies.add(new RamlBody(mediaType, schema, text(body.get("example"))));
        }
        return bodies;
    }

    /**
     * Builds one response per status key of a {@code responses} mapping.
     */
    private static List<RamlResponse> responses(String location, Map<String, String> schemas, String where,
                                                Object value) {
        Map<?, ?> declared = mapping(location, where, value);
        List<RamlResponse> responses = new ArrayList<>();
        for (Map.Entry<?, ?> entry : declared.entrySet()) {
            int status = status(location, where, entry.getKey());
            String responseWhere = where + " " + status;
            Map<?, ?> response = mapping(location, responseWhere, entry.getValue());
            responses.add(new RamlResponse(
                    status,
                    text(response.get("description")),
                    bodies(location, schemas, responseWhere + " body", response.get("body"))));
        }
        return responses;
    }

    /**
     * Reads a response status key: an {@code Integer}, or a {@code String} of digits.
     *
     * @throws IllegalStateException naming {@code location} for any other key (D-283)
     */
    private static int status(String location, String where, Object key) {
        if (key instanceof Integer status) {
            return status;
        }
        if (key instanceof String digits && DIGITS.matcher(digits).matches()) {
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException(
                        "RAML file " + location + ": " + where + " status is out of range: " + key, ex);
            }
        }
        throw new IllegalStateException("RAML file " + location + ": " + where + " status is not a number: " + key);
    }

    /**
     * {@return {@code value} as a mapping; an empty mapping for {@code null}}
     *
     * @throws IllegalStateException naming {@code location} when {@code value} is not a mapping (D-283)
     */
    private static Map<?, ?> mapping(String location, String where, Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw new IllegalStateException("RAML file " + location + ": " + where + " is not a mapping");
    }

    /**
     * {@return {@code value} as a boolean; {@code defaultValue} for {@code null}}
     *
     * @throws IllegalStateException naming {@code location} when {@code value} is not a boolean (D-283)
     */
    private static boolean bool(String location, String where, Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        throw new IllegalStateException("RAML file " + location + ": " + where + " is not a boolean: " + value);
    }

    /**
     * {@return {@code value} as an {@code Integer} through {@link Number#intValue()}; {@code null} for {@code null}}
     *
     * @throws IllegalStateException naming {@code location} when {@code value} is not a number (D-283)
     */
    private static Integer integer(String location, String where, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalStateException("RAML file " + location + ": " + where + " is not a number: " + value);
    }

    /**
     * {@return {@code value} as an unmodifiable list of strings; {@code null} for {@code null}}
     *
     * @throws IllegalStateException naming {@code location} when {@code value} is not a list (D-283)
     */
    private static List<String> strings(String location, String where, Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        throw new IllegalStateException("RAML file " + location + ": " + where + " is not a list: " + value);
    }

    /**
     * {@return {@code String.valueOf(value)}; {@code null} for {@code null}}
     */
    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * The console model served as {@code api.json} (D-009).
     *
     * @param title     the RAML {@code title}, or {@code null} when absent
     * @param basePath  listener base path of the router ({@code apikit.api-config.base-path})
     * @param resources every resource, nested ones flattened, in file order; unmodifiable
     */
    public record ConsoleModel(String title, String basePath, List<RamlResource> resources) {

        /** Stores an unmodifiable copy of {@code resources}. */
        public ConsoleModel {
            resources = List.copyOf(resources);
        }
    }

    /**
     * One RAML resource.
     *
     * @param path          full resource path, for example {@code /munit}
     * @param displayName   the RAML {@code displayName}, or the resource's own key when absent
     * @param uriParameters URI parameters of the enclosing resources followed by the resource's own;
     *                      unmodifiable
     * @param methods       methods in file order; unmodifiable
     */
    public record RamlResource(String path, String displayName, List<RamlParameter> uriParameters,
                               List<RamlMethod> methods) {

        /** Stores unmodifiable copies of the lists. */
        public RamlResource {
            uriParameters = List.copyOf(uriParameters);
            methods = List.copyOf(methods);
        }
    }

    /**
     * One method of a resource.
     *
     * @param method          upper-case method name, for example {@code GET}
     * @param description     the RAML {@code description}, or {@code null} when absent
     * @param queryParameters declared query parameters; unmodifiable
     * @param request         request bodies, one per declared media type; unmodifiable
     * @param responses       responses in file order; unmodifiable
     */
    public record RamlMethod(String method, String description, List<RamlParameter> queryParameters,
                             List<RamlBody> request, List<RamlResponse> responses) {

        /** Stores unmodifiable copies of the lists. */
        public RamlMethod {
            queryParameters = List.copyOf(queryParameters);
            request = List.copyOf(request);
            responses = List.copyOf(responses);
        }
    }

    /**
     * One declared response of a method.
     *
     * @param status      HTTP status code
     * @param description the RAML {@code description}, or {@code null} when absent
     * @param bodies      response bodies, one per declared media type; unmodifiable
     */
    public record RamlResponse(int status, String description, List<RamlBody> bodies) {

        /** Stores an unmodifiable copy of {@code bodies}. */
        public RamlResponse {
            bodies = List.copyOf(bodies);
        }
    }

    /**
     * One request or response body.
     *
     * @param mediaType declared media type, for example {@code application/json}
     * @param schema    schema text (a named root schema is replaced by its text), or {@code null}
     * @param example   example text, or {@code null}
     */
    public record RamlBody(String mediaType, String schema, String example) {
    }

    /**
     * One URI or query parameter.
     *
     * @param name         parameter name
     * @param type         RAML type; {@code string} when not declared
     * @param required     the RAML {@code required}; {@code true} for URI and {@code false} for query
     *                     parameters when not declared
     * @param enumValues   allowed values, or {@code null} when no {@code enum} is declared; unmodifiable
     * @param minLength    minimum length, or {@code null}
     * @param maxLength    maximum length, or {@code null}
     * @param defaultValue the RAML {@code default}, or {@code null}
     * @param example      the RAML {@code example}, or {@code null}
     */
    public record RamlParameter(String name, String type, boolean required, List<String> enumValues,
                                Integer minLength, Integer maxLength, String defaultValue, String example) {

        /** Stores an unmodifiable copy of {@code enumValues} when present. */
        public RamlParameter {
            enumValues = enumValues == null ? null : List.copyOf(enumValues);
        }
    }

    /**
     * SnakeYAML constructor that resolves the RAML {@code !include} tag (D-009).
     *
     * <p>An {@code !include} scalar names a file relative to the RAML's classpath folder; its value
     * becomes the UTF-8 text of {@code classpath:<folder>/<name>} ({@code classpath:<name>} when the
     * RAML has no folder). All other nodes are constructed by {@link Constructor}.
     */
    private static final class IncludeConstructor extends Constructor implements Construct {

        private final String folder;

        private final ResourceLoader loader;

        /**
         * Registers this instance for the {@code !include} tag.
         *
         * @param options loading options; {@code new Yaml(constructor)} applies them to the parser
         * @param folder  classpath folder of the RAML file; {@code ""} for the classpath root
         * @param loader  loader for the included files
         */
        IncludeConstructor(LoaderOptions options, String folder, ResourceLoader loader) {
            super(options);
            this.folder = folder;
            this.loader = loader;
            this.yamlConstructors.put(INCLUDE_TAG, this);
        }

        /**
         * {@return the UTF-8 text of the file the {@code !include} scalar names}
         *
         * @throws IllegalStateException naming the file when it is missing or unreadable, or when the
         *                               tagged node is not a scalar (D-283)
         */
        @Override
        public Object construct(Node node) {
            if (!(node instanceof ScalarNode scalar)) {
                throw new IllegalStateException("RAML !include is not a file name at " + node.getStartMark());
            }
            String name = constructScalar(scalar);
            String location = CLASSPATH_PREFIX + (folder.isEmpty() ? name : folder + "/" + name);
            return new String(readClasspath(loader, location, "RAML include"), StandardCharsets.UTF_8);
        }

        /**
         * Rejects a second construction step; {@code !include} scalars complete in one step.
         *
         * @throws IllegalStateException always
         */
        @Override
        public void construct2ndStep(Node node, Object object) {
            throw new IllegalStateException("RAML !include has no second construction step at " + node.getStartMark());
        }
    }
}
