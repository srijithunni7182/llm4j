package io.github.llm4j.tools.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.github.llm4j.tools.Options;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.HttpUrl;

/**
 * Reads an OpenAPI description without letting the parser touch the network or the disk by
 * itself.
 *
 * <p>The swagger parser fetches a remote description and every external {@code $ref} on its own,
 * with a plain URL connection, so it would skip the address rules every outbound call must pass
 * (phase 36, R2, guard G70). This class reads the description and each external reference itself:
 * a remote one through the {@link Fetcher} it is given (the guarded HTTP layer), a file one only
 * from the description's own folder. It pastes what it read into the description, and only then
 * hands the parser a document with nothing left to fetch. A reference that is circular or nested
 * too deep is refused, as is any that would leave the description's folder.
 */
public final class SpecLoader {

    /** Reads one remote document; implemented over the shared HTTP layer. */
    @FunctionalInterface
    public interface Fetcher {
        /** Refuses every remote document, for reading a local description offline. */
        Fetcher NONE =
                url -> {
                    throw new IOException(
                            "this description points at "
                                    + url.host()
                                    + ", and fetching it is not allowed here");
                };

        /**
         * Fetches the document at {@code url}.
         *
         * @throws IOException when it cannot be fetched, or the address rules refuse it
         */
        byte[] fetch(HttpUrl url) throws IOException;
    }

    /** The largest description, or referenced document, that is read. */
    static final long MAX_DOCUMENT_BYTES = 8L * 1024 * 1024;

    /** How many external documents one description may pull in. */
    static final int MAX_EXTERNAL_DOCUMENTS = 50;

    /** How deep references may nest; also what stops a circular external reference. */
    static final int MAX_REFERENCE_DEPTH = 12;

    private static final int MAX_NAME_CHARS = 64;
    private static final Pattern SERVER_VARIABLE = Pattern.compile("\\{([^}]+)}");
    private static final Pattern UNSAFE_NAME = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final Set<String> RESERVED_HEADERS =
            Set.of(
                    "authorization",
                    "content-type",
                    "content-length",
                    "accept",
                    "host",
                    "cookie",
                    "connection",
                    "transfer-encoding");
    private static final YAMLMapper TEXT = new YAMLMapper();

    private final Fetcher fetcher;
    private final Map<String, JsonNode> documents = new HashMap<>();
    private int fetched = 0;

    private SpecLoader(Fetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Reads the description at {@code location}.
     *
     * @param location an https address, or a path (absolute, or relative to {@code baseDir})
     * @param baseDir the folder relative paths are resolved against
     * @param fetcher how a remote document is fetched
     * @return the description
     * @throws IllegalArgumentException when it cannot be read, is not OpenAPI 3, or a reference is
     *     refused; the message says which and why
     */
    public static ApiDescription load(String location, Path baseDir, Fetcher fetcher) {
        return new SpecLoader(fetcher).read(location, baseDir);
    }

    private ApiDescription read(String location, Path baseDir) {
        Doc main;
        try {
            if (location.matches("(?i)https?://.*")) {
                HttpUrl url = HttpUrl.parse(location);
                if (url == null) throw new IllegalArgumentException(location + " is not a valid address");
                main = new Doc(parse(fetch(url), location), url, null);
            } else {
                Path file = baseDir.resolve(location).normalize();
                if (!Files.isRegularFile(file))
                    throw new IllegalArgumentException("the description " + location + " is not a file");
                main = new Doc(parse(readFile(file), location), null, file);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "could not read the description " + location + ": " + e.getMessage(), e);
        }
        JsonNode inlined = inline(main.root(), main, false, 0);
        String leftover = externalReference(inlined);
        if (leftover != null)
            throw new IllegalArgumentException("refused: the description still points at " + leftover);
        return describe(parseWithSwagger(inlined, location));
    }

    // ── Reading documents ────────────────────────────────────────────────────────────────────

    private byte[] fetch(HttpUrl url) throws IOException {
        if (++fetched > MAX_EXTERNAL_DOCUMENTS + 1)
            throw new IOException(
                    "more than " + MAX_EXTERNAL_DOCUMENTS + " documents are referenced; stopping");
        byte[] bytes = fetcher.fetch(url);
        if (bytes.length > MAX_DOCUMENT_BYTES)
            throw new IOException(url.host() + " sent a document larger than the limit");
        return bytes;
    }

    private static byte[] readFile(Path file) throws IOException {
        if (Files.size(file) > MAX_DOCUMENT_BYTES)
            throw new IOException(file.getFileName() + " is larger than the limit");
        return Files.readAllBytes(file);
    }

    private static JsonNode parse(byte[] bytes, String name) {
        try {
            JsonNode node = TEXT.readTree(bytes);
            if (node == null || !node.isObject())
                throw new IllegalArgumentException(name + " is not an OpenAPI description");
            return node;
        } catch (IOException e) {
            throw new IllegalArgumentException(name + " could not be read as JSON or YAML", e);
        }
    }

    // ── Pasting references in ────────────────────────────────────────────────────────────────

    /** A document and where it came from (an address or a file), so relative references resolve. */
    private record Doc(JsonNode root, HttpUrl url, Path file) {}

    /**
     * @param external whether {@code node} came from a referenced document: its own {@code #/...}
     *     references then point into that document and are pasted in too, because once pasted into
     *     the description they would point somewhere else
     */
    private JsonNode inline(JsonNode node, Doc doc, boolean external, int depth) {
        if (depth > MAX_REFERENCE_DEPTH)
            throw new IllegalArgumentException(
                    "refused: references nest more than "
                            + MAX_REFERENCE_DEPTH
                            + " deep (a circular reference in a referenced document is not supported)");
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual()) {
                String text = ref.asText();
                if (text.startsWith("#")) {
                    if (!external) return node; // the parser resolves it, inside this document
                    return inline(pointer(doc, text.substring(1)).deepCopy(), doc, true, depth + 1);
                }
                Target target = target(doc, text);
                return inline(pointer(target.doc(), target.fragment()).deepCopy(), target.doc(), true, depth + 1);
            }
            ObjectNode out = ((ObjectNode) node).objectNode();
            for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> field = it.next();
                out.set(field.getKey(), inline(field.getValue(), doc, external, depth));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = ((ArrayNode) node).arrayNode();
            node.forEach(item -> out.add(inline(item, doc, external, depth)));
            return out;
        }
        return node;
    }

    private record Target(Doc doc, String fragment) {}

    /** Resolves {@code ref} against {@code from}, fetching the document it names. */
    private Target target(Doc from, String ref) {
        int hash = ref.indexOf('#');
        String where = hash < 0 ? ref : ref.substring(0, hash);
        String fragment = hash < 0 ? "" : ref.substring(hash + 1);
        try {
            boolean absolute = where.matches("(?i)https?://.*");
            if (absolute || from.url() != null) {
                HttpUrl url = absolute ? HttpUrl.parse(where) : from.url().resolve(where);
                if (url == null)
                    throw new IllegalArgumentException(
                            "refused: " + ref + " is not an address a remote description may point at");
                JsonNode root = documents.get(url.toString());
                if (root == null) {
                    root = parse(fetch(url), url.toString());
                    documents.put(url.toString(), root);
                }
                return new Target(new Doc(root, url, null), fragment);
            }
            Path base = from.file().getParent().toRealPath();
            Path real = base.resolve(where).normalize().toRealPath();
            if (!real.startsWith(base))
                throw new IllegalArgumentException(
                        "refused: " + ref + " is outside the folder of the description");
            if (!Files.isRegularFile(real))
                throw new IllegalArgumentException("refused: " + ref + " is not a file");
            JsonNode root = documents.get(real.toString());
            if (root == null) {
                root = parse(readFile(real), real.toString());
                documents.put(real.toString(), root);
            }
            return new Target(new Doc(root, null, real), fragment);
        } catch (IOException e) {
            throw new IllegalArgumentException("could not read " + ref + ": " + e.getMessage(), e);
        }
    }

    private static JsonNode pointer(Doc doc, String fragment) {
        JsonNode found = fragment.isEmpty() ? doc.root() : doc.root().at(fragment);
        if (found == null || found.isMissingNode())
            throw new IllegalArgumentException(
                    "refused: the reference #" + fragment + " points at nothing in its document");
        return found;
    }

    /** The first {@code $ref} that does not point inside the document, or null. */
    private static String externalReference(JsonNode node) {
        if (node.isObject()) {
            JsonNode ref = node.get("$ref");
            if (ref != null && ref.isTextual() && !ref.asText().startsWith("#")) return ref.asText();
            for (Iterator<JsonNode> it = node.elements(); it.hasNext(); ) {
                String found = externalReference(it.next());
                if (found != null) return found;
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                String found = externalReference(item);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ── The swagger parser, given a document with nothing to fetch ───────────────────────────

    private static OpenAPI parseWithSwagger(JsonNode document, String name) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(true);
        SwaggerParseResult result;
        try {
            result = new OpenAPIV3Parser().readContents(TEXT.writeValueAsString(document), null, options);
        } catch (IOException e) {
            throw new IllegalArgumentException(name + " could not be read", e);
        }
        OpenAPI api = result.getOpenAPI();
        if (api == null) {
            String why =
                    result.getMessages() == null || result.getMessages().isEmpty()
                            ? "it is not an OpenAPI 3 description"
                            : String.join("; ", result.getMessages());
            throw new IllegalArgumentException("could not read the OpenAPI description " + name + ": " + why);
        }
        return api;
    }

    // ── From the swagger model to what a tool needs ──────────────────────────────────────────

    private static ApiDescription describe(OpenAPI api) {
        List<String> servers = new ArrayList<>();
        if (api.getServers() != null) for (Server s : api.getServers()) servers.add(withDefaults(s));
        List<ApiOperation> operations = new ArrayList<>();
        Set<String> taken = new HashSet<>();
        if (api.getPaths() != null) {
            api.getPaths()
                    .forEach(
                            (path, item) ->
                                    item.readOperationsMap()
                                            .forEach(
                                                    (method, op) -> {
                                                        ApiOperation built =
                                                                operation(path, method.name(), op, item, taken);
                                                        if (built != null) operations.add(built);
                                                    }));
        }
        String title = api.getInfo() != null && api.getInfo().getTitle() != null ? api.getInfo().getTitle() : "";
        String version = api.getInfo() != null && api.getInfo().getVersion() != null ? api.getInfo().getVersion() : "";
        String text =
                api.getInfo() != null && api.getInfo().getDescription() != null
                        ? api.getInfo().getDescription()
                        : "";
        return new ApiDescription(title, text, version, servers, operations);
    }

    private static String withDefaults(Server server) {
        String url = server.getUrl() == null ? "" : server.getUrl();
        if (server.getVariables() == null) return url;
        Matcher m = SERVER_VARIABLE.matcher(url);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            var variable = server.getVariables().get(m.group(1));
            m.appendReplacement(
                    out,
                    Matcher.quoteReplacement(
                            variable != null && variable.getDefault() != null
                                    ? variable.getDefault()
                                    : m.group()));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static ApiOperation operation(
            String path, String method, Operation op, PathItem item, Set<String> taken) {
        // a path that climbs out of the base address is not an operation to call
        for (String segment : path.split("/"))
            if (segment.equals(".") || segment.equals("..")) return null;
        Map<String, Parameter> merged = new LinkedHashMap<>();
        if (item.getParameters() != null)
            for (Parameter p : item.getParameters()) merged.put(p.getIn() + ":" + p.getName(), p);
        if (op.getParameters() != null)
            for (Parameter p : op.getParameters()) merged.put(p.getIn() + ":" + p.getName(), p);

        List<ApiOperation.Parameter> parameters = new ArrayList<>();
        Set<String> arguments = new HashSet<>(Set.of("body"));
        for (Parameter p : merged.values()) {
            String in = p.getIn() == null ? "" : p.getIn();
            boolean required = Boolean.TRUE.equals(p.getRequired()) || in.equals("path");
            if (in.equals("cookie")) {
                if (required) return null; // a call that cannot be made
                continue;
            }
            if (in.equals("header") && excludedHeader(p.getName())) continue;
            if (!in.equals("path") && !in.equals("query") && !in.equals("header")) continue;
            String argument = uniqueArgument(p.getName(), arguments);
            JsonNode schema =
                    p.getSchema() == null
                            ? Json.mapper().createObjectNode().put("type", "string")
                            : Json.mapper().valueToTree(p.getSchema());
            parameters.add(new ApiOperation.Parameter(p.getName(), argument, in, required, schema));
        }
        ApiOperation.Body body = null;
        if (op.getRequestBody() != null) {
            body = body(op);
            if (body == null && Boolean.TRUE.equals(op.getRequestBody().getRequired())) return null;
        }
        String id = uniqueId(op.getOperationId(), method, path, taken);
        String summary = op.getSummary() != null ? op.getSummary() : op.getDescription();
        return new ApiOperation(id, method, path, summary == null ? "" : summary, parameters, body);
    }

    private static boolean excludedHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return RESERVED_HEADERS.contains(lower) || Options.isSecretHeader(name);
    }

    private static ApiOperation.Body body(Operation op) {
        Content content = op.getRequestBody().getContent();
        if (content == null) return null;
        boolean required = Boolean.TRUE.equals(op.getRequestBody().getRequired());
        for (Map.Entry<String, MediaType> e : content.entrySet()) {
            String type = e.getKey().toLowerCase(Locale.ROOT);
            if (type.equals("application/json") || type.endsWith("+json"))
                return new ApiOperation.Body(required, "application/json", schemaOf(e.getValue()));
        }
        MediaType form = content.get("application/x-www-form-urlencoded");
        return form == null
                ? null
                : new ApiOperation.Body(required, "application/x-www-form-urlencoded", schemaOf(form));
    }

    private static JsonNode schemaOf(MediaType media) {
        return media.getSchema() == null
                ? Json.mapper().createObjectNode()
                : Json.mapper().valueToTree(media.getSchema());
    }

    /** A name providers accept for an argument, unique within the operation. */
    private static String uniqueArgument(String name, Set<String> taken) {
        String base = UNSAFE_NAME.matcher(name).replaceAll("_");
        if (base.isEmpty()) base = "param";
        if (base.length() > MAX_NAME_CHARS) base = base.substring(0, MAX_NAME_CHARS);
        String candidate = base;
        for (int n = 2; !taken.add(candidate); n++) candidate = base + "_" + n;
        return candidate;
    }

    private static String uniqueId(String declared, String method, String path, Set<String> taken) {
        String base =
                declared != null && !declared.isBlank()
                        ? declared
                        : method.toLowerCase(Locale.ROOT) + "_" + path;
        base = UNSAFE_NAME.matcher(base).replaceAll("_").replaceAll("_+", "_");
        if (base.length() > MAX_NAME_CHARS) base = base.substring(0, MAX_NAME_CHARS);
        String candidate = base;
        for (int n = 2; !taken.add(candidate); n++) candidate = base + "_" + n;
        return candidate;
    }
}
