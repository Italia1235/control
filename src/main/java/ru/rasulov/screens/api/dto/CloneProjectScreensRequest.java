package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Input of {@code clone_project_screens}. Apply calls contain only {@code dryRun=false} and {@code planId}. */
public final class CloneProjectScreensRequest {
    private final Boolean dryRun;
    private final String project;
    private final ScreenCloneScope scope;
    private final List<String> issueTypes;
    private final Boolean copyScreens;
    private final String planId;

    public CloneProjectScreensRequest(boolean dryRun, String project, ScreenCloneScope scope,
                                      List<String> issueTypes, boolean copyScreens) {
        this(dryRun, project, scope, issueTypes, copyScreens, null);
    }

    @JsonCreator
    public CloneProjectScreensRequest(@JsonProperty("dryRun") Boolean dryRun,
                                      @JsonProperty("project") String project,
                                      @JsonProperty("scope") ScreenCloneScope scope,
                                      @JsonProperty("issueTypes") List<String> issueTypes,
                                      @JsonProperty("copyScreens") Boolean copyScreens,
                                      @JsonProperty("planId") String planId) {
        this.dryRun = dryRun;
        this.project = project;
        this.scope = scope;
        this.issueTypes = Immutables.listOf(issueTypes);
        this.copyScreens = copyScreens;
        this.planId = planId;
    }

    @JsonProperty("dryRun") public Boolean isDryRun() { return dryRun; }
    @JsonProperty("project") public String getProject() { return project; }
    @JsonProperty("scope") public ScreenCloneScope getScope() { return scope; }
    @JsonProperty("issueTypes") public List<String> getIssueTypes() { return issueTypes; }
    @JsonProperty("copyScreens") public Boolean isCopyScreens() { return copyScreens; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
}
