package ru.rasulov.common.impl.tools;

import com.atlassian.plugin.Plugin;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
/**
 * What the plugin tells an LLM client about itself, from files inside the plugin: the tools ({@code llm/*-tools.json}),
 * the rules that hold for all of them ({@code llm/rules.md}) and the plugin version. One source, so a client always gets
 * the tools and rules of the plugin it is talking to.
 * <p>
 * The tool files keep the tool format LLM APIs use ({@code input_schema}); {@link #mcpTools()} gives the same tools in the
 * shape of the MCP {@code tools/list} answer. Internal providers remain registered for journal recovery.
 */
@Named
public class ToolCatalog {

    private static final String[] FILES = {"llm/common-tools.json", "llm/customfields-tools.json",
            "llm/workflow-tools.json", "llm/screens-tools.json", "llm/projects-tools.json", "llm/issuetypes-tools.json",
            "llm/resolution-tools.json", "llm/rollback-tools.json"};
    private static final String RULES_FILE = "llm/rules.md";

    /** Key of this plugin ({@code ${atlassian.plugin.key}}: group id and artifact id). */
    static final String PLUGIN_KEY = "ru.rasulov.control";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PluginAccessor pluginAccessor;
    private volatile ArrayNode mcpTools;
    private volatile String rules;
    private Set<String> domainMutations;
    private Set<String> publishedTools;
    private Map<String, String> titles;

    @Inject
    public ToolCatalog(@ComponentImport PluginAccessor pluginAccessor) {
        this.pluginAccessor = pluginAccessor;
    }

    /** Version of this plugin; {@code null} if Jira does not know the plugin (never in a running Jira). */
    public String pluginVersion() {
        Plugin plugin = pluginAccessor.getPlugin(PLUGIN_KEY);
        return plugin == null ? null : plugin.getPluginInformation().getVersion();
    }

    /** The rules that hold for all tools. */
    public String instructions() {
        String loaded = rules;
        if (loaded == null) {
            try (InputStream in = open(RULES_FILE)) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int n = in.read(buffer); n >= 0; n = in.read(buffer)) {
                    bytes.write(buffer, 0, n);
                }
                loaded = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + RULES_FILE, e);
            }
            rules = loaded;
        }
        return loaded;
    }

    /**
     * Every tool of every file as an MCP tool: {@code name}, {@code description}, {@code inputSchema} and
     * {@code annotations} (title and the read-only / destructive / idempotent hints that let a client decide what to ask
     * the user about). The nodes are shared: do not change them.
     */
    public ArrayNode mcpTools() {
        ArrayNode loaded = mcpTools;
        if (loaded == null) {
            loaded = MAPPER.createArrayNode();
            Set<String> mutations = new HashSet<String>();
            Set<String> names = new HashSet<String>();
            Map<String, String> toolTitles = new HashMap<String, String>();
            for (String file : FILES) {
                for (JsonNode tool : read(file)) {
                    ObjectNode mcp = MAPPER.createObjectNode();
                    mcp.put("name", tool.get("name"));
                    mcp.put("description", tool.get("description"));
                    mcp.put("inputSchema", tool.get("input_schema"));
                    mcp.put("annotations", tool.get("annotations"));
                    loaded.add(mcp);
                    names.add(tool.get("name").textValue());
                    JsonNode annotations = tool.get("annotations");
                    if (!"llm/rollback-tools.json".equals(file) && annotations != null
                            && annotations.path("readOnlyHint").isBoolean()
                            && !annotations.path("readOnlyHint").booleanValue()) {
                        mutations.add(tool.get("name").textValue());
                    }
                    if (annotations != null && annotations.path("title").isTextual()) {
                        toolTitles.put(tool.get("name").textValue(), annotations.get("title").textValue());
                    }
                }
            }
            domainMutations = Collections.unmodifiableSet(mutations);
            publishedTools = Collections.unmodifiableSet(names);
            titles = Collections.unmodifiableMap(toolTitles);
            mcpTools = loaded;
        }
        return loaded;
    }

    /** The client-facing title of a tool ({@code annotations.title}); the tool name if it has none. */
    public String title(String toolName) {
        mcpTools();
        String title = titles.get(toolName);
        return title == null || title.trim().isEmpty() ? toolName : title;
    }

    /** Journal management tools cannot recursively submit themselves as Jira mutations. */
    public boolean isDomainMutation(String name) {
        mcpTools();
        return domainMutations.contains(name);
    }

    /** Only published tools are callable through MCP; internal journal handlers stay registered. */
    public boolean isPublished(String name) {
        mcpTools();
        return publishedTools.contains(name);
    }

    private static JsonNode read(String file) {
        try (InputStream in = open(file)) {
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the tool file " + file, e);
        }
    }

    private static InputStream open(String file) {
        InputStream in = ToolCatalog.class.getClassLoader().getResourceAsStream(file);
        if (in == null) {
            throw new IllegalStateException("The plugin is missing " + file);
        }
        return in;
    }
}
