package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.project.Project;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse.CopyView;
import ru.rasulov.screens.api.dto.ScreenCloneScope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ScreenClonePlan {
    final Project project;
    final IssueTypeScreenScheme source;
    final ScreenCloneScope scope;
    final boolean copyScreens;
    final String newSchemeName;
    final Set<String> selectedIssueTypeIds;
    final Map<FieldScreenScheme, String> screenSchemes;
    final Map<FieldScreen, String> screens;
    final boolean separationNeeded;
    final CopyView schemeView;
    final List<CopyView> screenSchemeViews;
    final List<CopyView> screenViews;

    ScreenClonePlan(Project project, IssueTypeScreenScheme source, ScreenCloneScope scope, boolean copyScreens,
                    String newSchemeName, Set<String> selectedIssueTypeIds,
                    Map<FieldScreenScheme, String> screenSchemes, Map<FieldScreen, String> screens,
                    boolean separationNeeded, CopyView schemeView, List<CopyView> screenSchemeViews,
                    List<CopyView> screenViews) {
        this.project = project; this.source = source; this.scope = scope; this.copyScreens = copyScreens;
        this.newSchemeName = newSchemeName;
        this.selectedIssueTypeIds = Collections.unmodifiableSet(new LinkedHashSet<String>(selectedIssueTypeIds));
        this.screenSchemes = Collections.unmodifiableMap(new LinkedHashMap<FieldScreenScheme, String>(screenSchemes));
        this.screens = Collections.unmodifiableMap(new LinkedHashMap<FieldScreen, String>(screens));
        this.separationNeeded = separationNeeded; this.schemeView = schemeView;
        this.screenSchemeViews = screenSchemeViews; this.screenViews = screenViews;
    }
}
