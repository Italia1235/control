package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import ru.rasulov.workflow.api.dto.assign.AssignVerdict;
import ru.rasulov.workflow.api.dto.assign.IssueTypeAssignment;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an assignment would do: verdict per issue type and the action on the scheme. Nothing here has been written. */
final class AssignmentPlan {

    private final List<IssueTypeAssignment> assignments;
    private final SchemeAction action;
    private final AssignableWorkflowScheme scheme;
    private final boolean usingDefaultScheme;
    private final String newSchemeName;
    private final SchemeEdit edit;
    private final List<String> warnings;
    private final Map<String, Status> statusMappings;

    AssignmentPlan(List<IssueTypeAssignment> assignments, SchemeAction action, AssignableWorkflowScheme scheme,
                   boolean usingDefaultScheme, String newSchemeName, SchemeEdit edit, List<String> warnings) {
        this(assignments, action, scheme, usingDefaultScheme, newSchemeName, edit, warnings,
                Collections.<String, Status>emptyMap());
    }

    AssignmentPlan(List<IssueTypeAssignment> assignments, SchemeAction action, AssignableWorkflowScheme scheme,
                   boolean usingDefaultScheme, String newSchemeName, SchemeEdit edit, List<String> warnings,
                   Map<String, Status> statusMappings) {
        this.assignments = Collections.unmodifiableList(new ArrayList<IssueTypeAssignment>(assignments));
        this.action = action;
        this.scheme = scheme;
        this.usingDefaultScheme = usingDefaultScheme;
        this.newSchemeName = newSchemeName;
        this.edit = edit;
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
        this.statusMappings = Collections.unmodifiableMap(new LinkedHashMap<String, Status>(statusMappings));
    }

    List<IssueTypeAssignment> getAssignments() {
        return assignments;
    }

    SchemeAction getAction() {
        return action;
    }

    /** The scheme the project has now. */
    AssignableWorkflowScheme getScheme() {
        return scheme;
    }

    boolean isUsingDefaultScheme() {
        return usingDefaultScheme;
    }

    /** Name of the scheme a {@code CREATE} makes; {@code null} otherwise. */
    String getNewSchemeName() {
        return newSchemeName;
    }

    SchemeEdit getEdit() {
        return edit;
    }

    List<String> getWarnings() {
        return warnings;
    }

    /** Changed issue types and their source workflows, including initially empty types that may gain issues. */
    Map<String, String> workflowsToLeave() {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (IssueTypeAssignment assignment : assignments) {
            if (assignment.getVerdict() == AssignVerdict.MIGRATE
                    || assignment.getVerdict() == AssignVerdict.READY) {
                result.put(assignment.getIssueType().getId(), assignment.getFrom());
            }
        }
        return result;
    }

    /** Current status id to the explicitly selected status in the target workflow. */
    Map<String, Status> getStatusMappings() {
        return statusMappings;
    }
}
