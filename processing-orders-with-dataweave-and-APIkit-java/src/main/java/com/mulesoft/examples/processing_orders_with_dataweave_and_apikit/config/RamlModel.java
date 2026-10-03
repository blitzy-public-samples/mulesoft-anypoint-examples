package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Parsed RAML contract of the Currency API loaded from {@code classpath:api/currency.raml}, the contract that
 * {@code apikit:config name="currency-config" raml="currency.raml"} loaded
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/currency.xml:4] (D-009). It holds the root
 * {@code title} and {@code baseUri}, the resources keyed by full path with their methods and responses, and
 * the console model, every map in document order and every map and list unmodifiable (D-662).
 */
@Component
public class RamlModel {

    private static final String RAML_LOCATION = "api/currency.raml";

    private static final String API_DIRECTORY = "api/";

    private static final String FAILURE_PREFIX = "RAML file classpath:" + RAML_LOCATION;

    private static final Set<String> METHOD_KEYS = Set.of("get", "post", "put", "delete", "patch", "head", "options");

    private static final Pattern URI_TEMPLATE = Pattern.compile("\\{([^{}/]+)}");

    private final String title;

    private final String baseUri;

    private final Map<String, Resource> resources;

    private final Map<String, Object> consoleModel;

    /**
     * Declared or implicit RAML query or URI parameter: its name, {@code type} ({@code string} when absent),
     * {@code required} ({@code false} for an absent query-parameter value, {@code true} for an absent
     * URI-parameter value), {@code enum} entries (empty when absent), {@code default}, {@code minLength},
     * {@code maxLength} and {@code example}, each absent value {@code null} (D-009).
     *
     * @param name         the parameter name
     * @param type         the RAML type name
     * @param required     whether the parameter is required
     * @param enumValues   the {@code enum} entries as text, in document order
     * @param defaultValue the {@code default} value as text, or {@code null}
     * @param minLength    the {@code minLength} value, or {@code null}
     * @param maxLength    the {@code maxLength} value, or {@code null}
     * @param example      the {@code example} value as text, or {@code null}
     */
    public record Parameter(String name, String type, boolean required, List<String> enumValues,
                            String defaultValue, Integer minLength, Integer maxLength, String example) {

        /**
         * Creates the parameter and stores an unmodifiable copy of {@code enumValues} (D-662).
         *
         * @param name         the parameter name
         * @param type         the RAML type name
         * @param required     whether the parameter is required
         * @param enumValues   the {@code enum} entries as text, in document order
         * @param defaultValue the {@code default} value as text, or {@code null}
         * @param minLength    the {@code minLength} value, or {@code null}
         * @param maxLength    the {@code maxLength} value, or {@code null}
         * @param example      the {@code example} value as text, or {@code null}
         * @throws NullPointerException when {@code enumValues} or one of its entries is {@code null}
         */
        public Parameter {
            enumValues = List.copyOf(enumValues);
        }
    }

    /**
     * Request or response body of one media type: the media type, its {@code schema} text (an included
     * file's full text) and its {@code example} text, each absent value {@code null} (D-009).
     *
     * @param mediaType the media type key, e.g. {@code application/json}
     * @param schema    the schema text, or {@code null}
     * @param example   the example text, or {@code null}
     */
    public record Body(String mediaType, String schema, String example) {
    }

    /**
     * Declared response of one status: the status text, its {@code description} ({@code null} when absent)
     * and its bodies in document order (D-009).
     *
     * @param status      the status text, e.g. {@code "200"}
     * @param description the response description, or {@code null}
     * @param bodies      the response bodies, one per media type
     */
    public record Response(String status, String description, List<Body> bodies) {

        /**
         * Creates the response and stores an unmodifiable copy of {@code bodies} (D-662).
         *
         * @param status      the status text, e.g. {@code "200"}
         * @param description the response description, or {@code null}
         * @param bodies      the response bodies, one per media type
         * @throws NullPointerException when {@code bodies} or one of its entries is {@code null}
         */
        public Response {
            bodies = List.copyOf(bodies);
        }
    }

    /**
     * Declared method of one resource: the lower-case verb, its {@code description} ({@code null} when
     * absent), its query parameters, its request bodies and its responses keyed by status text, each in
     * document order (D-009).
     *
     * @param method          the lower-case RAML verb, e.g. {@code get}
     * @param description     the method description, or {@code null}
     * @param queryParameters the declared query parameters
     * @param bodies          the request bodies, one per media type
     * @param responses       the responses keyed by status text
     */
    public record Method(String method, String description, List<Parameter> queryParameters,
                         List<Body> bodies, Map<String, Response> responses) {

        /**
         * Creates the method and stores unmodifiable copies of {@code queryParameters}, {@code bodies} and
         * {@code responses}, the response map in its iteration order (D-662).
         *
         * @param method          the lower-case RAML verb, e.g. {@code get}
         * @param description     the method description, or {@code null}
         * @param queryParameters the declared query parameters
         * @param bodies          the request bodies, one per media type
         * @param responses       the responses keyed by status text
         * @throws NullPointerException when a list or the map is {@code null}, or a list holds {@code null}
         */
        public Method {
            queryParameters = List.copyOf(queryParameters);
            bodies = List.copyOf(bodies);
            responses = orderedCopy(responses);
        }
    }

    /**
     * Declared resource: its full path, its URI parameters (the inherited ones of its parents, its declared
     * ones and an implicit required {@code string} parameter for each other {@code {name}} of the path) and
     * its methods keyed by lower-case verb, each in document order (D-009).
     *
     * @param path          the full resource path, e.g. {@code /currencies}
     * @param uriParameters the URI parameters of the full path
     * @param methods       the methods keyed by lower-case verb
     */
    public record Resource(String path, List<Parameter> uriParameters, Map<String, Method> methods) {

        /**
         * Creates the resource and stores unmodifiable copies of {@code uriParameters} and {@code methods}, the
         * method map in its iteration order (D-662).
         *
         * @param path          the full resource path, e.g. {@code /currencies}
         * @param uriParameters the URI parameters of the full path
         * @param methods       the methods keyed by lower-case verb
         * @throws NullPointerException when the list or the map is {@code null}, or the list holds {@code null}
         */
        public Resource {
            uriParameters = List.copyOf(uriParameters);
            methods = orderedCopy(methods);
        }
    }

    /**
     * Loads and parses {@code classpath:api/currency.raml} once and builds the resource model and the console
     * model (D-009).
     *
     * @throws IllegalStateException naming {@code classpath:api/currency.raml} when the file is missing,
     *                               unreadable, not valid YAML or not a RAML document the model reads (D-662)
     */
    public RamlModel() {
        String text;
        try {
            text = readClasspathText(RAML_LOCATION);
        } catch (IOException e) {
            throw new IllegalStateException(FAILURE_PREFIX + " cannot be read", e);
        }
        Map<?, ?> root = parse(text);
        this.title = text(root.get("title"), "title");
        this.baseUri = text(root.get("baseUri"), "baseUri");
        Map<String, Resource> collected = new LinkedHashMap<>();
        Set<Object> enclosing = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Map.Entry<?, ?> entry : root.entrySet()) {
            String key = key(entry.getKey(), "root");
            if (key.startsWith("/")) {
                collectResource(key, entry.getValue(), List.of(), enclosing, collected);
            }
        }
        this.resources = Collections.unmodifiableMap(collected);
        this.consoleModel = consoleTree(this.title, this.baseUri, this.resources);
    }

    /**
     * Root {@code title} of the RAML contract, {@code Currency API} for the bundled file (D-009).
     *
     * @return the title, or {@code null} when absent
     */
    public String title() {
        return title;
    }

    /**
     * Root {@code baseUri} of the RAML contract, {@code http://localhost:8081/api/} for the bundled file (D-009).
     *
     * @return the base URI text, or {@code null} when absent
     */
    public String baseUri() {
        return baseUri;
    }

    /**
     * Every RAML resource keyed by its full path, in document order with each parent before its children
     * (D-009).
     *
     * @return the unmodifiable resource map
     */
    public Map<String, Resource> resources() {
        return resources;
    }

    /**
     * Console model served at {@code /api/console/api.json}: an unmodifiable tree with the keys
     * {@code title}, {@code baseUri} and {@code resources}, each resource holding {@code path},
     * {@code uriParameters} and {@code methods}, each method holding {@code method} (upper case),
     * {@code description}, {@code queryParameters}, {@code body} and {@code responses}, each response holding
     * {@code status}, {@code description} and {@code body}, each parameter holding {@code name}, {@code type},
     * {@code required}, {@code enum}, {@code default}, {@code minLength}, {@code maxLength} and
     * {@code example}, and each body holding {@code mediaType}, {@code schema} and {@code example}; absent
     * values are {@code null} and absent lists are empty (D-009).
     *
     * @return the unmodifiable console model
     */
    public Map<String, Object> consoleModel() {
        return consoleModel;
    }

    private static Map<?, ?> parse(String text) {
        Object document;
        try {
            document = new Yaml(new RamlConstructor(new LoaderOptions())).load(text);
        } catch (YAMLException e) {
            throw new IllegalStateException(FAILURE_PREFIX + " is not valid YAML: " + e.getMessage(), e);
        }
        if (!(document instanceof Map<?, ?> root)) {
            throw invalid("the document is not a mapping");
        }
        return root;
    }

    private static void collectResource(String path, Object value, List<Parameter> inherited,
                                        Set<Object> enclosing, Map<String, Resource> collected) {
        String where = "resource " + path;
        Map<?, ?> declaration = mapping(value, where);
        if (value != null && !enclosing.add(value)) {
            throw invalid(where + " refers back to an enclosing resource");
        }
        Map<String, Parameter> uriParameters = new LinkedHashMap<>();
        for (Parameter parameter : inherited) {
            uriParameters.put(parameter.name(), parameter);
        }
        for (Parameter parameter : parameters(declaration.get("uriParameters"), true, where + " uriParameters")) {
            uriParameters.put(parameter.name(), parameter);
        }
        Matcher template = URI_TEMPLATE.matcher(path);
        while (template.find()) {
            String name = template.group(1);
            uriParameters.putIfAbsent(name, new Parameter(name, "string", true, List.of(), null, null, null, null));
        }
        Map<String, Method> methods = new LinkedHashMap<>();
        List<Map.Entry<?, ?>> children = new ArrayList<>();
        for (Map.Entry<?, ?> entry : declaration.entrySet()) {
            String key = key(entry.getKey(), where);
            if (key.startsWith("/")) {
                children.add(entry);
            } else if (METHOD_KEYS.contains(key)) {
                methods.put(key, method(key, entry.getValue(), where + " method " + key));
            }
        }
        Resource resource = new Resource(path, new ArrayList<>(uriParameters.values()), methods);
        putUnique(collected, path, resource, where);
        for (Map.Entry<?, ?> child : children) {
            collectResource(path + key(child.getKey(), where), child.getValue(), resource.uriParameters(),
                    enclosing, collected);
        }
        if (value != null) {
            enclosing.remove(value);
        }
    }

    private static Method method(String verb, Object value, String where) {
        Map<?, ?> declaration = mapping(value, where);
        Map<String, Response> responses = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : mapping(declaration.get("responses"), where + " responses").entrySet()) {
            String status = key(entry.getKey(), where + " responses");
            String responseWhere = where + " response " + status;
            Map<?, ?> response = mapping(entry.getValue(), responseWhere);
            putUnique(responses, status, new Response(status,
                    text(response.get("description"), responseWhere + " description"),
                    bodies(response.get("body"), responseWhere + " body")), responseWhere);
        }
        return new Method(verb,
                text(declaration.get("description"), where + " description"),
                parameters(declaration.get("queryParameters"), false, where + " queryParameters"),
                bodies(declaration.get("body"), where + " body"),
                responses);
    }

    private static List<Body> bodies(Object value, String where) {
        List<Body> bodies = new ArrayList<>();
        for (Map.Entry<?, ?> entry : mapping(value, where).entrySet()) {
            String mediaType = key(entry.getKey(), where);
            String bodyWhere = where + " " + mediaType;
            Map<?, ?> body = mapping(entry.getValue(), bodyWhere);
            bodies.add(new Body(mediaType,
                    text(body.get("schema"), bodyWhere + " schema"),
                    text(body.get("example"), bodyWhere + " example")));
        }
        return bodies;
    }

    private static List<Parameter> parameters(Object value, boolean requiredByDefault, String where) {
        List<Parameter> parameters = new ArrayList<>();
        for (Map.Entry<?, ?> entry : mapping(value, where).entrySet()) {
            String name = key(entry.getKey(), where);
            parameters.add(parameter(name, entry.getValue(), requiredByDefault, where + " " + name));
        }
        return parameters;
    }

    private static Parameter parameter(String name, Object value, boolean requiredByDefault, String where) {
        Map<?, ?> declaration = mapping(value, where);
        String type = text(declaration.get("type"), where + " type");
        Object required = declaration.get("required");
        if (required != null && !(required instanceof Boolean)) {
            throw invalid(where + " required is not a boolean");
        }
        List<String> enumValues = new ArrayList<>();
        Object enumeration = declaration.get("enum");
        if (enumeration != null) {
            if (!(enumeration instanceof List<?> entries)) {
                throw invalid(where + " enum is not a list");
            }
            for (Object entry : entries) {
                String text = text(entry, where + " enum entry");
                if (text == null) {
                    throw invalid(where + " enum holds a null entry");
                }
                enumValues.add(text);
            }
        }
        return new Parameter(name,
                type == null ? "string" : type,
                required == null ? requiredByDefault : (Boolean) required,
                enumValues,
                text(declaration.get("default"), where + " default"),
                integer(declaration.get("minLength"), where + " minLength"),
                integer(declaration.get("maxLength"), where + " maxLength"),
                text(declaration.get("example"), where + " example"));
    }

    private static Map<String, Object> consoleTree(String title, String baseUri, Map<String, Resource> resources) {
        List<Map<String, Object>> resourceEntries = new ArrayList<>();
        for (Resource resource : resources.values()) {
            resourceEntries.add(consoleResource(resource));
        }
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("title", title);
        tree.put("baseUri", baseUri);
        tree.put("resources", List.copyOf(resourceEntries));
        return Collections.unmodifiableMap(tree);
    }

    private static Map<String, Object> consoleResource(Resource resource) {
        List<Map<String, Object>> methodEntries = new ArrayList<>();
        for (Method method : resource.methods().values()) {
            methodEntries.add(consoleMethod(method));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("path", resource.path());
        entry.put("uriParameters", consoleParameters(resource.uriParameters()));
        entry.put("methods", List.copyOf(methodEntries));
        return Collections.unmodifiableMap(entry);
    }

    private static Map<String, Object> consoleMethod(Method method) {
        List<Map<String, Object>> responseEntries = new ArrayList<>();
        for (Response response : method.responses().values()) {
            Map<String, Object> responseEntry = new LinkedHashMap<>();
            responseEntry.put("status", response.status());
            responseEntry.put("description", response.description());
            responseEntry.put("body", consoleBodies(response.bodies()));
            responseEntries.add(Collections.unmodifiableMap(responseEntry));
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("method", method.method().toUpperCase(Locale.ROOT));
        entry.put("description", method.description());
        entry.put("queryParameters", consoleParameters(method.queryParameters()));
        entry.put("body", consoleBodies(method.bodies()));
        entry.put("responses", List.copyOf(responseEntries));
        return Collections.unmodifiableMap(entry);
    }

    private static List<Map<String, Object>> consoleParameters(List<Parameter> parameters) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Parameter parameter : parameters) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", parameter.name());
            entry.put("type", parameter.type());
            entry.put("required", parameter.required());
            entry.put("enum", parameter.enumValues());
            entry.put("default", parameter.defaultValue());
            entry.put("minLength", parameter.minLength());
            entry.put("maxLength", parameter.maxLength());
            entry.put("example", parameter.example());
            entries.add(Collections.unmodifiableMap(entry));
        }
        return List.copyOf(entries);
    }

    private static List<Map<String, Object>> consoleBodies(List<Body> bodies) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Body body : bodies) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("mediaType", body.mediaType());
            entry.put("schema", body.schema());
            entry.put("example", body.example());
            entries.add(Collections.unmodifiableMap(entry));
        }
        return List.copyOf(entries);
    }

    private static Map<?, ?> mapping(Object value, String where) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> map) {
            return map;
        }
        throw invalid(where + " is not a mapping");
    }

    private static String text(Object value, String where) {
        if (value == null || value instanceof String) {
            return (String) value;
        }
        if (value instanceof Map<?, ?>) {
            throw invalid(where + " is a mapping, not a scalar");
        }
        if (value instanceof Collection<?>) {
            throw invalid(where + " is a sequence or set, not a scalar");
        }
        return String.valueOf(value);
    }

    private static String key(Object value, String where) {
        String key = text(value, where + " key");
        if (key == null) {
            throw invalid(where + " holds a null key");
        }
        return key;
    }

    private static Integer integer(Object value, String where) {
        if (value == null || value instanceof Integer) {
            return (Integer) value;
        }
        throw invalid(where + " is not an integer");
    }

    private static <V> void putUnique(Map<String, V> map, String key, V value, String where) {
        if (map.putIfAbsent(key, value) != null) {
            throw invalid(where + " is declared twice");
        }
    }

    private static <V> Map<String, V> orderedCopy(Map<String, V> map) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(map));
    }

    private static IllegalStateException invalid(String detail) {
        return new IllegalStateException(FAILURE_PREFIX + ": " + detail);
    }

    private static String readClasspathText(String location) throws IOException {
        try (InputStream input = new ClassPathResource(location).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * SnakeYAML safe constructor of the RAML file with the local tag {@code !include} (D-009).
     */
    private static final class RamlConstructor extends SafeConstructor {

        RamlConstructor(LoaderOptions loaderOptions) {
            super(loaderOptions);
            this.yamlConstructors.put(new Tag("!include"), new IncludeConstruct());
        }
    }

    /**
     * Construct of the {@code !include} tag: the UTF-8 text of {@code classpath:api/<value>}, unchanged, for
     * a trimmed scalar value naming a file under {@code api/} (D-009, D-662).
     */
    private static final class IncludeConstruct extends AbstractConstruct {

        @Override
        public Object construct(Node node) {
            if (!(node instanceof ScalarNode scalar)) {
                throw invalid("!include" + position(node) + " is not a scalar");
            }
            String name = scalar.getValue().trim();
            if (!isApiFileName(name)) {
                throw invalid("!include '" + name + "'" + position(node) + " names no file under " + API_DIRECTORY);
            }
            String location = API_DIRECTORY + name;
            try {
                return readClasspathText(location);
            } catch (IOException e) {
                throw new IllegalStateException(FAILURE_PREFIX + ": !include " + name + ": classpath:" + location
                        + " cannot be read", e);
            }
        }

        private static boolean isApiFileName(String name) {
            if (name.isEmpty() || name.indexOf('\\') >= 0) {
                return false;
            }
            for (String segment : name.split("/", -1)) {
                if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                    return false;
                }
            }
            return true;
        }

        private static String position(Node node) {
            Mark mark = node.getStartMark();
            return mark == null ? "" : " at line " + (mark.getLine() + 1);
        }
    }
}
