package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import ru.rasulov.workflow.api.dto.assign.SchemeAction;
import ru.rasulov.workflow.api.dto.assign.SchemeView;
import ru.rasulov.workflow.api.dto.clone.CloneScope;
import ru.rasulov.workflow.api.dto.clone.CloneVerdict;
import ru.rasulov.workflow.api.dto.clone.WorkflowCloneView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What a clone would do, worked out without writing anything: dry run and real call share it. */
final class ClonePlan {

    /** One workflow of the scheme and what happens to it. */
    static final class PlannedWorkflow {
        private final String name;
        private final JiraWorkflow source;
        private final String newName;
        private final CloneVerdict verdict;
        private final int otherProjects;
        private final List<String> otherProjectNames;
        private final boolean sourceHasDraft;

        PlannedWorkflow(String name, JiraWorkflow source, String newName, CloneVerdict verdict, int otherProjects,
                        List<String> otherProjectNames, boolean sourceHasDraft) {
            this.name = name;
            this.source = source;
            this.newName = newName;
            this.verdict = verdict;
            this.otherProjects = otherProjects;
            this.otherProjectNames = otherProjectNames;
            this.sourceHasDraft = sourceHasDraft;
        }

        String getName() {
            return name;
        }

        JiraWorkflow getSource() {
            return source;
        }

        /** {@code null} unless the verdict is {@code COPY}. */
        String getNewName() {
            return newName;
        }

        CloneVerdict getVerdict() {
            return verdict;
        }

        int otherProjects() {
            return otherProjects;
        }

        List<String> otherProjectNames() {
            return otherProjectNames;
        }

        /** Whether the source has a draft, which a copy leaves out; only looked up for the verdict {@code COPY}. */
        boolean sourceHasDraft() {
            return sourceHasDraft;
        }
    }

    private final CloneScope scope;
    private final SchemeAction action;
    private final AssignableWorkflowScheme scheme;
    private final SchemeView currentScheme;
    private final String newSchemeName;
    private final SchemeEdit edit;
    private final List<PlannedWorkflow> workflows;
    private final Map<String, String> leaving;
    private final List<String> warnings;

    ClonePlan(CloneScope scope, SchemeAction action, AssignableWorkflowScheme scheme, SchemeView currentScheme,
              String newSchemeName, SchemeEdit edit, List<PlannedWorkflow> workflows, Map<String, String> leaving,
              List<String> warnings) {
        this.scope = scope;
        this.action = action;
        this.scheme = scheme;
        this.currentScheme = currentScheme;
        this.newSchemeName = newSchemeName;
        this.edit = edit;
        this.workflows = Collections.unmodifiableList(new ArrayList<PlannedWorkflow>(workflows));
        this.leaving = Collections.unmodifiableMap(new LinkedHashMap<String, String>(leaving));
        this.warnings = Collections.unmodifiableList(new ArrayList<String>(warnings));
    }

    CloneScope getScope() {
        return scope;
    }

    /** {@code CREATE}: a copy of the scheme; {@code UPDATE}: the project's own scheme changes; {@code NONE}. */
    SchemeAction getAction() {
        return action;
    }

    /** The project's scheme as it is now. */
    AssignableWorkflowScheme getScheme() {
        return scheme;
    }

    SchemeView getCurrentScheme() {
        return currentScheme;
    }

    String getNewSchemeName() {
        return newSchemeName;
    }

    SchemeEdit getEdit() {
        return edit;
    }

    List<String> getWarnings() {
        return warnings;
    }

    /** The workflows that get a copy. */
    List<PlannedWorkflow> toCopy() {
        List<PlannedWorkflow> copies = new ArrayList<PlannedWorkflow>();
        for (PlannedWorkflow workflow : workflows) {
            if (workflow.getVerdict() == CloneVerdict.COPY) {
                copies.add(workflow);
            }
        }
        return copies;
    }

    /** Issue type id to the workflow its issues leave, for the types whose workflow gets a copy. */
    Map<String, String> workflowsToLeave() {
        return leaving;
    }

    List<WorkflowCloneView> views() {
        List<WorkflowCloneView> views = new ArrayList<WorkflowCloneView>();
        for (PlannedWorkflow workflow : workflows) {
            views.add(new WorkflowCloneView(workflow.name, workflow.newName, workflow.verdict, workflow.otherProjects,
                    workflow.otherProjectNames));
        }
        return views;
    }
}
