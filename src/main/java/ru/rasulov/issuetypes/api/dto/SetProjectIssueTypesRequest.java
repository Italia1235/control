package ru.rasulov.issuetypes.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.StatusMapping;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;

import java.util.List;

/**
 * Complete desired issue-type set of one project and conversions for types that leave it. The set is {@code issueTypes}
 * (existing types) plus {@code newIssueTypes} (definitions: reused when the exact name exists with the same kind,
 * created otherwise). {@code defaultIssueType} is a local key or a type of the set; without it the project keeps its
 * current default when that type stays, else the first standard type of the set is the default.
 */
public final class SetProjectIssueTypesRequest {
    private final Boolean dryRun;
    private final String project;
    private final List<String> issueTypes;
    private final List<TypeMapping> mappings;
    private final String planId;
    private final List<IssueTypeDefinition> newIssueTypes;
    private final String defaultIssueType;

    @JsonCreator
    public SetProjectIssueTypesRequest(@JsonProperty("dryRun") Boolean dryRun,
                                       @JsonProperty("project") String project,
                                       @JsonProperty("issueTypes") List<String> issueTypes,
                                       @JsonProperty("mappings") List<TypeMapping> mappings,
                                       @JsonProperty("planId") String planId,
                                       @JsonProperty("newIssueTypes") List<IssueTypeDefinition> newIssueTypes,
                                       @JsonProperty("defaultIssueType") String defaultIssueType) {
        this.dryRun = dryRun;
        this.project = project;
        this.issueTypes = Immutables.listOf(issueTypes);
        this.mappings = Immutables.listOf(mappings);
        this.planId = planId;
        this.newIssueTypes = Immutables.listOf(newIssueTypes);
        this.defaultIssueType = defaultIssueType;
    }

    public SetProjectIssueTypesRequest(Boolean dryRun, String project, List<String> issueTypes,
                                       List<TypeMapping> mappings, String planId) {
        this(dryRun, project, issueTypes, mappings, planId, null, null);
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("project") public String getProject() { return project; }
    @JsonProperty("issueTypes") public List<String> getIssueTypes() { return issueTypes; }
    @JsonProperty("mappings") public List<TypeMapping> getMappings() { return mappings; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("newIssueTypes") public List<IssueTypeDefinition> getNewIssueTypes() { return newIssueTypes; }
    @JsonProperty("defaultIssueType") public String getDefaultIssueType() { return defaultIssueType; }

    public static final class TypeMapping {
        private final String from;
        private final String to;
        private final List<StatusMapping> statusMappings;

        @JsonCreator
        public TypeMapping(@JsonProperty("from") String from,
                           @JsonProperty("to") String to,
                           @JsonProperty("statusMappings") List<StatusMapping> statusMappings) {
            this.from = from;
            this.to = to;
            this.statusMappings = Immutables.listOf(statusMappings);
        }

        @JsonProperty("from") public String getFrom() { return from; }
        @JsonProperty("to") public String getTo() { return to; }
        @JsonProperty("statusMappings") public List<StatusMapping> getStatusMappings() { return statusMappings; }
    }
}
