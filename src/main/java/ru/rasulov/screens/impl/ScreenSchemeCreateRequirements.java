package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.IssueFieldConstants;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which fields are required for creating an issue of a (project, issue type) pair and are absent from a given CREATE screen.
 * Only fields that can be put on a screen count (orderable per {@link FieldManager}; project and issue type are implicit),
 * and a custom field only when its context covers the pair. Unlike {@code RequiredOnCreate} it starts from the pairs and a
 * screen, not from a screen's current users, so archived projects and the screen of a plan's final table are covered.
 */
@Named
public class ScreenSchemeCreateRequirements {
    private final FieldLayoutManager layouts;
    private final FieldManager fieldManager;

    @Inject
    public ScreenSchemeCreateRequirements(@ComponentImport FieldLayoutManager layouts, @ComponentImport FieldManager fieldManager) {
        this.layouts = layouts; this.fieldManager = fieldManager;
    }

    /** A plan-local session: field configurations and screen fields are read once each. */
    Session session() { return new Session(); }

    final class Session {
        private static final long DEFAULT_LAYOUT = Long.MIN_VALUE;
        private final Map<Long, FieldConfigurationScheme> configurations = new HashMap<Long, FieldConfigurationScheme>();
        private final Map<Long, FieldLayout> layoutCache = new HashMap<Long, FieldLayout>();
        private final Map<Long, Set<String>> screenFields = new HashMap<Long, Set<String>>();

        /** The ids of the fields on any tab of the screen. Unreadable throws. */
        Set<String> fieldsOf(FieldScreen screen) {
            Set<String> cached = screenFields.get(screen.getId());
            if (cached != null) return cached;
            List<FieldScreenTab> tabs = screen.getTabs();
            if (tabs == null) throw new IllegalStateException("Could not read the tabs of screen " + screen.getId());
            Set<String> ids = new TreeSet<String>();
            for (FieldScreenTab tab : tabs) {
                List<FieldScreenLayoutItem> items = tab.getFieldScreenLayoutItems();
                if (items == null) throw new IllegalStateException("Could not read the fields of screen " + screen.getId());
                for (FieldScreenLayoutItem item : items) ids.add(item.getFieldId());
            }
            screenFields.put(screen.getId(), ids);
            return ids;
        }

        /** Required fields of the pair that {@code screen} lacks: id to name, sorted by id. Unreadable configuration throws. */
        Map<String, String> missing(Project project, IssueType type, FieldScreen screen) {
            Set<String> onScreen = fieldsOf(screen);
            Map<String, String> missing = new TreeMap<String, String>();
            for (FieldLayoutItem item : layout(project, type).getFieldLayoutItems()) {
                if (!item.isRequired()) continue;
                OrderableField<?> field = item.getOrderableField();
                if (field == null) throw new IllegalStateException("A required field configuration item has no field.");
                String id = field.getId();
                if (IssueFieldConstants.PROJECT.equals(id) || IssueFieldConstants.ISSUE_TYPE.equals(id)) continue;
                if (!fieldManager.isOrderableField(id)) continue;
                if (fieldManager.isCustomFieldId(id)) {
                    CustomField custom = fieldManager.getCustomField(id);
                    if (custom == null) throw new IllegalStateException("Could not read custom field " + id);
                    if (!custom.isInScope(project, Collections.singletonList(type.getId()))) continue;
                }
                if (!onScreen.contains(id)) missing.put(id, field.getName());
            }
            return missing;
        }

        /** Jira resolves the issue-type mapping and its default in getFieldLayoutId; no scheme means Jira's default configuration. */
        private FieldLayout layout(Project project, IssueType type) {
            FieldConfigurationScheme configuration;
            if (configurations.containsKey(project.getId())) configuration = configurations.get(project.getId());
            else {
                configuration = layouts.getFieldConfigurationScheme(project);
                configurations.put(project.getId(), configuration);
            }
            Long id = configuration == null ? null : configuration.getFieldLayoutId(type.getId());
            long key = id == null ? DEFAULT_LAYOUT : id;
            FieldLayout layout = layoutCache.get(key);
            if (layout == null) {
                layout = id == null ? layouts.getFieldLayout() : layouts.getFieldLayout(id);
                if (layout == null) throw new IllegalStateException("Could not read field configuration " + id);
                layoutCache.put(key, layout);
            }
            return layout;
        }
    }
}
