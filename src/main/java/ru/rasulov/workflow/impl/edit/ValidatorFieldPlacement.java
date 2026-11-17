package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.operation.ScreenableIssueOperation;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowScheme;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.workflow.api.dto.edit.ValidatorFieldRef;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Warnings for fields that a required-fields validator would demand but a user may not be able to fill in: not on the
 * transition screen and on no create/edit/view screen of a project and issue type that use the workflow, or hidden in
 * the field configuration of such a pair. Read only; a warning never blocks a plan.
 */
@Named
public class ValidatorFieldPlacement {

    private static final List<ScreenableIssueOperation> OPERATIONS = Arrays.asList(IssueOperations.CREATE_ISSUE_OPERATION,
            IssueOperations.EDIT_ISSUE_OPERATION, IssueOperations.VIEW_ISSUE_OPERATION);

    private final WorkflowSchemeManager workflowSchemes;
    private final IssueTypeScreenSchemeManager screenSchemes;
    private final FieldLayoutManager layouts;
    private final FieldScreenManager screens;

    @Inject
    public ValidatorFieldPlacement(@ComponentImport WorkflowSchemeManager workflowSchemes,
                                   @ComponentImport IssueTypeScreenSchemeManager screenSchemes,
                                   @ComponentImport FieldLayoutManager layouts,
                                   @ComponentImport FieldScreenManager screens) {
        this.workflowSchemes = workflowSchemes;
        this.screenSchemes = screenSchemes;
        this.layouts = layouts;
        this.screens = screens;
    }

    private record Pair(Project project, IssueType type) {
        String label() {
            return project.getKey() + " / " + type.getName();
        }
    }

    /** @param transitionScreenId the {@code jira.fieldscreen.id} of the transition, or null when it has no screen */
    List<String> warnings(JiraWorkflow main, String transitionScreenId, List<ValidatorFieldRef> added) {
        return warnings(main, transitionScreenId, added, null);
    }

    List<String> warnings(JiraWorkflow main, String transitionScreenId, List<ValidatorFieldRef> added,
                          java.util.Set<String> plannedFields) {
        List<String> warnings = new ArrayList<String>();
        List<Pair> pairs = pairs(main);
        List<ValidatorFieldRef> offTransition = new ArrayList<ValidatorFieldRef>();
        for (ValidatorFieldRef field : added) {
            if (plannedFields == null ? !transitionScreenHolds(transitionScreenId, field.getId())
                    : !plannedFields.contains(field.getId())) offTransition.add(field);
        }
        if (pairs.isEmpty()) {
            for (ValidatorFieldRef field : offTransition) {
                warnings.add("The workflow is not assigned to any project and issue type, so only the transition screen "
                        + "was checked: " + describe(field) + " is not on it, and a user may be unable to fill it in.");
            }
            return warnings;
        }
        List<String> unreadable = new ArrayList<String>();
        Map<String, List<String>> offEveryScreen = new LinkedHashMap<String, List<String>>();
        Map<String, List<String>> hidden = new LinkedHashMap<String, List<String>>();
        for (Pair pair : pairs) {
            try {
                for (ValidatorFieldRef field : offTransition) {
                    if (!onAnyScreen(pair, field.getId())) {
                        offEveryScreen.computeIfAbsent(field.getId(), k -> new ArrayList<String>()).add(pair.label());
                    }
                }
                for (ValidatorFieldRef field : added) {
                    if (layouts.getFieldLayout(pair.project(), pair.type().getId()).isFieldHidden(field.getId())) {
                        hidden.computeIfAbsent(field.getId(), k -> new ArrayList<String>()).add(pair.label());
                    }
                }
            } catch (RuntimeException | LinkageError e) {
                unreadable.add(pair.label());
            }
        }
        for (ValidatorFieldRef field : offTransition) {
            List<String> where = offEveryScreen.get(field.getId());
            if (where != null) {
                warnings.add(describe(field) + " is not on the transition screen and not on a create, edit or view "
                        + "screen of: " + String.join(", ", where) + ". A user may be unable to fill it in there.");
            }
        }
        for (ValidatorFieldRef field : added) {
            List<String> where = hidden.get(field.getId());
            if (where != null) {
                warnings.add(describe(field) + " is hidden in the field configuration of: " + String.join(", ", where)
                        + ". A user cannot fill it in there.");
            }
        }
        for (String label : unreadable) {
            warnings.add("The screens or the field configuration of " + label + " could not be read, so the placement "
                    + "of the added fields there was not checked.");
        }
        return warnings;
    }
    /** Exact relevant placement observations, not merely the text of warnings that happened to be emitted. */
    Object facts(JiraWorkflow main, List<ValidatorFieldRef> required) {
        List<Object> observations = new ArrayList<>();
        for (Pair pair : pairs(main)) {
            Map<String,Object> state = new LinkedHashMap<>();
            state.put("project", pair.project().getId()); state.put("key", pair.project().getKey());
            state.put("type", pair.type().getId()); state.put("typeName", pair.type().getName());
            Map<String,Object> fieldStates = new LinkedHashMap<>();
            try {
                for (ValidatorFieldRef field : required) {
                    fieldStates.put(field.getId(), java.util.Arrays.asList(
                            layouts.getFieldLayout(pair.project(), pair.type().getId()).isFieldHidden(field.getId()),
                            onAnyScreen(pair, field.getId())));
                }
                state.put("fields", fieldStates);
            } catch (RuntimeException | LinkageError e) {
                state.put("unavailable", e.getClass().getName());
            }
            observations.add(state);
        }
        return observations;
    }


    private static String describe(ValidatorFieldRef field) {
        return "Field '" + (field.getName() == null ? field.getId() : field.getName()) + "' (" + field.getId() + ")";
    }

    /** Pairs of project and issue type whose actual workflow, through an assigned scheme, is {@code main}. */
    private List<Pair> pairs(JiraWorkflow main) {
        Map<String, Pair> found = new LinkedHashMap<String, Pair>();
        for (WorkflowScheme scheme : workflowSchemes.getSchemesForWorkflowIncludingDrafts(main)) {
            if (!(scheme instanceof AssignableWorkflowScheme)) continue;
            AssignableWorkflowScheme assigned = (AssignableWorkflowScheme) scheme;
            for (Project project : workflowSchemes.getProjectsUsing(assigned)) {
                for (IssueType type : project.getIssueTypes()) {
                    if (main.getName().equals(assigned.getActualWorkflow(type.getId()))) {
                        Pair pair = new Pair(project, type);
                        found.putIfAbsent(project.getId() + "/" + type.getId(), pair);
                    }
                }
            }
        }
        return new ArrayList<Pair>(found.values());
    }

    private boolean transitionScreenHolds(String screenId, String fieldId) {
        if (screenId == null) return false;
        long id;
        try {
            id = Long.parseLong(screenId.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        FieldScreen screen = screens.getFieldScreen(id);
        return screen != null && screen.containsField(fieldId);
    }

    /** The field is on the create, edit or view screen of the pair; a scheme item without operation is the default. */
    private boolean onAnyScreen(Pair pair, String fieldId) {
        IssueTypeScreenScheme top = screenSchemes.getIssueTypeScreenScheme(pair.project());
        if (top == null) throw new IllegalStateException("no issue type screen scheme");
        FieldScreenScheme scheme = top.getEffectiveFieldScreenScheme(pair.type());
        if (scheme == null) throw new IllegalStateException("no screen scheme");
        for (ScreenableIssueOperation operation : OPERATIONS) {
            FieldScreen screen = screenOf(scheme, operation);
            if (screen != null && screen.containsField(fieldId)) return true;
        }
        return false;
    }

    private static FieldScreen screenOf(FieldScreenScheme scheme, ScreenableIssueOperation operation) {
        FieldScreen fallback = null;
        for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
            if (item.getIssueOperation() == null) {
                fallback = item.getFieldScreen();
            } else if (Objects.equals(item.getIssueOperation(), operation)) {
                return item.getFieldScreen();
            }
        }
        return fallback;
    }
}
