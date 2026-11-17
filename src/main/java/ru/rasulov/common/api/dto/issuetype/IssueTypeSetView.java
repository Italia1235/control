package ru.rasulov.common.api.dto.issuetype;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;

import java.util.List;

/**
 * The final issue-type set of a project as a plan ({@code assignment} absent) and, after apply, as it really ended up.
 * Entries are in request order: existing references first, then new definitions. Before a Jira id exists an entry is
 * addressed by its local {@code key}; {@code id} is set only for a type that exists. An id on an entry whose status is
 * not {@code CREATED} or {@code EXISTS} is only observed and must not be used by a dependent step.
 */
public final class IssueTypeSetView {
    public enum Origin { REFERENCE, DEFINITION }
    public enum AssignmentState { NOT_STARTED, COMPLETED, PARTIALLY_CHANGED, UNKNOWN }

    private final List<Entry> entries;
    private final IssueTypeRef defaultIssueType;
    private final String defaultSource;
    private final List<IssueTypeRef> excludedTypes;
    private final String excludedNote;
    private final String globalImpact;
    private final List<String> residualRisks;
    private final Assignment assignment;

    public IssueTypeSetView(List<Entry> entries, IssueTypeRef defaultIssueType, String defaultSource,
                            List<IssueTypeRef> excludedTypes, String excludedNote, String globalImpact,
                            List<String> residualRisks, Assignment assignment) {
        this.entries = Immutables.listOf(entries);
        this.defaultIssueType = defaultIssueType;
        this.defaultSource = defaultSource;
        this.excludedTypes = excludedTypes;
        this.excludedNote = excludedNote;
        this.globalImpact = globalImpact;
        this.residualRisks = Immutables.listOf(residualRisks);
        this.assignment = assignment;
    }

    public IssueTypeSetView withAssignment(List<Entry> actualEntries, Assignment actual) {
        return new IssueTypeSetView(actualEntries, defaultIssueType, defaultSource, excludedTypes, excludedNote,
                globalImpact, residualRisks, actual);
    }

    @JsonProperty("mode") public String getMode() { return "EXPLICIT_SET"; }
    @JsonProperty("entries") public List<Entry> getEntries() { return entries; }
    @JsonProperty("defaultIssueType") public IssueTypeRef getDefaultIssueType() { return defaultIssueType; }
    /** REQUESTED, PREVIOUS_DEFAULT (existing project keeps its default) or FIRST_STANDARD (first standard type in the set). */
    @JsonProperty("defaultSource") public String getDefaultSource() { return defaultSource; }
    /** Types that leave the project; null while the project does not exist yet. A type leaving a project is never deleted globally. */
    @JsonProperty("excludedTypes") public List<IssueTypeRef> getExcludedTypes() { return excludedTypes; }
    @JsonProperty("excludedNote") public String getExcludedNote() { return excludedNote; }
    @JsonProperty("globalImpact") public String getGlobalImpact() { return globalImpact; }
    @JsonProperty("residualRisks") public List<String> getResidualRisks() { return residualRisks; }
    /** Only after apply: what was really assigned. */
    @JsonProperty("assignment") public Assignment getAssignment() { return assignment; }

    public static final class Entry {
        private final String key;
        private final String name;
        private final boolean subtask;
        private final String id;
        private final ItemStatus status;
        private final Origin origin;
        private final boolean defaultType;
        private final String message;

        public Entry(String key, String name, boolean subtask, String id, ItemStatus status, Origin origin,
                     boolean defaultType, String message) {
            this.key = key; this.name = name; this.subtask = subtask; this.id = id; this.status = status;
            this.origin = origin; this.defaultType = defaultType; this.message = message;
        }

        @JsonProperty("key") public String getKey() { return key; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("subtask") public boolean isSubtask() { return subtask; }
        @JsonProperty("id") public String getId() { return id; }
        @JsonProperty("status") public ItemStatus getStatus() { return status; }
        @JsonProperty("origin") public Origin getOrigin() { return origin; }
        @JsonProperty("isDefault") public boolean isDefaultType() { return defaultType; }
        @JsonProperty("message") public String getMessage() { return message; }
    }

    /** The real outcome of assigning the final set to the project. */
    public static final class Assignment {
        private final AssignmentState state;
        private final String message;
        private final List<IssueTypeRef> issueTypes;
        private final List<IssueTypeRef> excludedTypes;
        private final IssueTypeRef defaultIssueType;
        private final SchemeView scheme;
        private final ExecutionSummary execution;

        public Assignment(AssignmentState state, String message, List<IssueTypeRef> issueTypes,
                          List<IssueTypeRef> excludedTypes, IssueTypeRef defaultIssueType, SchemeView scheme,
                          ExecutionSummary execution) {
            this.state = state; this.message = message; this.issueTypes = issueTypes; this.excludedTypes = excludedTypes;
            this.defaultIssueType = defaultIssueType; this.scheme = scheme; this.execution = execution;
        }

        /** NOT_STARTED: the project's types were not touched. UNKNOWN and PARTIALLY_CHANGED: see {@code execution}. */
        @JsonProperty("state") public AssignmentState getState() { return state; }
        @JsonProperty("message") public String getMessage() { return message; }
        /** The set the project really has after a COMPLETED assignment; null otherwise. */
        @JsonProperty("issueTypes") public List<IssueTypeRef> getIssueTypes() { return issueTypes; }
        @JsonProperty("excludedTypes") public List<IssueTypeRef> getExcludedTypes() { return excludedTypes; }
        @JsonProperty("defaultIssueType") public IssueTypeRef getDefaultIssueType() { return defaultIssueType; }
        @JsonProperty("scheme") public SchemeView getScheme() { return scheme; }
        @JsonProperty("execution") public ExecutionSummary getExecution() { return execution; }
    }
}
