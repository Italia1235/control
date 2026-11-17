package ru.rasulov.common.rest;

import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.impl.tools.ChangeTools;
import ru.rasulov.common.impl.tools.ToolCall;

import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ChangesResourceTest {

    private static final String BODY = "{\"dryRun\":true}";

    private ToolCall tool;
    private ChangesResource resource;

    @Before
    public void setUp() {
        tool = mock(ToolCall.class);
        when(tool.call(any())).thenReturn(Collections.singletonMap("ok", true));
        Map<String, ToolCall> tools = new HashMap<String, ToolCall>();
        for (String name : Arrays.asList("create_change_session", "list_change_sessions", "begin_change_set",
                "list_session_changes", "finish_change_set", "start_operation", "get_operation", "cancel_operation",
                "rollback_changes")) {
            tools.put(name, tool);
        }
        ChangeTools changes = mock(ChangeTools.class);
        when(changes.tools()).thenReturn(tools);
        ApplicationProperties properties = mock(ApplicationProperties.class);
        when(properties.getBaseUrl(UrlMode.CANONICAL)).thenReturn("https://jira.example.com/jira");
        resource = new ChangesResource(changes, new BearerGate(properties));
    }

    /** Every endpoint of the resource, called with the given request headers. */
    private List<Consumer<HttpHeaders>> endpoints() {
        return Arrays.<Consumer<HttpHeaders>>asList(
                h -> resource.createSession(h),
                h -> resource.sessions(h),
                h -> resource.beginSet(h, "s"),
                h -> resource.changes(h, "s"),
                h -> resource.finishSet(h, "c"),
                h -> resource.start(h, BODY),
                h -> resource.operation(h, "o"),
                h -> resource.cancel(h, "o", "{\"rollback\":false}"),
                h -> resource.rollback(h, BODY));
    }

    private static HttpHeaders headers(String authorization, String origin) {
        HttpHeaders headers = mock(HttpHeaders.class);
        if (authorization != null) {
            when(headers.getRequestHeader(HttpHeaders.AUTHORIZATION)).thenReturn(Collections.singletonList(authorization));
        }
        if (origin != null) when(headers.getRequestHeader("Origin")).thenReturn(Collections.singletonList(origin));
        return headers;
    }

    @Test
    public void aSessionCookieOrBasicRequestIs401OnEveryEndpointAndNeverReachesATool() {
        for (String authorization : new String[] {null, "Basic YWRtaW46YWRtaW4="}) {
            for (Consumer<HttpHeaders> endpoint : endpoints()) {
                try {
                    endpoint.accept(headers(authorization, null));
                    fail(String.valueOf(authorization));
                } catch (AccessException e) {
                    assertEquals(Response.Status.UNAUTHORIZED, ServiceExceptionMapper.statusOf(e));
                }
            }
        }
        verifyNoInteractions(tool);
    }

    @Test
    public void aForeignOriginIs403OnEveryEndpointEvenWithBearer() {
        for (Consumer<HttpHeaders> endpoint : endpoints()) {
            try {
                endpoint.accept(headers("Bearer token", "https://evil.example"));
                fail();
            } catch (AccessException e) {
                assertEquals(Response.Status.FORBIDDEN, ServiceExceptionMapper.statusOf(e));
            }
        }
        verifyNoInteractions(tool);
    }

    @Test
    public void bearerReachesTheToolOnEveryEndpoint() {
        for (Consumer<HttpHeaders> endpoint : endpoints()) endpoint.accept(headers("Bearer token", null));
        verify(tool, times(9)).call(any());
    }
}
