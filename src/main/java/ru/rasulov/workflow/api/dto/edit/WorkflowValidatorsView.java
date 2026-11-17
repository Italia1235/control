package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * Output of {@code get_workflow_validators}: the validators of every transition of one version of a workflow, in
 * descriptor order. Only the required-fields validator of ScriptRunner is read into fields; every other validator is
 * reported by its type and class only, never by its arguments.
 */
public final class WorkflowValidatorsView {
    /** Kind of a ScriptRunner "Field(s) required" validator in the exact captured form. */
    public static final String REQUIRED_FIELDS = "REQUIRED_FIELDS";
    /** Kind of a ScriptRunner "Comment required" validator in the exact captured form. */
    public static final String COMMENT_REQUIRED = "COMMENT_REQUIRED";
    /** Kind of every other validator. */
    public static final String OTHER = "OTHER";

    private final String workflow;
    private final String version;
    private final String revision;
    private final ScriptRunnerStatus scriptRunner;
    private final List<TransitionValidators> transitions;

    public WorkflowValidatorsView(String workflow, String version, String revision, ScriptRunnerStatus scriptRunner,
                                  List<TransitionValidators> transitions) {
        this.workflow = workflow;
        this.version = version;
        this.revision = revision;
        this.scriptRunner = scriptRunner;
        this.transitions = Immutables.listOf(transitions);
    }

    @JsonProperty("workflow") public String getWorkflow() { return workflow; }
    @JsonProperty("version") public String getVersion() { return version; }
    /** The revision of get_workflow for the same workflow: pass it as expectedRevision. */
    @JsonProperty("revision") public String getRevision() { return revision; }
    @JsonProperty("scriptRunner") public ScriptRunnerStatus getScriptRunner() { return scriptRunner; }
    @JsonProperty("transitions") public List<TransitionValidators> getTransitions() { return transitions; }

    /** One transition with its validators. */
    public static final class TransitionValidators {
        private final int actionId;
        private final String name;
        private final String screenId;
        private final String screenName;
        private final List<ValidatorView> validators;

        public TransitionValidators(int actionId, String name, String screenId, String screenName,
                                    List<ValidatorView> validators) {
            this.actionId = actionId;
            this.name = name;
            this.screenId = screenId;
            this.screenName = screenName;
            this.validators = Immutables.listOf(validators);
        }

        @JsonProperty("actionId") public int getActionId() { return actionId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("screenId") public String getScreenId() { return screenId; }
        @JsonProperty("screenName") public String getScreenName() { return screenName; }
        @JsonProperty("validators") public List<ValidatorView> getValidators() { return validators; }
    }

    /**
     * One validator. {@code position} counts from 1 in descriptor order. {@code REQUIRED_FIELDS} fills fieldIds, fields
     * and conditional; {@code OTHER} fills type, className and cannedScript; {@code COMMENT_REQUIRED} fills none of
     * them but may carry {@code problem}. The keys that do not apply are null.
     */
    public static final class ValidatorView {
        private final int position;
        private final String kind;
        private final List<String> fieldIds;
        private final List<ValidatorFieldRef> fields;
        private final Boolean conditional;
        private final String type;
        private final String className;
        private final String cannedScript;
        private final String problem;

        public ValidatorView(int position, String kind, List<String> fieldIds, List<ValidatorFieldRef> fields,
                             Boolean conditional, String type, String className, String cannedScript, String problem) {
            this.position = position;
            this.kind = kind;
            this.fieldIds = fieldIds == null ? null : Immutables.listOf(fieldIds);
            this.fields = fields == null ? null : Immutables.listOf(fields);
            this.conditional = conditional;
            this.type = type;
            this.className = className;
            this.cannedScript = cannedScript;
            this.problem = problem;
        }

        @JsonProperty("position") public int getPosition() { return position; }
        @JsonProperty("kind") public String getKind() { return kind; }
        @JsonProperty("fieldIds") public List<String> getFieldIds() { return fieldIds; }
        @JsonProperty("fields") public List<ValidatorFieldRef> getFields() { return fields; }
        @JsonProperty("conditional") public Boolean getConditional() { return conditional; }
        @JsonProperty("type") public String getType() { return type; }
        @JsonProperty("className") public String getClassName() { return className; }
        @JsonProperty("cannedScript") public String getCannedScript() { return cannedScript; }
        /** A diagnosis of a validator that cannot work as it stands; null when there is none. */
        @JsonProperty("problem") public String getProblem() { return problem; }
    }
}
