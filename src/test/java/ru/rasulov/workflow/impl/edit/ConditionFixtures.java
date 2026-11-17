package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;

import static ru.rasulov.workflow.impl.edit.EditFixtures.DTD;
import static ru.rasulov.workflow.impl.edit.EditFixtures.HISTORY;
import static ru.rasulov.workflow.impl.edit.EditFixtures.REINDEX;
import static ru.rasulov.workflow.impl.edit.EditFixtures.STATUS;

/**
 * Workflows whose transitions carry conditions in the exact forms that were exported from the stand (ScriptRunner
 * 9.46.0 on Jira 10.3.24): the Jira "User in group" and "Resolve Issues" permission conditions as in the CFLTB sample,
 * and the ScriptRunner "Project role(s) condition" with one role, two roles joined by {@code |||} and with invert on
 * (the value {@code on}), as exported from a Jira 10.3.24 stand with ScriptRunner 9.46.0.
 */
final class ConditionFixtures {

    static final String ID_1 = "9dc3f41a-fa69-4658-b20f-e3626c975743";
    static final String ID_2 = "77f1f6e0-ab42-4586-9baf-774d640f5e50";
    static final String ID_3 = "d5c2f9b2-2d1b-4ad3-9f45-4d8ad5140b1b";
    static final String ID_4 = "ec4f48fb-cc72-4e2b-8968-5327ecae2d2e";

    private ConditionFixtures() { }

    /** Jira "Resolve Issues" permission condition (an OTHER node). */
    static final String PERMISSION = "<condition type=\"class\"><arg name=\"permission\">RESOLVE_ISSUES</arg>"
            + "<arg name=\"class.name\">com.atlassian.jira.workflow.condition.PermissionCondition</arg></condition>";

    /** The Jira condition "Only users in group can execute", as in the CFLTB sample. */
    static String group(String name) {
        return "<condition type=\"class\"><arg name=\"class.name\">" + ConditionDescriptors.GROUP_CLASS
                + "</arg><arg name=\"group\">" + name + "</arg></condition>";
    }

    /** The ScriptRunner sample: {@code ids} as stored ({@code 10002} or {@code 10002|||10101}); invert "" or "on". */
    static String role(String functionId, String ids, String invert) {
        return "<condition type=\"class\"><arg name=\"FIELD_PROJECT_ROLE_IDS\">" + ids + "</arg>"
                + "<arg name=\"FIELD_FUNCTION_ID\">" + functionId + "</arg>"
                + "<arg name=\"FIELD_INVERT_CONDITION\">" + invert + "</arg>"
                + "<arg name=\"canned-script\">" + ConditionDescriptors.ROLE_CANNED + "</arg>"
                + "<arg name=\"class.name\">" + ConditionDescriptors.ROLE_CLASS + "</arg></condition>";
    }

    /**
     * The inverted sample exactly as ScriptRunner 9.46.0 exported it from the stand: the arguments in another order
     * (ids, invert, function id) than the other samples; the order is not part of the form.
     */
    static final String ROLE_INVERT_EXPORTED_ORDER = "<condition type=\"class\">"
            + "<arg name=\"FIELD_PROJECT_ROLE_IDS\">10100</arg><arg name=\"FIELD_INVERT_CONDITION\">on</arg>"
            + "<arg name=\"FIELD_FUNCTION_ID\">" + ID_4 + "</arg>"
            + "<arg name=\"canned-script\">" + ConditionDescriptors.ROLE_CANNED + "</arg>"
            + "<arg name=\"class.name\">" + ConditionDescriptors.ROLE_CLASS + "</arg></condition>";

    /** The group condition with an argument that Jira does not write for it: not the captured form. */
    static final String GROUP_WITH_EXTRA_ARG = "<condition type=\"class\"><arg name=\"class.name\">"
            + ConditionDescriptors.GROUP_CLASS + "</arg><arg name=\"group\">Odd</arg><arg name=\"note\">TOP-SECRET-ARG</arg>"
            + "</condition>";

    /** The role condition without its function id: not the captured form. */
    static final String ROLE_WITHOUT_ID = "<condition type=\"class\"><arg name=\"FIELD_PROJECT_ROLE_IDS\">10002</arg>"
            + "<arg name=\"FIELD_INVERT_CONDITION\"></arg><arg name=\"canned-script\">" + ConditionDescriptors.ROLE_CANNED
            + "</arg><arg name=\"class.name\">" + ConditionDescriptors.ROLE_CLASS + "</arg></condition>";

    /** The role condition with an invert value that ScriptRunner does not write ("yes"): not the captured form. */
    static final String ROLE_ODD_INVERT = role(ID_3, "10002", "yes");

    /** The group condition negated by the descriptor: it means the opposite, so it is not the captured form. */
    static final String GROUP_NEGATED = "<condition type=\"class\" negate=\"true\"><arg name=\"class.name\">"
            + ConditionDescriptors.GROUP_CLASS + "</arg><arg name=\"group\">Negated</arg></condition>";

    static String and(String... conditions) {
        return "<restrict-to><conditions type=\"AND\">" + String.join("", conditions) + "</conditions></restrict-to>";
    }

    /** A root that has no {@code type}: what Jira's UI writes for the first condition. */
    static String untyped(String... conditions) {
        return "<restrict-to><conditions>" + String.join("", conditions) + "</conditions></restrict-to>";
    }

    static String or(String... conditions) {
        return "<restrict-to><conditions type=\"OR\">" + String.join("", conditions) + "</conditions></restrict-to>";
    }

    static String nested(String operator, String... conditions) {
        return "<conditions type=\"" + operator + "\">" + String.join("", conditions) + "</conditions>";
    }

    private static final String VALIDATOR = "<validators><validator name=\"\" type=\"class\"><arg name=\"permission\">"
            + "Resolve Issue</arg><arg name=\"class.name\">com.atlassian.jira.workflow.validator.PermissionValidator"
            + "</arg></validator></validators>";

    private static String action(int id, String name, int target, String restrict) {
        return "<action id=\"" + id + "\" name=\"" + name + "\" view=\"fieldscreen\"><meta name=\"jira.description\">keep "
                + id + "</meta><meta name=\"jira.fieldscreen.id\">9</meta>" + restrict + VALIDATOR
                + "<results><unconditional-result old-status=\"Not Done\" status=\"Done\" step=\"" + target
                + "\"><post-functions>" + STATUS + HISTORY + REINDEX + "</post-functions></unconditional-result>"
                + "</results></action>";
    }

    /**
     * Step 1 Open(status 1), 2 In Progress(3), 3 Done(5). Transitions:
     * 11 Done 2-&gt;3 the CFLTB sample: AND root with permission, role {@code 10002}, group {@code jira-administrators};
     * 12 Untyped 2-&gt;3 a root without type with one permission condition; 13 Plain 2-&gt;3 no restrict-to;
     * 14 Nested 2-&gt;3 AND root with permission and a nested OR of group {@code Inner} and role {@code 10100};
     * 15 OrRoot 2-&gt;3 OR root with group {@code Team-A} and permission; 16 Grouped 2-&gt;3 AND root with
     * group {@code Team-A}; 17 Roled 2-&gt;3 AND root with roles {@code 10002|||10101} and an inverted {@code 10100};
     * 18 Odd 2-&gt;3 forms that were not captured (extra argument, no function id, odd invert, negated);
     * 19 InNested 2-&gt;3 AND root whose only group {@code Team-A} and role {@code 10002} sit in a nested AND;
     * 20 Exported 2-&gt;3 the inverted role sample in the argument order of the stand's export;
     * 31 global Reopen -&gt;1 without conditions, 41 common Close 3-&gt;1 with a condition, initial 1 Create.
     */
    static WorkflowDescriptor workflow() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + DTD + "\n<workflow>"
                + "<meta name=\"jira.description\">Support</meta>"
                + "<initial-actions><action id=\"1\" name=\"Create\"><results><unconditional-result old-status=\"null\" "
                + "status=\"open\" step=\"1\"><post-functions>"
                + EditFixtures.fn("com.atlassian.jira.workflow.function.issue.IssueCreateFunction") + REINDEX
                + "</post-functions></unconditional-result></results></action></initial-actions>"
                + "<global-actions>" + action(31, "Reopen", 1, "") + "</global-actions>"
                + "<common-actions>" + action(41, "Close", 1, and(PERMISSION)) + "</common-actions>"
                + "<steps>"
                + "<step id=\"1\" name=\"Open\"><meta name=\"jira.status.id\">1</meta><actions>"
                + action(10, "Start", 2, "") + "</actions></step>"
                + "<step id=\"2\" name=\"In Progress\"><meta name=\"jira.status.id\">3</meta><actions>"
                + "<common-action id=\"41\"/>"
                + action(11, "Done", 3, and(PERMISSION, role(ID_1, "10002", ""), group("jira-administrators")))
                + action(12, "Untyped", 3, untyped(PERMISSION))
                + action(13, "Plain", 3, "")
                + action(14, "Nested", 3, "<restrict-to><conditions type=\"AND\">" + PERMISSION
                        + nested("OR", group("Inner"), role(ID_4, "10100", "on")) + "</conditions></restrict-to>")
                + action(15, "OrRoot", 3, or(group("Team-A"), PERMISSION))
                + action(16, "Grouped", 3, and(group("Team-A")))
                + action(17, "Roled", 3, and(role(ID_3, "10002|||10101", ""), role(ID_4, "10100", "on")))
                + action(18, "Odd", 3, and(GROUP_WITH_EXTRA_ARG, ROLE_WITHOUT_ID, ROLE_ODD_INVERT, GROUP_NEGATED))
                + action(20, "Exported", 3, and(ROLE_INVERT_EXPORTED_ORDER))
                + action(19, "InNested", 3, "<restrict-to><conditions type=\"AND\">" + nested("AND", group("Team-A"),
                        role(ID_2, "10002", "")) + "</conditions></restrict-to>")
                + "</actions></step>"
                + "<step id=\"3\" name=\"Done\"><meta name=\"jira.status.id\">5</meta></step>"
                + "</steps></workflow>";
        try {
            return WorkflowUtil.convertXMLtoWorkflowDescriptor(xml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ActionDescriptor action(WorkflowDescriptor d, int id) {
        return DescriptorFacts.locate(d).get(id).action();
    }

    /** The definition after Jira's own save and reload. */
    static WorkflowDescriptor reloaded(WorkflowDescriptor d) {
        try {
            return WorkflowUtil.convertXMLtoWorkflowDescriptor(WorkflowUtil.convertDescriptorToXML(d));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
