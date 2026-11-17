package ru.rasulov.customfields.api.dto.context;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;

import java.util.List;

/** Input of update_field_contexts. Apply contains only dryRun=false and planId. */
public final class UpdateFieldContextsRequest {
    private final Boolean dryRun;
    private final String planId;
    private final String fieldId;
    private final List<ContextUpdate> contexts;

    @JsonCreator
    public UpdateFieldContextsRequest(@JsonProperty("dryRun") Boolean dryRun,
                                      @JsonProperty("planId") String planId,
                                      @JsonProperty("fieldId") String fieldId,
                                      @JsonProperty("contexts") List<ContextUpdate> contexts) {
        this.dryRun = dryRun;
        this.planId = planId;
        this.fieldId = fieldId;
        this.contexts = contexts == null ? null : Immutables.listOf(contexts);
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("fieldId") public String getFieldId() { return fieldId; }
    @JsonProperty("contexts") public List<ContextUpdate> getContexts() { return contexts; }

    /** Both nullable flags and absent versus empty lists survive for exact axis validation by the service. */
    public static final class ContextUpdate {
        private final Long contextId;
        private final Boolean allProjects;
        private final List<String> projects;
        private final Boolean allIssueTypes;
        private final List<String> issueTypes;
        private final AssetsFieldSettings assets;
        private final OptionsChange options;

        public ContextUpdate(Long contextId, Boolean allProjects, List<String> projects, Boolean allIssueTypes,
                             List<String> issueTypes) {
            this(contextId, allProjects, projects, allIssueTypes, issueTypes, null, null);
        }

        public ContextUpdate(Long contextId, Boolean allProjects, List<String> projects, Boolean allIssueTypes,
                             List<String> issueTypes, AssetsFieldSettings assets) {
            this(contextId, allProjects, projects, allIssueTypes, issueTypes, assets, null);
        }

        @JsonCreator
        public ContextUpdate(@JsonProperty("contextId") Long contextId,
                             @JsonProperty("allProjects") Boolean allProjects,
                             @JsonProperty("projects") List<String> projects,
                             @JsonProperty("allIssueTypes") Boolean allIssueTypes,
                             @JsonProperty("issueTypes") List<String> issueTypes,
                             @JsonProperty("assets") AssetsFieldSettings assets,
                             @JsonProperty("options") OptionsChange options) {
            this.contextId = contextId;
            this.allProjects = allProjects;
            this.projects = projects == null ? null : Immutables.listOf(projects);
            this.allIssueTypes = allIssueTypes;
            this.issueTypes = issueTypes == null ? null : Immutables.listOf(issueTypes);
            this.assets = assets;
            this.options = options;
        }

        @JsonProperty("contextId") public Long getContextId() { return contextId; }
        @JsonProperty("allProjects") public Boolean isAllProjects() { return allProjects; }
        @JsonProperty("projects") public List<String> getProjects() { return projects; }
        @JsonProperty("allIssueTypes") public Boolean isAllIssueTypes() { return allIssueTypes; }
        @JsonProperty("issueTypes") public List<String> getIssueTypes() { return issueTypes; }
        /** Optional Assets object settings of this existing context; absent leaves them untouched. */
        @JsonProperty("assets") public AssetsFieldSettings getAssets() { return assets; }
        /** Optional changes of this context's options (add, rename, disable, enable, order); absent leaves them untouched. */
        @JsonProperty("options") public OptionsChange getOptions() { return options; }
    }
}
