package ru.rasulov.projects.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;

import java.util.List;

/**
 * Create mode is selected by {@code action="create"} with a {@code templateKey} taken from {@code list_templates};
 * the project type is the type of that template. A preview carries the project details; an apply carries only the
 * opaque plan id returned by that preview (no action and no template). The read-only catalog
 * ({@code action="list_templates"}) takes no other argument and has no request DTO.
 * dryRun is boxed so an omitted or explicit JSON null remains distinguishable from a write request.
 * <p>
 * Issue types. Without {@code issueTypes} and {@code newIssueTypes} the project keeps the template's own issue types
 * (template mode). With either of them the lists together are the COMPLETE final set of the project:
 * {@code issueTypes} are existing types (exact name or id), {@code newIssueTypes} are definitions that are reused when
 * the exact name exists with the same kind and created otherwise. Every other type of the new project leaves it (it is
 * never deleted globally). {@code defaultIssueType} is a local key or an existing type of the set; without it the first
 * standard type of the set is the default.
 */
public final class CreateProjectRequest {
    public static final String ACTION_CREATE = "create";
    public static final String ACTION_LIST_TEMPLATES = "list_templates";

    private final String action;
    private final String templateKey;
    private final Boolean dryRun;
    private final String name;
    private final String key;
    private final String lead;
    private final String planId;
    private final List<String> issueTypes;
    private final List<IssueTypeDefinition> newIssueTypes;
    private final String defaultIssueType;

    @JsonCreator
    public CreateProjectRequest(@JsonProperty("action") String action,
                                @JsonProperty("templateKey") String templateKey,
                                @JsonProperty("dryRun") Boolean dryRun,
                                @JsonProperty("name") String name,
                                @JsonProperty("key") String key,
                                @JsonProperty("lead") String lead,
                                @JsonProperty("planId") String planId,
                                @JsonProperty("issueTypes") List<String> issueTypes,
                                @JsonProperty("newIssueTypes") List<IssueTypeDefinition> newIssueTypes,
                                @JsonProperty("defaultIssueType") String defaultIssueType) {
        this.action = action;
        this.templateKey = templateKey;
        this.dryRun = dryRun;
        this.name = name;
        this.key = key;
        this.lead = lead;
        this.planId = planId;
        this.issueTypes = Immutables.listOf(issueTypes);
        this.newIssueTypes = Immutables.listOf(newIssueTypes);
        this.defaultIssueType = defaultIssueType;
    }

    @JsonProperty("action")
    public String getAction() {
        return action;
    }

    @JsonProperty("templateKey")
    public String getTemplateKey() {
        return templateKey;
    }

    @JsonProperty("dryRun")
    public Boolean isDryRun() {
        return dryRun;
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("key")
    public String getKey() {
        return key;
    }

    @JsonProperty("lead")
    public String getLead() {
        return lead;
    }

    @JsonProperty("planId")
    public String getPlanId() {
        return planId;
    }

    @JsonProperty("issueTypes")
    public List<String> getIssueTypes() {
        return issueTypes;
    }

    @JsonProperty("newIssueTypes")
    public List<IssueTypeDefinition> getNewIssueTypes() {
        return newIssueTypes;
    }

    @JsonProperty("defaultIssueType")
    public String getDefaultIssueType() {
        return defaultIssueType;
    }

    /** True when the request names a complete final issue-type set instead of using the template's types. */
    public boolean hasExplicitTypeSet() {
        return !issueTypes.isEmpty() || !newIssueTypes.isEmpty();
    }
}
