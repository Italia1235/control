package ru.rasulov.workflow.api;

import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsRequest;
import ru.rasulov.workflow.api.dto.clone.CloneWorkflowsResponse;

/**
 * Detaches one project from workflows it shares with other projects: an exact copy of its workflow scheme and, as
 * asked, of the workflows, under new names, given to the project. The originals are never changed or deleted, so the
 * other projects notice nothing. The issues of the project move to the copies and keep their status. Independent of
 * the transport.
 */
public interface WorkflowCloner {

    /**
     * All or nothing: a problem with the request itself is a {@code FAILED} result, not an exception.
     *
     * @throws InvalidRequestException {@code request} is {@code null}
     * @throws AccessException         the caller is anonymous or not a Jira administrator
     * @throws BusyException           another call is assigning or copying workflows and did not finish within
     *                                 60 seconds
     */
    CloneWorkflowsResponse clone(CloneWorkflowsRequest request);
}
