package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.project.Project;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse.CopyView;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Named
public class FieldConfigurationClonePlanner {
    private static final int MAX_NAME = 255;
    private static final String DEFAULT_SCHEME_NAME = "Default Field Configuration Scheme";
    private final FieldLayoutManager manager;

    @Inject
    public FieldConfigurationClonePlanner(@ComponentImport FieldLayoutManager manager) {
        this.manager = manager;
    }

    FieldConfigurationClonePlan plan(Project project) {
        manager.refresh();
        FieldConfigurationScheme sourceView = manager.getFieldConfigurationScheme(project);
        FieldLayoutScheme source = sourceView == null ? null : manager.getMutableFieldLayoutScheme(sourceView.getId());
        if (sourceView != null && source == null) {
            throw new FieldConfigurationCloneFailure(
                    ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse.ErrorCode.JIRA_ERROR,
                    "Jira could not read the project's field configuration scheme.");
        }

        List<FieldConfigurationClonePlan.Mapping> mappings = mappings(source);
        Map<String, FieldLayout> unique = new LinkedHashMap<String, FieldLayout>();
        for (FieldConfigurationClonePlan.Mapping mapping : mappings) unique.put(layoutKey(mapping.layout), mapping.layout);

        Set<String> layoutNames = new HashSet<String>();
        for (FieldLayout layout : manager.getEditableFieldLayouts()) {
            if (layout.getName() != null) layoutNames.add(normalize(layout.getName()));
        }
        Set<String> schemeNames = new HashSet<String>();
        for (FieldLayoutScheme scheme : manager.getFieldLayoutSchemes()) {
            if (scheme.getName() != null) schemeNames.add(normalize(scheme.getName()));
        }

        Map<FieldLayout, List<Project>> layoutOthers = new LinkedHashMap<FieldLayout, List<Project>>();
        Map<Long, Project> topOtherProjects = new LinkedHashMap<Long, Project>();
        boolean sharedLayout = false;
        for (FieldLayout layout : sortedLayouts(unique.values())) {
            List<Project> others = otherProjects(manager.getProjectsUsing(layout), project);
            if (!others.isEmpty()) sharedLayout = true;
            for (Project other : others) topOtherProjects.put(other.getId(), other);
            layoutOthers.put(layout, others);
        }
        List<Project> schemeOthers = sourceView == null
                ? sortedProjects(topOtherProjects.values())
                : otherProjects(manager.getProjectsUsing(sourceView), project);
        boolean needed = !schemeOthers.isEmpty() || sharedLayout;

        // Already separate: nothing is copied, so no configuration or scheme may carry a planned copy name.
        Map<FieldLayout, String> copies = new LinkedHashMap<FieldLayout, String>();
        List<CopyView> layoutViews = new ArrayList<CopyView>();
        for (Map.Entry<FieldLayout, List<Project>> entry : layoutOthers.entrySet()) {
            FieldLayout layout = entry.getKey();
            String newName = null;
            if (needed) {
                newName = freeName(titled(layout.getName(), project, entry.getValue()), layoutNames);
                layoutNames.add(normalize(newName));
                copies.put(layout, newName);
            }
            layoutViews.add(view(layout.getId(), layout.getName(), newName, entry.getValue()));
        }
        String sourceName = sourceView == null ? DEFAULT_SCHEME_NAME : sourceView.getName();
        String newSchemeName = needed ? freeName(titled(sourceName, project, schemeOthers), schemeNames) : null;
        CopyView schemeCopy = view(sourceView == null ? null : sourceView.getId(), sourceName, newSchemeName, schemeOthers);
        return new FieldConfigurationClonePlan(project, source, sourceView == null ? null : sourceView.getId(),
                sourceView == null ? "" : sourceView.getDescription(), newSchemeName, mappings, copies, needed,
                schemeCopy, layoutViews);
    }

    private List<FieldConfigurationClonePlan.Mapping> mappings(FieldLayoutScheme source) {
        if (source == null) {
            FieldLayout layout = manager.getFieldLayout();
            if (layout == null) throw jiraError("Jira could not read the default field configuration.");
            return Collections.singletonList(new FieldConfigurationClonePlan.Mapping(null, layout));
        }
        List<FieldLayoutSchemeEntity> entities = new ArrayList<FieldLayoutSchemeEntity>(source.getEntities());
        entities.sort(Comparator.comparing(entity -> entity.getIssueTypeId() == null ? "" : entity.getIssueTypeId()));
        List<FieldConfigurationClonePlan.Mapping> result = new ArrayList<FieldConfigurationClonePlan.Mapping>();
        for (FieldLayoutSchemeEntity entity : entities) {
            Long id = entity.getFieldLayoutId();
            FieldLayout layout = id == null ? manager.getFieldLayout() : manager.getFieldLayout(id);
            if (layout == null) throw jiraError("Jira could not read field configuration " + id + ".");
            result.add(new FieldConfigurationClonePlan.Mapping(entity.getIssueTypeId(), layout));
        }
        if (result.isEmpty()) throw jiraError("The project's field configuration scheme has no mappings.");
        return result;
    }

    private static FieldConfigurationCloneFailure jiraError(String message) {
        return new FieldConfigurationCloneFailure(
                ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse.ErrorCode.JIRA_ERROR,
                message);
    }

    private static CopyView view(Long id, String name, String newName, List<Project> others) {
        List<ProjectRef> refs = new ArrayList<ProjectRef>();
        for (int i = 0; i < others.size() && i < Limits.MAX_PROJECTS_SHOWN; i++) {
            Project project = others.get(i);
            refs.add(new ProjectRef(project.getKey(), project.getName()));
        }
        return new CopyView(id, name, null, newName, others.size(), refs);
    }

    private static List<Project> otherProjects(Collection<Project> projects, Project selected) {
        List<Project> result = new ArrayList<Project>();
        if (projects != null) for (Project project : projects) {
            if (!selected.getId().equals(project.getId())) result.add(project);
        }
        return sortedProjects(result);
    }

    private static List<Project> sortedProjects(Collection<Project> projects) {
        List<Project> result = new ArrayList<Project>(projects);
        result.sort(Comparator.comparing(Project::getKey));
        return result;
    }

    private static List<FieldLayout> sortedLayouts(Collection<FieldLayout> layouts) {
        List<FieldLayout> result = new ArrayList<FieldLayout>(layouts);
        result.sort(Comparator.comparing((FieldLayout layout) -> safe(layout.getName()))
                .thenComparing(FieldConfigurationClonePlanner::layoutKey));
        return result;
    }

    public static String titled(String oldName, Project project, List<Project> users) {
        String name = safe(oldName).isEmpty() ? "Field Configuration" : oldName;
        String key = project.getKey();
        if (startsWithKey(name, key)) return name;
        for (Project user : users) {
            if (startsWithKey(name, user.getKey())) return key + name.substring(user.getKey().length());
        }
        return key + ": " + name;
    }

    public static String freeName(String proposed, Set<String> taken) {
        String fitted = proposed.length() > MAX_NAME ? proposed.substring(0, MAX_NAME) : proposed;
        if (!taken.contains(normalize(fitted))) return fitted;
        String stem = fitted.replaceFirst("\\s+v\\d+$", "");
        for (int n = 1; ; n++) {
            String suffix = " v" + n;
            String base = stem.length() + suffix.length() > MAX_NAME
                    ? stem.substring(0, MAX_NAME - suffix.length()) : stem;
            if (!taken.contains(normalize(base + suffix))) return base + suffix;
        }
    }

    private static boolean startsWithKey(String name, String key) {
        return name.length() > key.length() && name.regionMatches(true, 0, key, 0, key.length())
                && !Character.isLetterOrDigit(name.charAt(key.length()));
    }
    private static String layoutKey(FieldLayout layout) {
        return layout.isDefault() ? "default" : String.valueOf(layout.getId());
    }
    private static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static String safe(String value) { return value == null ? "" : value; }
}
