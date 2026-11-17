package ru.rasulov.customfields.impl.screen;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import org.ofbiz.core.entity.GenericValue;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * How far a screen reaches: the projects that use it through any screen scheme, whatever their issue types. Walks
 * Jira's chain backwards: screen, screen schemes containing it, issue type screen schemes containing those, projects
 * on those. One instance serves one plan, and remembers what it has read: a scheme shared by a hundred screens is
 * read once, not a hundred times.
 */
final class ScreenReach {

    private final FieldScreenSchemeManager screenSchemeManager;
    private final IssueTypeScreenSchemeManager issueTypeScreenSchemeManager;
    private final Map<Long, Set<Long>> projectsOfScreenScheme = new HashMap<Long, Set<Long>>();
    private final Map<Long, Set<Long>> projectsOfIssueTypeScreenScheme = new HashMap<Long, Set<Long>>();
    private final Map<Long, Set<Long>> projectsOfScreen = new HashMap<Long, Set<Long>>();

    ScreenReach(FieldScreenSchemeManager screenSchemeManager, IssueTypeScreenSchemeManager issueTypeScreenSchemeManager) {
        this.screenSchemeManager = screenSchemeManager;
        this.issueTypeScreenSchemeManager = issueTypeScreenSchemeManager;
    }

    /** Ids of all projects that use the screen. */
    Set<Long> projectIdsUsing(FieldScreen screen) {
        Set<Long> cached = projectsOfScreen.get(screen.getId());
        if (cached != null) {
            return cached;
        }
        Set<Long> ids = new LinkedHashSet<Long>();
        for (FieldScreenScheme scheme : screenSchemeManager.getFieldScreenSchemes(screen)) {
            ids.addAll(projectIdsOf(scheme));
        }
        Set<Long> shared = java.util.Collections.unmodifiableSet(ids);
        projectsOfScreen.put(screen.getId(), shared);
        return shared;
    }

    private Set<Long> projectIdsOf(FieldScreenScheme scheme) {
        Set<Long> cached = projectsOfScreenScheme.get(scheme.getId());
        if (cached != null) {
            return cached;
        }
        Set<Long> ids = new LinkedHashSet<Long>();
        for (Object each : issueTypeScreenSchemeManager.getIssueTypeScreenSchemes(scheme)) {
            ids.addAll(projectIdsOf((IssueTypeScreenScheme) each));
        }
        projectsOfScreenScheme.put(scheme.getId(), ids);
        return ids;
    }

    private Set<Long> projectIdsOf(IssueTypeScreenScheme scheme) {
        Set<Long> cached = projectsOfIssueTypeScreenScheme.get(scheme.getId());
        if (cached != null) {
            return cached;
        }
        Set<Long> ids = new LinkedHashSet<Long>();
        Collection<GenericValue> projects = issueTypeScreenSchemeManager.getProjects(scheme);
        for (GenericValue project : projects) {
            ids.add(project.getLong("id"));
        }
        projectsOfIssueTypeScreenScheme.put(scheme.getId(), ids);
        return ids;
    }
}
