package ru.rasulov.common.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.tools.ChangeTools;
import ru.rasulov.common.impl.tools.ToolCall;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import java.util.Map;

/**
 * REST and MCP share validation, ownership checks and operation lifecycle, and the same {@link BearerGate}: every
 * method checks it before anything else, so a session cookie alone never reaches a tool.
 */
@Named
@Path("/changes")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChangesResource {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final Map<String, ToolCall> tools;
    private final BearerGate gate;

    @Inject
    public ChangesResource(ChangeTools changes, BearerGate gate) {
        tools = changes.tools();
        this.gate = gate;
    }

    @POST
    @Path("/sessions")
    public Object createSession(@Context HttpHeaders headers) {
        gate.require(headers);
        return call("create_change_session", MAPPER.createObjectNode());
    }

    @GET
    @Path("/sessions")
    public Object sessions(@Context HttpHeaders headers) {
        gate.require(headers);
        return call("list_change_sessions", MAPPER.createObjectNode());
    }

    @POST
    @Path("/sessions/{sessionId}/sets")
    public Object beginSet(@Context HttpHeaders headers, @PathParam("sessionId") String sessionId) {
        gate.require(headers);
        return call("begin_change_set", argument("sessionId", sessionId));
    }

    @GET
    @Path("/sessions/{sessionId}/operations")
    public Object changes(@Context HttpHeaders headers, @PathParam("sessionId") String sessionId) {
        gate.require(headers);
        return call("list_session_changes", argument("sessionId", sessionId));
    }

    @POST
    @Path("/sets/{changeSetId}/finish")
    public Object finishSet(@Context HttpHeaders headers, @PathParam("changeSetId") String changeSetId) {
        gate.require(headers);
        return call("finish_change_set", argument("changeSetId", changeSetId));
    }

    @POST
    @Path("/operations")
    public Object start(@Context HttpHeaders headers, String body) {
        gate.require(headers);
        return call("start_operation", body(body));
    }

    @GET
    @Path("/operations/{operationId}")
    public Object operation(@Context HttpHeaders headers, @PathParam("operationId") String operationId) {
        gate.require(headers);
        return call("get_operation", argument("operationId", operationId));
    }

    @POST
    @Path("/operations/{operationId}/cancel")
    public Object cancel(@Context HttpHeaders headers, @PathParam("operationId") String operationId, String body) {
        gate.require(headers);
        ObjectNode args = body(body);
        ru.rasulov.common.impl.tools.ToolArguments.allowOnly(args, "rollback");
        args.put("operationId", operationId);
        return call("cancel_operation", args);
    }

    @POST
    @Path("/rollback")
    public Object rollback(@Context HttpHeaders headers, String body) {
        gate.require(headers);
        return call("rollback_changes", body(body));
    }

    private Object call(String name, ObjectNode args) {
        return tools.get(name).call(args);
    }

    private static ObjectNode argument(String name, String value) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put(name, value);
        return args;
    }

    private static ObjectNode body(String body) {
        ObjectNode args = RequestBodies.parse(body, ObjectNode.class);
        if (args == null) throw new InvalidRequestException("Send a JSON object, not null.");
        return args;
    }
}
