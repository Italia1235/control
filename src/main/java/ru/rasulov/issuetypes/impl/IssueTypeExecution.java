package ru.rasulov.issuetypes.impl;

import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.JournalCancelledException;
import ru.rasulov.common.api.dto.issuetype.ExecutionSummary;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.ErrorCode;
import ru.rasulov.common.api.dto.issuetype.UnitDetail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded native intents/receipts; a crash may leave one whole <=50-issue batch UNKNOWN. */
class IssueTypeExecution {
    private static final int MAX_DETAILS = 100;
    private final ChangeJournal journal;
    private final String operationId;
    private final List<UnitDetail> completedDetails = new ArrayList<UnitDetail>(MAX_DETAILS);
    private long completed;
    private long highestCompletedId;
    private Long currentIssue;
    private boolean changed;
    private boolean finished;
    private ExecutionSummary.StoppedAt stoppedAt;
    private int pass;
    private int batchId;
    private List<Long> issueIds;
    private long batchStart;
    private int batchCompleted;
    private boolean intentProjection;
    private String schemeStep;
    private String schemeResource;
    private boolean schemeConfirmed;

    IssueTypeExecution(ChangeJournal journal, boolean migration, int schemeSteps) {
        this.journal = journal;
        this.operationId = journal.currentOperationId();
        // At most one partial issue batch per pass. Scheme steps have their own fixed slots.
        int issueBatches = migration ? IssueTypeChangePlanner.MAX_ISSUES_TO_MIGRATE / IssueTypeMigrator.PAGE_SIZE + 1 : 0;
        journal.startIssueTypeProgress(summary(), issueBatches + schemeSteps);
    }

    void beginPass() {
        checkCancellation();
        pass++;
        stoppedAt = ExecutionSummary.StoppedAt.unit(completed);
        journal.issueTypeProgress(summary(), false);
    }

    void beginBatch(List<Long> ids) {
        checkCancellation();
        if (batchId != 0 || ids.isEmpty() || ids.size() > IssueTypeMigrator.PAGE_SIZE)
            throw new IllegalStateException("Invalid issue progress batch boundary.");
        if (completed + ids.size() > IssueTypeChangePlanner.MAX_ISSUES_TO_MIGRATE)
            throw new IssueTypeChangeFailure(ErrorCode.TOO_MANY_ISSUES,
                    "The 10000-issue limit includes late issues. No further batch was started; retained changes require reconciliation.");
        if (pass > 1 && ids.get(0) <= highestCompletedId && journal.hasCompletedIssueTypeIds(ids))
            throw new IssueTypeChangeFailure(ErrorCode.JIRA_ERROR,
                    "An issue completed earlier has changed type again. No repeated write was started; reconcile it read-only.");
        issueIds = ids;
        batchStart = completed;
        batchCompleted = 0;
        intentProjection = true;
        stoppedAt = ExecutionSummary.StoppedAt.unit(completed);
        Map<String, Object> intent = new LinkedHashMap<String, Object>();
        intent.put("pass", pass);
        intent.put("firstUnitIndex", batchStart);
        intent.put("issueIds", ids);
        batchId = journal.beginIssueTypeBatch("ISSUES", intent, summary());
        intentProjection = false;
    }

    void beginIssue(long issueId) {
        checkCancellation();
        if (issueIds == null || issueId != issueIds.get(batchCompleted))
            throw new IllegalStateException("The native issue does not match its durable batch intent.");
        currentIssue = issueId;
        stoppedAt = ExecutionSummary.StoppedAt.unit(completed);
    }

    void completedIssue() {
        if (completedDetails.size() < MAX_DETAILS)
            completedDetails.add(new UnitDetail(completed, Long.toString(currentIssue), UnitDetail.State.COMPLETED));
        completed++;
        highestCompletedId = Math.max(highestCompletedId, currentIssue);
        batchCompleted++;
        currentIssue = null;
        changed = true;
        stoppedAt = ExecutionSummary.StoppedAt.unit(completed);
        if (batchCompleted == issueIds.size()) closeIssueBatch();
        checkCancellation(); // On cancel, flush the partial batch before leaving this whole pair boundary.
    }

    void beginScheme(String step) {
        checkCancellation();
        if (batchId != 0) throw new IllegalStateException("An issue batch must finish before scheme cutover.");
        schemeStep = step;
        schemeResource = null;
        schemeConfirmed = false;
        stoppedAt = ExecutionSummary.StoppedAt.scheme(step);
        Map<String, Object> intent = new LinkedHashMap<String, Object>();
        intent.put("schemeStep", step);
        batchId = journal.beginIssueTypeBatch("SCHEME", intent, summary());
    }

    void completedScheme(Long resourceId) {
        schemeConfirmed = true;
        schemeResource = Long.toString(resourceId);
        changed = true;
        closeSchemeBatch();
        checkCancellation();
    }

    /** Best-effort stop receipt; if persistence fails, the previous durable whole-batch UNKNOWN intent survives. */
    void stop() {
        intentProjection = false;
        if (batchId == 0) return;
        if (issueIds != null) closeIssueBatch();
        else closeSchemeBatch();
    }

    /** The domain progress after {@link #stop()}: PARTIALLY_CHANGED if something was confirmed, else UNKNOWN. */
    ExecutionSummary stoppedSummary() {
        return summary();
    }

    ExecutionSummary finish() {
        if (batchId != 0 || currentIssue != null) throw new IllegalStateException("Execution has an unfinished batch.");
        finished = true;
        stoppedAt = null;
        ExecutionSummary result = summary();
        journal.issueTypeProgress(result, true);
        return result;
    }

    private void closeIssueBatch() {
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("completedPrefix", batchCompleted);
        receipt.put("unknown", currentIssue == null ? 0 : 1);
        receipt.put("notStarted", issueIds.size() - batchCompleted - (currentIssue == null ? 0 : 1));
        journal.finishIssueTypeBatch(batchId, receipt, summary());
        batchId = 0;
        issueIds = null;
    }

    private void closeSchemeBatch() {
        Map<String, Object> receipt = new LinkedHashMap<String, Object>();
        receipt.put("completed", schemeConfirmed);
        if (schemeResource != null) receipt.put("resourceId", schemeResource);
        journal.finishIssueTypeBatch(batchId, receipt, summary());
        batchId = 0;
        schemeStep = null;
    }

    private void checkCancellation() {
        try { journal.checkIssueTypeCancellation(); }
        catch (JournalCancelledException cancelled) { stop(); throw cancelled; }
    }

    private ExecutionSummary summary() {
        ExecutionSummary.Outcome outcome = finished ? ExecutionSummary.Outcome.COMPLETED
                : changed ? ExecutionSummary.Outcome.PARTIALLY_CHANGED : ExecutionSummary.Outcome.UNKNOWN;
        int pending = issueIds == null ? 0 : issueIds.size() - batchCompleted;
        int confirmedLimit = Math.min(completedDetails.size(), MAX_DETAILS - pending);
        List<UnitDetail> details = new ArrayList<UnitDetail>(confirmedLimit + pending);
        details.addAll(completedDetails.subList(0, confirmedLimit));
        if (issueIds != null) {
            for (int i = batchCompleted; i < issueIds.size(); i++) {
                UnitDetail.State state = intentProjection || (currentIssue != null && i == batchCompleted)
                        ? UnitDetail.State.UNKNOWN : UnitDetail.State.NOT_STARTED;
                details.add(new UnitDetail(batchStart + i, Long.toString(issueIds.get(i)), state));
            }
        }
        long unknown = intentProjection ? pending : currentIssue == null ? 0 : 1;
        return new ExecutionSummary(outcome, new ExecutionSummary.Counts(completed, unknown, finished ? 0L : null),
                finished, details, completed > confirmedLimit, stoppedAt, operationId);
    }
}
