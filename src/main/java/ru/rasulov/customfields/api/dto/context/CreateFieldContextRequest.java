package ru.rasulov.customfields.api.dto.context;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.common.api.dto.field.OptionDefinition;

import java.util.List;

/**
 * Input of create_field_context: preview with the field and the new context, apply with {@code dryRun=false} and
 * the preview's planId only. Both nullable flags and absent versus empty lists survive for exact axis validation.
 */
public final class CreateFieldContextRequest {
    private final Boolean dryRun;
    private final String planId;
    private final String fieldId;
    private final String name;
    private final String description;
    private final Boolean allProjects;
    private final List<String> projects;
    private final Boolean allIssueTypes;
    private final List<String> issueTypes;
    private final List<OptionDefinition> options;
    private final AssetsFieldSettings assets;

    public CreateFieldContextRequest(Boolean dryRun, String planId, String fieldId, String name, String description,
                                     Boolean allProjects, List<String> projects, Boolean allIssueTypes,
                                     List<String> issueTypes, List<OptionDefinition> options) {
        this(dryRun, planId, fieldId, name, description, allProjects, projects, allIssueTypes, issueTypes, options, null);
    }

    @JsonCreator
    public CreateFieldContextRequest(@JsonProperty("dryRun") Boolean dryRun,
                                     @JsonProperty("planId") String planId,
                                     @JsonProperty("fieldId") String fieldId,
                                     @JsonProperty("name") String name,
                                     @JsonProperty("description") String description,
                                     @JsonProperty("allProjects") Boolean allProjects,
                                     @JsonProperty("projects") List<String> projects,
                                     @JsonProperty("allIssueTypes") Boolean allIssueTypes,
                                     @JsonProperty("issueTypes") List<String> issueTypes,
                                     @JsonProperty("options") List<OptionDefinition> options,
                                     @JsonProperty("assets") AssetsFieldSettings assets) {
        this.dryRun = dryRun;
        this.planId = planId;
        this.fieldId = fieldId;
        this.name = name;
        this.description = description;
        this.allProjects = allProjects;
        this.projects = projects == null ? null : Immutables.listOf(projects);
        this.allIssueTypes = allIssueTypes;
        this.issueTypes = issueTypes == null ? null : Immutables.listOf(issueTypes);
        this.options = options == null ? null : Immutables.listOf(options);
        this.assets = assets;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("allProjects") public Boolean isAllProjects() { return allProjects; }
    @JsonProperty("projects") public List<String> getProjects() { return projects; }
    @JsonProperty("allIssueTypes") public Boolean isAllIssueTypes() { return allIssueTypes; }
    @JsonProperty("issueTypes") public List<String> getIssueTypes() { return issueTypes; }
    /** Own options of the new context of a select-like field, in display order; absent for every other type. */
    @JsonProperty("options") public List<OptionDefinition> getOptions() { return options; }
    /** Optional Assets object settings of the new context; accepted only for an Assets object field. */
    @JsonProperty("assets") public AssetsFieldSettings getAssets() { return assets; }
}
