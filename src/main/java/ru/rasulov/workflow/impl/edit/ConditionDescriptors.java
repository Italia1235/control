package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ConditionDescriptor;
import com.opensymphony.workflow.loader.ConditionsDescriptor;
import com.opensymphony.workflow.loader.DescriptorFactory;
import com.opensymphony.workflow.loader.RestrictionDescriptor;
import ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.ConditionNode;
import ru.rasulov.workflow.api.dto.edit.WorkflowConditionsView.RoleRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.regex.Pattern;

/**
 * The two conditions that Control reads and writes, as they stand in a workflow descriptor: the Jira "User in group"
 * condition and the ScriptRunner "Project role(s) condition", each in exactly the form that was exported from a stand.
 * How they are recognized, read as a tree, compared for idempotence and appended to the root of a transition. Knows only
 * the descriptor, never ScriptRunner.
 */
final class ConditionDescriptors {

    static final String GROUP_CLASS = "com.atlassian.jira.workflow.condition.UserInGroupCondition";
    static final String ROLE_CANNED = "com.onresolve.scriptrunner.canned.jira.workflow.conditions.ProjectRoleCondition";
    static final String ROLE_CLASS = "com.onresolve.jira.groovy.GroovyCondition";
    /** Joins several role ids in {@code FIELD_PROJECT_ROLE_IDS}, as captured on ScriptRunner 9.46.0. */
    static final String SEPARATOR = "|||";
    /** The value of {@code FIELD_INVERT_CONDITION} when the box is ticked, as captured; unticked is the empty string. */
    static final String INVERT_ON = "on";

    private static final String ARG_CLASS = "class.name";
    private static final String ARG_GROUP = "group";
    private static final String ARG_ROLES = "FIELD_PROJECT_ROLE_IDS";
    private static final String ARG_FUNCTION_ID = "FIELD_FUNCTION_ID";
    private static final String ARG_INVERT = "FIELD_INVERT_CONDITION";
    private static final String ARG_CANNED = "canned-script";
    private static final String TYPE_CLASS = "class";
    private static final String AND = "AND";
    private static final String OR = "OR";
    private static final Pattern ROLE_ID = Pattern.compile("[0-9]{1,18}");

    /** The arguments of the captured forms: no other argument, no other type. */
    private static final Set<String> GROUP_ARGS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(ARG_CLASS, ARG_GROUP)));
    private static final Set<String> ROLE_ARGS = Collections.unmodifiableSet(new LinkedHashSet<String>(
            Arrays.asList(ARG_ROLES, ARG_FUNCTION_ID, ARG_INVERT, ARG_CANNED, ARG_CLASS)));

    enum Outcome { ADD, UNCHANGED, UNSUPPORTED }

    /** What is to be added: a group name, or role ids (in the order given) with invert. */
    record Candidate(String group, List<Long> roleIds, boolean invert) {
        static Candidate group(String group) {
            return new Candidate(group, null, false);
        }

        static Candidate roles(List<Long> roleIds, boolean invert) {
            return new Candidate(null, List.copyOf(roleIds), invert);
        }

        boolean isGroup() {
            return group != null;
        }
    }

    /** {@code reason} says why the descriptor is refused; {@code groupInRoot}: a GROUP is a direct child of the AND root. */
    record Check(Outcome outcome, String reason, boolean groupInRoot) { }

    /** A recognized ScriptRunner role condition. */
    private record RoleForm(List<Long> ids, boolean inverted) { }

    private ConditionDescriptors() { }

    // ---- reading ------------------------------------------------------------------------------------------------

    /** The root of the transition's conditions as a tree; null when the transition has none. */
    static ConditionNode read(ActionDescriptor action, LongFunction<String> roleName) {
        ConditionsDescriptor root = root(action);
        return root == null ? null : compound(root, 1, roleName);
    }

    private static ConditionNode compound(ConditionsDescriptor group, int position, LongFunction<String> roleName) {
        List<ConditionNode> children = new ArrayList<ConditionNode>();
        int index = 0;
        for (Object child : children(group)) {
            index++;
            children.add(child instanceof ConditionsDescriptor
                    ? compound((ConditionsDescriptor) child, index, roleName)
                    : leaf((ConditionDescriptor) child, index, roleName));
        }
        return ConditionNode.compound(position, operator(group), children);
    }

    private static ConditionNode leaf(ConditionDescriptor condition, int position, LongFunction<String> roleName) {
        String group = capturedGroup(condition);
        if (group != null) return ConditionNode.group(position, group);
        RoleForm role = capturedRole(condition);
        if (role != null) return describe(Candidate.roles(role.ids(), role.inverted()), position, roleName);
        Map<?, ?> args = condition.getArgs();
        return ConditionNode.other(position, blankToNull(condition.getType()), text(args.get(ARG_CLASS)),
                text(args.get(ARG_CANNED)));
    }

    /** The node that reading the descriptor gives for a condition of this candidate, once it has been added. */
    static ConditionNode describe(Candidate candidate, int position, LongFunction<String> roleName) {
        if (candidate.isGroup()) return ConditionNode.group(position, candidate.group());
        List<RoleRef> refs = new ArrayList<RoleRef>();
        for (Long id : candidate.roleIds()) refs.add(new RoleRef(id, roleName.apply(id)));
        return ConditionNode.projectRole(position, candidate.roleIds(), refs, candidate.invert());
    }

    // ---- checking and adding ------------------------------------------------------------------------------------

    /** Whether the candidate would be added, is already there, or the root cannot take it. Never changes the descriptor. */
    static Check check(ActionDescriptor action, Candidate candidate) {
        ConditionsDescriptor root = root(action);
        if (root == null) return new Check(Outcome.ADD, null, false);
        String type = root.getType();
        if (OR.equals(type)) {
            return new Check(Outcome.UNSUPPORTED, "The conditions of this transition are joined by OR: a condition added "
                    + "to an OR root would widen access to the transition instead of narrowing it, so nothing is added "
                    + "(Control only adds conditions that narrow access; change an OR root in the Jira workflow editor).",
                    false);
        }
        if (!isAnd(type)) {
            return new Check(Outcome.UNSUPPORTED, "The conditions of this transition are joined by '" + type + "', which "
                    + "is neither AND nor OR, so nothing is added.", false);
        }
        boolean groupInRoot = false;
        boolean present = false;
        for (Object child : children(root)) {
            if (!(child instanceof ConditionDescriptor)) continue;
            ConditionDescriptor condition = (ConditionDescriptor) child;
            String group = capturedGroup(condition);
            if (group != null) {
                groupInRoot = true;
                if (candidate.isGroup() && group.equalsIgnoreCase(candidate.group())) present = true;
            }
            RoleForm role = capturedRole(condition);
            if (role != null && !candidate.isGroup() && role.inverted() == candidate.invert()
                    && new HashSet<Long>(role.ids()).equals(new HashSet<Long>(candidate.roleIds()))) {
                present = true;
            }
        }
        return new Check(present ? Outcome.UNCHANGED : Outcome.ADD, null, groupInRoot);
    }

    /**
     * Appends one condition in the captured form at the end of the AND root; creates the root ({@code type="AND"}) when
     * the transition has no conditions. Existing conditions are not touched. A root without a type is AND already, but
     * Jira writes {@code type=""} (which it cannot read back) for an untyped root of two or more conditions, so such a
     * root gets the explicit {@code type="AND"}: the same meaning, and the form Jira itself writes. (Jira omits the type
     * of a root with a single condition when it saves; the root is still read as AND.) Returns the 1-based position of
     * the new condition in the root.
     */
    @SuppressWarnings("unchecked")
    static int apply(ActionDescriptor action, Candidate candidate, String functionId) {
        DescriptorFactory factory = DescriptorFactory.getFactory();
        RestrictionDescriptor restriction = action.getRestriction();
        if (restriction == null) {
            restriction = new RestrictionDescriptor();
            restriction.setParent(action);
            action.setRestriction(restriction);
        }
        ConditionsDescriptor root = restriction.getConditionsDescriptor();
        if (root == null) {
            root = factory.createConditionsDescriptor();
            root.setType(AND);
            root.setParent(restriction);
            restriction.setConditionsDescriptor(root);
        } else if (root.getType() == null || root.getType().isEmpty()) {
            root.setType(AND);
        }
        ConditionDescriptor created = factory.createConditionDescriptor();
        created.setType(TYPE_CLASS);
        created.setParent(root);
        Map<String, String> args = created.getArgs();
        if (candidate.isGroup()) {
            args.put(ARG_CLASS, GROUP_CLASS);
            args.put(ARG_GROUP, candidate.group());
        } else {
            StringBuilder ids = new StringBuilder();
            for (Long id : candidate.roleIds()) ids.append(ids.length() == 0 ? "" : SEPARATOR).append(id);
            args.put(ARG_ROLES, ids.toString());
            args.put(ARG_FUNCTION_ID, functionId);
            args.put(ARG_INVERT, candidate.invert() ? INVERT_ON : "");
            args.put(ARG_CANNED, ROLE_CANNED);
            args.put(ARG_CLASS, ROLE_CLASS);
        }
        List<Object> list = root.getConditions();
        if (list == null) {
            list = new ArrayList<Object>();
            root.setConditions(list);
        }
        list.add(created);
        return list.size();
    }

    // ---- recognition --------------------------------------------------------------------------------------------

    private static ConditionsDescriptor root(ActionDescriptor action) {
        RestrictionDescriptor restriction = action.getRestriction();
        return restriction == null ? null : restriction.getConditionsDescriptor();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> children(ConditionsDescriptor group) {
        List<Object> list = group.getConditions();
        return list == null ? Collections.<Object>emptyList() : list;
    }

    private static boolean isAnd(String type) {
        return type == null || type.isEmpty() || AND.equals(type);
    }

    private static String operator(ConditionsDescriptor group) {
        String type = group.getType();
        return type == null || type.isEmpty() ? AND : type;
    }

    /** True when the condition has neither a name nor a negation and its arguments are exactly {@code expected}. */
    private static boolean plain(ConditionDescriptor condition, Set<String> expected) {
        if (!TYPE_CLASS.equals(condition.getType()) || condition.isNegate()) return false;
        if (condition.getName() != null && !condition.getName().isEmpty()) return false;
        Map<?, ?> args = condition.getArgs();
        if (!expected.equals(new LinkedHashSet<Object>(args.keySet()))) return false;
        for (Object value : args.values()) {
            if (!(value instanceof String)) return false;
        }
        return true;
    }
    static boolean recognized(ConditionDescriptor condition, boolean group) {
        return group ? capturedGroup(condition) != null : capturedRole(condition) != null;
    }


    /** The group of a Jira {@code UserInGroupCondition} in the captured form; null for anything else. */
    private static String capturedGroup(ConditionDescriptor condition) {
        if (!plain(condition, GROUP_ARGS)) return null;
        Map<?, ?> args = condition.getArgs();
        if (!GROUP_CLASS.equals(args.get(ARG_CLASS))) return null;
        String group = (String) args.get(ARG_GROUP);
        return group.isEmpty() ? null : group;
    }

    /** The roles of a ScriptRunner {@code ProjectRoleCondition} in the captured form; null for anything else. */
    private static RoleForm capturedRole(ConditionDescriptor condition) {
        if (!plain(condition, ROLE_ARGS)) return null;
        Map<?, ?> args = condition.getArgs();
        if (!ROLE_CANNED.equals(args.get(ARG_CANNED)) || !ROLE_CLASS.equals(args.get(ARG_CLASS))) return null;
        String functionId = (String) args.get(ARG_FUNCTION_ID);
        if (functionId.isEmpty()) return null;
        String invert = (String) args.get(ARG_INVERT);
        if (!invert.isEmpty() && !INVERT_ON.equals(invert)) return null;
        List<Long> ids = new ArrayList<Long>();
        for (String id : ((String) args.get(ARG_ROLES)).split(Pattern.quote(SEPARATOR), -1)) {
            if (!ROLE_ID.matcher(id).matches()) return null;
            ids.add(Long.valueOf(id));
        }
        return new RoleForm(ids, INVERT_ON.equals(invert));
    }

    private static String text(Object value) {
        return value instanceof String ? blankToNull((String) value) : null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
