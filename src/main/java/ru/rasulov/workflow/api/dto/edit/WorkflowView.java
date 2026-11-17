package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * What {@code get_workflow} returns: the graph of one version of a workflow with the stable ids an edit needs, what an
 * edit may do to each part and why not, who uses it, and how the draft differs from the active version. No XML and no
 * arguments of third-party functions are returned.
 */
public final class WorkflowView {

    private final String workflow;
    private final String version;
    private final String revision;
    private final Long nativeId;
    private final String description;
    private final boolean system;
    private final boolean active;
    private final boolean hasDraft;
    private final List<StepView> steps;
    private final List<TransitionView> transitions;
    private final List<String> capabilities;
    private final Consumers consumers;
    private final List<String> draftDiff;

    public WorkflowView(String workflow, String version, String revision, Long nativeId, String description,
                        boolean system, boolean active, boolean hasDraft, List<StepView> steps,
                        List<TransitionView> transitions, List<String> capabilities, Consumers consumers,
                        List<String> draftDiff) {
        this.workflow = workflow;
        this.version = version;
        this.revision = revision;
        this.nativeId = nativeId;
        this.description = description;
        this.system = system;
        this.active = active;
        this.hasDraft = hasDraft;
        this.steps = Immutables.listOf(steps);
        this.transitions = Immutables.listOf(transitions);
        this.capabilities = Immutables.listOf(capabilities);
        this.consumers = consumers;
        this.draftDiff = Immutables.listOf(draftDiff);
    }

    @JsonProperty("workflow") public String getWorkflow() { return workflow; }
    @JsonProperty("version") public String getVersion() { return version; }
    /** Pass this as {@code expectedRevision} of configure_workflow.existing. */
    @JsonProperty("revision") public String getRevision() { return revision; }
    @JsonProperty("nativeId") public Long getNativeId() { return nativeId; }
    @JsonProperty("description") public String getDescription() { return description; }
    @JsonProperty("system") public boolean isSystem() { return system; }
    @JsonProperty("active") public boolean isActive() { return active; }
    @JsonProperty("hasDraft") public boolean isHasDraft() { return hasDraft; }
    @JsonProperty("steps") public List<StepView> getSteps() { return steps; }
    @JsonProperty("transitions") public List<TransitionView> getTransitions() { return transitions; }
    /** Which structural configure_workflow changes are supported and the reason for each refusal. */
    @JsonProperty("capabilities") public List<String> getCapabilities() { return capabilities; }
    @JsonProperty("consumers") public Consumers getConsumers() { return consumers; }
    /** Active to draft, line by line; empty when there is no draft or it equals the active version. */
    @JsonProperty("draftDiff") public List<String> getDraftDiff() { return draftDiff; }

    /** One step of the graph. */
    public static final class StepView {
        private final int stepId;
        private final String statusId;
        private final String name;
        private final String category;
        private final String description;
        private final boolean initial;

        public StepView(int stepId, String statusId, String name, String category, String description,
                        boolean initial) {
            this.stepId = stepId;
            this.statusId = statusId;
            this.name = name;
            this.category = category;
            this.description = description;
            this.initial = initial;
        }

        @JsonProperty("stepId") public int getStepId() { return stepId; }
        @JsonProperty("statusId") public String getStatusId() { return statusId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("category") public String getCategory() { return category; }
        @JsonProperty("description") public String getDescription() { return description; }
        @JsonProperty("initial") public boolean isInitial() { return initial; }
    }

    /** One transition. {@code kind}: INITIAL, LOCAL, GLOBAL or COMMON. */
    public static final class TransitionView {
        private final int actionId;
        private final String kind;
        private final String name;
        private final List<Integer> sourceStepIds;
        private final List<Integer> targetStepIds;
        private final String screenId;
        private final Rule resolution;
        private final List<String> unsupported;

        public TransitionView(int actionId, String kind, String name, List<Integer> sourceStepIds,
                              List<Integer> targetStepIds, String screenId, Rule resolution,
                              List<String> unsupported) {
            this.actionId = actionId;
            this.kind = kind;
            this.name = name;
            this.sourceStepIds = Immutables.listOf(sourceStepIds);
            this.targetStepIds = Immutables.listOf(targetStepIds);
            this.screenId = screenId;
            this.resolution = resolution;
            this.unsupported = Immutables.listOf(unsupported);
        }

        @JsonProperty("actionId") public int getActionId() { return actionId; }
        @JsonProperty("kind") public String getKind() { return kind; }
        @JsonProperty("name") public String getName() { return name; }
        /** Empty for GLOBAL and INITIAL. */
        @JsonProperty("sourceStepIds") public List<Integer> getSourceStepIds() { return sourceStepIds; }
        /** More than one target means conditional results. */
        @JsonProperty("targetStepIds") public List<Integer> getTargetStepIds() { return targetStepIds; }
        @JsonProperty("screenId") public String getScreenId() { return screenId; }
        @JsonProperty("resolution") public Rule getResolution() { return resolution; }
        /** Why edits of some fields of this transition are refused; empty when all are possible. */
        @JsonProperty("unsupported") public List<String> getUnsupported() { return unsupported; }
    }

    /** The resolution rule of a transition: SET (value), CLEAR, PRESERVE (nothing writes it) or UNKNOWN (reason). */
    public static final class Rule {
        private final String action;
        private final String value;
        private final String name;
        private final String reason;

        public Rule(String action, String value, String name, String reason) {
            this.action = action;
            this.value = value;
            this.name = name;
            this.reason = reason;
        }

        @JsonProperty("action") public String getAction() { return action; }
        @JsonProperty("value") public String getValue() { return value; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("reason") public String getReason() { return reason; }
    }

    /** Everything that uses the workflow; counts are complete, the lists are bounded. */
    public static final class Consumers {
        private final int schemeCount;
        private final int projectCount;
        private final List<String> schemes;
        private final List<String> projects;
        private final boolean truncated;

        public Consumers(int schemeCount, int projectCount, List<String> schemes, List<String> projects,
                         boolean truncated) {
            this.schemeCount = schemeCount;
            this.projectCount = projectCount;
            this.schemes = Immutables.listOf(schemes);
            this.projects = Immutables.listOf(projects);
            this.truncated = truncated;
        }

        @JsonProperty("schemeCount") public int getSchemeCount() { return schemeCount; }
        @JsonProperty("projectCount") public int getProjectCount() { return projectCount; }
        /** Scheme name, id and the issue types it maps to the workflow. Includes scheme drafts. */
        @JsonProperty("schemes") public List<String> getSchemes() { return schemes; }
        @JsonProperty("projects") public List<String> getProjects() { return projects; }
        @JsonProperty("truncated") public boolean isTruncated() { return truncated; }
    }
}
