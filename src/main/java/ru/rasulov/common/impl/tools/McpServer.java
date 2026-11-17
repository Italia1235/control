package ru.rasulov.common.impl.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.exception.ServiceException;
import ru.rasulov.common.impl.AdministratorGuard;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * An MCP server (Model Context Protocol, Streamable HTTP transport) inside the plugin: an LLM client posts JSON-RPC
 * messages and gets the tools of the plugin, which run the same services as the REST adapters, in the caller's rights.
 * No SDK: it is JSON-RPC over one POST, and the part of the protocol a tool server needs is small.
 * <ul>
 *   <li>{@code initialize}: protocol version (the client's if this server speaks it, else the newest), capabilities
 *       ({@code tools}), server name and version, and the rules for the model as {@code instructions};</li>
 *   <li>{@code ping}; {@code tools/list}; {@code tools/call};</li>
 *   <li>notifications and answers from the client need no reply.</li>
 * </ul>
 * Stateless (no session id) and always answers with plain JSON, never a stream: works in a cluster and with the
 * simplest client. Refusals and failed domain executions are tool results with {@code isError}; their diagnostic
 * payload is preserved. Lifecycle tools return operation receipts: a successful read of a failed operation is not
 * itself an execution error. Unknown methods/tools and malformed envelopes are JSON-RPC errors.
 */
@Named
public class McpServer {

    static final String NEWEST_PROTOCOL = "2025-06-18";
    static final List<String> PROTOCOLS = Arrays.asList(NEWEST_PROTOCOL, "2025-03-26", "2024-11-05");
    static final String SERVER_NAME = "jira-control";

    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);

    /** Same JSON as the REST adapters: no auto-detected getters, no nulls (Jira's REST mapper omits them). */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .configure(MapperFeature.AUTO_DETECT_IS_GETTERS, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    private final AdministratorGuard administratorGuard;
    private final ToolCatalog catalog;
    private final ToolRegistry registry;

    @Inject
    public McpServer(AdministratorGuard administratorGuard, ToolCatalog catalog, ToolRegistry registry) {
        this.administratorGuard = administratorGuard;
        this.catalog = catalog;
        this.registry = registry;
    }

    /** A JSON-RPC error that is the answer to the message, not a tool result. */
    private static final class RpcException extends RuntimeException {
        final int code;

        RpcException(int code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }

    /**
     * @param body the POSTed JSON: one message, or an array of them (batching of the 2025-03-26 protocol)
     * @return the JSON to answer with, or {@code null} when there is nothing to answer (only notifications or answers)
     * @throws ru.rasulov.common.api.exception.AccessException the caller is anonymous or not a Jira administrator
     */
    public String handle(String body) {
        administratorGuard.requireAdministrator();
        if (body != null && body.length() > McpLimits.MAX_REQUEST_CHARS) {
            return write(error(null, INVALID_REQUEST, "The request is too large (" + body.length() + " characters; "
                    + "the limit is " + McpLimits.MAX_REQUEST_CHARS + "). Send a smaller request."));
        }
        JsonNode message;
        try {
            message = MAPPER.readTree(body == null ? "" : body);
        } catch (IOException e) {
            return write(error(null, PARSE_ERROR, "The body is not JSON."));
        }
        if (message == null || message.isMissingNode()) {
            return write(error(null, PARSE_ERROR, "The body is empty."));
        }
        if (message.isArray() && message.size() > McpLimits.MAX_BATCH_MESSAGES) {
            return write(error(null, INVALID_REQUEST, "A batch holds at most " + McpLimits.MAX_BATCH_MESSAGES
                    + " messages; it has " + message.size() + "."));
        }
        if (message.isArray()) {
            ArrayNode answers = MAPPER.createArrayNode();
            for (JsonNode each : message) {
                JsonNode answer = respondTo(each);
                if (answer != null) {
                    answers.add(answer);
                }
            }
            return answers.size() == 0 ? null : write(answers);
        }
        JsonNode answer = respondTo(message);
        return answer == null ? null : write(answer);
    }

    private JsonNode respondTo(JsonNode message) {
        if (!message.isObject()) {
            return error(null, INVALID_REQUEST, "A message is a JSON object.");
        }
        JsonNode id = message.get("id");
        JsonNode method = message.get("method");
        if (method == null || !method.isTextual()) {
            // an answer to a request of ours (we make none) is ignored; anything else is not a message
            return message.has("result") || message.has("error") ? null
                    : error(id, INVALID_REQUEST, "A message has a 'method'.");
        }
        boolean notification = id == null || id.isNull();
        try {
            JsonNode result = dispatch(method.textValue(), message.get("params"));
            return notification ? null : result(id, result);
        } catch (RpcException e) {
            return notification ? null : error(id, e.code, e.getMessage());
        }
    }

    private JsonNode dispatch(String method, JsonNode params) {
        switch (method) {
            case "initialize":
                return initialize(params);
            case "ping":
                return MAPPER.createObjectNode();
            case "tools/list":
                ObjectNode list = MAPPER.createObjectNode();
                list.put("tools", catalog.mcpTools());
                return list;
            case "tools/call":
                return callTool(params);
            default:
                if (method.startsWith("notifications/")) {
                    return null;
                }
                throw new RpcException(METHOD_NOT_FOUND, "Method not found: " + method);
        }
    }

    private static boolean present(JsonNode node, String field) {
        return node != null && node.get(field) != null && !node.get(field).isNull();
    }

    private JsonNode initialize(JsonNode params) {
        String asked = present(params, "protocolVersion")
                ? params.get("protocolVersion").asText() : null;
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", asked != null && PROTOCOLS.contains(asked) ? asked : NEWEST_PROTOCOL);
        ObjectNode tools = MAPPER.createObjectNode();
        tools.put("listChanged", false);
        result.putObject("capabilities").put("tools", tools);
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", SERVER_NAME);
        info.put("version", catalog.pluginVersion());
        result.put("instructions", catalog.instructions());
        return result;
    }

    private JsonNode callTool(JsonNode params) {
        if (!present(params, "name") || !params.get("name").isTextual()) {
            throw new RpcException(INVALID_PARAMS, "tools/call needs the 'name' of a tool.");
        }
        String name = params.get("name").textValue();
        ToolCall tool = registry.find(name);
        if (tool == null || !catalog.isPublished(name)) {
            throw new RpcException(INVALID_PARAMS, "Unknown tool: " + name);
        }
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = MAPPER.createObjectNode();
        } else if (!arguments.isObject()) {
            throw new RpcException(INVALID_PARAMS, "'arguments' is a JSON object.");
        }
        try {
            JsonNode dryRun = arguments.get("dryRun");
            if (catalog.isDomainMutation(name) && dryRun != null && dryRun.isBoolean() && !dryRun.booleanValue()) {
                return toolResult("Apply confirmed previews through start_operation. "
                        + "Direct MCP apply does not start an operation.", true);
            }
            // The model never sees undo or rollback details: REST and journal consumers keep them, this view does not.
            JsonNode payload = withoutUndoDetails(MAPPER.valueToTree(tool.call(arguments)));
            String text = MAPPER.writeValueAsString(payload);
            if (catalog.isDomainMutation(name)) {
                return toolResult(text, domainFailed(payload));
            }
            if ("start_operation".equals(name) || "get_operation".equals(name)) {
                return toolResult(text, false);
            }
            if (text.length() > McpLimits.MAX_READ_RESULT_CHARS) {
                return toolResult("The result of " + name + " is too large (" + text.length() + " characters; the "
                        + "limit is " + McpLimits.MAX_READ_RESULT_CHARS + "). Nothing was cut off: narrow the query "
                        + "or lower the limit and call again.", true);
            }
            return toolResult(text, false);
        } catch (ServiceException e) {
            return toolResult(e.getMessage(), true);
        } catch (IOException | RuntimeException e) {
            log.error("Tool {} failed", name, e);
            return toolResult("Jira failed while running the tool (details are in the Jira log). Nothing more is known "
                    + "here: tell the user, and do not repeat the call blindly.", true);
        }
    }

    private static final List<String> UNDO_FIELDS = Arrays.asList("undoWarning", "undoExpiresAt", "rollbackRequested");

    /** Removes the undo and rollback fields at every depth of a tool result. */
    private static JsonNode withoutUndoDetails(JsonNode node) {
        if (node != null && node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            for (String field : UNDO_FIELDS) {
                object.remove(field);
            }
        }
        if (node != null) {
            for (JsonNode child : node) {
                withoutUndoDetails(child);
            }
        }
        return node;
    }

    /** Inspect domain outcome containers only, not arbitrary data or persisted lifecycle history. */
    private static boolean domainFailed(JsonNode payload) {
        if (payload == null) return false;
        if (payload.isArray()) {
            for (JsonNode item : payload) {
                if (domainFailed(item)) return true;
            }
            return false;
        }
        if (!payload.isObject()) return false;
        if (present(payload, "errorCode")) return true;
        String status = payload.path("status").asText();
        if ("FAILED".equals(status) || "PARTIALLY_ASSIGNED".equals(status) || "PARTIALLY_CHANGED".equals(status)
                || "PARTIALLY_CLONED".equals(status) || "UNKNOWN".equals(status)) {
            return true;
        }
        return domainFailed(payload.get("results")) || domainFailed(payload.get("fields"));
    }

    private static ObjectNode toolResult(String text, boolean isError) {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode content = result.putArray("content").addObject();
        content.put("type", "text");
        content.put("text", text);
        result.put("isError", isError);
        return result;
    }

    private static ObjectNode result(JsonNode id, JsonNode result) {
        ObjectNode answer = envelope(id);
        answer.put("result", result);
        return answer;
    }

    private static ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode answer = envelope(id);
        ObjectNode error = answer.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return answer;
    }

    private static ObjectNode envelope(JsonNode id) {
        ObjectNode answer = MAPPER.createObjectNode();
        answer.put("jsonrpc", "2.0");
        answer.put("id", id == null ? MAPPER.getNodeFactory().nullNode() : id);
        return answer;
    }

    private static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write the MCP answer", e);
        }
    }
}
