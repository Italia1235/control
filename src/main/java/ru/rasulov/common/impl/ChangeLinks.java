package ru.rasulov.common.impl;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.api.dto.report.ChangeReport;
import ru.rasulov.common.api.dto.report.ChangedObject;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** The only place that knows Jira admin page paths; links start at the canonical base URL, so the context path stays. */
@Named
public class ChangeLinks {

    private final ApplicationProperties properties;

    @Inject
    public ChangeLinks(@ComponentImport ApplicationProperties properties) {
        this.properties = properties;
    }

    public Report report() {
        return new Report();
    }

    /**
     * @param ref the value the kind's page needs: project key, {@code customfield_NNNNN}, field config id, screen id,
     *            workflow name, status id, issue type id or resolution id
     * @return absolute url, or {@code null} when the kind has no reliable page or {@code ref} is blank
     */
    public String url(ChangeKind kind, String ref) {
        return build(properties.getBaseUrl(UrlMode.CANONICAL), kind, ref);
    }

    static String build(String baseUrl, ChangeKind kind, String ref) {
        if (baseUrl == null || ref == null || ref.trim().isEmpty()) return null;
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        switch (kind) {
            case PROJECT:
                return base + "/plugins/servlet/project-config/" + encode(ref);
            case CUSTOM_FIELD:
            case FIELD_CONTEXT:
                return ref.startsWith("customfield_")
                        ? base + "/secure/admin/ConfigureCustomField!default.jspa?customFieldId="
                        + encode(ref.substring("customfield_".length())) : null;
            case OPTION:
                return base + "/secure/admin/EditCustomFieldOptions!default.jspa?fieldConfigId=" + encode(ref);
            case SCREEN:
            case SCREEN_TAB:
                return base + "/secure/admin/ConfigureFieldScreen.jspa?id=" + encode(ref);
            case WORKFLOW:
                return base + "/secure/admin/workflows/ViewWorkflowXml.jspa?workflowMode=live&workflowName="
                        + encode(ref);
            case STATUS:
                return base + "/secure/admin/EditStatus!default.jspa?id=" + encode(ref);
            case ISSUE_TYPE:
                return base + "/secure/admin/EditIssueType!default.jspa?id=" + encode(ref);
            case RESOLUTION:
                return base + "/secure/admin/EditResolution!default.jspa?id=" + encode(ref);
            case FIELD_CONFIGURATION:
            case FIELD_CONFIGURATION_SCHEME:
                return null; // no admin route is confirmed on a live Jira; the entry carries the id without a url
            default:
                return null;
        }
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Collects the created and reused objects of one apply; the tool adds each entry where it really happens. */
    public final class Report {
        private final List<ChangedObject> created = new ArrayList<ChangedObject>();
        private final List<ChangedObject> reused = new ArrayList<ChangedObject>();

        private Report() { }

        public Report created(ChangeKind kind, String id, String name, String ref) {
            created.add(new ChangedObject(kind, id, name, url(kind, ref), null));
            return this;
        }

        /** Options are counted, not listed: a field can carry hundreds. */
        public Report createdOptions(String configId, String name, int count) {
            created.add(new ChangedObject(ChangeKind.OPTION, configId, name, url(ChangeKind.OPTION, configId), count));
            return this;
        }

        public Report reused(ChangeKind kind, String id, String name, String ref) {
            reused.add(new ChangedObject(kind, id, name, url(kind, ref), null));
            return this;
        }

        /** Adds what another tool's report already recorded, e.g. the fields a screen creation provisioned. */
        public Report merge(ChangeReport other) {
            if (other != null) {
                created.addAll(other.getCreated());
                reused.addAll(other.getReused());
            }
            return this;
        }

        public ChangeReport build() {
            return new ChangeReport(created, reused);
        }
    }
}
