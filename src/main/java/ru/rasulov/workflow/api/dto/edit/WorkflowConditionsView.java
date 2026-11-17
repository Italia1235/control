package ru.rasulov.workflow.api.dto.edit;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * Output of {@code get_workflow_conditions}: the conditions of every transition of one version of a workflow as a tree
 * in descriptor order. Only two conditions are read into fields, each in the exact form that was captured on a stand:
 * the Jira "User in group" condition and the ScriptRunner "Project role(s) condition". Every other condition is reported
 * by its type and class only, never by its arguments.
 */
public final class WorkflowConditionsView {
    /** A Jira {@code UserInGroupCondition} in the captured form. */
    public static final String GROUP = "GROUP";
    /** A ScriptRunner "Project role(s) condition" in the captured form. */
    public static final String PROJECT_ROLE = "PROJECT_ROLE";
    /** A nested or root group of conditions joined by AND or OR. */
    public static final String COMPOUND = "COMPOUND";
    /** Every other condition. */
    public static final String OTHER = "OTHER";

    private final String workflow;
    private final String version;
    private final String revision;
    private final ScriptRunnerStatus scriptRunner;
    private final List<TransitionConditions> transitions;

    public WorkflowConditionsView(String workflow, String version, String revision, ScriptRunnerStatus scriptRunner,
                                  List<TransitionConditions> transitions) {
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
    @JsonProperty("transitions") public List<TransitionConditions> getTransitions() { return transitions; }

    /** One transition with the root of its conditions; {@code root} is null when the transition has none. */
    public static final class TransitionConditions {
        private final int actionId;
        private final String name;
        private final ConditionNode root;

        public TransitionConditions(int actionId, String name, ConditionNode root) {
            this.actionId = actionId;
            this.name = name;
            this.root = root;
        }

        @JsonProperty("actionId") public int getActionId() { return actionId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("root") public ConditionNode getRoot() { return root; }
    }

    /** A role of a PROJECT_ROLE node; {@code name} is null when Jira has no such role any more. */
    public static final class RoleRef {
        private final long id;
        private final String name;

        public RoleRef(long id, String name) {
            this.id = id;
            this.name = name;
        }

        @JsonProperty("id") public long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
    }

    /**
     * One node. {@code position} counts from 1 inside its parent (the root is 1). GROUP fills {@code group};
     * PROJECT_ROLE fills {@code roleIds}, {@code roles} and {@code inverted}; COMPOUND fills {@code operator} and
     * {@code children}; OTHER fills {@code type}, {@code className} and {@code cannedScript}. The keys that do not
     * apply are null.
     */
    public static final class ConditionNode {
        private final int position;
        private final String kind;
        private final String operator;
        private final List<ConditionNode> children;
        private final String group;
        private final List<Long> roleIds;
        private final List<RoleRef> roles;
        private final Boolean inverted;
        private final String type;
        private final String className;
        private final String cannedScript;

        private ConditionNode(int position, String kind, String operator, List<ConditionNode> children, String group,
                              List<Long> roleIds, List<RoleRef> roles, Boolean inverted, String type, String className,
                              String cannedScript) {
            this.position = position;
            this.kind = kind;
            this.operator = operator;
            this.children = children == null ? null : Immutables.listOf(children);
            this.group = group;
            this.roleIds = roleIds == null ? null : Immutables.listOf(roleIds);
            this.roles = roles == null ? null : Immutables.listOf(roles);
            this.inverted = inverted;
            this.type = type;
            this.className = className;
            this.cannedScript = cannedScript;
        }

        public static ConditionNode compound(int position, String operator, List<ConditionNode> children) {
            return new ConditionNode(position, COMPOUND, operator, children, null, null, null, null, null, null, null);
        }

        public static ConditionNode group(int position, String group) {
            return new ConditionNode(position, GROUP, null, null, group, null, null, null, null, null, null);
        }

        public static ConditionNode projectRole(int position, List<Long> roleIds, List<RoleRef> roles, boolean inverted) {
            return new ConditionNode(position, PROJECT_ROLE, null, null, null, roleIds, roles, inverted, null, null, null);
        }

        public static ConditionNode other(int position, String type, String className, String cannedScript) {
            return new ConditionNode(position, OTHER, null, null, null, null, null, null, type, className, cannedScript);
        }

        @JsonProperty("position") public int getPosition() { return position; }
        @JsonProperty("kind") public String getKind() { return kind; }
        /** COMPOUND: {@code AND} or {@code OR}. */
        @JsonProperty("operator") public String getOperator() { return operator; }
        @JsonProperty("children") public List<ConditionNode> getChildren() { return children; }
        @JsonProperty("group") public String getGroup() { return group; }
        @JsonProperty("roleIds") public List<Long> getRoleIds() { return roleIds; }
        @JsonProperty("roles") public List<RoleRef> getRoles() { return roles; }
        /** PROJECT_ROLE: true when the condition allows users who are NOT members of the roles. */
        @JsonProperty("inverted") public Boolean getInverted() { return inverted; }
        @JsonProperty("type") public String getType() { return type; }
        @JsonProperty("className") public String getClassName() { return className; }
        @JsonProperty("cannedScript") public String getCannedScript() { return cannedScript; }
    }
}
