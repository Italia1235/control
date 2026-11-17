package ru.rasulov.common.rest;

import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.ServiceException;
import ru.rasulov.common.api.exception.InvalidRequestException;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;
import javax.ws.rs.ext.Provider;
import java.util.Collections;

/**
 * The one place that knows HTTP status codes of service failures: 400 invalid request, 401 anonymous, 403 not an
 * administrator, 409 another call holds the lock (retry).
 */
@Provider
public class ServiceExceptionMapper implements ExceptionMapper<ServiceException> {

    @Override
    public Response toResponse(ServiceException e) {
        return Response.status(statusOf(e))
                .type(MediaType.APPLICATION_JSON)
                .entity(Collections.singletonMap("message", e.getMessage()))
                .build();
    }

    static Response.Status statusOf(ServiceException e) {
        if (e instanceof InvalidRequestException) {
            return Response.Status.BAD_REQUEST;
        }
        if (e instanceof AccessException) {
            return ((AccessException) e).isAnonymous()
                    ? Response.Status.UNAUTHORIZED : Response.Status.FORBIDDEN;
        }
        if (e instanceof BusyException) {
            return Response.Status.CONFLICT;
        }
        return Response.Status.INTERNAL_SERVER_ERROR;
    }
}
