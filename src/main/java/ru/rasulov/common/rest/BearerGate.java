package ru.rasulov.common.rest;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import ru.rasulov.common.api.exception.AccessException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.core.HttpHeaders;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * CSRF / DNS rebinding gate of the endpoints that an LLM client calls ({@code /mcp}, {@code /changes}): before any
 * processing the request must carry {@code Authorization: Bearer <PAT>} (a browser never attaches it on its own, and a
 * cross-origin page cannot add it without a CORS preflight that Jira refuses), so a request authenticated only by the
 * session cookie or by Basic is a {@code 401}. A present {@code Origin} that is not the Jira base URL's origin is a
 * {@code 403}. Jira itself validates the token; the administrator check stays with the called service.
 */
@Named
public class BearerGate {

    private static final String BEARER = "bearer ";

    private final ApplicationProperties properties;

    @Inject
    public BearerGate(@ComponentImport ApplicationProperties properties) {
        this.properties = properties;
    }

    /** @throws AccessException a foreign {@code Origin} (403) or no Bearer authorization (401) */
    public void require(HttpHeaders headers) {
        require(first(headers, HttpHeaders.AUTHORIZATION), first(headers, "Origin"));
    }

    /** @throws AccessException a foreign {@code Origin} (403) or no Bearer authorization (401) */
    public void require(String authorization, String origin) {
        if (origin != null && !sameOrigin(origin, properties.getBaseUrl(UrlMode.CANONICAL))) {
            throw new AccessException("Origin " + origin + " is not the Jira base URL.", false);
        }
        if (!isBearer(authorization)) {
            throw new AccessException("This endpoint accepts only Authorization: Bearer <personal access token>.", true);
        }
    }

    private static String first(HttpHeaders headers, String name) {
        List<String> values = headers.getRequestHeader(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }

    static boolean isBearer(String authorization) {
        return authorization != null && authorization.length() > BEARER.length()
                && authorization.regionMatches(true, 0, BEARER, 0, BEARER.length())
                && !authorization.substring(BEARER.length()).trim().isEmpty();
    }

    /** Scheme, host and effective port of {@code origin} equal those of {@code baseUrl}; "null" and garbage never do. */
    static boolean sameOrigin(String origin, String baseUrl) {
        try {
            URI o = new URI(origin.trim());
            URI b = new URI(baseUrl);
            if (o.getScheme() == null || o.getHost() == null || b.getScheme() == null || b.getHost() == null) return false;
            String scheme = o.getScheme().toLowerCase(Locale.ROOT);
            return scheme.equals(b.getScheme().toLowerCase(Locale.ROOT))
                    && o.getHost().equalsIgnoreCase(b.getHost())
                    && port(scheme, o.getPort()) == port(scheme, b.getPort());
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private static int port(String scheme, int port) {
        if (port != -1) return port;
        return "https".equals(scheme) ? 443 : "http".equals(scheme) ? 80 : -1;
    }
}
