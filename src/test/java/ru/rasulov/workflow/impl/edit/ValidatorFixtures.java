package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ValidatorDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;

import java.util.List;

import static ru.rasulov.workflow.impl.edit.EditFixtures.DTD;
import static ru.rasulov.workflow.impl.edit.EditFixtures.HISTORY;
import static ru.rasulov.workflow.impl.edit.EditFixtures.REINDEX;
import static ru.rasulov.workflow.impl.edit.EditFixtures.STATUS;

/**
 * A workflow whose transitions carry ScriptRunner "Field(s) required" validators in the exact form that ScriptRunner
 * 9.46.0 stored on the stand (see the sample in the spec), next to the neighbours the sample was found with: a
 * ScriptRunner CommentRequiredValidator, Jira validators, conditions and post-functions.
 */
final class ValidatorFixtures {

    static final String ID_1 = "d679b03f-d6ba-47e9-a4f9-8db9ce4c6b9c";
    static final String ID_2 = "0a6c5e83-1d47-4c4e-9a53-4f1f6f7b7c01";
    static final String ID_3 = "7e21b1a9-5b3f-4a39-8f0a-2a9b3c4d5e02";
    static final String COMMENT_CANNED = "com.onresolve.scriptrunner.canned.jira.workflow.validators.CommentRequiredValidator";

    private ValidatorFixtures() { }

    /** The exact sample of the stand: attribute and argument names as ScriptRunner 9.46.0 writes them. */
    static String required(String functionId, String fields, String condition) {
        return "<validator name=\"\" type=\"class\">"
                + "<arg name=\"FIELD_FUNCTION_ID\">" + functionId + "</arg>"
                + "<arg name=\"FIELD_REQUIRED_FIELDS\">" + fields + "</arg>"
                + "<arg name=\"canned-script\">" + RequiredFieldsDescriptor.CANNED_SCRIPT + "</arg>"
                + "<arg name=\"FIELD_CONDITION\">" + condition + "</arg>"
                + "<arg name=\"class.name\">" + RequiredFieldsDescriptor.CLASS_NAME + "</arg></validator>";
    }

    /** A neighbour validator of ScriptRunner that carries an argument which must never be shown. */
    static final String COMMENT = "<validator name=\"\" type=\"class\"><arg name=\"NOTE\">TOP-SECRET-NOTE</arg>"
            + "<arg name=\"canned-script\">" + COMMENT_CANNED + "</arg><arg name=\"class.name\">"
            + RequiredFieldsDescriptor.CLASS_NAME + "</arg></validator>";

    static final String JIRA_PERMISSION = "<validator name=\"\" type=\"class\"><arg name=\"permission\">Resolve Issue</arg>"
            + "<arg name=\"class.name\">com.atlassian.jira.workflow.validator.PermissionValidator</arg></validator>";

    /** The same validator with an argument that ScriptRunner 9.46.0 does not write: not the captured form. */
    static final String REQUIRED_WITH_EXTRA_ARG = "<validator name=\"\" type=\"class\">"
            + "<arg name=\"FIELD_FUNCTION_ID\">" + ID_3 + "</arg><arg name=\"FIELD_REQUIRED_FIELDS\">assignee</arg>"
            + "<arg name=\"canned-script\">" + RequiredFieldsDescriptor.CANNED_SCRIPT + "</arg>"
            + "<arg name=\"FIELD_CONDITION\"></arg><arg name=\"FIELD_MESSAGE\">custom</arg>"
            + "<arg name=\"class.name\">" + RequiredFieldsDescriptor.CLASS_NAME + "</arg></validator>";

    /** A transition with a screen carries {@code view="fieldscreen"}, as Jira itself writes it. */
    private static String action(int id, String name, int target, String meta, String restrict, String validators) {
        String view = meta.contains("jira.fieldscreen.id") ? " view=\"fieldscreen\"" : "";
        return "<action id=\"" + id + "\" name=\"" + name + "\"" + view + "><meta name=\"jira.description\">keep " + id + "</meta>"
                + meta + restrict + (validators.isEmpty() ? "" : "<validators>" + validators + "</validators>")
                + "<results><unconditional-result old-status=\"Not Done\" status=\"Done\" step=\"" + target
                + "\"><post-functions>" + STATUS + HISTORY + REINDEX + "</post-functions></unconditional-result>"
                + "</results></action>";
    }

    private static final String JIRA_CONDITION = "<restrict-to><conditions type=\"AND\"><condition type=\"class\">"
            + "<arg name=\"class.name\">com.atlassian.jira.workflow.condition.AllowOnlyAssignee</arg></condition>"
            + "</conditions></restrict-to>";

    /**
     * Step 1 Open(status 1), 2 In Progress(3), 3 Done(5). Transitions:
     * 11 Start 1-&gt;2 no required-fields validator, a Jira condition and neighbours;
     * 21 Done 2-&gt;3 {@code assignee|||customfield_10118}, a conditional one, a CommentRequiredValidator;
     * 22 Finish 2-&gt;3 {@code assignee} and a Jira validator;
     * 23 Split 2-&gt;3 {@code assignee} in the first and {@code description} in the second validator;
     * 24 Maybe 2-&gt;3 only a conditional {@code assignee};
     * 25 Odd 2-&gt;3 a required-fields validator in a form that was not captured;
     * 26 Plain 2-&gt;3 no validators at all;
     * 31 global Reopen -&gt;1, 41 common Close 3-&gt;1 (screen 7), initial 1 Create with a Jira validator.
     */
    static WorkflowDescriptor workflow() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + DTD + "\n<workflow>"
                + "<meta name=\"jira.description\">Support</meta>"
                + "<initial-actions><action id=\"1\" name=\"Create\"><validators>" + JIRA_PERMISSION
                + "</validators><results><unconditional-result old-status=\"null\" status=\"open\" step=\"1\">"
                + "<post-functions>" + EditFixtures.fn("com.atlassian.jira.workflow.function.issue.IssueCreateFunction")
                + REINDEX + "</post-functions></unconditional-result></results></action></initial-actions>"
                + "<global-actions>" + action(31, "Reopen", 1, "", "", "") + "</global-actions>"
                + "<common-actions>" + action(41, "Close", 1, "<meta name=\"jira.fieldscreen.id\">7</meta>", "",
                required(ID_3, "assignee", "")) + "</common-actions>"
                + "<steps>"
                + "<step id=\"1\" name=\"Open\"><meta name=\"jira.status.id\">1</meta><actions>"
                + action(11, "Start", 2, "<meta name=\"jira.fieldscreen.id\">9</meta>", JIRA_CONDITION,
                JIRA_PERMISSION + COMMENT)
                + "</actions></step>"
                + "<step id=\"2\" name=\"In Progress\"><meta name=\"jira.status.id\">3</meta><actions>"
                + "<common-action id=\"41\"/>"
                + action(21, "Done", 3, "", JIRA_CONDITION,
                required(ID_1, "assignee|||customfield_10118", "") + required(ID_2, "description", "issue.priority")
                        + COMMENT)
                + action(22, "Finish", 3, "", "", required(ID_1, "assignee", "") + JIRA_PERMISSION)
                + action(23, "Split", 3, "", "", required(ID_1, "assignee", "") + required(ID_2, "description", ""))
                + action(24, "Maybe", 3, "", "", required(ID_2, "assignee", "issue.priority"))
                + action(25, "Odd", 3, "", "", REQUIRED_WITH_EXTRA_ARG)
                + action(26, "Plain", 3, "", "", "")
                + "</actions></step>"
                + "<step id=\"3\" name=\"Done\"><meta name=\"jira.status.id\">5</meta></step>"
                + "</steps></workflow>";
        try {
            return WorkflowUtil.convertXMLtoWorkflowDescriptor(xml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The function id of the sample exported from the stand (40 hex characters, not a UUID). */
    static final String SAMPLE_COMMENT_ID = "f5fd7dbcb6f11e34a8a5aabd38a2a78363b6d449";

    /** The ScriptRunner "Comment required" validator exactly as ScriptRunner 9.46.0 stores it: three arguments in this order. */
    static String commentRequired(String functionId) {
        return "<validator name=\"\" type=\"class\">"
                + "<arg name=\"FIELD_FUNCTION_ID\">" + functionId + "</arg>"
                + "<arg name=\"canned-script\">" + COMMENT_CANNED + "</arg>"
                + "<arg name=\"class.name\">" + RequiredFieldsDescriptor.CLASS_NAME + "</arg></validator>";
    }

    /** The sample with an argument that ScriptRunner 9.46.0 does not write: not the captured form. */
    static final String COMMENT_WITH_EXTRA_ARG = "<validator name=\"\" type=\"class\">"
            + "<arg name=\"FIELD_FUNCTION_ID\">" + ID_3 + "</arg><arg name=\"canned-script\">" + COMMENT_CANNED + "</arg>"
            + "<arg name=\"FIELD_MESSAGE\">custom</arg>"
            + "<arg name=\"class.name\">" + RequiredFieldsDescriptor.CLASS_NAME + "</arg></validator>";

    /** The sample without its function id: not the captured form. */
    static final String COMMENT_WITHOUT_ID = "<validator name=\"\" type=\"class\">"
            + "<arg name=\"canned-script\">" + COMMENT_CANNED + "</arg>"
            + "<arg name=\"class.name\">" + RequiredFieldsDescriptor.CLASS_NAME + "</arg></validator>";

    private static String screen(String id) {
        return "<meta name=\"jira.fieldscreen.id\">" + id + "</meta>";
    }

    /**
     * Step 1 Open(status 1), 2 Done(5). Transitions, all 1-&gt;2, with a Jira condition and the post-functions of the sample:
     * 11 Plain screen 9, Jira and required-fields validators, no comment validator (add);
     * 12 Has screen 9, the comment validator (unchanged);
     * 13 SampleNoScreen no screen, required-fields then the comment validator with the sample's 40-hex id (the stand sample);
     * 14 NoScreen no screen, no comment validator;
     * 15 NotNumber screen "abc", no comment validator; 16 NotNumberHas the same with the comment validator;
     * 17 GoneScreen screen 99 which Jira does not have, no comment validator; 18 GoneHas the same with the comment validator;
     * 19 ExtraArg screen 9, the comment script with an extra argument; 20 NoId screen 9, the comment script without id;
     * 21 Sample screen 9, required-fields then the comment validator (read order).
     */
    static WorkflowDescriptor commentWorkflow() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + DTD + "\n<workflow>"
                + "<meta name=\"jira.description\">Support</meta>"
                + "<initial-actions><action id=\"1\" name=\"Create\"><validators>" + JIRA_PERMISSION
                + "</validators><results><unconditional-result old-status=\"null\" status=\"open\" step=\"1\">"
                + "<post-functions>" + EditFixtures.fn("com.atlassian.jira.workflow.function.issue.IssueCreateFunction")
                + REINDEX + "</post-functions></unconditional-result></results></action></initial-actions>"
                + "<steps><step id=\"1\" name=\"Open\"><meta name=\"jira.status.id\">1</meta><actions>"
                + action(11, "Plain", 2, screen("9"), JIRA_CONDITION, JIRA_PERMISSION + required(ID_1, "assignee", ""))
                + action(12, "Has", 2, screen("9"), JIRA_CONDITION, commentRequired(ID_2))
                + action(13, "SampleNoScreen", 2, "", JIRA_CONDITION,
                required(ID_1, "assignee", "") + commentRequired(SAMPLE_COMMENT_ID))
                + action(14, "NoScreen", 2, "", JIRA_CONDITION, JIRA_PERMISSION)
                + action(15, "NotNumber", 2, screen("abc"), JIRA_CONDITION, "")
                + action(16, "NotNumberHas", 2, screen("abc"), JIRA_CONDITION, commentRequired(ID_2))
                + action(17, "GoneScreen", 2, screen("99"), JIRA_CONDITION, "")
                + action(18, "GoneHas", 2, screen("99"), JIRA_CONDITION, commentRequired(ID_2))
                + action(19, "ExtraArg", 2, screen("9"), JIRA_CONDITION, COMMENT_WITH_EXTRA_ARG)
                + action(20, "NoId", 2, screen("9"), JIRA_CONDITION, COMMENT_WITHOUT_ID)
                + action(21, "Sample", 2, screen("9"), JIRA_CONDITION,
                required(ID_1, "assignee", "") + commentRequired(SAMPLE_COMMENT_ID))
                + "</actions></step>"
                + "<step id=\"2\" name=\"Done\"><meta name=\"jira.status.id\">5</meta></step>"
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

    /** The first validator of the XML text, read the way Jira reads it. */
    static ValidatorDescriptor validator(String validatorXml) {
        WorkflowDescriptor d;
        try {
            d = WorkflowUtil.convertXMLtoWorkflowDescriptor("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + DTD
                    + "\n<workflow><initial-actions><action id=\"1\" name=\"Create\"><validators>" + validatorXml
                    + "</validators><results><unconditional-result old-status=\"null\" status=\"open\" step=\"1\"/>"
                    + "</results></action></initial-actions><steps><step id=\"1\" name=\"Open\"><meta "
                    + "name=\"jira.status.id\">1</meta></step></steps></workflow>");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        @SuppressWarnings("unchecked")
        List<ValidatorDescriptor> validators = (List<ValidatorDescriptor>) action(d, 1).getValidators();
        return validators.get(0);
    }

    @SuppressWarnings("unchecked")
    static List<ValidatorDescriptor> validators(ActionDescriptor action) {
        return (List<ValidatorDescriptor>) action.getValidators();
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
