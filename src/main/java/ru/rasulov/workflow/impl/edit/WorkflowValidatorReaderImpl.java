package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.workflow.api.WorkflowValidatorReader;
import ru.rasulov.workflow.api.dto.edit.ValidatorFieldRef;
import ru.rasulov.workflow.api.dto.edit.WorkflowValidatorsView;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;

/** {@code get_workflow_validators}: reads the validators of every transition of one version; never changes anything. */
@ExportAsService(WorkflowValidatorReader.class)
@Named
public class WorkflowValidatorReaderImpl implements WorkflowValidatorReader {

    private static final String NO_SCREEN_PROBLEM = "This transition has a Comment required validator but no usable "
            + "screen (the transition has no screen, or its screen id is not a number, or Jira has no such screen), so "
            + "ScriptRunner rejects every execution of the transition with 'You need to provide a comment for this "
            + "transition but no screen is in use.' Assign a screen to the transition with configure_workflow.existing.";

    private static final String NO_VIEW_PROBLEM = "This transition has a Comment required validator and a screen, but "
            + "the transition lacks view=\"fieldscreen\", so Jira does not open the screen (\"Screen: None\") and "
            + "ScriptRunner rejects every execution of the transition with 'You need to provide a comment for this "
            + "transition but no screen is in use.' Set the screen again with configure_workflow.existing "
            + "(transitionRules screen SET) to repair the view.";

    private final AdministratorGuard guard;
    private final WorkflowInspector inspector;
    private final FieldManager fields;
    private final FieldScreenManager screens;
    private final ScriptRunnerGate gate;

    @Inject
    public WorkflowValidatorReaderImpl(AdministratorGuard guard, WorkflowInspector inspector,
                                       @ComponentImport FieldManager fields,
                                       @ComponentImport FieldScreenManager screens, ScriptRunnerGate gate) {
        this.guard = guard;
        this.inspector = inspector;
        this.fields = fields;
        this.screens = screens;
        this.gate = gate;
    }

    @Override
    public WorkflowValidatorsView read(String workflow, String version) {
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
        List<WorkflowValidatorsView.TransitionValidators> transitions =
                new ArrayList<WorkflowValidatorsView.TransitionValidators>();
        for (DescriptorFacts.Located located : DescriptorFacts.locate(shown.getDescriptor()).values()) {
            String screenId = DescriptorFacts.meta(located.action()).get(DescriptorFacts.SCREEN_META);
            transitions.add(new WorkflowValidatorsView.TransitionValidators(located.id(), located.action().getName(),
                    screenId, screenName(screenId), validators(located.action(), screenId)));
        }
        return new WorkflowValidatorsView(s.name(), version, s.revision(), gate.status(), transitions);
    }

    private List<WorkflowValidatorsView.ValidatorView> validators(com.opensymphony.workflow.loader.ActionDescriptor action,
                                                                  String screenId) {
        List<WorkflowValidatorsView.ValidatorView> views = new ArrayList<WorkflowValidatorsView.ValidatorView>();
        for (RequiredFieldsDescriptor.Entry entry : RequiredFieldsDescriptor.scan(action)) {
            if (entry.requiredFields()) {
                List<ValidatorFieldRef> refs = new ArrayList<ValidatorFieldRef>();
                for (String id : entry.fieldIds()) {
                    Field field = fields.getField(id);
                    refs.add(new ValidatorFieldRef(id, field == null ? null : field.getName()));
                }
                views.add(new WorkflowValidatorsView.ValidatorView(entry.position(),
                        WorkflowValidatorsView.REQUIRED_FIELDS, entry.fieldIds(), refs, entry.conditional(), null,
                        null, null, null));
            } else if (entry.commentRequired()) {
                views.add(new WorkflowValidatorsView.ValidatorView(entry.position(),
                        WorkflowValidatorsView.COMMENT_REQUIRED, null, null, null, null, null, null,
                        problem(action, screenId)));
            } else {
                views.add(new WorkflowValidatorsView.ValidatorView(entry.position(), WorkflowValidatorsView.OTHER,
                        null, null, null, entry.type(), blankToNull(entry.className()),
                        blankToNull(entry.cannedScript()), null));
            }
        }
        return views;
    }

    /** Why a Comment required validator on this transition cannot work, or null when the transition has its screen. */
    private String problem(com.opensymphony.workflow.loader.ActionDescriptor action, String screenId) {
        if (CommentRequiredDescriptor.screenUsable(action, screens)) return null;
        return CommentRequiredDescriptor.screenFound(screenId, screens) ? NO_VIEW_PROBLEM : NO_SCREEN_PROBLEM;
    }

    private String screenName(String screenId) {
        if (screenId == null) return null;
        try {
            FieldScreen screen = screens.getFieldScreen(Long.parseLong(screenId.trim()));
            return screen == null ? null : screen.getName();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
