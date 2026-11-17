package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.project.Project;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse.CopyView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class FieldConfigurationClonePlan {
    static final class Mapping {
        final String issueTypeId;
        final FieldLayout layout;

        Mapping(String issueTypeId, FieldLayout layout) {
            this.issueTypeId = issueTypeId;
            this.layout = layout;
        }
    }

    final Project project;
    final FieldLayoutScheme sourceScheme;
    final Long sourceSchemeId;
    final String sourceDescription;
    final String newSchemeName;
    final List<Mapping> mappings;
    final Map<FieldLayout, String> layouts;
    final boolean separationNeeded;
    final CopyView schemeView;
    final List<CopyView> layoutViews;

    FieldConfigurationClonePlan(Project project, FieldLayoutScheme sourceScheme, Long sourceSchemeId,
                                String sourceDescription, String newSchemeName, List<Mapping> mappings,
                                Map<FieldLayout, String> layouts, boolean separationNeeded,
                                CopyView schemeView, List<CopyView> layoutViews) {
        this.project = project;
        this.sourceScheme = sourceScheme;
        this.sourceSchemeId = sourceSchemeId;
        this.sourceDescription = sourceDescription;
        this.newSchemeName = newSchemeName;
        this.mappings = Collections.unmodifiableList(new ArrayList<Mapping>(mappings));
        this.layouts = Collections.unmodifiableMap(new LinkedHashMap<FieldLayout, String>(layouts));
        this.separationNeeded = separationNeeded;
        this.schemeView = schemeView;
        this.layoutViews = Collections.unmodifiableList(new ArrayList<CopyView>(layoutViews));
    }
}
