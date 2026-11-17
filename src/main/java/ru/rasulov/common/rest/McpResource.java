package ru.rasulov.common.rest;

import ru.rasulov.common.impl.tools.McpServer;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.Consumes;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * {@code POST /mcp}: the MCP endpoint (Streamable HTTP transport, JSON answers). A message that needs no answer (a
 * notification) gets {@code 202} without a body. There is no stream: {@code GET} and {@code DELETE} are not offered, so
 * JAX-RS answers them with {@code 405}, which is what the transport allows a server without streams to do. The body is
 * taken as text and parsed by {@link McpServer}, so that a bad body is a JSON-RPC error and not a Jersey one.
 * <p>
 * CSRF / DNS rebinding: {@link BearerGate} runs before any processing.
 */
@Named
@Path("/mcp")
@Consumes(MediaType.WILDCARD)
@Produces(MediaType.APPLICATION_JSON)
public class McpResource {

    private final McpServer mcpServer;
    private final BearerGate gate;

    @Inject
    public McpResource(McpServer mcpServer, BearerGate gate) {
        this.mcpServer = mcpServer;
        this.gate = gate;
    }

    @POST
    public Response post(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
                         @HeaderParam("Origin") String origin, String body) {
        gate.require(authorization, origin);
        String answer = mcpServer.handle(body);
        return answer == null ? Response.status(Response.Status.ACCEPTED).build() : Response.ok(answer).build();
    }
}
