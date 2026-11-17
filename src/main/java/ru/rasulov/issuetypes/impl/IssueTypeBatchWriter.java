package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.ErrorCode;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.IssueTypeView;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.ItemResult;
import ru.rasulov.common.api.dto.issuetype.ItemStatus;
import ru.rasulov.issuetypes.impl.IssueTypeBatchPlanner.Action;
import ru.rasulov.issuetypes.impl.IssueTypeBatchPlanner.Item;
import ru.rasulov.issuetypes.impl.IssueTypeBatchPlanner.Plan;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Executes an already confirmed {@link Plan} inside a running journal operation, one item at a time, and records what
 * really happened per item. Callers hold the issue-type lock and have compared the plan fingerprint before the first
 * call; the creations of this batch are expected changes and are never compared against that fingerprint again.
 * <p>
 * Between two writes only conflicts that matter to the batch are checked (a name taken in the meantime, a reused type
 * that vanished or changed kind). Jira's own writers do not take the plugin lock, so this narrows the race window but
 * does not close it. A failure stops the batch: the remaining creations are {@code NOT_EXECUTED}, nothing already
 * created is undone and nothing is retried.
 */
@Named
public class IssueTypeBatchWriter {
    private static final Logger log = LoggerFactory.getLogger(IssueTypeBatchWriter.class);

    private final IssueTypeManager manager;
    private final ChangeJournal journal;
    private final IssueTypeUndoSupport undoSupport;

    @Inject
    public IssueTypeBatchWriter(@ComponentImport IssueTypeManager manager, ChangeJournal journal,
                                IssueTypeUndoSupport undoSupport) {
        this.manager = manager;
        this.journal = journal;
        this.undoSupport = undoSupport;
    }

    /** What happened to one item; {@code type} is set exactly when the type exists in Jira. */
    static final class Outcome {
        final Item item;
        final ItemStatus status;
        final IssueType type;
        final String message;
        final ErrorCode errorCode;

        Outcome(Item item, ItemStatus status, IssueType type, String message, ErrorCode errorCode) {
            this.item = item; this.status = status; this.type = type; this.message = message; this.errorCode = errorCode;
        }

        ItemResult result() {
            IssueTypeView view = type != null ? IssueTypeView.of(type)
                    : status == ItemStatus.FAILED || status == ItemStatus.UNKNOWN || status == ItemStatus.NOT_EXECUTED
                    ? new IssueTypeView(null, item.name, item.description, item.subtask) : null;
            return new ItemResult(item.key, status, view, message, errorCode);
        }
    }

    /** Needs a running journal operation. Throws only for journal failures (before or after a native write). */
    List<Outcome> write(Plan plan) {
        List<Outcome> outcomes = new ArrayList<Outcome>();
        List<String> createdIds = new ArrayList<String>();
        boolean halted = false;
        for (Item item : plan.items) {
            if (item.action == Action.REUSE) {
                outcomes.add(new Outcome(item, ItemStatus.EXISTS, item.existing,
                        "Existing type; reused as it is. Its name and description were not changed.", null));
                continue;
            }
            if (halted) {
                outcomes.add(new Outcome(item, ItemStatus.NOT_EXECUTED, null,
                        "Not created: an earlier item of this batch failed. Nothing was attempted.", null));
                continue;
            }
            Outcome outcome = create(plan, item, createdIds);
            outcomes.add(outcome);
            if (outcome.status != ItemStatus.CREATED) halted = true;
        }
        return outcomes;
    }

    private Outcome create(Plan plan, Item item, List<String> createdIds) {
        String conflict;
        try {
            conflict = externalConflict(plan, item, createdIds);
        } catch (RuntimeException e) {
            log.error("Could not re-read issue types before creating '{}'", item.name, e);
            return new Outcome(item, ItemStatus.FAILED, null, "Jira could not be read before this write; nothing "
                    + "was written for this item.", ErrorCode.JIRA_ERROR);
        }
        if (conflict != null) return new Outcome(item, ItemStatus.FAILED, null, conflict, ErrorCode.NAME_CONFLICT);
        Map<String, Object> before = undoSupport.creationBefore(item.name);
        UndoStep step = journal.prepare("issuetype.created", "issue-type-name:" + item.name,
                undoSupport.json(before), undoSupport.json(before));
        IssueType created;
        try {
            created = item.subtask
                    ? manager.createSubTaskIssueType(item.name, item.description, plan.avatarId)
                    : manager.createIssueType(item.name, item.description, plan.avatarId);
            if (created == null) throw new IllegalStateException("Jira did not return the created issue type.");
        } catch (RuntimeException e) {
            log.error("Jira failed while creating issue type '{}'", item.name, e);
            IssueType found;
            try {
                found = findNewByName(plan, item, createdIds);
            } catch (RuntimeException readFailure) {
                log.error("Could not read back issue type '{}' after a failed creation", item.name, readFailure);
                // The step stays PREPARED: the journal keeps the write as uncertain instead of claiming either result.
                return new Outcome(item, ItemStatus.UNKNOWN, null, "Jira failed while creating this type and could not "
                        + "be read afterwards: the type MAY have been created. This is not confirmed and not reported "
                        + "as created. Check the issue types in Jira before any retry.", ErrorCode.JIRA_ERROR);
            }
            if (found == null) {
                journal.discard(step);
                return new Outcome(item, ItemStatus.FAILED, null,
                        "Jira failed while creating this type; it was read back and does not exist.", ErrorCode.JIRA_ERROR);
            }
            // Jira gave no confirmed identity. A type with this name exists, but it may have been made by someone
            // else at the same moment, so it is neither claimed as created nor ever undone by this operation.
            return new Outcome(item, ItemStatus.UNKNOWN, found, "Jira failed while creating this type, and a type with "
                    + "this name and kind (id " + found.getId() + ") exists now. That id is only observed: the plugin "
                    + "cannot confirm that this operation created it, so it is not reported as created, is not "
                    + "removed and must not be used by dependent steps. Preview again: it is then reused as an "
                    + "existing type.", ErrorCode.JIRA_ERROR);
        }
        journal.applied(step, undoSupport.json(undoSupport.creationAfter(before, created)));
        journal.checkpoint();
        createdIds.add(created.getId());
        log.info("Created global {} issue type '{}' (id {})", created.isSubTask() ? "sub-task" : "standard",
                created.getName(), created.getId());
        return new Outcome(item, ItemStatus.CREATED, created, null, null);
    }

    /** A name taken by someone else since planning, or a reused type that is gone or changed kind. */
    private String externalConflict(Plan plan, Item item, List<String> createdIds) {
        Collection<IssueType> live = manager.getIssueTypes();
        for (IssueType type : live) {
            if (createdIds.contains(type.getId())) continue;
            if (type.getName() != null && type.getName().toLowerCase(Locale.ROOT).equals(item.name.toLowerCase(Locale.ROOT))) {
                return "An issue type named '" + type.getName() + "' appeared in Jira after the plan was made; "
                        + "nothing was written for this item. Preview again.";
            }
        }
        for (Item other : plan.items) {
            if (other.action != Action.REUSE) continue;
            IssueType now = null;
            for (IssueType type : live) if (type.getId().equals(other.existing.getId())) now = type;
            if (now == null || now.isSubTask() != other.subtask || !other.name.equalsIgnoreCase(now.getName())) {
                return "The reused issue type '" + other.name + "' was removed or changed in Jira after the plan was "
                        + "made; nothing was written for this item. Preview again.";
            }
        }
        return null;
    }

    /** A type with this name and kind that neither the plan's catalog nor this batch contained. */
    private IssueType findNewByName(Plan plan, Item item, List<String> createdIds) {
        for (IssueType type : manager.getIssueTypes()) {
            if (createdIds.contains(type.getId()) || plan.snapshot.containsKey(type.getId())) continue;
            if (type.getName() != null && type.getName().equalsIgnoreCase(item.name)
                    && type.isSubTask() == item.subtask) return type;
        }
        return null;
    }
}
