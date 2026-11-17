package ru.rasulov.workflow.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.status.Status;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.workflow.api.dto.TransitionResolution;

import java.util.List;

/**
 * A request after validation: statuses matched to the ones Jira has (or marked new), transitions pointing at those
 * statuses, warnings for the user. Nothing here is written to Jira yet.
 */
public final class ResolvedWorkflow {

    private final String name;
    private final String description;
    private final List<WorkflowStatus> statuses;
    private final WorkflowStatus initial;
    private final List<WorkflowTransition> transitions;
    private final List<String> warnings;

    ResolvedWorkflow(String name, String description, List<WorkflowStatus> statuses, WorkflowStatus initial,
                     List<WorkflowTransition> transitions, List<String> warnings) {
        this.name = name;
        this.description = description;
        this.statuses = Immutables.listOf(statuses);
        this.initial = initial;
        this.transitions = Immutables.listOf(transitions);
        this.warnings = Immutables.listOf(warnings);
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    /** In the order of the request; the position is the step number in the workflow. */
    public List<WorkflowStatus> getStatuses() {
        return statuses;
    }

    public WorkflowStatus getInitial() {
        return initial;
    }

    public List<WorkflowTransition> getTransitions() {
        return transitions;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    /** A status of the workflow: an existing Jira status, or one this call has to create. */
    public static final class WorkflowStatus {
        private final String name;
        private final Status existing;
        private final String category;
        private final String description;

        /**
         * @param existing    the Jira status, or {@code null} when it has to be created
         * @param category    {@code TO_DO}, {@code IN_PROGRESS}, {@code DONE} or {@code UNDEFINED}
         * @param description of a new status; ignored for an existing one
         */
        public WorkflowStatus(String name, Status existing, String category, String description) {
            this.name = name;
            this.existing = existing;
            this.category = category;
            this.description = description;
        }

        public String getName() {
            return name;
        }

        /** {@code null} for a status that does not exist yet. */
        public Status getExisting() {
            return existing;
        }

        public boolean isNew() {
            return existing == null;
        }

        public String getCategory() {
            return category;
        }

        public String getDescription() {
            return description;
        }
    }

    /** A transition between two statuses of the workflow; {@code from} is {@code null} for "from any status". */
    public static final class WorkflowTransition {
        private final String name;
        private final WorkflowStatus from;
        private final WorkflowStatus to;
        private final FieldScreen screen;
        private final TransitionResolution resolution;

        WorkflowTransition(String name, WorkflowStatus from, WorkflowStatus to, FieldScreen screen,
                           TransitionResolution resolution) {
            this.name = name;
            this.from = from;
            this.to = to;
            this.screen = screen;
            this.resolution = resolution;
        }

        public String getName() {
            return name;
        }

        /** {@code null}: the transition is available from every status. */
        public WorkflowStatus getFrom() {
            return from;
        }

        public boolean isFromAny() {
            return from == null;
        }

        public WorkflowStatus getTo() {
            return to;
        }

        public FieldScreen getScreen() {
            return screen;
        }

        public TransitionResolution getResolution() {
            return resolution;
        }
    }
}
