package ru.rasulov.common.rest;

import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.applies.ApplyQuery;
import ru.rasulov.common.impl.applies.ApplyReader;

import javax.ws.rs.core.Response;
import java.util.Collections;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AppliesResourceTest {
    private AdministratorGuard guard;
    private ApplyReader reader;
    private AppliesResource resource;

    @Before
    public void setUp() {
        guard = mock(AdministratorGuard.class);
        reader = mock(ApplyReader.class);
        resource = new AppliesResource(guard, reader);
    }

    @Test
    public void anonymousGets401AndNonAdministratorGets403ForBothEndpointsAndNothingIsRead() {
        for (boolean anonymous : new boolean[] {true, false}) {
            doThrow(new AccessException("no", anonymous)).when(guard).requireAdministrator();
            Response.Status expected = anonymous ? Response.Status.UNAUTHORIZED : Response.Status.FORBIDDEN;
            try {
                resource.list(null, null, null, null, null, null, null, null);
                fail();
            } catch (AccessException e) {
                assertEquals(expected, ServiceExceptionMapper.statusOf(e));
            }
            try {
                resource.facets();
                fail();
            } catch (AccessException e) {
                assertEquals(expected, ServiceExceptionMapper.statusOf(e));
            }
        }
        verifyNoInteractions(reader);
    }

    @Test
    public void filtersAreParsedAndPassedAndAnyAdministratorSeesEveryActor() {
        when(reader.list(any(ApplyQuery.class))).thenReturn(Collections.<String, Object>singletonMap("items", Collections.emptyList()));
        Object body = resource.list("alice", "create_screen", "COMPLETED", "CREATED", "10", "20", "30", "5");
        assertEquals(Collections.<String, Object>singletonMap("items", Collections.emptyList()), body);
        org.mockito.ArgumentCaptor<ApplyQuery> query = org.mockito.ArgumentCaptor.forClass(ApplyQuery.class);
        verify(reader).list(query.capture());
        assertEquals("alice", query.getValue().actor);
        assertEquals("create_screen", query.getValue().tool);
        assertEquals("COMPLETED", query.getValue().state);
        assertEquals("CREATED", query.getValue().status);
        assertEquals(Long.valueOf(10), query.getValue().from);
        assertEquals(Long.valueOf(20), query.getValue().to);
        assertEquals(Long.valueOf(30), query.getValue().before);
        assertEquals(5, query.getValue().limit);
    }

    @Test
    public void badParametersAreA400AndNothingIsRead() {
        try {
            resource.list(null, null, null, null, "abc", null, null, null);
            fail();
        } catch (InvalidRequestException e) {
            assertEquals(Response.Status.BAD_REQUEST, ServiceExceptionMapper.statusOf(e));
        }
        verifyNoInteractions(reader);
    }
}
