package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.workflow.api.dto.TransitionResolution;
import ru.rasulov.workflow.api.dto.edit.ScreenPatch;

import ru.rasulov.workflow.api.dto.edit.TransitionSource;
import ru.rasulov.workflow.api.dto.edit.WorkflowChange;
import ru.rasulov.workflow.impl.edit.WorkflowDescriptorPatcher.Catalog;
import ru.rasulov.workflow.impl.edit.WorkflowDescriptorPatcher.ResolutionInfo;
import ru.rasulov.workflow.impl.edit.WorkflowDescriptorPatcher.StatusInfo;

import java.util.HashMap;
import java.util.Map;

/**
 * A workflow the way an administrator's Jira holds one, with what a rebuild from a simple model would lose: a
 * third-party validator and function with arguments, step and action properties, parallel transitions with one name,
 * a global and a common action, a transition with conditional results and every kind of resolution rule.
 */
final class EditFixtures {

    static final String DTD = "<!DOCTYPE workflow PUBLIC \"-//OpenSymphony Group//DTD OSWorkflow 2.8//EN\" "
            + "\"http://www.opensymphony.com/osworkflow/workflow_2_8.dtd\">";

    private EditFixtures() { }

    static String fn(String cls, String... args) {
        StringBuilder xml = new StringBuilder("<function type=\"class\">");
        for (int i = 0; i < args.length; i += 2) xml.append("<arg name=\"").append(args[i]).append("\">")
                .append(args[i + 1]).append("</arg>");
        return xml.append("<arg name=\"class.name\">").append(cls).append("</arg></function>").toString();
    }

    static final String HISTORY = fn("com.atlassian.jira.workflow.function.issue.GenerateChangeHistoryFunction");
    static final String REINDEX = fn("com.atlassian.jira.workflow.function.issue.IssueReindexFunction");
    static final String EVENT = fn("com.atlassian.jira.workflow.function.event.FireIssueEventFunction",
            "eventTypeId", "13");
    static final String STATUS = fn("com.atlassian.jira.workflow.function.issue.UpdateIssueStatusFunction");
    static final String ACME = fn("com.acme.jira.NotifyFunction", "secret", "TOP-SECRET", "channel", "ops");

    static String writer(String value) {
        return fn(DescriptorFacts.UPDATE_FIELD_FUNCTION, "field.name", "resolution", "field.value", value);
    }

    /** A transition with a screen carries {@code view="fieldscreen"}, as Jira itself writes it. */
    static String action(int id, String name, int target, String extraMeta, String functions) {
        String view = extraMeta.contains("jira.fieldscreen.id") ? " view=\"fieldscreen\"" : "";
        return "<action id=\"" + id + "\" name=\"" + name + "\"" + view + "><meta name=\"jira.description\">keep me " + id
                + "</meta>" + extraMeta + "<results><unconditional-result old-status=\"Not Done\" status=\"Done\" "
                + "step=\"" + target + "\"><post-functions>" + functions + "</post-functions></unconditional-result>"
                + "</results></action>";
    }

    /** Step 1 Open(1), 2 In Progress(3), 3 Resolved(5, done), 4 Closed(6, done). */
    static WorkflowDescriptor workflow() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" + DTD + "\n<workflow>"
                + "<meta name=\"jira.description\">Support</meta><meta name=\"acme.owner\">team-a</meta>"
                + "<initial-actions><action id=\"1\" name=\"Create\"><meta name=\"jira.i18n.title\">common.forms.create"
                + "</meta><validators><validator name=\"\" type=\"class\"><arg name=\"permission\">Create Issue</arg>"
                + "<arg name=\"class.name\">com.atlassian.jira.workflow.validator.PermissionValidator</arg></validator>"
                + "<validator name=\"\" type=\"class\"><arg name=\"token\">hush</arg><arg name=\"class.name\">"
                + "com.acme.jira.PolicyValidator</arg></validator></validators><results><unconditional-result "
                + "old-status=\"null\" status=\"open\" step=\"1\"><post-functions>"
                + fn("com.atlassian.jira.workflow.function.issue.IssueCreateFunction") + ACME + REINDEX
                + "</post-functions></unconditional-result></results></action></initial-actions>"
                + "<global-actions>" + action(21, "Reopen", 1, "", STATUS + writer("null") + HISTORY + REINDEX + EVENT)
                + "</global-actions>"
                + "<common-actions>" + action(31, "Close", 4, "<meta name=\"jira.fieldscreen.id\">7</meta>",
                STATUS + writer("1") + HISTORY + REINDEX + EVENT) + "</common-actions>"
                + "<steps>"
                + "<step id=\"1\" name=\"Open\"><meta name=\"jira.status.id\">1</meta><meta name=\"jira.issue.editable\">"
                + "false</meta><actions><common-action id=\"31\"/>"
                + action(11, "Start", 2, "", STATUS + ACME + HISTORY + REINDEX + EVENT)
                + action(13, "Start", 3, "", STATUS + writer("1") + HISTORY + REINDEX + EVENT)
                + "</actions></step>"
                + "<step id=\"2\" name=\"In Progress\"><meta name=\"jira.status.id\">3</meta><actions>"
                + action(41, "Stop", 1, "", STATUS + HISTORY + REINDEX + EVENT)
                + action(42, "Finish", 3, "<meta name=\"jira.fieldscreen.id\">9</meta>",
                STATUS + writer("1") + ACME + HISTORY + REINDEX + EVENT)
                + "<action id=\"43\" name=\"Branch\"><restrict-to><conditions type=\"AND\"><condition type=\"class\">"
                + "<arg name=\"class.name\">com.acme.jira.WhenCondition</arg></condition></conditions></restrict-to>"
                + "<results><result old-status=\"Not Done\" status=\"Done\" step=\"1\"><conditions type=\"AND\">"
                + "<condition type=\"class\"><arg name=\"class.name\">com.acme.jira.PickCondition</arg></condition>"
                + "</conditions><post-functions>" + STATUS + HISTORY + "</post-functions></result>"
                + "<unconditional-result old-status=\"Not Done\" status=\"Done\" step=\"3\"><post-functions>" + STATUS
                + writer("1") + HISTORY + "</post-functions></unconditional-result></results></action>"
                + action(44, "Dual", 3, "", STATUS + writer("1") + writer("2") + HISTORY)
                + action(45, "Foreign", 3, "", STATUS + fn("com.acme.jira.SetFieldFunction", "field.name",
                "resolution", "field.value", "3") + HISTORY)
                + "</actions></step>"
                + "<step id=\"3\" name=\"Resolved\"><meta name=\"jira.status.id\">5</meta><actions>"
                + action(51, "Back", 1, "", STATUS + writer("null") + ACME + HISTORY + REINDEX + EVENT)
                + action(52, "Archive", 4, "", STATUS + writer("1") + HISTORY + REINDEX + EVENT)
                + "</actions></step>"
                + "<step id=\"4\" name=\"Closed\"><meta name=\"jira.status.id\">6</meta></step>"
                + "</steps></workflow>";
        try {
            return WorkflowUtil.convertXMLtoWorkflowDescriptor(xml);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Catalog catalog() {
        Map<String, StatusInfo> statuses = new HashMap<String, StatusInfo>();
        statuses.put("1", new StatusInfo("1", "Open", "new"));
        statuses.put("3", new StatusInfo("3", "In Progress", "indeterminate"));
        statuses.put("5", new StatusInfo("5", "Resolved", "done"));
        statuses.put("6", new StatusInfo("6", "Closed", "done"));
        statuses.put("10001", new StatusInfo("10001", "Review", "indeterminate"));
        statuses.put("10002", new StatusInfo("10002", "Shipped", "done"));
        return new Catalog() {
            public StatusInfo status(String id) { return statuses.get(id); }
            public boolean screenExists(long id) { return id == 7 || id == 9 || id == 11; }
            public ResolutionInfo resolution(String id) {
                return "1".equals(id) ? new ResolutionInfo("1", "Fixed") : "2".equals(id)
                        ? new ResolutionInfo("2", "Won't Fix") : null;
            }
        };
    }

    // ---- change builders ----------------------------------------------------------------------------------------

    static WorkflowChange rename(int actionId, String name) {
        return new WorkflowChange("UPDATE_TRANSITION",actionId,null,null,null,name,null,null,null,null,null,null,null, null);
    }

    static WorkflowChange resolution(int actionId, String action, String value) {
        return new WorkflowChange("UPDATE_TRANSITION",actionId,null,null,null,null,null,null,null,null,null,new TransitionResolution(action, value),null, null);
    }

    static WorkflowChange screen(int actionId, String action, Long screenId) {
        return new WorkflowChange("UPDATE_TRANSITION",actionId,null,null,null,null,null,null,null,null,new ScreenPatch(action, screenId),null,null, null);
    }

    static WorkflowChange retarget(int actionId, int target) {
        return new WorkflowChange("UPDATE_TRANSITION",actionId,null,null,null,null,null,null,target,null,null,null,null, null);
    }

    static WorkflowChange move(int actionId, TransitionSource source) {
        return new WorkflowChange("UPDATE_TRANSITION",actionId,null,null,null,null,null,source,null,null,null,null,null, null);
    }

    static WorkflowChange addStatus(String statusId, String clientKey) {
        return new WorkflowChange("ADD_STATUS",null,null,statusId,clientKey,null,null,null,null,null,null,null,null, null);
    }

    static WorkflowChange replaceStatus(int stepId, String statusId) {
        return new WorkflowChange("REPLACE_STATUS",null,stepId,statusId,null,null,null,null,null,null,null,null,null, null);
    }

    static WorkflowChange removeStatus(int stepId) {
        return new WorkflowChange("REMOVE_STATUS",null,stepId,null,null,null,null,null,null,null,null,null,null, null);
    }

    static WorkflowChange removeTransition(int actionId) {
        return new WorkflowChange("REMOVE_TRANSITION",actionId,null,null,null,null,null,null,null,null,null,null,null, null);
    }

    static WorkflowChange addTransition(String name, TransitionSource source, Integer target, String targetKey,
                                        TransitionResolution resolution) {
        return new WorkflowChange("ADD_TRANSITION",null,null,null,null,name,null,source,target,targetKey,null,resolution,null, null);
    }

    static WorkflowChange setInitial(int stepId) {
        return new WorkflowChange("SET_INITIAL_STATUS",null,stepId,null,null,null,null,null,null,null,null,null,null, null);
    }

    static WorkflowChange description(String text) {
        return new WorkflowChange("UPDATE_METADATA",null,null,null,null,null,text,null,null,null,null,null,null, null);
    }

    static TransitionSource from(int stepId) {
        return new TransitionSource(stepId, null, null);
    }

    static TransitionSource fromKey(String key) {
        return new TransitionSource(null, key, null);
    }

    static TransitionSource fromAny() {
        return new TransitionSource(null, null, true);
    }

}
