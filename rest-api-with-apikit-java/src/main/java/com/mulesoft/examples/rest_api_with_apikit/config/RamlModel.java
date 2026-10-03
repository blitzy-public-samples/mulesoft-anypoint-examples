package com.mulesoft.examples.rest_api_with_apikit.config;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
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
import org.springframework.core.io.InputStreamSource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Component;
import org.springframework.util.ResourceUtils;
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
 * for concurrent use. Each {@code !include <path>} is resolved against the URL of the RAML file, inside
 * the RAML folder of that same classpath entry, and replaced by the included file's text, kept raw:
 * schemas and examples are never parsed here, and a file that is not strict JSON (for example
 * {@code examples/match-get-example.json}) loads unchanged.
 *
 * <p>Contract rules, each enforced at construction (D-354):
 * <ul>
 *   <li>A mapping holds each key once.</li>
 *   <li>An include path is a relative path of file and folder names: no leading {@code /}, no
 *       {@code \}, {@code :} or {@code %}, and no empty, {@code .} or {@code ..} segment. It names a
 *       readable file in the RAML folder or one of its subfolders, in the classpath entry that holds the
 *       RAML file.</li>
 *   <li>A named-parameter {@code type} is exactly one of {@code string}, {@code number},
 *       {@code integer}, {@code date}, {@code boolean} and {@code file}; an absent type reads
 *       {@code string}.</li>
 *   <li>Every element read as text ({@code title}, {@code version}, {@code baseUri},
 *       {@code displayName}, {@code description}, {@code type}, {@code example}, {@code default},
 *       {@code schema}, each {@code enum} item) and every mapping key is a scalar, not a YAML mapping,
 *       sequence or set.</li>
 *   <li>No resource mapping is the RAML root or the mapping of a resource that encloses it. The resource
 *       tree is at most 50 levels deep, holds at most 10,000 resources, has no full path longer than
 *       8,192 characters, and holds at most 100,000 model elements (resources, methods, responses,
 *       bodies and parameters).</li>
 * </ul>
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

    /** RAML 0.8 named-parameter types, matched exactly and case-sensitively (D-354). */
    private static final List<String> PARAMETER_TYPES =
            List.of("string", "number", "integer", "date", "boolean", "file");

    /** Deepest resource level the walk enters; a resource at the RAML root is level 1 (D-354). */
    private static final int MAX_RESOURCE_DEPTH = 50;

    /** Largest number of resources the walk adds (D-354). */
    private static final int MAX_RESOURCES = 10_000;

    /** Longest full path template of a resource the walk accepts, in characters (D-354). */
    private static final int MAX_PATH_LENGTH = 8_192;

    /**
     * Largest number of model elements the walk builds: resources, their URI parameters, methods, query
     * parameters, request bodies, responses, response headers and response bodies (D-354).
     */
    private static final int MAX_MODEL_ELEMENTS = 100_000;

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
     * @throws IllegalStateException if the location is blank or names no file, or the RAML file is not on
     *     the classpath; the message names the location. Also, with a message that starts with
     *     {@code RAML <location>:}, if the YAML does not parse or a mapping holds a key twice, an
     *     {@code !include} path breaks the include path rule or names no readable file in the RAML folder,
     *     a parameter {@code type} is not a RAML 0.8 named-parameter type, a text element or mapping key is
     *     a YAML collection, a resource refers back to an enclosing resource, the resource tree exceeds its
     *     depth, resource, path-length or model-element limit, or a RAML node has an unsupported shape;
     *     the message names the RAML node, the include path and its position, or the resource path (D-354)
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
        URL ramlUrl = classpathUrl(location);

        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setTagInspector(tag -> INCLUDE_TAG.equals(tag.getValue()));
        IncludeConstructor constructor = new IncludeConstructor(options, ramlFolder, ramlUrl);
        List<Resource> walked = new ArrayList<>();
        try {
            Object root = new Yaml(constructor).load(new String(ramlBytes, StandardCharsets.UTF_8));
            if (!(root instanceof Map<?, ?> raml)) {
                throw new IllegalStateException("the document is not a YAML mapping");
            }
            this.title = text(raml.get("title"), "title");
            this.version = text(raml.get("version"), "version");
            this.baseUri = text(raml.get("baseUri"), "baseUri");
            Set<Map<?, ?>> enclosing = Collections.newSetFromMap(new IdentityHashMap<>());
            enclosing.add(raml);
            int[] elements = {0};
            for (Map.Entry<?, ?> entry : raml.entrySet()) {
                String key = key(entry.getKey(), "root");
                if (key.startsWith("/")) {
                    parseResource("", key, entry.getValue(), List.of(), 1, enclosing, elements, walked);
                }
            }
        } catch (YAMLException e) {
            throw new IllegalStateException("RAML " + location + ": invalid YAML: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("RAML " + location + ": " + e.getMessage(), e);
        }
        this.basePath = pathOf(baseUri);
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
     * SnakeYAML safe constructor that resolves {@code !include <path>} against the URL of the RAML file,
     * inside the RAML folder of that same classpath entry, and records each included file's bytes by its
     * include path (D-354).
     */
    private static final class IncludeConstructor extends SafeConstructor {

        private final String ramlFolder;

        /** URL of the RAML file as the class loader resolved it. */
        private final URL ramlUrl;

        /** {@link #ramlUrl} up to and including its last {@code /}: the URL of the RAML folder. */
        private final String folderUrl;

        /** Bytes of every included file, keyed by trimmed include path, in first-include order. */
        private final Map<String, byte[]> includedFiles = new LinkedHashMap<>();

        /**
         * Creates the constructor and registers the {@code !include} construct.
         *
         * @param options the loader options of the parse
         * @param ramlFolder classpath folder of the RAML file, empty for the classpath root
         * @param ramlUrl URL of the RAML file, a {@code file:} or {@code jar:} URL
         */
        private IncludeConstructor(LoaderOptions options, String ramlFolder, URL ramlUrl) {
            super(options);
            this.ramlFolder = ramlFolder;
            this.ramlUrl = ramlUrl;
            String url = ramlUrl.toString();
            this.folderUrl = url.substring(0, url.lastIndexOf('/') + 1);
            this.yamlConstructors.put(new Tag(INCLUDE_TAG), new IncludeConstruct());
        }

        /**
         * Reads the file that include path {@code path} names relative to {@link #ramlUrl}.
         *
         * @param path a trimmed include path that {@code includePathProblem} accepts
         * @param location {@code <ramlFolder>/<path>}, the classpath location named in error messages
         * @param where the tag, include path and YAML position named in error messages
         * @return the file's bytes
         * @throws IllegalStateException if the resolved URL is outside {@link #folderUrl} or is not a readable
         *     file
         */
        private byte[] readInclude(String path, String location, String where) {
            URL url;
            try {
                url = ResourceUtils.toRelativeURL(ramlUrl, path);
            } catch (MalformedURLException e) {
                throw new IllegalStateException(where + " is not a valid relative URL: " + e.getMessage(), e);
            }
            if (!url.toString().startsWith(folderUrl)) {
                throw new IllegalStateException(
                        where + " resolves to " + url + ", outside the RAML folder " + folderUrl);
            }
            UrlResource include = new UrlResource(url);
            if (!include.isReadable()) {
                throw new IllegalStateException(where + " names no readable file: classpath resource " + location
                        + " is not a file in the RAML folder " + folderUrl);
            }
            return read(include, location);
        }

        /** Builds an {@link Included} from an {@code !include} scalar. */
        private final class IncludeConstruct extends AbstractConstruct {

            /**
             * Loads the file {@code <ramlFolder>/<path>} named by the scalar from the RAML folder and returns its
             * text.
             *
             * @param node the {@code !include} node
             * @return the include path and the file's UTF-8 text
             * @throws IllegalStateException naming the include path and its position if the node is not a
             *     non-empty scalar, the path is absolute, holds a {@code \}, {@code :} or {@code %} or an empty,
             *     {@code .} or {@code ..} segment, or the path names no readable file in the RAML folder of the
             *     RAML file's own classpath entry (D-354)
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
                String where = INCLUDE_TAG + " " + path + " " + position(node);
                String problem = includePathProblem(path);
                if (problem != null) {
                    throw new IllegalStateException(where + " " + problem
                            + "; an include path names a file in the RAML folder or one of its subfolders");
                }
                String location = ramlFolder.isEmpty() ? path : ramlFolder + "/" + path;
                byte[] bytes = includedFiles.computeIfAbsent(path, key -> readInclude(key, location, where));
                return new Included(path, new String(bytes, StandardCharsets.UTF_8));
            }
        }
    }

    /**
     * Returns what makes a trimmed, non-empty {@code !include} path unusable, or {@code null} for a relative
     * path of file and folder names: no leading {@code /}, no {@code \}, {@code :} or {@code %}, and no
     * empty, {@code .} or {@code ..} segment (D-354).
     *
     * @param path the include path
     * @return the problem, or {@code null}
     */
    private static String includePathProblem(String path) {
        if (path.startsWith("/")) {
            return "is an absolute path";
        }
        if (path.indexOf('\\') >= 0) {
            return "contains a backslash";
        }
        if (path.indexOf(':') >= 0) {
            return "contains ':', a URL scheme or drive separator";
        }
        if (path.indexOf('%') >= 0) {
            return "contains '%', a URL escape";
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                return "has an empty, '.' or '..' path segment";
            }
        }
        return null;
    }


    /**
     * Adds the resource at {@code parentPath + relativePath} and then its child resources, depth-first in
     * RAML order, to {@code out}.
     *
     * @param parentPath full path of the parent resource, empty for a root resource
     * @param relativePath the resource key, for example {@code /{teamId}}
     * @param value the resource mapping; {@code null} counts as an empty mapping
     * @param inherited URI parameters declared by the ancestors, nearest last
     * @param depth the resource level, 1 for a resource at the RAML root
     * @param enclosing the RAML root mapping and the mappings of the resources enclosing this one, by
     *     identity
     * @param elements one-element array holding the number of model elements built so far
     * @param out the resources walked so far
     * @throws IllegalStateException naming the resource path if the path is longer than
     *     {@value #MAX_PATH_LENGTH} characters, {@code depth} exceeds {@value #MAX_RESOURCE_DEPTH},
     *     {@code out} already holds {@value #MAX_RESOURCES} resources, the mapping is one of
     *     {@code enclosing}, or the model elements exceed {@value #MAX_MODEL_ELEMENTS} (D-354)
     */
    private static void parseResource(String parentPath, String relativePath, Object value,
            List<Parameter> inherited, int depth, Set<Map<?, ?>> enclosing, int[] elements, List<Resource> out) {
        String path = parentPath + relativePath;
        if (path.length() > MAX_PATH_LENGTH) {
            throw new IllegalStateException("RAML resource " + path.substring(0, 64) + "... has a path longer than "
                    + MAX_PATH_LENGTH + " characters");
        }
        if (depth > MAX_RESOURCE_DEPTH) {
            throw new IllegalStateException("RAML resource " + path + " is nested deeper than "
                    + MAX_RESOURCE_DEPTH + " resource levels");
        }
        if (out.size() >= MAX_RESOURCES) {
            throw new IllegalStateException("RAML resource " + path + " exceeds the limit of " + MAX_RESOURCES
                    + " resources");
        }
        Map<?, ?> properties = mapping(value, path);
        if (enclosing.contains(properties)) {
            throw new IllegalStateException("RAML resource " + path + " refers back to an enclosing resource");
        }

        List<Parameter> declared = new ArrayList<>(inherited);
        for (Parameter parameter : parseParameters(properties.get("uriParameters"), true, path + " uriParameters")) {
            declared.removeIf(existing -> existing.name().equals(parameter.name()));
            declared.add(parameter);
        }

        List<Parameter> uriParameters = uriParameters(path, declared);
        count(elements, 1 + uriParameters.size(), path);

        List<Method> methods = new ArrayList<>();
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String key = key(entry.getKey(), path);
            if (METHOD_KEYS.contains(key)) {
                methods.add(parseMethod(key, entry.getValue(), path, elements));
            }
        }

        String displayName = text(properties.get("displayName"), path + " displayName");
        out.add(new Resource(path, displayName == null ? relativePath : displayName,
                text(properties.get("description"), path + " description"), uriParameters, methods));

        enclosing.add(properties);
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            String key = key(entry.getKey(), path);
            if (key.startsWith("/")) {
                parseResource(path, key, entry.getValue(), declared, depth + 1, enclosing, elements, out);
            }
        }
        enclosing.remove(properties);
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
     * @param elements one-element array holding the number of model elements built so far
     * @return the method
     */
    private static Method parseMethod(String verb, Object value, String path, int[] elements) {
        String context = verb.toUpperCase(Locale.ROOT) + " " + path;
        Map<?, ?> properties = mapping(value, context);
        String description = text(properties.get("description"), context + " description");
        List<Parameter> queryParameters =
                parseParameters(properties.get("queryParameters"), false, context + " queryParameters");
        List<Body> body = parseBodies(properties.get("body"), context + " body");
        count(elements, 1 + queryParameters.size() + body.size(), context);
        return new Method(verb, description, queryParameters, body,
                parseResponses(properties.get("responses"), context + " responses", elements));
    }

    /**
     * Parses a {@code responses} mapping of status code to response properties.
     *
     * @param value the mapping; {@code null} counts as an empty mapping
     * @param context the RAML node named in error messages
     * @param elements one-element array holding the number of model elements built so far
     * @return the responses in RAML order
     */
    private static List<Response> parseResponses(Object value, String context, int[] elements) {
        List<Response> responses = new ArrayList<>();
        for (Map.Entry<?, ?> entry : mapping(value, context).entrySet()) {
            String key = key(entry.getKey(), context).trim();
            int status;
            try {
                status = Integer.parseInt(key);
            } catch (NumberFormatException e) {
                throw new IllegalStateException("RAML " + context + " key " + key + " is not a status code", e);
            }
            String responseContext = context + " " + status;
            Map<?, ?> properties = mapping(entry.getValue(), responseContext);
            String description = text(properties.get("description"), responseContext + " description");
            List<Parameter> headers = parseParameters(properties.get("headers"), false, responseContext + " headers");
            List<Body> bodies = parseBodies(properties.get("body"), responseContext + " body");
            count(elements, 1 + headers.size() + bodies.size(), responseContext);
            responses.add(new Response(status, description, headers, bodies));
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
            String mediaType = key(entry.getKey(), context);
            String bodyContext = context + " " + mediaType;
            Map<?, ?> properties = mapping(entry.getValue(), bodyContext);
            Object schema = properties.get("schema");
            Object example = properties.get("example");
            bodies.add(new Body(mediaType, text(schema, bodyContext + " schema"), includePath(schema),
                    text(example, bodyContext + " example"), includePath(example)));
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
     * @throws IllegalStateException naming the parameter and its {@code type} if the type is not one of
     *     {@code string}, {@code number}, {@code integer}, {@code date}, {@code boolean} and {@code file}
     *     (D-354)
     */
    private static List<Parameter> parseParameters(Object value, boolean requiredByDefault, String context) {
        List<Parameter> parameters = new ArrayList<>();
        for (Map.Entry<?, ?> entry : mapping(value, context).entrySet()) {
            String name = key(entry.getKey(), context);
            String parameterContext = context + " " + name;
            Map<?, ?> properties = mapping(entry.getValue(), parameterContext);
            String displayName = text(properties.get("displayName"), parameterContext + " displayName");
            String type = text(properties.get("type"), parameterContext + " type");
            if (type != null && !PARAMETER_TYPES.contains(type)) {
                throw new IllegalStateException("RAML " + parameterContext + " type " + type
                        + " is not a RAML 0.8 named-parameter type; the types are "
                        + String.join(", ", PARAMETER_TYPES));
            }
            parameters.add(new Parameter(
                    name,
                    displayName == null ? name : displayName,
                    text(properties.get("description"), parameterContext + " description"),
                    type == null ? DEFAULT_TYPE : type,
                    bool(properties.get("required"), requiredByDefault, parameterContext + " required"),
                    text(properties.get("example"), parameterContext + " example"),
                    integer(properties.get("minLength"), parameterContext + " minLength"),
                    integer(properties.get("maxLength"), parameterContext + " maxLength"),
                    texts(properties.get("enum"), parameterContext + " enum"),
                    text(properties.get("default"), parameterContext + " default")));
        }
        return parameters;
    }

    /**
     * Adds {@code added} to the number of model elements built so far.
     *
     * @param elements one-element array holding the number of model elements built so far
     * @param added the elements a resource, method or response adds: itself and its parameters and bodies
     * @param context the RAML node named in the error message
     * @throws IllegalStateException naming {@code context} if the number exceeds
     *     {@value #MAX_MODEL_ELEMENTS} (D-354)
     */
    private static void count(int[] elements, int added, String context) {
        elements[0] += added;
        if (elements[0] > MAX_MODEL_ELEMENTS) {
            throw new IllegalStateException("RAML node " + context + " exceeds the limit of " + MAX_MODEL_ELEMENTS
                    + " model elements (resources, methods, responses, bodies and parameters)");
        }
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
        return read(new ClassPathResource(location), location);
    }

    /**
     * Reads a contract file completely.
     *
     * @param source the file
     * @param location the classpath location named in the error message
     * @return the file bytes
     * @throws IllegalStateException naming {@code location} if the file is missing or unreadable
     */
    private static byte[] read(InputStreamSource source, String location) {
        try (InputStream in = source.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read classpath resource " + location + ": " + e.getMessage(), e);
        }
    }

    /**
     * Returns the URL the class loader resolves for a classpath resource.
     *
     * @param location the classpath location of a resource that was read
     * @return the URL, a {@code file:} URL for an exploded classpath entry or a {@code jar:} URL inside a jar
     * @throws IllegalStateException naming {@code location} if the class loader resolves no URL
     */
    private static URL classpathUrl(String location) {
        try {
            return new ClassPathResource(location).getURL();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "RAML " + location + ": cannot resolve its classpath URL: " + e.getMessage(), e);
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
     * Returns the text of a scalar YAML value: an include's file text, any other scalar through
     * {@link String#valueOf(Object)}, {@code null} for {@code null}.
     *
     * @param value a YAML value
     * @param context the RAML node named in the error message
     * @return the text, or {@code null}
     * @throws IllegalStateException naming {@code context} if {@code value} is a YAML mapping, sequence or
     *     set (D-354)
     */
    private static String text(Object value, String context) {
        if (value == null) {
            return null;
        }
        if (value instanceof Included included) {
            return included.text();
        }
        return scalar(value, context);
    }

    /**
     * Returns a mapping key through {@link String#valueOf(Object)}; a {@code null} key reads {@code "null"}.
     *
     * @param key a key of a YAML mapping
     * @param context the RAML node whose mapping holds the key, named in the error message
     * @return the key text
     * @throws IllegalStateException naming {@code context} if {@code key} is a YAML mapping, sequence or set
     *     (D-354)
     */
    private static String key(Object key, String context) {
        return scalar(key, context + " key");
    }

    /**
     * Returns {@link String#valueOf(Object)} of a value that is not a YAML collection.
     *
     * @param value a YAML value or key
     * @param context the RAML node named in the error message
     * @return the text
     * @throws IllegalStateException naming {@code context} if {@code value} is a YAML mapping, sequence or
     *     set (D-354)
     */
    private static String scalar(Object value, String context) {
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            throw new IllegalStateException("RAML node " + context + " is not a scalar");
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
        String digits = text(value, context).trim();
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
        String literal = text(value, context).trim();
        if ("true".equalsIgnoreCase(literal) || "false".equalsIgnoreCase(literal)) {
            return Boolean.parseBoolean(literal);
        }
        throw new IllegalStateException("RAML " + context + " value " + literal + " is not a boolean");
    }

    /**
     * Returns a YAML sequence of scalars as texts in order, a scalar as one text, {@code null} as an empty
     * list.
     *
     * @throws IllegalStateException naming {@code context} if {@code value} is a mapping or set, or an item
     *     is a YAML mapping, sequence or set (D-354)
     */
    private static List<String> texts(Object value, String context) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> values = new ArrayList<>();
            for (Object element : list) {
                String item = text(element, context + " item");
                if (item != null) {
                    values.add(item);
                }
            }
            return values;
        }
        return List.of(text(value, context));
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

