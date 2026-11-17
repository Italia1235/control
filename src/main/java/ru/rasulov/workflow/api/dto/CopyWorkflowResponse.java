package ru.rasulov.workflow.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.report.ChangeReport;
import java.util.List;

/** Outcome of copying a main workflow version to a new, unassigned workflow. */
public final class CopyWorkflowResponse {
    private final boolean dryRun;
    private final WorkflowResultStatus status;
    private final String source;
    private final String name;
    private final String description;
    private final int steps;
    private final int transitions;
    private final boolean sourceHasDraft;
    private final List<String> warnings;
    private final String message;
    private final String next;
    private final WorkflowErrorCode errorCode;
    private final List<String> suggestions;
    private final String planId;
    private final Long expiresAt;
    private final ChangeReport report;

    private CopyWorkflowResponse(boolean dryRun, WorkflowResultStatus status, String source, String name,
            String description, int steps, int transitions, boolean sourceHasDraft, List<String> warnings,
            String message, String next, WorkflowErrorCode errorCode, List<String> suggestions,
            String planId, Long expiresAt, ChangeReport report) {
        this.dryRun = dryRun; this.status = status; this.source = source; this.name = name;
        this.description = description; this.steps = steps; this.transitions = transitions;
        this.sourceHasDraft = sourceHasDraft; this.warnings = Immutables.listOf(warnings);
        this.message = message; this.next = next; this.errorCode = errorCode;
        this.suggestions = Immutables.listOf(suggestions); this.planId = planId;
        this.expiresAt = expiresAt; this.report = report;
    }

    public static CopyWorkflowResponse wouldCreate(String source, String name, String description, int steps,
            int transitions, boolean sourceHasDraft, List<String> warnings) {
        return new CopyWorkflowResponse(true, WorkflowResultStatus.WOULD_CREATE, source, name, description,
                steps, transitions, sourceHasDraft, warnings,
                "The main version will be copied to an inactive workflow, assigned to no scheme. The original is unchanged.",
                "Read the copy with get_workflow and edit it with configure_workflow.existing; assign it with assign_workflow and statusMappings. This is not a rename.",
                null, null, null, null, null);
    }

    public CopyWorkflowResponse created(ChangeReport changes) {
        return new CopyWorkflowResponse(false, WorkflowResultStatus.CREATED, source, name, description, steps,
                transitions, sourceHasDraft, warnings,
                "The main version was copied to an inactive workflow, assigned to no scheme. The original is unchanged.",
                next, null, null, null, null, changes);
    }

    public static CopyWorkflowResponse failed(boolean dryRun, String source, String name, WorkflowErrorCode code,
            String message, List<String> suggestions) {
        return new CopyWorkflowResponse(dryRun, WorkflowResultStatus.FAILED, source, name, null, 0, 0, false,
                null, message, null, code, suggestions, null, null, null);
    }

    public CopyWorkflowResponse failed(WorkflowErrorCode code, String detail, List<String> extraWarnings,
            ChangeReport changes) {
        return new CopyWorkflowResponse(false, WorkflowResultStatus.FAILED, source, name, description, steps,
                transitions, sourceHasDraft, extraWarnings, detail, next, code, null, null, null, changes);
    }

    public CopyWorkflowResponse withPlan(String id, long expiry) {
        return new CopyWorkflowResponse(true, status, source, name, description, steps, transitions, sourceHasDraft,
                warnings, message, next, errorCode, suggestions, id, expiry, report);
    }

    public static CopyWorkflowResponse stale() {
        return failed(false, null, null, WorkflowErrorCode.PREVIEW_STALE,
                "Preview is missing, expired, foreign, consumed, or Jira changed. Run preview again.", null);
    }

    @JsonProperty("dryRun") public boolean isDryRun() { return dryRun; }
    @JsonProperty("status") public WorkflowResultStatus getStatus() { return status; }
    @JsonProperty("source") public String getSource() { return source; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("steps") public int getSteps() { return steps; }
    @JsonProperty("transitions") public int getTransitions() { return transitions; }
    @JsonProperty("sourceHasDraft") public boolean isSourceHasDraft() { return sourceHasDraft; }
    @JsonProperty("warnings") public List<String> getWarnings() { return warnings; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("next") public String getNext() { return next; }
    @JsonProperty("errorCode") public WorkflowErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("suggestions") public List<String> getSuggestions() { return suggestions; }
    @JsonProperty("planId") public String getPlanId() { return planId; }
    @JsonProperty("expiresAt") public Long getExpiresAt() { return expiresAt; }
    @JsonProperty("report") public ChangeReport getReport() { return report; }
}
