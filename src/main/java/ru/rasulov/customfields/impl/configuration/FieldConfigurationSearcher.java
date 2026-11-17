package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.Item;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.Kind;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.LinkView;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse.UsageView;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.SchemeRef;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Search mode of {@code get_field_configurations}: Field Configurations and Field Configuration Schemes by id or name,
 * including standalone ones no project uses. Read-only. Usage is read per page item; a usage that cannot be read is
 * reported UNAVAILABLE, never as an empty list.
 */
@Named
public class FieldConfigurationSearcher {
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 50;
    public static final int DEFAULT_USAGE_LIMIT = 20;
    public static final int MAX_USAGE_LIMIT = 100;
    private static final Logger LOG = LoggerFactory.getLogger(FieldConfigurationSearcher.class);
    private static final String UNAVAILABLE = "Jira failed while reading where this object is used. Details are in the "
            + "Jira log. Unknown usage does not mean the object is unused or safe to delete.";

    private final AdministratorGuard guard;
    private final FieldConfigurationCatalog catalog;
    private final FieldLayoutManager manager;
    private final ProjectManager projects;

    @Inject
    public FieldConfigurationSearcher(AdministratorGuard guard, FieldConfigurationCatalog catalog,
                                      @ComponentImport FieldLayoutManager manager,
                                      @ComponentImport ProjectManager projects) {
        this.guard = guard;
        this.catalog = catalog;
        this.manager = manager;
        this.projects = projects;
    }

    /** {@code usageOffset}/{@code usageLimit} page the usage links of the objects on the page (an exact id: all of its). */
    public FieldConfigurationSearchResponse search(Kind kind, String id, String name, int offset, int limit,
                                                   int usageOffset, int usageLimit) {
        guard.requireAdministrator();
        try {
            manager.refresh();
            return kind == Kind.CONFIGURATION ? configurations(id, name, offset, limit, usageOffset, usageLimit)
                    : schemes(id, name, offset, limit, usageOffset, usageLimit);
        } catch (FieldConfigurationCatalog.Failure e) {
            return FieldConfigurationSearchResponse.failed(kind, id, name, e.getCode(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Failed to search field configurations", e);
            return FieldConfigurationSearchResponse.failed(kind, id, name, ErrorCode.JIRA_ERROR,
                    "Jira failed while searching the field configurations. Details are in the Jira log.");
        }
    }

    // ---- Field Configurations -------------------------------------------------------------------------------------

    private FieldConfigurationSearchResponse configurations(String id, String name, int offset, int limit,
                                                           int usageOffset, int usageLimit) {
        List<FieldLayout> found = new ArrayList<FieldLayout>();
        if (id != null) {
            found.add(catalog.configuration(id));
        } else {
            Map<String, FieldLayout> all = new LinkedHashMap<String, FieldLayout>();
            for (EditableFieldLayout layout : manager.getEditableFieldLayouts()) {
                all.put(FieldConfigurationCatalog.key(layout), layout);
            }
            FieldLayout fallback = manager.getFieldLayout();
            if (fallback != null) all.putIfAbsent(FieldConfigurationCatalog.key(fallback), fallback);
            for (FieldLayout layout : all.values()) if (contains(layout.getName(), name)) found.add(layout);
            found.sort(Comparator.comparing((FieldLayout l) -> lower(l.getName()))
                    .thenComparing(l -> l.isDefault() ? -1L : l.getId()));
        }
        Page page = Page.of(found.size(), id == null ? offset : 0, id == null ? limit : found.size());
        Map<String, List<LinkView>> linksByConfiguration = null;
        String unavailable = null;
        boolean needsUsage = page.to > page.from;
        if (needsUsage) {
            try {
                linksByConfiguration = linksByConfiguration();
            } catch (RuntimeException e) {
                LOG.error("Failed to read field configuration scheme rows", e);
                unavailable = UNAVAILABLE;
            }
        }
        List<Item> items = new ArrayList<Item>();
        for (FieldLayout layout : found.subList(page.from, page.to)) {
            UsageView usage;
            if (unavailable != null) {
                usage = UsageView.unavailable(unavailable);
            } else {
                List<LinkView> links = linksByConfiguration.get(FieldConfigurationCatalog.key(layout));
                usage = linkUsage(links == null ? new ArrayList<LinkView>() : links, usageOffset, usageLimit);
            }
            items.add(new Item(FieldConfigurationCatalog.ref(layout), null, usage));
        }
        return FieldConfigurationSearchResponse.ok(Kind.CONFIGURATION, id, name, found.size(), page.from, page.size,
                page.next, items);
    }

    /**
     * Every way a scheme reaches a configuration, by configuration key, in scheme name/id order: its physical rows,
     * the implicit Default scheme, and for the DEFAULT configuration the fallback of a scheme that has no DEFAULT row
     * (the resolver gives its issue types without their own row Jira's Default Field Configuration).
     */
    private Map<String, List<LinkView>> linksByConfiguration() {
        Map<String, List<LinkView>> result = new LinkedHashMap<String, List<LinkView>>();
        List<FieldLayoutScheme> schemes = new ArrayList<FieldLayoutScheme>(manager.getFieldLayoutSchemes());
        schemes.sort(Comparator.comparing((FieldLayoutScheme s) -> lower(s.getName())).thenComparing(FieldLayoutScheme::getId));
        FieldLayout fallback = manager.getFieldLayout();
        Long defaultId = fallback == null ? null : fallback.getId();
        List<LinkView> defaults = new ArrayList<LinkView>();
        result.put(FieldConfigurationCatalog.DEFAULT, defaults);
        defaults.add(new LinkView(FieldConfigurationCatalog.ref((FieldLayoutScheme) null), null, null, true, false));
        for (FieldLayoutScheme scheme : schemes) {
            SchemeRef ref = FieldConfigurationCatalog.ref(scheme);
            boolean hasDefaultRow = false;
            List<FieldLayoutSchemeEntity> entities = catalog.entities(scheme);
            entities.sort(Comparator.comparing(FieldLayoutSchemeEntity::getIssueTypeId,
                    Comparator.nullsFirst(Comparator.<String>naturalOrder())));
            for (FieldLayoutSchemeEntity entity : entities) {
                Long layoutId = entity.getFieldLayoutId();
                String target = layoutId == null || layoutId.equals(defaultId) ? FieldConfigurationCatalog.DEFAULT
                        : String.valueOf(layoutId);
                boolean defaultRow = entity.getIssueTypeId() == null;
                hasDefaultRow |= defaultRow;
                IssueType type = defaultRow ? null : entity.getIssueTypeObject();
                result.computeIfAbsent(target, key -> new ArrayList<LinkView>()).add(new LinkView(ref,
                        entity.getIssueTypeId(), type == null ? null : type.getName(), defaultRow, false));
            }
            if (!hasDefaultRow) defaults.add(new LinkView(ref, null, null, true, true));
        }
        return result;
    }

    private static UsageView linkUsage(List<LinkView> links, int offset, int limit) {
        java.util.Set<String> schemes = new java.util.HashSet<String>();
        for (LinkView link : links) schemes.add(String.valueOf(link.getScheme().getId()));
        Page page = Page.of(links.size(), offset, limit);
        return UsageView.ofLinks(links.size(), schemes.size(), page.from, page.next, links.subList(page.from, page.to));
    }

    // ---- Field Configuration Schemes ------------------------------------------------------------------------------

    private FieldConfigurationSearchResponse schemes(String id, String name, int offset, int limit,
                                                     int usageOffset, int usageLimit) {
        List<FieldLayoutScheme> found = new ArrayList<FieldLayoutScheme>();   // null element = the implicit default scheme
        if (id != null) {
            found.add(catalog.scheme(id));
        } else {
            if (contains(FieldConfigurationCatalog.DEFAULT_SCHEME_NAME, name)) found.add(null);
            for (FieldLayoutScheme scheme : manager.getFieldLayoutSchemes()) if (contains(scheme.getName(), name)) found.add(scheme);
            found.sort(Comparator.comparing((FieldLayoutScheme s) -> lower(schemeName(s)))
                    .thenComparing(s -> s == null ? -1L : s.getId()));
        }
        Page page = Page.of(found.size(), id == null ? offset : 0, id == null ? limit : found.size());
        List<Item> items = new ArrayList<Item>();
        for (FieldLayoutScheme scheme : found.subList(page.from, page.to)) {
            items.add(new Item(null, FieldConfigurationCatalog.ref(scheme), projectUsage(scheme, usageOffset, usageLimit)));
        }
        return FieldConfigurationSearchResponse.ok(Kind.SCHEME, id, name, found.size(), page.from, page.size, page.next,
                items);
    }

    private UsageView projectUsage(FieldLayoutScheme scheme, int usageOffset, int usageLimit) {
        try {
            List<Project> users = scheme == null ? defaultSchemeProjects() : catalog.projectsUsing(scheme);
            Page page = Page.of(users.size(), usageOffset, usageLimit);
            List<ProjectRef> shown = new ArrayList<ProjectRef>();
            for (Project project : users.subList(page.from, page.to)) {
                shown.add(new ProjectRef(project.getKey(), project.getName()));
            }
            return UsageView.ofProjects(users.size(), page.from, page.next, shown);
        } catch (RuntimeException e) {
            LOG.error("Failed to read projects using a field configuration scheme", e);
            return UsageView.unavailable(UNAVAILABLE);
        }
    }

    /** Projects with no explicit scheme use Jira's implicit Default Field Configuration Scheme. */
    private List<Project> defaultSchemeProjects() {
        List<Project> result = new ArrayList<Project>();
        for (Project project : projects.getProjects()) {
            if (manager.getFieldConfigurationScheme(project) == null) result.add(project);
        }
        return FieldConfigurationCatalog.sorted(result);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static boolean contains(String text, String needle) {
        return needle == null || lower(text).contains(lower(needle.trim()));
    }

    private static String schemeName(FieldLayoutScheme scheme) {
        return scheme == null ? FieldConfigurationCatalog.DEFAULT_SCHEME_NAME : scheme.getName();
    }

    private static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    /** A page of a sorted result: [from, to) of {@code total}; {@code next} is null on the last page. */
    private static final class Page {
        final int from;
        final int to;
        final int size;
        final Integer next;

        private Page(int from, int to, int size, Integer next) {
            this.from = from; this.to = to; this.size = size; this.next = next;
        }

        static Page of(int total, int offset, int limit) {
            int from = Math.min(Math.max(offset, 0), total);
            int to = (int) Math.min((long) from + Math.max(limit, 0), total);
            return new Page(from, to, limit, to < total ? Integer.valueOf(to) : null);
        }
    }
}
