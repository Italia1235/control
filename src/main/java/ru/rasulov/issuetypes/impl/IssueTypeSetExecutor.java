package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.impl.ChangeLinks;
import ru.rasulov.common.impl.undo.JournalCancelledException;
import ru.rasulov.common.impl.undo.JournalCapacityException;
import ru.rasulov.common.api.dto.issuetype.ItemStatus;
import ru.rasulov.common.api.dto.issuetype.ExecutionSummary;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView.Assignment;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView.AssignmentState;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView.Entry;
import ru.rasulov.common.api.dto.issuetype.IssueTypeSetView.Origin;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest.TypeMapping;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.ErrorCode;
import ru.rasulov.common.api.dto.issuetype.SchemeAction;
import ru.rasulov.issuetypes.impl.IssueTypeBatchWriter.Outcome;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a confirmed {@link ResolvedTypeSet} on an existing project inside a running journal operation: first the
 * missing global types ({@link IssueTypeBatchWriter}), then, only if every one of them is confirmed, the checked
 * assignment of the final set ({@link IssueTypeChangePlanner} + {@link IssueTypeSetWriter}). It issues no plan and
 * takes no lock: the caller already holds the plan and the locks and has compared the fingerprint.
 * <p>
 * Jira and domain failures become a result, never an exception, so the journal does not undo what already happened:
 * any step that is FAILED, UNKNOWN or NOT_EXECUTED blocks the steps that depend on it. Only journal cancellation and
 * journal capacity errors propagate.
 */
@Named
public class IssueTypeSetExecutor {
    private static final Logger log = LoggerFactory.getLogger(IssueTypeSetExecutor.class);

    private final IssueTypeBatchWriter batchWriter;
    private final IssueTypeChangePlanner planner;
    private final IssueTypeSetWriter setWriter;

    @Inject
    public IssueTypeSetExecutor(IssueTypeBatchWriter batchWriter, IssueTypeChangePlanner planner,
                                IssueTypeSetWriter setWriter) {
        this.batchWriter = batchWriter;
        this.planner = planner;
        this.setWriter = setWriter;
    }

    /** What happened; {@code plan} is the checked assignment plan when the planning step was reached. */
    public static final class Applied {
        private final IssueTypeSetView view;
        private final boolean complete;
        private final boolean typesChanged;
        private final ErrorCode errorCode;
        private final String message;
        final IssueTypeChangePlan plan;
        final IssueTypeSetWriter.Written written;

        Applied(IssueTypeSetView view, boolean complete, boolean typesChanged, ErrorCode errorCode, String message,
                IssueTypeChangePlan plan, IssueTypeSetWriter.Written written) {
            this.view = view; this.complete = complete; this.typesChanged = typesChanged; this.errorCode = errorCode;
            this.message = message; this.plan = plan; this.written = written;
        }

        public IssueTypeSetView view() { return view; }
        /** True only when the final set is assigned and every type of it is confirmed. */
        public boolean complete() { return complete; }
        /** True when the types step confirmed or may have written something (created or UNKNOWN). */
        public boolean typesChanged() { return typesChanged; }
        public ErrorCode errorCode() { return errorCode; }
        public String message() { return message; }
    }

    /** Public entry point for callers outside this package: apply the set to an existing project. */
    public Applied apply(Project project, ResolvedTypeSet set, List<TypeMapping> mappings, String previousDefaultId,
                         ApplicationUser user, ChangeLinks.Report report) {
        Map<ResolvedTypeSet.Member, Outcome> outcomes = createTypes(set, report);
        List<Entry> entries = entries(set, outcomes, null);
        boolean typesChanged = false;
        boolean typesComplete = true;
        for (ResolvedTypeSet.Member member : set.members) {
            Outcome outcome = outcomes.get(member);
            if (outcome == null) continue;
            if (outcome.status == ItemStatus.CREATED || outcome.status == ItemStatus.UNKNOWN) typesChanged = true;
            if (outcome.status != ItemStatus.CREATED && outcome.status != ItemStatus.EXISTS) typesComplete = false;
        }
        if (!typesComplete) {
            return blocked(set, entries, typesChanged, ErrorCode.JIRA_ERROR, "The missing issue types were not all "
                    + "created and confirmed, so the final set was NOT assigned and the project's types were not "
                    + "touched. Types that were created remain in Jira (see entries). Check Jira and request a new "
                    + "plan.", null);
        }
        List<IssueType> types = new ArrayList<IssueType>();
        List<String> ids = new ArrayList<String>();
        for (ResolvedTypeSet.Member member : set.members) {
            IssueType type = member.type != null ? member.type : outcomes.get(member).type;
            types.add(type);
            ids.add(type.getId());
        }
        String[] source = new String[1];
        ResolvedTypeSet.Member chosen = set.defaultMember(previousDefaultId, source);
        String explicitDefault = set.requestedDefault == null ? null : types.get(set.members.indexOf(chosen)).getId();
        IssueTypeChangePlan plan;
        try {
            plan = planner.plan(project, new SetProjectIssueTypesRequest(false, project.getKey(), ids, mappings, null),
                    Collections.<IssueTypeRef>emptyList(), explicitDefault);
        } catch (IssueTypeChangeFailure failure) {
            return blocked(set, entries, typesChanged, failure.getCode(), failure.getMessage()
                    + " The final set was NOT assigned and the project's types were not touched. The types listed in "
                    + "entries exist in Jira. Request a new plan (with mappings if issues use a leaving type).", null);
        } catch (RuntimeException failure) {
            log.error("Could not plan the assignment of the final issue-type set to project {}", project.getKey(), failure);
            return blocked(set, entries, typesChanged, ErrorCode.JIRA_ERROR, "Jira could not plan the assignment. The "
                    + "final set was NOT assigned and the project's types were not touched. Request a new plan.", null);
        }
        if (plan.action == SchemeAction.NONE) {
            return done(set, entries, plan, null, "The project already has exactly this set.", typesChanged);
        }
        IssueTypeExecution execution = setWriter.begin(plan);
        try {
            IssueTypeSetWriter.Written written = setWriter.write(plan, user, execution);
            return done(set, entries, plan, written, written.lateNote, typesChanged);
        } catch (JournalCancelledException | JournalCapacityException journalFailure) {
            throw journalFailure;
        } catch (RuntimeException failure) {
            log.error("Assigning the final issue-type set to project {} failed", project.getKey(), failure);
            ExecutionSummary summary = execution.stoppedSummary();
            boolean partial = summary.getOutcome() == ExecutionSummary.Outcome.PARTIALLY_CHANGED;
            Assignment assignment = new Assignment(partial ? AssignmentState.PARTIALLY_CHANGED : AssignmentState.UNKNOWN,
                    "Assigning the set failed after it started: " + (partial ? "part of it was written" : "whether "
                            + "anything was written is UNKNOWN") + ". It is not retried and not rolled back; read the "
                            + "project's real issue types before any new plan.", null, null, null, null, summary);
            return new Applied(set.preview(plan.previousDefaultTypeId, null).withAssignment(entries, assignment),
                    false, true, ErrorCode.JIRA_ERROR, assignment.getMessage(), plan, null);
        }
    }

    /** Creates the missing types (if any) and fills the report; reused and referenced types are reported as reused. */
    private Map<ResolvedTypeSet.Member, Outcome> createTypes(ResolvedTypeSet set, ChangeLinks.Report report) {
        Map<ResolvedTypeSet.Member, Outcome> byMember = new HashMap<ResolvedTypeSet.Member, Outcome>();
        if (set.batch != null) {
            Map<IssueTypeBatchPlanner.Item, Outcome> byItem = new HashMap<IssueTypeBatchPlanner.Item, Outcome>();
            if (set.creates() > 0) {
                for (Outcome outcome : batchWriter.write(set.batch)) byItem.put(outcome.item, outcome);
            }
            for (ResolvedTypeSet.Member member : set.members) {
                if (member.item == null) continue;
                Outcome outcome = byItem.get(member.item);
                byMember.put(member, outcome != null ? outcome : new Outcome(member.item, ItemStatus.EXISTS,
                        member.type, null, null));
            }
        }
        for (ResolvedTypeSet.Member member : set.members) {
            Outcome outcome = byMember.get(member);
            IssueType type = outcome != null ? outcome.type : member.type;
            if (outcome != null && outcome.status == ItemStatus.CREATED) {
                report.created(ChangeKind.ISSUE_TYPE, type.getId(), type.getName(), type.getId());
            } else if ((outcome == null || outcome.status == ItemStatus.EXISTS) && type != null) {
                report.reused(ChangeKind.ISSUE_TYPE, type.getId(), type.getName(), type.getId());
            }
        }
        return byMember;
    }

    private List<Entry> entries(ResolvedTypeSet set, Map<ResolvedTypeSet.Member, Outcome> outcomes, String unused) {
        String[] source = new String[1];
        ResolvedTypeSet.Member chosen = set.defaultMember(null, source);
        List<Entry> entries = new ArrayList<Entry>();
        for (ResolvedTypeSet.Member member : set.members) {
            Outcome outcome = outcomes.get(member);
            ItemStatus status = outcome == null ? ItemStatus.EXISTS : outcome.status;
            IssueType type = outcome != null ? outcome.type : member.type;
            entries.add(new Entry(member.key, member.name, member.subtask, type == null ? null : type.getId(), status,
                    member.origin, member == chosen, outcome == null ? null : outcome.message));
        }
        return entries;
    }

    private Applied blocked(ResolvedTypeSet set, List<Entry> entries, boolean typesChanged, ErrorCode code,
                            String message, ExecutionSummary execution) {
        Assignment assignment = new Assignment(AssignmentState.NOT_STARTED, message, null, null, null, null, execution);
        return new Applied(set.preview(null, null).withAssignment(entries, assignment), false, typesChanged, code,
                message, null, null);
    }

    private Applied done(ResolvedTypeSet set, List<Entry> entries, IssueTypeChangePlan plan,
                         IssueTypeSetWriter.Written written, String note, boolean typesChanged) {
        List<IssueTypeRef> actual = new ArrayList<IssueTypeRef>();
        for (IssueType type : plan.desired) actual.add(new IssueTypeRef(type.getId(), type.getName()));
        String wanted = IssueTypeSetWriter.wantedDefault(plan);
        IssueTypeRef defaultRef = null;
        for (IssueType type : plan.desired) if (type.getId().equals(wanted)) defaultRef = new IssueTypeRef(type.getId(), type.getName());
        List<Entry> marked = new ArrayList<Entry>();
        for (Entry entry : entries) {
            marked.add(new Entry(entry.getKey(), entry.getName(), entry.isSubtask(), entry.getId(), entry.getStatus(),
                    entry.getOrigin(), wanted != null && wanted.equals(entry.getId()), entry.getMessage()));
        }
        Assignment assignment = new Assignment(AssignmentState.COMPLETED, note == null ? "The final set is assigned." : note,
                actual, plan.removed, defaultRef, written == null ? setWriter.schemeView(plan, false) : written.scheme,
                written == null ? null : written.execution);
        return new Applied(set.preview(plan.previousDefaultTypeId, plan.removed).withAssignment(marked, assignment),
                true, typesChanged, null, assignment.getMessage(), plan, written);
    }
}
