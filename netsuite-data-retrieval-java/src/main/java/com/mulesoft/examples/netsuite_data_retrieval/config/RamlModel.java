package com.mulesoft.examples.netsuite_data_retrieval.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Parsed model of the RAML 0.8 contract {@code classpath:api/netsuite-api.raml}, the RAML of the APIkit
 * router {@code netsuite-api-config} and of its console at {@code console} (D-009).
 *
 * <p>The public constructor reads the RAML file once, as UTF-8, and parses it with SnakeYAML's
 * {@link SafeConstructor} plus the local tag {@code !include}. Each included value becomes an
 * {@link Include}: the trimmed file name and the text of that file in the include directory
 * {@code api/}, decoded as UTF-8 and kept verbatim, never parsed as JSON (D-045). One
 * {@link LoaderOptions} instance, with duplicate mapping keys rejected, configures both the YAML
 * parser and the constructor (D-506). The parsed model is immutable; {@link #toConsoleModel()} renders
 * it as the console model {@code api.json} (D-009), and {@code RamlRequestValidator} and
 * {@code RamlContractTest} read it through {@link #findMethod(String, String)} and
 * {@link #resources()}.
 *
 * <p>Model rules:
 * <ul>
 *   <li>Root {@code title}, {@code version} and {@code baseUri} are read as text.</li>
 *   <li>Every mapping key starting with {@code /} is a {@link Resource}. Nested resources form one flat
 *       list in file order, each parent before its children, with the full path (parent path plus
 *       key).</li>
 *   <li>The resource keys {@code get}, {@code post}, {@code put}, {@code delete}, {@code patch},
 *       {@code head} and {@code options} are its {@link Method}s, in file order, named in upper case.
 *       Every other resource key that does not start with {@code /} is ignored.</li>
 *   <li>A method carries its {@code description}, its {@code queryParameters} as
 *       {@link QueryParameter}s in file order and its {@code responses} as {@link Response}s: one per
 *       status and {@code body} media type in file order, or one without a media type for a status
 *       that declares no {@code body}. Every other method key is ignored.</li>
 *   <li>A query parameter's {@code type} defaults to {@code string}; a declared type is exactly one of
 *       {@code string}, {@code number}, {@code integer}, {@code date}, {@code boolean} and
 *       {@code file} (D-506).</li>
 *   <li>A missing, unreadable or non-UTF-8 RAML or included file, YAML that does not parse, a
 *       duplicate mapping key, an {@code !include} name that is not a relative file path inside the
 *       include directory, an element of another shape than its rule reads, a resource that refers
 *       back to an enclosing resource, and a model of more than 100,000 elements fail construction
 *       with an {@link IllegalStateException} that names the file (D-506).</li>
 * </ul>
 *
 * <pre>{@code
 * RamlModel model = new RamlModel();
 * model.title();                                           // "Netsuite-Data-retrieval-Example"
 * model.resources().get(1).path();                         // "/items"
 * model.findMethod("/items", "get").orElseThrow()
 *         .queryParameters().get(0).defaultValue();        // Integer 0
 * model.toConsoleModel().get("baseUri");                   // "http://localhost:8081/api"
 * }</pre>
 */
@Component
public class RamlModel {

    /** Classpath location of the RAML file. */
    public static final String RAML_LOCATION = "api/netsuite-api.raml";

    /** Classpath folder in which {@code !include} file names are resolved. */
    public static final String INCLUDE_DIRECTORY = "api/";

    private static final Logger LOG = LoggerFactory.getLogger(RamlModel.class);

    private static final String CLASSPATH_PREFIX = "classpath:";

    private static final String INCLUDE_TAG = "!include";

    private static final List<String> METHOD_KEYS =
            List.of("get", "post", "put", "delete", "patch", "head", "options");

    private static final String DEFAULT_PARAMETER_TYPE = "string";

    /** RAML 0.8 named-parameter types, matched exactly and case-sensitively (D-506). */
    private static final List<String> PARAMETER_TYPES =
            List.of("string", "number", "integer", "date", "boolean", "file");

    /**
     * Largest number of model elements: resources, methods, query parameters, enum values and
     * responses (D-506).
     */
    private static final int MAX_MODEL_ELEMENTS = 100_000;

    private final String title;

    private final String version;

    private final String baseUri;

    private final List<Resource> resources;

    /**
     * Reads and parses {@code classpath:api/netsuite-api.raml}, resolving its {@code !include} file
     * names in {@code classpath:api/}.
     *
     * @throws IllegalStateException naming the file, when the RAML file or an included file is
     *                               missing or unreadable, or the RAML breaks a model rule of this
     *                               class
     */
    public RamlModel() {
        this(RAML_LOCATION, INCLUDE_DIRECTORY);
    }

    /**
     * Reads and parses the RAML file {@code classpath:<ramlLocation>}, resolving its {@code !include}
     * file names in {@code classpath:<includeDirectory>}.
     *
     * @param ramlLocation     classpath location of the RAML file, without the {@code classpath:}
     *                         prefix
     * @param includeDirectory classpath folder of the included files; a missing trailing {@code /} is
     *                         added
     * @throws IllegalStateException naming the file, when the RAML file or an included file is
     *                               missing or unreadable, or the RAML breaks a model rule of this
     *                               class
     */
    RamlModel(String ramlLocation, String includeDirectory) {
        Objects.requireNonNull(ramlLocation, "ramlLocation");
        Objects.requireNonNull(includeDirectory, "includeDirectory");
        String directory = includeDirectory.isEmpty() || includeDirectory.endsWith("/")
                ? includeDirectory
                : includeDirectory + "/";
        String ramlFile = CLASSPATH_PREFIX + ramlLocation;

        String text = readText(ramlLocation, "RAML file", "");
        Map<?, ?> root = parse(ramlFile, text, directory);

        ModelReader reader = new ModelReader(ramlFile);
        this.title = reader.text(root.get("title"), "title");
        this.version = reader.text(root.get("version"), "version");
        this.baseUri = reader.text(root.get("baseUri"), "baseUri");
        this.resources = reader.resources(root);

        LOG.info("Loaded RAML {}: title '{}', version '{}', {} resource(s)",
                ramlFile, title, version, resources.size());
    }

    /**
     * {@return the root {@code title} as text, or {@code null} when the RAML declares none}
     */
    public String title() {
        return title;
    }

    /**
     * {@return the root {@code version} as text, or {@code null} when the RAML declares none}
     */
    public String version() {
        return version;
    }

    /**
     * {@return the root {@code baseUri} as text, or {@code null} when the RAML declares none}
     */
    public String baseUri() {
        return baseUri;
    }

    /**
     * {@return every resource of the RAML, nested ones included, in file order with each parent
     * before its children; the list is unmodifiable}
     */
    public List<Resource> resources() {
        return resources;
    }

    /**
     * Finds the RAML method declared for a resource path and an HTTP method.
     *
     * @param resourcePath full resource path exactly as {@link Resource#path()} holds it, for example
     *                     {@code /items}
     * @param httpMethod   HTTP method, matched case-insensitively, for example {@code get} or
     *                     {@code GET}
     * @return the first matching method, or an empty {@link Optional} when either argument is
     *         {@code null} or no resource with that path declares the method
     */
    public Optional<Method> findMethod(String resourcePath, String httpMethod) {
        if (resourcePath == null || httpMethod == null) {
            return Optional.empty();
        }
        for (Resource resource : resources) {
            if (!resource.path().equals(resourcePath)) {
                continue;
            }
            for (Method method : resource.methods()) {
                if (method.method().equalsIgnoreCase(httpMethod)) {
                    return Optional.of(method);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Builds the console model served as {@code api.json} (D-009): a new structure on every call,
     * made of {@link LinkedHashMap}s and {@link ArrayList}s only, with the keys in this order:
     *
     * <pre>{@code
     * {title, version, baseUri,
     *  resources: [{path,
     *    methods: [{method, description,
     *      queryParameters: [{name, description, type, enum, default}],
     *      uriParameters: [],
     *      body: null,
     *      responses: [{status, mediaType, schema: null, example, exampleFile}]}]}]}
     * }</pre>
     *
     * <p>{@code enum} and {@code default} are {@code null} when the parameter declares none;
     * {@code status} is an {@link Integer}; {@code example} is the included file text verbatim
     * (D-045).
     *
     * @return the console model
     */
    public Map<String, Object> toConsoleModel() {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("title", title);
        model.put("version", version);
        model.put("baseUri", baseUri);
        List<Object> resourceList = new ArrayList<>(resources.size());
        for (Resource resource : resources) {
            Map<String, Object> resourceEntry = new LinkedHashMap<>();
            resourceEntry.put("path", resource.path());
            List<Object> methodList = new ArrayList<>(resource.methods().size());
            for (Method method : resource.methods()) {
                methodList.add(consoleMethod(method));
            }
            resourceEntry.put("methods", methodList);
            resourceList.add(resourceEntry);
        }
        model.put("resources", resourceList);
        return model;
    }

    /**
     * Renders one method of the console model.
     *
     * @param method the RAML method
     * @return the method entry of {@link #toConsoleModel()}
     */
    private static Map<String, Object> consoleMethod(Method method) {
        Map<String, Object> methodEntry = new LinkedHashMap<>();
        methodEntry.put("method", method.method());
        methodEntry.put("description", method.description());
        List<Object> parameterList = new ArrayList<>(method.queryParameters().size());
        for (QueryParameter parameter : method.queryParameters()) {
            Map<String, Object> parameterEntry = new LinkedHashMap<>();
            parameterEntry.put("name", parameter.name());
            parameterEntry.put("description", parameter.description());
            parameterEntry.put("type", parameter.type());
            parameterEntry.put("enum",
                    parameter.enumValues() == null ? null : new ArrayList<Object>(parameter.enumValues()));
            parameterEntry.put("default", parameter.defaultValue());
            parameterList.add(parameterEntry);
        }
        methodEntry.put("queryParameters", parameterList);
        methodEntry.put("uriParameters", new ArrayList<Object>());
        methodEntry.put("body", null);
        List<Object> responseList = new ArrayList<>(method.responses().size());
        for (Response response : method.responses()) {
            Map<String, Object> responseEntry = new LinkedHashMap<>();
            responseEntry.put("status", response.status());
            responseEntry.put("mediaType", response.mediaType());
            responseEntry.put("schema", null);
            responseEntry.put("example", response.example());
            responseEntry.put("exampleFile", response.exampleFile());
            responseList.add(responseEntry);
        }
        methodEntry.put("responses", responseList);
        return methodEntry;
    }

    /**
     * Parses the RAML text into its root mapping.
     *
     * @param ramlFile         {@code classpath:} location of the RAML file, named in error messages
     * @param text             the RAML text
     * @param includeDirectory classpath folder of the included files, ending with {@code /} or empty
     * @return the root mapping, in file order
     * @throws IllegalStateException naming the file, when the text is not valid YAML, holds a
     *                               duplicate mapping key, has an invalid {@code !include}, or its
     *                               root is not a mapping
     */
    private static Map<?, ?> parse(String ramlFile, String text, String includeDirectory) {
        // Duplicate mapping keys fail the load (D-506); the Yaml parser reads the constructor's options.
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Yaml yaml = new Yaml(new IncludeConstructor(options, ramlFile, includeDirectory));
        Object root;
        try {
            root = yaml.load(text);
        } catch (YAMLException ex) {
            throw new IllegalStateException(
                    "RAML file " + ramlFile + " is not valid YAML: " + ex.getMessage(), ex);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalStateException(
                    "RAML file " + ramlFile + ": the document root is not a mapping");
        }
        return map;
    }

    /**
     * Reads a classpath file as UTF-8 text, every byte kept.
     *
     * @param location classpath location, without the {@code classpath:} prefix
     * @param kind     label of the file in error messages ({@code RAML file} or {@code RAML include})
     * @param context  text that starts each error message, empty for none
     * @return the decoded text
     * @throws IllegalStateException naming {@code classpath:<location>}, when the file is missing, is
     *                               not a readable file, cannot be read or is not valid UTF-8
     */
    private static String readText(String location, String kind, String context) {
        String classpathLocation = CLASSPATH_PREFIX + location;
        ClassPathResource resource = new ClassPathResource(location, RamlModel.class.getClassLoader());
        if (!resource.exists()) {
            throw new IllegalStateException(context + kind + " not found: " + classpathLocation);
        }
        if (!resource.isReadable()) {
            throw new IllegalStateException(context + kind + " is not a readable file: " + classpathLocation);
        }
        byte[] bytes;
        try (InputStream in = resource.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException(context + kind + " cannot be read: " + classpathLocation, ex);
        }
        // Malformed or unmappable UTF-8 fails the read instead of being replaced (D-506).
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalStateException(context + kind + " is not valid UTF-8: " + classpathLocation, ex);
        }
    }

    /**
     * Checks that a trimmed {@code !include} name is a relative file path inside the include
     * directory: not empty, no leading {@code /}, no {@code \}, and no empty, {@code .} or {@code ..}
     * path segment (D-506).
     *
     * @param file    the trimmed include name
     * @param context text that starts the error message
     * @throws IllegalStateException when the name breaks the rule
     */
    private static void checkIncludeName(String file, String context) {
        boolean valid = !file.isEmpty() && !file.startsWith("/") && file.indexOf('\\') < 0;
        if (valid) {
            for (String segment : file.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    valid = false;
                    break;
                }
            }
        }
        if (!valid) {
            throw new IllegalStateException(context + "'" + file
                    + "' is not a relative file path inside the include directory");
        }
    }

    /**
     * Describes a YAML node position for error messages.
     *
     * @param mark start mark of the node, possibly {@code null}
     * @return {@code line <n>, column <m>}, both 1-based, or {@code unknown position}
     */
    private static String position(Mark mark) {
        if (mark == null) {
            return "unknown position";
        }
        return "line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
    }


    /**
     * {@link SafeConstructor} with the local tag {@code !include}: a tagged scalar becomes the
     * {@link Include} of the named file in the include directory, its text read verbatim as UTF-8
     * (D-045). No other custom tag is constructed.
     */
    private static final class IncludeConstructor extends SafeConstructor {

        private final String ramlFile;

        private final String includeDirectory;

        /**
         * Creates the constructor and registers {@code !include}.
         *
         * @param options          loading options, the same instance the {@link Yaml} parser uses
         * @param ramlFile         {@code classpath:} location of the RAML file, named in error messages
         * @param includeDirectory classpath folder of the included files, ending with {@code /} or
         *                         empty
         */
        IncludeConstructor(LoaderOptions options, String ramlFile, String includeDirectory) {
            super(options);
            this.ramlFile = ramlFile;
            this.includeDirectory = includeDirectory;
            this.yamlConstructors.put(new Tag(INCLUDE_TAG), new ConstructInclude());
        }

        /**
         * Builds the {@link Include} of one {@code !include} node.
         */
        private final class ConstructInclude extends AbstractConstruct {

            /**
             * Reads the file named by the scalar.
             *
             * @param node the {@code !include} node
             * @return the include: trimmed file name and verbatim UTF-8 text (D-045)
             * @throws IllegalStateException naming the RAML file, the include position and the
             *                               included file, when the node is not a scalar, the name
             *                               breaks the include name rule, or the file is missing,
             *                               unreadable or not UTF-8
             */
            @Override
            public Object construct(Node node) {
                String context = "RAML file " + ramlFile + ": " + INCLUDE_TAG + " at "
                        + position(node.getStartMark()) + ": ";
                if (!(node instanceof ScalarNode scalar)) {
                    throw new IllegalStateException(context + "the tag is not applied to a scalar");
                }
                String file = constructScalar(scalar).trim();
                checkIncludeName(file, context);
                return new Include(file, readText(includeDirectory + file, "RAML include", context));
            }
        }
    }

    /**
     * Reads the parsed YAML tree into the model records, applying the model rules of
     * {@link RamlModel}. One instance reads one RAML file.
     */
    private static final class ModelReader {

        private final String prefix;

        /** Resource mappings on the path from the root to the resource being read, by identity. */
        private final Set<Object> enclosing = Collections.newSetFromMap(new IdentityHashMap<>());

        private int elements;

        /**
         * @param ramlFile {@code classpath:} location of the RAML file, named in error messages
         */
        ModelReader(String ramlFile) {
            this.prefix = "RAML file " + ramlFile + ": ";
        }

        /**
         * Reads every resource below the root, in file order, each parent before its children.
         *
         * @param root the root mapping
         * @return the unmodifiable resource list
         */
        private List<Resource> resources(Map<?, ?> root) {
            List<Resource> found = new ArrayList<>();
            enclosing.add(root);
            collectResources(root, "", found);
            enclosing.remove(root);
            return List.copyOf(found);
        }

        /**
         * Adds each resource of a mapping, then its nested resources, to {@code found}.
         *
         * @param parent     the root or resource mapping
         * @param parentPath full path of {@code parent}, empty for the root
         * @param found      the resources read so far
         */
        private void collectResources(Map<?, ?> parent, String parentPath, List<Resource> found) {
            for (Map.Entry<?, ?> entry : parent.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !key.startsWith("/")) {
                    continue;
                }
                String path = parentPath + key;
                String where = "resource " + path;
                count(1, where);
                Object value = entry.getValue();
                Map<?, ?> resource = mapping(value, where);
                // A resource mapping that is also an enclosing resource mapping fails the load (D-506).
                if (value != null && enclosing.contains(value)) {
                    throw new IllegalStateException(prefix + where + " refers back to an enclosing resource");
                }
                found.add(new Resource(path, methods(resource, path)));
                if (value != null) {
                    enclosing.add(value);
                    collectResources(resource, path, found);
                    enclosing.remove(value);
                }
            }
        }

        /**
         * Reads the methods of one resource.
         *
         * @param resource the resource mapping
         * @param path     full path of the resource
         * @return the methods in file order
         */
        private List<Method> methods(Map<?, ?> resource, String path) {
            List<Method> methods = new ArrayList<>();
            for (Map.Entry<?, ?> entry : resource.entrySet()) {
                if (!(entry.getKey() instanceof String key) || !METHOD_KEYS.contains(key)) {
                    continue;
                }
                String verb = key.toUpperCase(Locale.ROOT);
                String where = verb + " " + path;
                count(1, where);
                Map<?, ?> method = mapping(entry.getValue(), where);
                methods.add(new Method(
                        verb,
                        text(method.get("description"), where + " description"),
                        queryParameters(method.get("queryParameters"), where),
                        responses(method.get("responses"), where)));
            }
            return methods;
        }

        /**
         * Reads the {@code queryParameters} of one method.
         *
         * @param value the {@code queryParameters} value, possibly {@code null}
         * @param where method label for error messages
         * @return the parameters in file order
         */
        private List<QueryParameter> queryParameters(Object value, String where) {
            Map<?, ?> declared = mapping(value, where + " queryParameters");
            List<QueryParameter> parameters = new ArrayList<>(declared.size());
            for (Map.Entry<?, ?> entry : declared.entrySet()) {
                String name = key(entry.getKey(), where + " query parameter name");
                String at = where + " query parameter " + name;
                count(1, at);
                Map<?, ?> parameter = mapping(entry.getValue(), at);
                parameters.add(new QueryParameter(
                        name,
                        text(parameter.get("description"), at + " description"),
                        type(parameter.get("type"), at),
                        enumValues(parameter.get("enum"), at),
                        scalar(parameter.get("default"), at + " default")));
            }
            return parameters;
        }

        /**
         * Reads a parameter {@code type}.
         *
         * @param value the {@code type} value, possibly {@code null}
         * @param at    parameter label for error messages
         * @return the declared type, or {@code string} when none is declared
         * @throws IllegalStateException when the type is not a RAML 0.8 named-parameter type (D-506)
         */
        private String type(Object value, String at) {
            String type = text(value, at + " type");
            if (type == null) {
                return DEFAULT_PARAMETER_TYPE;
            }
            if (!PARAMETER_TYPES.contains(type)) {
                throw new IllegalStateException(prefix + at + " type '" + type + "' is not one of "
                        + String.join(", ", PARAMETER_TYPES));
            }
            return type;
        }

        /**
         * Reads a parameter {@code enum}.
         *
         * @param value the {@code enum} value, possibly {@code null}
         * @param at    parameter label for error messages
         * @return the values as text in declared order, or {@code null} when no {@code enum} is
         *         declared
         */
        private List<String> enumValues(Object value, String at) {
            if (value == null) {
                return null;
            }
            String where = at + " enum";
            if (!(value instanceof List<?> list)) {
                throw new IllegalStateException(prefix + where + " is " + describe(value) + ", not a sequence");
            }
            count(list.size(), where);
            List<String> values = new ArrayList<>(list.size());
            for (Object item : list) {
                values.add(key(item, where + " value"));
            }
            return values;
        }

        /**
         * Reads the {@code responses} of one method.
         *
         * @param value the {@code responses} value, possibly {@code null}
         * @param where method label for error messages
         * @return one response per status and media type, in file order
         */
        private List<Response> responses(Object value, String where) {
            Map<?, ?> declared = mapping(value, where + " responses");
            List<Response> responses = new ArrayList<>(declared.size());
            for (Map.Entry<?, ?> entry : declared.entrySet()) {
                int status = status(entry.getKey(), where);
                String at = where + " response " + status;
                Map<?, ?> response = mapping(entry.getValue(), at);
                Map<?, ?> body = mapping(response.get("body"), at + " body");
                if (body.isEmpty()) {
                    count(1, at);
                    responses.add(new Response(status, null, null, null));
                    continue;
                }
                for (Map.Entry<?, ?> media : body.entrySet()) {
                    String mediaType = key(media.getKey(), at + " body media type");
                    String bodyAt = at + " body " + mediaType;
                    count(1, bodyAt);
                    Object example = mapping(media.getValue(), bodyAt).get("example");
                    if (example instanceof Include include) {
                        responses.add(new Response(status, mediaType, include.text(), include.file()));
                    } else {
                        responses.add(new Response(status, mediaType, text(example, bodyAt + " example"), null));
                    }
                }
            }
            return responses;
        }

        /**
         * Reads a response status key, an {@link Integer} or a digit string.
         *
         * @param key   the status key
         * @param where method label for error messages
         * @return the status code
         */
        private int status(Object key, String where) {
            String text = key(key, where + " response status");
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ex) {
                throw new IllegalStateException(
                        prefix + where + " response status '" + text + "' is not an integer", ex);
            }
        }

        /**
         * Reads a value that is a mapping or absent.
         *
         * @param value the value, possibly {@code null}
         * @param where element label for error messages
         * @return the mapping, or an empty mapping for {@code null}
         * @throws IllegalStateException when the value is neither {@code null} nor a mapping
         */
        private Map<?, ?> mapping(Object value, String where) {
            if (value == null) {
                return Map.of();
            }
            if (value instanceof Map<?, ?> map) {
                return map;
            }
            throw new IllegalStateException(prefix + where + " is " + describe(value) + ", not a mapping");
        }

        /**
         * Reads a text element: an included file's text, or any other scalar as {@link String#valueOf}.
         *
         * @param value the value, possibly {@code null}
         * @param where element label for error messages
         * @return the text, or {@code null} for {@code null}
         * @throws IllegalStateException when the value is a mapping, sequence or set
         */
        private String text(Object value, String where) {
            Object scalar = scalar(value, where);
            return scalar == null ? null : String.valueOf(scalar);
        }

        /**
         * Reads a mapping key or sequence item that must be present.
         *
         * @param value the value
         * @param where element label for error messages
         * @return the text
         * @throws IllegalStateException when the value is {@code null}, a mapping, a sequence or a set
         */
        private String key(Object value, String where) {
            String text = text(value, where);
            if (text == null) {
                throw new IllegalStateException(prefix + where + " is null");
            }
            return text;
        }

        /**
         * Reads a scalar element: an included file's text, or the parsed YAML scalar unchanged.
         *
         * @param value the value, possibly {@code null}
         * @param where element label for error messages
         * @return the scalar, or {@code null} for {@code null}
         * @throws IllegalStateException when the value is a mapping, sequence or set
         */
        private Object scalar(Object value, String where) {
            if (value instanceof Include include) {
                return include.text();
            }
            if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
                throw new IllegalStateException(prefix + where + " is " + describe(value) + ", not a scalar");
            }
            return value;
        }

        /**
         * Adds model elements to the count.
         *
         * @param added number of elements read
         * @param where element label for error messages
         * @throws IllegalStateException when the count exceeds the element limit (D-506)
         */
        private void count(int added, String where) {
            if (added > MAX_MODEL_ELEMENTS - elements) {
                throw new IllegalStateException(prefix + where + ": the model exceeds " + MAX_MODEL_ELEMENTS
                        + " elements (resources, methods, query parameters, enum values and responses)");
            }
            elements += added;
        }

        /**
         * Names the YAML kind of a value for error messages.
         *
         * @param value the value
         * @return {@code a mapping}, {@code a sequence or set}, or {@code a scalar}
         */
        private static String describe(Object value) {
            if (value instanceof Map<?, ?>) {
                return "a mapping";
            }
            if (value instanceof Collection<?>) {
                return "a sequence or set";
            }
            return "a scalar";
        }
    }

    /**
     * The value of one {@code !include}: the trimmed file name and the file's UTF-8 text, verbatim
     * (D-045).
     *
     * @param file the include name, relative to the include directory, for example
     *             {@code customers-response.json}
     * @param text the file content
     */
    public record Include(String file, String text) {

        /**
         * @throws NullPointerException when {@code file} or {@code text} is {@code null}
         */
        public Include {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * One RAML resource.
     *
     * @param path    full resource path: the parent's path plus the RAML key, for example
     *                {@code /customers}
     * @param methods the resource's methods in file order; stored as an unmodifiable copy
     */
    public record Resource(String path, List<Method> methods) {

        /**
         * @throws NullPointerException when {@code path} or {@code methods} or one of its elements is
         *                              {@code null}
         */
        public Resource {
            Objects.requireNonNull(path, "path");
            methods = List.copyOf(methods);
        }
    }

    /**
     * One method of a RAML resource.
     *
     * @param method          the HTTP method in upper case, for example {@code GET}
     * @param description     the RAML {@code description}, or {@code null}
     * @param queryParameters the declared query parameters in file order; stored as an unmodifiable
     *                        copy
     * @param responses       the declared responses, one per status and media type, in file order;
     *                        stored as an unmodifiable copy
     */
    public record Method(String method, String description, List<QueryParameter> queryParameters,
                         List<Response> responses) {

        /**
         * @throws NullPointerException when {@code method}, a list or a list element is {@code null}
         */
        public Method {
            Objects.requireNonNull(method, "method");
            queryParameters = List.copyOf(queryParameters);
            responses = List.copyOf(responses);
        }
    }

    /**
     * One query parameter of a RAML method.
     *
     * @param name         the parameter name
     * @param description  the RAML {@code description}, or {@code null}
     * @param type         the RAML {@code type}; the model reads {@code string} when none is declared
     * @param enumValues   the {@code enum} values as text in declared order, stored as an
     *                     unmodifiable copy, or {@code null} when no {@code enum} is declared
     * @param defaultValue the parsed YAML scalar of {@code default}, for example {@link Integer}
     *                     {@code 0} or {@code "GREATER_THAN"}, or {@code null}
     */
    public record QueryParameter(String name, String description, String type, List<String> enumValues,
                                 Object defaultValue) {

        /**
         * @throws NullPointerException when {@code name} or an {@code enumValues} element is
         *                              {@code null}
         */
        public QueryParameter {
            Objects.requireNonNull(name, "name");
            enumValues = enumValues == null ? null : List.copyOf(enumValues);
        }
    }

    /**
     * One declared response of a RAML method, for one status and one body media type.
     *
     * @param status      the status code, for example {@code 200}
     * @param mediaType   the body media type, or {@code null} when the status declares no body
     * @param example     the example text: an included file's text verbatim (D-045) or the inline
     *                    example, or {@code null}
     * @param exampleFile the included example's file name, or {@code null} when the example is
     *                    inline or absent
     */
    public record Response(int status, String mediaType, String example, String exampleFile) {
    }
}

