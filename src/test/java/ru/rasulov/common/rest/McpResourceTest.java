package ru.rasulov.common.rest;

import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.impl.tools.McpServer;

import javax.ws.rs.core.Response;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class McpResourceTest {
    private static final String BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";

    private McpServer server;
    private McpResource resource;

    @Before
    public void setUp() {
        server = mock(McpServer.class);
        ApplicationProperties properties = mock(ApplicationProperties.class);
        when(properties.getBaseUrl(UrlMode.CANONICAL)).thenReturn("https://jira.example.com/jira");
        when(server.handle(BODY)).thenReturn("{}");
        resource = new McpResource(server, new BearerGate(properties));
    }

    @Test
    public void withoutBearerTheRequestIs401AndNeverReachesTheServer() {
        for (String authorization : new String[] {null, "", "Basic YWRtaW46YWRtaW4=", "Bearer", "Bearer   ", "Bearerx abc"}) {
            try {
                resource.post(authorization, null, BODY);
                fail(String.valueOf(authorization));
            } catch (AccessException e) {
                assertEquals(Response.Status.UNAUTHORIZED, ServiceExceptionMapper.statusOf(e));
            }
        }
        verifyNoInteractions(server);
    }

    @Test
    public void aForeignOriginIs403EvenWithBearerAndNeverReachesTheServer() {
        for (String origin : new String[] {"https://evil.example", "null", "http://jira.example.com",
                "https://jira.example.com:8443", "https://jira.example.com.evil.example", "not a url"}) {
            try {
                resource.post("Bearer token", origin, BODY);
                fail(origin);
            } catch (AccessException e) {
                assertEquals(Response.Status.FORBIDDEN, ServiceExceptionMapper.statusOf(e));
            }
        }
        verifyNoInteractions(server);
    }

    @Test
    public void bearerWithoutOriginOrWithTheBaseUrlOriginIsHandled() {
        for (String origin : new String[] {null, "https://jira.example.com", "HTTPS://JIRA.example.com:443"}) {
            assertEquals(200, resource.post("bearer token", origin, BODY).getStatus());
        }
        verify(server, times(3)).handle(BODY);
    }
}
