package ru.rasulov.common.web;

import javax.inject.Named;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import com.atlassian.sal.api.auth.LoginUriProvider;
import com.atlassian.sal.api.message.I18nResolver;
import com.atlassian.sal.api.websudo.WebSudoManager;
import com.atlassian.templaterenderer.TemplateRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.impl.AdministratorGuard;

import javax.inject.Inject;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Admin page shell of the apply journal. It renders no journal data: the table is built in the browser from REST. The
 * page texts ({@code control.applies.*}) go to the browser in {@code data-i18n}: the {@code jsI18n} web-resource
 * transformer does not rewrite {@code AJS.I18n.getText} on Jira 10.3. The keys come from the bundled
 * {@code control.properties} and every text from {@link I18nResolver#getText}: {@code getAllTranslationsForPrefix}
 * kept the texts of the previous plugin build after an upgrade on Jira 10.3.24. Context values end in {@code Html}:
 * they are escaped here, and Velocity's HTML-safe naming keeps it from escaping them twice.
 */
@Named
public class AppliesAdminServlet extends HttpServlet {
    static final String TEMPLATE = "templates/applies.vm";
    static final String I18N_PREFIX = "control.applies.";
    static final String I18N_RESOURCE = "control.properties";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AdministratorGuard guard;
    private final WebSudoManager webSudo;
    private final LoginUriProvider loginUris;
    private final TemplateRenderer renderer;
    private final ApplicationProperties properties;
    private final I18nResolver i18n;

    @Inject
    public AppliesAdminServlet(AdministratorGuard guard, @ComponentImport WebSudoManager webSudo,
                               @ComponentImport LoginUriProvider loginUris, @ComponentImport TemplateRenderer renderer,
                               @ComponentImport ApplicationProperties properties, @ComponentImport I18nResolver i18n) {
        this.guard = guard;
        this.webSudo = webSudo;
        this.loginUris = loginUris;
        this.renderer = renderer;
        this.properties = properties;
        this.i18n = i18n;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            guard.requireAdministrator();
        } catch (AccessException denied) {
            if (denied.isAnonymous()) response.sendRedirect(loginUris.getLoginUri(requested(request)).toASCIIString());
            else response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        if (!webSudo.canExecuteRequest(request)) {
            webSudo.enforceWebSudoProtection(request, response);
            return;
        }
        Map<String, Object> context = new HashMap<String, Object>();
        context.put("titleHtml", html(i18n.getText("control.applies.title")));
        context.put("baseUrlHtml", html(properties.getBaseUrl(UrlMode.CANONICAL)));
        context.put("contextPathHtml", html(request.getContextPath()));
        Map<String, String> texts = new TreeMap<String, String>();
        for (String key : textKeys()) texts.put(key, i18n.getText(key));
        context.put("i18nHtml", html(JSON.writeValueAsString(texts)));
        response.setContentType("text/html;charset=UTF-8");
        renderer.render(TEMPLATE, context, response.getWriter());
    }

    /** The {@code control.applies.*} keys of the plugin's own i18n resource. */
    static Set<String> textKeys() throws IOException {
        Properties bundle = new Properties();
        try (InputStream in = AppliesAdminServlet.class.getClassLoader().getResourceAsStream(I18N_RESOURCE)) {
            if (in == null) throw new IOException("Missing i18n resource " + I18N_RESOURCE);
            bundle.load(in);
        }
        Set<String> keys = new TreeSet<String>();
        for (String key : bundle.stringPropertyNames()) if (key.startsWith(I18N_PREFIX)) keys.add(key);
        return keys;
    }

    private static URI requested(HttpServletRequest request) {
        StringBuffer url = request.getRequestURL();
        if (request.getQueryString() != null) url.append('?').append(request.getQueryString());
        return URI.create(url.toString());
    }

    static String html(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&': out.append("&amp;"); break;
                case '<': out.append("&lt;"); break;
                case '>': out.append("&gt;"); break;
                case '"': out.append("&quot;"); break;
                case '\'': out.append("&#39;"); break;
                default: out.append(c);
            }
        }
        return out.toString();
    }
}
