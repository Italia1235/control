package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.api.dto.issuetype.ExecutionSummary;
import ru.rasulov.common.api.dto.issuetype.SchemeAction;
import ru.rasulov.common.api.dto.issuetype.SchemeView;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Writes one checked {@link IssueTypeChangePlan} to a project: issue migration, scheme create/update, default type and
 * association, each with durable progress. Needs a running journal operation that tracks issue-type progress. It is
 * shared by {@code set_project_issue_types} and by project creation with a final type set; it takes no plan, lock or
 * confirmation of its own.
 */
@Named
public class IssueTypeSetWriter {
    private static final Logger log = LoggerFactory.getLogger(IssueTypeSetWriter.class);

    private final IssueTypeSchemeManager schemeManager;
    private final IssueTypeMigrator migrator;
    private final ChangeJournal journal;

    @Inject
    public IssueTypeSetWriter(@ComponentImport IssueTypeSchemeManager schemeManager, IssueTypeMigrator migrator,
                              ChangeJournal journal) {
        this.schemeManager = schemeManager;
        this.migrator = migrator;
        this.journal = journal;
    }

    /** What a completed write left behind. */
    static final class Written {
        final SchemeView scheme;
        final ExecutionSummary execution;
        final String lateNote;
        final int converted;

        Written(SchemeView scheme, ExecutionSummary execution, String lateNote, int converted) {
            this.scheme = scheme; this.execution = execution; this.lateNote = lateNote; this.converted = converted;
        }
    }

    /** Reserves the durable progress budget; call it before the first native write of this plan. */
    IssueTypeExecution begin(IssueTypeChangePlan plan) {
        return new IssueTypeExecution(journal, !plan.mappings.isEmpty(), plan.action == SchemeAction.CREATE ? 3 : 2);
    }

    /**
     * Runs the write. On any failure the execution receipt is closed (best effort) and the original failure is
     * rethrown; the caller decides whether that is a thrown error or a domain result.
     */
    Written write(IssueTypeChangePlan plan, ApplicationUser user, IssueTypeExecution execution) {
        try {
            int changed = migrator.migrate(plan.project, plan.mappings, user, execution);
            FieldConfigScheme created = null;
            if (plan.action == SchemeAction.CREATE) {
                execution.beginScheme("CREATE_SCHEME");
                created = schemeManager.create(plan.newSchemeName,
                        "Private issue type scheme for project " + plan.project.getKey(), ids(plan.desired));
                if (created == null) throw new IllegalStateException("Jira did not return the new issue type scheme.");
                execution.completedScheme(created.getId());
                execution.beginScheme("SET_DEFAULT");
                setDefault(findScheme(created.getId()), wantedDefault(plan));
                execution.completedScheme(created.getId());
            }
            if (plan.action == SchemeAction.CREATE) {
                execution.beginScheme("ASSOCIATE_PROJECT");
                schemeManager.addProjectAssociations(created, Collections.singletonList(plan.project));
                execution.completedScheme(created.getId());
            } else {
                execution.beginScheme("UPDATE_SCHEME");
                schemeManager.update(plan.sourceScheme, ids(plan.desired));
                execution.completedScheme(plan.sourceScheme.getId());
                execution.beginScheme("SET_DEFAULT");
                setDefault(findScheme(plan.sourceScheme.getId()), wantedDefault(plan));
                execution.completedScheme(plan.sourceScheme.getId());
            }
            int late = migrator.migrate(plan.project, plan.mappings, user, execution);
            SchemeView written = plan.action == SchemeAction.CREATE
                    ? new SchemeView(SchemeAction.CREATE, created.getId(), created.getName(), false,
                    plan.otherProjectNames.size(), plan.otherProjectNames) : schemeView(plan, true);
            log.info("Changed issue types of project {}: {} scheme, {} issue(s) converted (user {})",
                    plan.project.getKey(), plan.action, changed + late, user.getUsername());
            return new Written(written, execution.finish(), late == 0 ? null
                    : late + " issue(s) created while the change was running were converted too.", changed + late);
        } catch (RuntimeException | Error failure) {
            try { execution.stop(); }
            catch (RuntimeException | Error receiptFailure) {
                if (receiptFailure != failure) failure.addSuppressed(receiptFailure);
            }
            throw failure;
        }
    }

    SchemeView schemeView(IssueTypeChangePlan plan, boolean written) {
        FieldConfigScheme source = plan.sourceScheme;
        boolean creating = plan.action == SchemeAction.CREATE && !written;
        return new SchemeView(plan.action, creating ? null : source.getId(),
                creating ? plan.newSchemeName : source.getName(),
                creating ? false : schemeManager.isDefaultIssueTypeScheme(source),
                plan.otherProjectNames.size(), plan.otherProjectNames);
    }

    /** The explicit default, else the previous default when it stays, else the first standard type of the set. */
    static String wantedDefault(IssueTypeChangePlan plan) {
        if (plan.explicitDefaultTypeId != null) return plan.explicitDefaultTypeId;
        for (IssueType type : plan.desired) if (type.getId().equals(plan.previousDefaultTypeId)) return type.getId();
        for (IssueType type : plan.desired) if (!type.isSubTask()) return type.getId();
        return plan.desired.get(0).getId();
    }

    private FieldConfigScheme findScheme(long id) {
        for (FieldConfigScheme scheme : schemeManager.getAllSchemes()) if (scheme.getId() == id) return scheme;
        return null;
    }

    private void setDefault(FieldConfigScheme scheme, String issueTypeId) {
        if (scheme == null || scheme.getOneAndOnlyConfig() == null || issueTypeId == null)
            throw new IllegalStateException("Jira did not return the scheme configuration required to set its default.");
        schemeManager.setDefaultValue(scheme.getOneAndOnlyConfig(), issueTypeId);
    }

    private static List<String> ids(List<IssueType> types) {
        List<String> result = new ArrayList<String>();
        for (IssueType type : types) result.add(type.getId());
        return result;
    }
}
