package ru.rasulov.issuetypes.api.dto;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.issuetype.ItemStatus;
import ru.rasulov.common.api.dto.report.ChangeReport;

import java.util.List;

/**
 * Preview or outcome of one batch of global issue types.
 * <p>
 * Top-level {@code status}: preview {@code WOULD_CREATE} (a plan exists), {@code EXISTS} (everything exists, nothing to
 * write, no plan) or {@code FAILED}; apply {@code CREATED} (every missing type was created), {@code PARTIALLY_CHANGED}
 * (some types were created, or the outcome of one write is {@code UNKNOWN}, and the rest did not complete) or
 * {@code FAILED} (certainly nothing was written). {@code results} always has one entry per requested item in request
 * order: the local {@code key}, its status and, when the type is observable in Jira, its id. An id on an item whose
 * status is not {@code CREATED} or {@code EXISTS} is only an observation: the type is not confirmed as made by this
 * operation and no dependent step may use it.
 */
public final class CreateIssueTypesResponse {
    public enum Status { WOULD_CREATE, EXISTS, CREATED, PARTIALLY_CHANGED, FAILED }
    public enum ErrorCode { VALIDATION_ERROR, NAME_CONFLICT, PREVIEW_STALE, JIRA_ERROR }

    private final Status status;
    private final boolean dryRun;
    private final List<ItemResult> results;
    private final String message;
    private final ErrorCode errorCode;
    private final String planId;
    private final Long planExpiresAt;
    private final ChangeReport report;

    private CreateIssueTypesResponse(Status status, boolean dryRun, List<ItemResult> results, String message,
                                     ErrorCode errorCode, String planId, Long planExpiresAt, ChangeReport report) {
        this.status = status;
        this.dryRun = dryRun;
        this.results = Immutables.listOf(results);
        this.message = message;
        this.errorCode = errorCode;
        this.planId = planId;
        this.planExpiresAt = planExpiresAt;
        this.report = report;
    }

    public static CreateIssueTypesResponse wouldCreate(List<ItemResult> results, String message) {
        return new CreateIssueTypesResponse(Status.WOULD_CREATE, true, results, message, null, null, null, null);
    }

    public static CreateIssueTypesResponse exists(List<ItemResult> results, String message) {
        return new CreateIssueTypesResponse(Status.EXISTS, true, results, message, null, null, null, null);
    }

    /** {@code status} is CREATED, PARTIAL or FAILED; {@code code} is null only for CREATED. */
    public static CreateIssueTypesResponse applied(Status status, List<ItemResult> results, String message,
                                                   ErrorCode code, ChangeReport report) {
        return new CreateIssueTypesResponse(status, false, results, message, code, null, null, report);
    }

    public static CreateIssueTypesResponse failed(boolean dryRun, List<ItemResult> results, ErrorCode code,
                                                  String message) {
        return new CreateIssueTypesResponse(Status.FAILED, dryRun, results, message, code, null, null, null);
    }

    public CreateIssueTypesResponse withPlan(String id, long expiresAt) {
        return new CreateIssueTypesResponse(status, dryRun, results, message, errorCode, id, expiresAt, report);
    }

    @JsonProperty("status")
    public Status getStatus() { return status; }
    @JsonProperty("dryRun")
    public boolean isDryRun() { return dryRun; }
    /** One entry per requested item, in request order. */
    @JsonProperty("results")
    public List<ItemResult> getResults() { return results; }
    @JsonProperty("message")
    public String getMessage() { return message; }
    @JsonProperty("errorCode")
    public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("planId")
    public String getPlanId() { return planId; }
    /** Expiry of planId as Unix epoch milliseconds. */
    @JsonProperty("planExpiresAt")
    public Long getPlanExpiresAt() { return planExpiresAt; }
    /** What the apply created and reused, with canonical ids; {@code null} in a preview. */
    @JsonProperty("report")
    public ChangeReport getReport() { return report; }

    /** The outcome of one requested type. {@code issueType.id} is present exactly when the type exists in Jira. */
    public static final class ItemResult {
        private final String key;
        private final ItemStatus status;
        private final IssueTypeView issueType;
        private final String message;
        private final ErrorCode errorCode;

        public ItemResult(String key, ItemStatus status, IssueTypeView issueType, String message, ErrorCode errorCode) {
            this.key = key;
            this.status = status;
            this.issueType = issueType;
            this.message = message;
            this.errorCode = errorCode;
        }

        @JsonProperty("key")
        public String getKey() { return key; }
        @JsonProperty("status")
        public ItemStatus getStatus() { return status; }
        @JsonProperty("issueType")
        public IssueTypeView getIssueType() { return issueType; }
        @JsonProperty("message")
        public String getMessage() { return message; }
        @JsonProperty("errorCode")
        public ErrorCode getErrorCode() { return errorCode; }
    }

    public static final class IssueTypeView {
        private final String id;
        private final String name;
        private final String description;
        private final Boolean subtask;

        public IssueTypeView(String id, String name, String description, Boolean subtask) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.subtask = subtask;
        }

        public static IssueTypeView of(IssueType type) {
            return new IssueTypeView(type.getId(), type.getName(), type.getDescription(), type.isSubTask());
        }

        @JsonProperty("id")
        public String getId() { return id; }
        @JsonProperty("name")
        public String getName() { return name; }
        @JsonProperty("description")
        public String getDescription() { return description; }
        @JsonProperty("subtask")
        public Boolean isSubtask() { return subtask; }
    }
}
