package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.project.Project;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ConfigurationRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.FieldChange;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.FieldRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.SchemeRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.Usage;
import ru.rasulov.customfields.impl.undo.FieldLayoutState;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Lookups and views over Jira's field configurations and their schemes, shared by the read, create and assign tools.
 * Addresses are ids or {@code DEFAULT}; names are not unique and never resolve anything.
 */
@Named
public class FieldConfigurationCatalog {
    public static final String DEFAULT = "DEFAULT";
    static final String DEFAULT_SCHEME_NAME = "Default Field Configuration Scheme";

    private final FieldLayoutManager manager;

    @Inject
    public FieldConfigurationCatalog(@ComponentImport FieldLayoutManager manager) {
        this.manager = manager;
    }

    /** Jira's Default Field Configuration, or an explicit one by id. */
    public FieldLayout configuration(String address) {
        String text = address == null ? "" : address.trim();
        if (DEFAULT.equals(text)) return manager.getFieldLayout();
        Long id = id(text, "fieldConfiguration", ErrorCode.UNKNOWN_FIELD_CONFIGURATION);
        FieldLayout layout = configuration(id);
        if (layout == null) throw new Failure(ErrorCode.UNKNOWN_FIELD_CONFIGURATION, "No field configuration has the id "
                + id + ". Take ids from get_field_configurations, or use DEFAULT.");
        return layout;
    }

    /** An explicit (non-default) field configuration, or null; never Jira's fallback to the default. */
    public FieldLayout configuration(Long id) {
        if (id == null) return manager.getFieldLayout();
        for (EditableFieldLayout layout : manager.getEditableFieldLayouts()) {
            if (id.equals(layout.getId())) return layout;
        }
        FieldLayout fallback = manager.getFieldLayout();
        return fallback != null && id.equals(fallback.getId()) ? fallback : null;
    }

    /** An explicit field configuration scheme by id; {@code DEFAULT} is Jira's implicit scheme and returns null. */
    public FieldLayoutScheme scheme(String address) {
        String text = address == null ? "" : address.trim();
        if (DEFAULT.equals(text)) return null;
        Long id = id(text, "scheme", ErrorCode.UNKNOWN_SCHEME);
        for (FieldLayoutScheme scheme : manager.getFieldLayoutSchemes()) {
            if (id.equals(scheme.getId())) return manager.getMutableFieldLayoutScheme(id);
        }
        throw new Failure(ErrorCode.UNKNOWN_SCHEME, "No field configuration scheme has the id " + id
                + ". Take ids from get_field_configurations, or use DEFAULT.");
    }

    /** The configuration an issue type gets from a scheme: its own row, else the DEFAULT row, else Jira's default. */
    public FieldLayout effective(FieldLayoutScheme scheme, String issueTypeId) {
        if (scheme == null) return manager.getFieldLayout();
        Map<String, Long> rows = rows(scheme);
        Long id = rows.containsKey(issueTypeId) ? rows.get(issueTypeId) : rows.get(null);
        FieldLayout layout = configuration(id);
        if (layout == null) throw new IllegalStateException("Scheme " + scheme.getId()
                + " maps to missing field configuration " + id);
        return layout;
    }

    /** Scheme rows: issue type id (null for the DEFAULT row) to field configuration id (null for Jira's default). */
    public Map<String, Long> rows(FieldLayoutScheme scheme) {
        Map<String, Long> rows = new LinkedHashMap<String, Long>();
        for (FieldLayoutSchemeEntity entity : entities(scheme)) rows.put(entity.getIssueTypeId(), entity.getFieldLayoutId());
        return rows;
    }

    public List<FieldLayoutSchemeEntity> entities(FieldLayoutScheme scheme) {
        Collection<FieldLayoutSchemeEntity> entities = manager.getFieldLayoutSchemeEntities(scheme);
        return entities == null ? new ArrayList<FieldLayoutSchemeEntity>() : new ArrayList<FieldLayoutSchemeEntity>(entities);
    }

    public Usage otherProjects(FieldLayout layout, Project selected) {
        return usage(manager.getProjectsUsing(layout), selected);
    }

    public Usage otherProjects(FieldConfigurationScheme scheme, Project selected) {
        return usage(manager.getProjectsUsing(scheme), selected);
    }

    public Usage otherProjects(FieldLayoutScheme scheme, Project selected) {
        return usage(manager.getProjectsUsing(scheme), selected);
    }

    public List<Project> projectsUsing(FieldLayoutScheme scheme) {
        return sorted(manager.getProjectsUsing(scheme));
    }

    public int schemesUsing(FieldLayout layout) {
        Collection<FieldConfigurationScheme> schemes = manager.getFieldConfigurationSchemes(layout);
        return schemes == null ? 0 : schemes.size();
    }

    /** Names of every explicit field configuration, for uniqueness checks. */
    public List<String> configurationNames() {
        List<String> names = new ArrayList<String>();
        for (EditableFieldLayout layout : manager.getEditableFieldLayouts()) names.add(layout.getName());
        FieldLayout fallback = manager.getFieldLayout();
        if (fallback != null && fallback.getName() != null) names.add(fallback.getName());
        return names;
    }

    /** Names of every field configuration scheme, for uniqueness checks. */
    public List<String> schemeNames() {
        List<String> names = new ArrayList<String>();
        for (FieldLayoutScheme scheme : manager.getFieldLayoutSchemes()) names.add(scheme.getName());
        names.add(DEFAULT_SCHEME_NAME);
        return names;
    }

    /**
     * Fields whose required or hidden flag differs between two configurations, in field id order. Only fields that the
     * filter accepts are compared: callers pass the fields that can appear for the project and issue type.
     */
    public List<FieldChange> diff(FieldLayout before, FieldLayout after, java.util.function.Predicate<OrderableField> filter) {
        Map<String, FieldChange> changes = new TreeMap<String, FieldChange>();
        if (sameLayout(before, after)) return new ArrayList<FieldChange>();
        Map<String, FieldLayoutItem> old = items(before);
        Map<String, FieldLayoutItem> now = items(after);
        for (Map.Entry<String, FieldLayoutItem> entry : now.entrySet()) {
            FieldLayoutItem was = old.get(entry.getKey());
            FieldLayoutItem is = entry.getValue();
            OrderableField field = is.getOrderableField();
            if (!filter.test(field)) continue;
            boolean requiredBefore = was != null && was.isRequired(), hiddenBefore = was != null && was.isHidden();
            if (requiredBefore != is.isRequired() || hiddenBefore != is.isHidden()) {
                changes.put(entry.getKey(), new FieldChange(field(field), requiredBefore, is.isRequired(),
                        hiddenBefore, is.isHidden()));
            }
        }
        return new ArrayList<FieldChange>(changes.values());
    }

    /** Content fingerprints of the given configurations, for plan staleness checks. */
    public List<String> snapshots(Collection<FieldLayout> layouts) {
        List<String> result = new ArrayList<String>();
        for (FieldLayout layout : layouts) result.add(FieldLayoutState.snapshot(layout.getName(), layout));
        return result;
    }

    public static boolean sameLayout(FieldLayout a, FieldLayout b) {
        return key(a).equals(key(b));
    }

    public static String key(FieldLayout layout) {
        return layout == null || layout.isDefault() ? DEFAULT : String.valueOf(layout.getId());
    }

    public static ConfigurationRef ref(FieldLayout layout) {
        return new ConfigurationRef(layout.isDefault() ? null : layout.getId(), layout.getName(), layout.isDefault());
    }

    public static SchemeRef ref(FieldLayoutScheme scheme) {
        return scheme == null ? new SchemeRef(null, DEFAULT_SCHEME_NAME, null, true)
                : new SchemeRef(scheme.getId(), scheme.getName(), scheme.getDescription(), false);
    }

    public static SchemeRef ref(FieldConfigurationScheme scheme) {
        return scheme == null ? new SchemeRef(null, DEFAULT_SCHEME_NAME, null, true)
                : new SchemeRef(scheme.getId(), scheme.getName(), scheme.getDescription(), false);
    }

    public static FieldRef field(OrderableField field) {
        if (!(field instanceof CustomField)) return new FieldRef(field.getId(), field.getName(), null, "System field");
        CustomFieldType<?, ?> type = ((CustomField) field).getCustomFieldType();
        return new FieldRef(field.getId(), field.getName(), type == null ? null : type.getKey(),
                type == null ? null : type.getName());
    }

    public static List<Project> sorted(Collection<Project> projects) {
        List<Project> result = new ArrayList<Project>(projects == null ? Arrays.<Project>asList() : projects);
        result.sort(Comparator.comparing(Project::getKey));
        return result;
    }

    private static Usage usage(Collection<Project> users, Project selected) {
        List<Project> others = new ArrayList<Project>();
        for (Project project : sorted(users)) {
            if (selected == null || !selected.getId().equals(project.getId())) others.add(project);
        }
        List<ProjectRef> shown = new ArrayList<ProjectRef>();
        for (int i = 0; i < others.size() && i < Limits.MAX_PROJECTS_SHOWN; i++) {
            shown.add(new ProjectRef(others.get(i).getKey(), others.get(i).getName()));
        }
        return new Usage(others.size(), shown);
    }

    private static Map<String, FieldLayoutItem> items(FieldLayout layout) {
        Map<String, FieldLayoutItem> items = new LinkedHashMap<String, FieldLayoutItem>();
        if (layout != null && layout.getFieldLayoutItems() != null) {
            for (FieldLayoutItem item : layout.getFieldLayoutItems()) {
                if (item.getOrderableField() != null) items.put(item.getOrderableField().getId(), item);
            }
        }
        return items;
    }

    private static Long id(String text, String what, ErrorCode code) {
        try {
            long value = Long.parseLong(text);
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // reported below
        }
        throw new Failure(code, "'" + what + "' must be a positive id or DEFAULT, not '" + text + "'.");
    }

    /** A lookup the caller reports as FAILED with this code; nothing was changed. */
    public static final class Failure extends RuntimeException {
        private final ErrorCode code;
        private final List<String> suggestions;

        public Failure(ErrorCode code, String message) {
            this(code, message, null);
        }

        public Failure(ErrorCode code, String message, List<String> suggestions) {
            super(message, null, false, false);
            this.code = code;
            this.suggestions = suggestions;
        }

        public ErrorCode getCode() { return code; }
        public List<String> suggestions() { return suggestions; }
    }
}
