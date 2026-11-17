package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.GenericValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.SchemeInfo;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.Status;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Switches the project with ONE Jira call and never rolls back. The outcome is what a fresh read shows: Jira keeps the
 * association cached, so the cache is dropped before the read-back (if that fails the read is not trusted), and an error from
 * Jira alone decides nothing. Success is the new scheme read back AND the project no longer listed under the old one.
 */
@Named
public class IssueTypeScreenSchemeAssignmentWriter {
    private static final Logger log = LoggerFactory.getLogger(IssueTypeScreenSchemeAssignmentWriter.class);
    private final IssueTypeScreenSchemeManager tops;

    @Inject
    public IssueTypeScreenSchemeAssignmentWriter(@ComponentImport IssueTypeScreenSchemeManager tops) {
        this.tops = tops;
    }

    /** The outcome and the scheme the project uses after the write ({@code observed} is null when it could not be read). */
    static final class Result {
        final Status status;
        final SchemeInfo observed;
        final boolean readBack;
        final List<String> notes = new ArrayList<String>();
        final String message;

        Result(Status status, SchemeInfo observed, boolean readBack, String message) {
            this.status = status; this.observed = observed; this.readBack = readBack; this.message = message;
        }
    }

    Result write(IssueTypeScreenSchemeAssignmentPlan plan) {
        String error = null;
        try {
            tops.addSchemeAssociation(plan.project, plan.target);
        } catch (RuntimeException e) {
            log.error("Jira failed to switch project " + plan.project.getKey() + " to Issue Type Screen Scheme " + plan.target.getId(), e);
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
        return observe(plan, error);
    }

    private Result observe(IssueTypeScreenSchemeAssignmentPlan plan, String error) {
        String project = "Project " + plan.project.getKey();
        IssueTypeScreenScheme now;
        try {
            tops.refresh();
            now = tops.getIssueTypeScreenScheme(plan.project);
        } catch (RuntimeException e) {
            log.error("Could not read back the Issue Type Screen Scheme of project " + plan.project.getKey(), e);
            return unknown(null, false, error, project + ": Jira's answer could not be read back, so which Issue Type Screen Scheme the project uses is not known.");
        }
        if (now == null) return unknown(null, true, error, project + ": Jira reports no Issue Type Screen Scheme for the project.");
        SchemeInfo observed = new SchemeInfo(now.getId(), now.getName(), now.isDefault());
        if (now.getId().equals(plan.target.getId())) {
            Boolean listed = listedUnder(plan.current, plan.project.getId());
            if (Boolean.FALSE.equals(listed)) {
                Result done = new Result(Status.CHANGED, observed, true, project + " now uses Issue Type Screen Scheme " + now.getId() + " '"
                        + now.getName() + "' (read back from Jira). No row, Screen Scheme or screen was changed.");
                if (error != null) done.notes.add("Jira reported an error (" + error + ") but the read-back shows the project already uses the new scheme.");
                return done;
            }
            return unknown(observed, true, error, project + " reads back as using the new Issue Type Screen Scheme, but "
                    + (listed == null ? "its association with the old one could not be read" : "it is still listed under the old one too")
                    + ", so the state is not trustworthy.");
        }
        if (now.getId().equals(plan.current.getId())) {
            Result failed = new Result(Status.FAILED, observed, true, project + " still reads back as using Issue Type Screen Scheme " + now.getId() + " '"
                    + now.getName() + "': " + (error == null ? "the write had no visible effect on the project's association" : "Jira reported an error (" + error + ")")
                    + ". Nothing was rolled back. Read get_project_screens, then run a NEW preview before any retry.");
            return failed;
        }
        return unknown(observed, true, error, project + " reads back as using Issue Type Screen Scheme " + now.getId() + " '" + now.getName()
                + "', which is neither the old nor the new one.");
    }

    private static Result unknown(SchemeInfo observed, boolean readBack, String error, String reason) {
        Result result = new Result(Status.UNKNOWN, observed, readBack, reason
                + " Nothing was rolled back. Read get_project_screens, then run a NEW preview before any retry.");
        if (error != null) result.notes.add("Jira reported an error: " + error);
        return result;
    }

    /** Whether Jira lists the project under the scheme; null when the list cannot be read. */
    private Boolean listedUnder(IssueTypeScreenScheme scheme, Long projectId) {
        try {
            Collection<GenericValue> values = tops.getProjects(scheme);
            if (values != null) {
                for (GenericValue value : values) if (value != null && projectId.equals(value.getLong("id"))) return Boolean.TRUE;
            }
            return Boolean.FALSE;
        } catch (RuntimeException e) {
            log.error("Could not list the projects of Issue Type Screen Scheme " + scheme.getId(), e);
            return null;
        }
    }
}
