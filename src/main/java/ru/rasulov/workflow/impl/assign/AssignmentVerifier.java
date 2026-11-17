package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads Jira back after a scheme was assigned or changed: does the project now resolve each issue type to the expected
 * workflow, and does Jira report those workflows active. Native writes can succeed while the answers Jira gives stay
 * stale (the active-workflow cache), and an "inactive" workflow of a project in use makes {@code configure_workflow} write
 * to the live version instead of a draft. Read-only: a mismatch is reported, never repaired.
 */
@Named
public class AssignmentVerifier {
    private static final Logger log = LoggerFactory.getLogger(AssignmentVerifier.class);

    private final WorkflowManager workflowManager;
    private final WorkflowSchemeManager schemeManager;

    @Inject
    public AssignmentVerifier(@ComponentImport WorkflowManager workflowManager,
                              @ComponentImport WorkflowSchemeManager schemeManager) {
        this.workflowManager = workflowManager;
        this.schemeManager = schemeManager;
    }

    /**
     * @param expected issue type id to the name of the workflow the project must use for it
     * @return {@code null} when everything matches, otherwise what differs (and that nothing was repaired)
     */
    String verify(Project project, Map<String, String> expected) {
        if (expected.isEmpty()) {
            return null;
        }
        try {
            List<String> problems = problems(project, expected);
            if (problems.isEmpty()) {
                return null;
            }
            log.error("Project {} does not match the assignment just written: {}", project.getKey(), problems);
            return "Jira does not report the assignment as expected: " + String.join("; ", problems) + ". The "
                    + "scheme was written and issues were migrated; nothing was repaired or reassigned. Do not edit "
                    + "these workflows until a Jira administrator has checked the project's workflow scheme.";
        } catch (RuntimeException e) {
            log.error("Reading back the assignment of project {} failed", project.getKey(), e);
            return "The assignment was written, but reading it back failed (details are in the Jira log), so the "
                    + "workflow scheme and workflow activity are not confirmed. Nothing was repaired.";
        }
    }

    /** What Jira reports after a write that raised an error: confirmed, or why it is not. */
    static final class Readback {
        final boolean confirmed;
        final String detail;

        private Readback(boolean confirmed, String detail) {
            this.confirmed = confirmed;
            this.detail = detail;
        }
    }

    /**
     * Read-only, like {@link #verify}, but worded for a write that did not finish normally, so it claims nothing about
     * what was written. Never throws: an unreadable Jira is "not confirmed".
     */
    Readback readback(Project project, Map<String, String> expected) {
        if (expected.isEmpty()) {
            return new Readback(false, "no issue type was expected to change, so there is nothing to compare");
        }
        try {
            List<String> problems = problems(project, expected);
            return problems.isEmpty() ? new Readback(true, null) : new Readback(false, String.join("; ", problems));
        } catch (RuntimeException e) {
            log.error("Reading back project {} after a write that raised an error failed", project.getKey(), e);
            return new Readback(false, "Jira could not be read");
        }
    }

    private List<String> problems(Project project, Map<String, String> expected) {
        List<String> problems = new ArrayList<String>();
        AssignableWorkflowScheme scheme = schemeManager.getWorkflowSchemeObj(project);
        Set<String> names = new LinkedHashSet<String>();
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actual = scheme.getActualWorkflow(entry.getKey());
            if (actual == null || !actual.toLowerCase(Locale.ROOT).equals(entry.getValue().toLowerCase(Locale.ROOT))) {
                problems.add("issue type " + entry.getKey() + " resolves to '" + actual + "', expected '"
                        + entry.getValue() + "'");
            }
            names.add(entry.getValue());
        }
        for (String name : names) {
            JiraWorkflow workflow = workflowManager.getWorkflow(name);
            if (workflow == null) {
                problems.add("workflow '" + name + "' cannot be read");
            } else if (!workflowManager.isActive(workflow)) {
                problems.add("workflow '" + name + "' is reported inactive");
            }
        }
        return problems;
    }
}
