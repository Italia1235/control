package ru.rasulov.screens.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.Collections;
import java.util.List;

/** Preview or outcome of {@code clone_project_screens}. */
public final class CloneProjectScreensResponse {
    /** PARTIALLY_CLONED: a write failed after the call started; the created copies are listed and remain in Jira. */
    public enum Status { WOULD_CLONE, CLONED, ALREADY_SEPARATE, FAILED, PARTIALLY_CLONED }
    public enum ErrorCode { VALIDATION_ERROR, UNKNOWN_PROJECT, UNKNOWN_ISSUE_TYPE, NAME_TAKEN, JIRA_ERROR, PREVIEW_STALE }
    public enum Verdict { COPY, KEEP }

    private final boolean dryRun;
    private final Status status;
    private final ProjectRef project;
    private final ScreenCloneScope scope;
    private final Boolean copyScreens;
    private final CopyView issueTypeScreenScheme;
    private final List<CopyView> screenSchemes;
    private final List<CopyView> screens;
    private final String message;
    private final ErrorCode errorCode;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;

    private CloneProjectScreensResponse(boolean dryRun, Status status, ProjectRef project, ScreenCloneScope scope,
                                        Boolean copyScreens, CopyView issueTypeScreenScheme,
                                        List<CopyView> screenSchemes, List<CopyView> screens, String message,
                                        ErrorCode errorCode, List<String> suggestions, String planId, Long expiresAt) {
        this.dryRun = dryRun; this.status = status; this.project = project; this.scope = scope;
        this.copyScreens = copyScreens; this.issueTypeScreenScheme = issueTypeScreenScheme;
        this.screenSchemes = Immutables.listOf(screenSchemes); this.screens = Immutables.listOf(screens);
        this.message = message; this.errorCode = errorCode; this.suggestions = Immutables.listOf(suggestions);
        this.planId = planId; this.expiresAt = expiresAt;
    }

    public static CloneProjectScreensResponse success(boolean dryRun, ProjectRef project, ScreenCloneScope scope,
                                                       boolean copyScreens, CopyView scheme,
                                                       List<CopyView> screenSchemes, List<CopyView> screens) {
        return new CloneProjectScreensResponse(dryRun, dryRun ? Status.WOULD_CLONE : Status.CLONED, project, scope,
                copyScreens, scheme, screenSchemes, screens, null, null, null, null, null);
    }

    public static CloneProjectScreensResponse alreadySeparate(boolean dryRun, ProjectRef project,
                                                               ScreenCloneScope scope, boolean copyScreens,
                                                               CopyView scheme, List<CopyView> screenSchemes,
                                                               List<CopyView> screens) {
        return new CloneProjectScreensResponse(dryRun, Status.ALREADY_SEPARATE, project, scope, copyScreens, scheme,
                screenSchemes, screens, "The requested screen setup is already used only by this project; nothing was changed.",
                null, null, null, null);
    }

    public static CloneProjectScreensResponse failed(boolean dryRun, ProjectRef project, ScreenCloneScope scope,
                                                      Boolean copyScreens, ErrorCode code, String message,
                                                      List<String> suggestions) {
        return new CloneProjectScreensResponse(dryRun, Status.FAILED, project, scope, copyScreens, null, null, null,
                message, code, suggestions, null, null);
    }

    /**
     * A Jira write failed or was not confirmed after the call started. The views list only what is known to exist; the
     * message says what could not be confirmed (the failed call itself, the project association).
     */
    public static CloneProjectScreensResponse partial(ProjectRef project, ScreenCloneScope scope, boolean copyScreens,
                                                       CopyView scheme, List<CopyView> screenSchemes,
                                                       List<CopyView> screens, String message) {
        return new CloneProjectScreensResponse(false, Status.PARTIALLY_CLONED, project, scope, copyScreens, scheme,
                screenSchemes, screens, message, ErrorCode.JIRA_ERROR, null, null, null);
    }

    public static CloneProjectScreensResponse stale() {
        return failed(false, null, null, null, ErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again.", null);
    }

    public CloneProjectScreensResponse withPlan(String id, long expiry) {
        return new CloneProjectScreensResponse(true, status, project, scope, copyScreens, issueTypeScreenScheme,
                screenSchemes, screens, message, errorCode, suggestions, id, expiry);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("undoWarning") public String getUndoWarning() { return "This operation cannot be rolled back through Undo."; }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("project") public ProjectRef getProject() { return project; }
    @JsonProperty("scope") public ScreenCloneScope getScope() { return scope; }
    @JsonProperty("copyScreens") public Boolean isCopyScreens() { return copyScreens; }
    @JsonProperty("issueTypeScreenScheme") public CopyView getIssueTypeScreenScheme() { return issueTypeScreenScheme; }
    @JsonProperty("screenSchemes") public List<CopyView> getScreenSchemes() { return screenSchemes; }
    @JsonProperty("screens") public List<CopyView> getScreens() { return screens; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }

    public static final class CopyView {
        private final Long sourceId;
        private final String sourceName;
        private final Long newId;
        private final String newName;
        private final Verdict verdict;
        private final int otherProjects;
        private final List<ProjectRef> otherProjectNames;

        public CopyView(Long sourceId, String sourceName, Long newId, String newName, Verdict verdict,
                        int otherProjects, List<ProjectRef> otherProjectNames) {
            this.sourceId = sourceId; this.sourceName = sourceName; this.newId = newId; this.newName = newName;
            this.verdict = verdict; this.otherProjects = otherProjects;
            this.otherProjectNames = otherProjectNames == null ? Collections.<ProjectRef>emptyList()
                    : Immutables.listOf(otherProjectNames);
        }
        @JsonProperty("sourceId") public Long getSourceId() { return sourceId; }
        @JsonProperty("sourceName") public String getSourceName() { return sourceName; }
        @JsonProperty("newId") public Long getNewId() { return newId; }
        @JsonProperty("newName") public String getNewName() { return newName; }
        @JsonProperty("verdict") public Verdict getVerdict() { return verdict; }
        @JsonProperty("otherProjects") public int getOtherProjects() { return otherProjects; }
        @JsonProperty("otherProjectNames") public List<ProjectRef> getOtherProjectNames() { return otherProjectNames; }
    }
}
