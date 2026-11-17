package ru.rasulov.common.rest;

import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.applies.ApplyQuery;
import ru.rasulov.common.impl.applies.ApplyReader;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;

/**
 * {@code GET /applies} and {@code GET /applies/facets}: the apply journal for every administrator. Read only and not
 * published through MCP.
 */
@Named
@Path("/applies")
@Produces(MediaType.APPLICATION_JSON)
public class AppliesResource {
    private final AdministratorGuard guard;
    private final ApplyReader reader;

    @Inject
    public AppliesResource(AdministratorGuard guard, ApplyReader reader) {
        this.guard = guard;
        this.reader = reader;
    }

    @GET
    public Object list(@QueryParam("actor") String actor, @QueryParam("tool") String tool,
                       @QueryParam("state") String state, @QueryParam("status") String status,
                       @QueryParam("from") String from, @QueryParam("to") String to,
                       @QueryParam("before") String before, @QueryParam("limit") String limit) {
        guard.requireAdministrator();
        return reader.list(ApplyQuery.parse(actor, tool, state, status, from, to, before, limit));
    }

    @GET
    @Path("/facets")
    public Object facets() {
        guard.requireAdministrator();
        return reader.facets();
    }
}
