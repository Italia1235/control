package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Preview input of {@code assign_issue_type_screen_scheme}: one project and one EXISTING Issue Type Screen Scheme
 * (id, exact name or {@code DEFAULT}). Apply carries only {@code dryRun=false} and {@code planId}.
 */
public final class AssignIssueTypeScreenSchemeRequest {
    private final Boolean dryRun;
    private final String project;
    private final String issueTypeScreenScheme;
    private final String planId;

    @JsonCreator
    public AssignIssueTypeScreenSchemeRequest(@JsonProperty("dryRun") Boolean dryRun,
                                              @JsonProperty("project") String project,
                                              @JsonProperty("issueTypeScreenScheme") String issueTypeScreenScheme,
                                              @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.project = project;
        this.issueTypeScreenScheme = issueTypeScreenScheme;
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    /** Key or name of an active project. */
    @JsonProperty("project") public String getProject() { return project; }
    /** Positive id, exact name (case-insensitive) of an existing Issue Type Screen Scheme, or the token {@code DEFAULT}. */
    @JsonProperty("issueTypeScreenScheme") public String getIssueTypeScreenScheme() { return issueTypeScreenScheme; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
