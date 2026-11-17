package ru.rasulov.common.web;

import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.sal.api.message.I18nResolver;
import com.atlassian.sal.api.websudo.WebSudoManager;
import com.atlassian.templaterenderer.TemplateRenderer;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.impl.AdministratorGuard;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AppliesAdminServletTest {
    private AdministratorGuard guard;
    private WebSudoManager webSudo;
    private LoginUriProvider login;
    private TemplateRenderer renderer;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private AppliesAdminServlet servlet;

    @Before
    public void setUp() throws Exception {
        guard = mock(AdministratorGuard.class);
        webSudo = mock(WebSudoManager.class);
        login = mock(LoginUriProvider.class);
        renderer = mock(TemplateRenderer.class);
        ApplicationProperties properties = mock(ApplicationProperties.class);
        I18nResolver i18n = mock(I18nResolver.class);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        when(properties.getBaseUrl(UrlMode.CANONICAL)).thenReturn("http://jira.example/jira");
        when(i18n.getText(anyString())).thenAnswer(call -> "T " + call.getArgument(0));
        when(i18n.getText("control.applies.title")).thenReturn("Title <b>");
        when(i18n.getText("control.applies.empty")).thenReturn("No \"rows\" <i>");
        when(request.getContextPath()).thenReturn("/jira");
        when(request.getRequestURL()).thenReturn(new StringBuffer("http://jira.example/jira/plugins/servlet/control/applies"));
        when(request.getQueryString()).thenReturn("a=1");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        servlet = new AppliesAdminServlet(guard, webSudo, login, renderer, properties, i18n);
    }

    @Test
    public void anonymousIsRedirectedToLoginWithReturnAddress() throws Exception {
        when(guard.requireAdministrator()).thenThrow(new AccessException("login", true));
        when(login.getLoginUri(any(URI.class))).thenReturn(URI.create("http://jira.example/jira/login.jsp"));
        servlet.doGet(request, response);
        ArgumentCaptor<URI> back = ArgumentCaptor.forClass(URI.class);
        verify(login).getLoginUri(back.capture());
        assertEquals("http://jira.example/jira/plugins/servlet/control/applies?a=1", back.getValue().toString());
        verify(response).sendRedirect("http://jira.example/jira/login.jsp");
        verifyNoInteractions(renderer);
    }

    @Test
    public void nonAdministratorGets403WithoutWebSudoOrPage() throws Exception {
        when(guard.requireAdministrator()).thenThrow(new AccessException("admin", false));
        servlet.doGet(request, response);
        verify(response).sendError(HttpServletResponse.SC_FORBIDDEN);
        verifyNoInteractions(renderer, webSudo);
    }

    @Test
    public void administratorWithoutWebSudoIsSentToWebSudo() throws Exception {
        when(webSudo.canExecuteRequest(request)).thenReturn(false);
        servlet.doGet(request, response);
        verify(webSudo).enforceWebSudoProtection(request, response);
        verifyNoInteractions(renderer);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void administratorGetsPageWithEscapedValuesTextsAndNoJournalData() throws Exception {
        when(webSudo.canExecuteRequest(request)).thenReturn(true);
        servlet.doGet(request, response);
        ArgumentCaptor<Map> context = ArgumentCaptor.forClass(Map.class);
        verify(renderer).render(eq(AppliesAdminServlet.TEMPLATE), context.capture(), any(java.io.Writer.class));
        assertEquals("Title &lt;b&gt;", context.getValue().get("titleHtml"));
        assertEquals("http://jira.example/jira", context.getValue().get("baseUrlHtml"));
        assertEquals("/jira", context.getValue().get("contextPathHtml"));
        // Every control.applies.* key of control.properties is sent, resolved through getText (locale, upgrades).
        String texts = (String) context.getValue().get("i18nHtml");
        assertTrue(texts.contains("&quot;control.applies.tool.create_workflow&quot;:&quot;T control.applies.tool.create_workflow&quot;"));
        assertTrue(texts.contains("&quot;control.applies.tool.configure_workflow&quot;:&quot;T control.applies.tool.configure_workflow&quot;"));
        // Texts reach the browser as JSON inside an attribute: quotes and tags must not break out of it.
        assertTrue(texts.contains("&quot;control.applies.empty&quot;:&quot;No \\&quot;rows\\&quot; &lt;i&gt;&quot;"));
        assertEquals(4, context.getValue().size());
    }

    @Test
    public void htmlEscapesAttributeBreakers() {
        assertEquals("&amp;&lt;&gt;&quot;&#39;", AppliesAdminServlet.html("&<>\"'"));
    }
}
