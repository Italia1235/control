package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.workflow.api.WorkflowConditionReader;
import ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/** {@code get_workflow_conditions}: reads the conditions of every transition of one version; never changes anything. */
@ExportAsService(WorkflowConditionReader.class)
@Named
public class WorkflowConditionReaderImpl implements WorkflowConditionReader {

    private final AdministratorGuard guard;
    private final WorkflowInspector inspector;
    private final ScriptRunnerGate gate;
    private final ProjectRoleManager roles;

    @Inject
    public WorkflowConditionReaderImpl(AdministratorGuard guard, WorkflowInspector inspector, ScriptRunnerGate gate,
                                       @ComponentImport ProjectRoleManager roles) {
        this.guard = guard;
        this.inspector = inspector;
        this.gate = gate;
        this.roles = roles;
    }

    @Override
    public WorkflowConditionsView read(String workflow, String version) {
        guard.requireAdministrator();
        String name = workflow == null ? "" : workflow.trim();
        if (name.isEmpty()) throw new InvalidRequestException("'workflow' is blank. Give the exact workflow name.");
        if (!"ACTIVE".equals(version) && !"DRAFT".equals(version)) {
            throw new InvalidRequestException("'version' must be ACTIVE or DRAFT.");
        }
        WorkflowInspector.Snapshot s = inspector.snapshot(name);
        if (s == null) {
            throw new InvalidRequestException("No workflow is named '" + name + "'. Names are exact; use "
                    + "get_project_workflows to find them.");
        }
        JiraWorkflow shown = "DRAFT".equals(version) ? s.draft() : s.main();
        if (shown == null) {
            throw new InvalidRequestException("The workflow '" + s.name() + "' has no draft"
                    + (s.active() ? "" : " (it is not active, so it has only its saved version)")
                    + ". Read version ACTIVE instead; no other version is returned in place of DRAFT.");
        }
        LongFunction<String> roleName = roleNames();
        List<WorkflowConditionsView.TransitionConditions> transitions =
                new ArrayList<WorkflowConditionsView.TransitionConditions>();
        for (DescriptorFacts.Located located : DescriptorFacts.locate(shown.getDescriptor()).values()) {
            transitions.add(new WorkflowConditionsView.TransitionConditions(located.id(), located.action().getName(),
                    ConditionDescriptors.read(located.action(), roleName)));
        }
        return new WorkflowConditionsView(s.name(), version, s.revision(), gate.conditionStatus(), transitions);
    }

    /** The name of a role that Jira has, or null when it has no such role (any more). */
    private LongFunction<String> roleNames() {
        return id -> {
            ProjectRole role = roles.getProjectRole(id);
            return role == null ? null : role.getName();
        };
    }
}
