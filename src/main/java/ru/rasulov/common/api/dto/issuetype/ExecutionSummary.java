package ru.rasulov.common.api.dto.issuetype;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Domain progress, independent of the journal operation's lifecycle status. */
public final class ExecutionSummary {
    public enum Outcome { COMPLETED, PARTIALLY_CHANGED, UNKNOWN }

    private final Outcome outcome;
    private final Counts counts;
    private final boolean selectionComplete;
    private final List<UnitDetail> details;
    private final boolean detailsTruncated;
    private final StoppedAt stoppedAt;
    private final String journalOperationId;

    public ExecutionSummary(Outcome outcome, Counts counts, boolean selectionComplete, List<UnitDetail> details,
                            boolean detailsTruncated, StoppedAt stoppedAt, String journalOperationId) {
        this.outcome = outcome;
        this.counts = counts;
        this.selectionComplete = selectionComplete;
        this.details = Immutables.listOf(details);
        this.detailsTruncated = detailsTruncated;
        this.stoppedAt = stoppedAt;
        this.journalOperationId = journalOperationId;
    }

    @JsonProperty("outcome") public Outcome getOutcome() { return outcome; }
    @JsonProperty("counts") public Counts getCounts() { return counts; }
    @JsonProperty("selectionComplete") public boolean isSelectionComplete() { return selectionComplete; }
    @JsonProperty("details") public List<UnitDetail> getDetails() { return details; }
    @JsonProperty("detailsTruncated") public boolean isDetailsTruncated() { return detailsTruncated; }
    @JsonProperty("stoppedAt") @JsonInclude(JsonInclude.Include.NON_NULL)
    public StoppedAt getStoppedAt() { return stoppedAt; }
    @JsonProperty("compensation") public boolean isCompensation() { return false; }
    @JsonProperty("journalOperationId") public String getJournalOperationId() { return journalOperationId; }

    public static final class Counts {
        private final long completed;
        private final long unknown;
        private final Long notStarted;
        public Counts(long completed, long unknown, Long notStarted) {
            this.completed = completed; this.unknown = unknown; this.notStarted = notStarted;
        }
        @JsonProperty("completed") public long getCompleted() { return completed; }
        @JsonProperty("unknown") public long getUnknown() { return unknown; }
        @JsonProperty("notStarted") public Long getNotStarted() { return notStarted; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static final class StoppedAt {
        private final Long unitIndex;
        private final String schemeStep;
        private StoppedAt(Long unitIndex, String schemeStep) {
            this.unitIndex = unitIndex; this.schemeStep = schemeStep;
        }
        public static StoppedAt unit(long index) { return new StoppedAt(index, null); }
        public static StoppedAt scheme(String step) { return new StoppedAt(null, step); }
        @JsonProperty("unitIndex") public Long getUnitIndex() { return unitIndex; }
        @JsonProperty("schemeStep") public String getSchemeStep() { return schemeStep; }
    }
}
