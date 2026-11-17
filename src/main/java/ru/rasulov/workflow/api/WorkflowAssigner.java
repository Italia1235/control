package ru.rasulov.workflow.api;

import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowRequest;
import ru.rasulov.workflow.api.dto.assign.AssignWorkflowResponse;
import ru.rasulov.workflow.api.dto.assign.ProjectWorkflowsResponse;

/**
 * Gives an existing workflow to a project: reads what the project has now, then changes its workflow scheme (and moves
 * the issues to the new workflow with the same status). Independent of the transport.
 */
public interface WorkflowAssigner {

    /**
     * Current workflow configuration; optional full issue counts by type/status, without native-entry diagnosis.
     *
     * @param project key or name
     * @param includeIssueCounts explicitly request a linear scan of project issues; false reads configuration only
     * @throws InvalidRequestException the project is blank, unknown or archived
     * @throws AccessException         the caller is anonymous or not a Jira administrator
     */
    ProjectWorkflowsResponse projectWorkflows(String project, boolean includeIssueCounts);

    /**
     * Assigns the workflow to all issue types of the project or to the named ones. All or nothing: a problem with the
     * assignment itself is a {@code FAILED} result, not an exception.
     *
     * @throws InvalidRequestException {@code request} is {@code null}
     * @throws AccessException         the caller is anonymous or not a Jira administrator
     * @throws BusyException           another call is assigning workflows and did not finish within 60 seconds
     */
    AssignWorkflowResponse assign(AssignWorkflowRequest request);
}
