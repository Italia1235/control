package ru.rasulov.workflow.impl;

import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.opensymphony.workflow.loader.WorkflowDescriptor;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;

/**
 * Stores a workflow. {@code JiraWorkflow} has one implementation, and it is not in {@code jira-api}, so a new workflow
 * is made the way the admin UI's "copy workflow" makes one: copy the built-in {@code jira} workflow under the new name
 * (Jira creates it, unassigned to any project), swap the copy's steps and actions for the requested ones and save it.
 */
@Named
public class WorkflowWriter {

    private final WorkflowManager workflowManager;
    private final OfBizDelegator delegator;
    private final ChangeJournal journal;

    @Inject
    public WorkflowWriter(@ComponentImport WorkflowManager workflowManager,
                          @ComponentImport OfBizDelegator delegator, ChangeJournal journal) {
        this.workflowManager = workflowManager;
        this.delegator = delegator;
        this.journal = journal;
    }


    /** Journal an exact, unassigned copy as a newly created workflow for normal Audit rollback. */
    public JiraWorkflow copyCreated(ApplicationUser user, JiraWorkflow source, String name, String description) {
        UndoStep step = journal.prepare("workflow.created", "workflow:" + name,
                WorkflowSnapshots.json(WorkflowSnapshots.absent(name)),
                WorkflowSnapshots.json(WorkflowSnapshots.absent(name)));
        JiraWorkflow copy = workflowManager.copyWorkflow(user, name, description, source);
        journal.applied(step, WorkflowSnapshots.json(WorkflowSnapshots.workflow(copy, delegator)));
        journal.checkpoint();
        return copy;
    }

    /**
     * An exact copy of a workflow under a new name, the way the admin UI's "copy workflow" makes one: Jira copies the
     * descriptor as it is (steps, transitions, conditions, validators, post-functions, properties). The copy belongs
     * to no scheme yet.
     *
     * @throws RuntimeException Jira refused to copy the workflow
     */
    public JiraWorkflow copy(ApplicationUser user, JiraWorkflow source, String newName) {
        workflowManager.copyWorkflow(user, newName, source.getDescription(), source);
        JiraWorkflow copy = workflowManager.getWorkflow(newName);
        return copy;
    }


}
