package ru.rasulov.common.rest;

import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;

import javax.ws.rs.core.Response;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class ServiceExceptionMapperTest {

    private final ServiceExceptionMapper mapper = new ServiceExceptionMapper();

    @Test
    public void invalidRequestIs400() {
        assertEquals(400, mapper.toResponse(new InvalidRequestException("no fields")).getStatus());
    }

    @Test
    public void anonymousCallerIs401() {
        assertEquals(401, mapper.toResponse(new AccessException("login", true)).getStatus());
    }

    @Test
    public void authenticatedNonAdministratorIs403() {
        assertEquals(403, mapper.toResponse(new AccessException("admin only", false)).getStatus());
    }

    @Test
    public void busyIs409() {
        assertEquals(409, mapper.toResponse(new BusyException("retry")).getStatus());
    }

    @Test
    public void bodyCarriesTheMessage() {
        Response response = mapper.toResponse(new InvalidRequestException("no fields"));

        assertEquals(Collections.singletonMap("message", "no fields"), response.getEntity());
    }
}
