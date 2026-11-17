package ru.rasulov.workflow.impl;

import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.FactoryException;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowStatus;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowTransition;

import javax.inject.Named;
import java.util.List;
import java.util.Map;

/**
 * Turns a validated workflow into the descriptor Jira stores. The descriptor is written as XML in the form Jira itself
 * writes for a workflow made in the admin UI, then parsed by Jira's own {@link WorkflowUtil}, so Jira decides what is
 * well formed.
 * <p>
 * A step is a status (linked by {@code jira.status.id}); a transition is an action of its source step with one
 * unconditional result: the target step. "From any status" transitions are global actions. Every transition carries the
 * post functions Jira itself puts on a new transition: without them the status would not change, nothing would be
 * recorded in the issue history and the index would not be updated. They are not automations the user asked for.
 */
@Named
public class WorkflowDescriptorBuilder {

    static final int INITIAL_ACTION_ID = 1;
    /** Action ids of transitions: 11, 21, 31, ... like Jira's own wizard, so they never meet the initial action. */
    private static final int FIRST_TRANSITION_ID = 11;
    private static final int TRANSITION_ID_STEP = 10;
    /** Jira's generic issue event. */
    private static final int GENERIC_EVENT_ID = 13;
    private static final int CREATED_EVENT_ID = 1;

    /**
     * @param statusIds Jira status id of every status of the workflow (created ones included)
     * @throws FactoryException Jira could not parse the descriptor: a bug here, not a problem of the request
     */
    public WorkflowDescriptor build(ResolvedWorkflow workflow, Map<WorkflowStatus, String> statusIds)
            throws FactoryException {
        return WorkflowUtil.convertXMLtoWorkflowDescriptor(toXml(workflow, statusIds));
    }

    String toXml(ResolvedWorkflow workflow, Map<WorkflowStatus, String> statusIds) {
        List<WorkflowStatus> statuses = workflow.getStatuses();
        List<WorkflowTransition> transitions = workflow.getTransitions();
        StringBuilder xml = new StringBuilder(4096);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<!DOCTYPE workflow PUBLIC \"-//OpenSymphony Group//DTD OSWorkflow 2.8//EN\" "
                        + "\"http://www.opensymphony.com/osworkflow/workflow_2_8.dtd\">\n")
                .append("<workflow>\n")
                .append("  <meta name=\"jira.description\">").append(escape(workflow.getDescription()))
                .append("</meta>\n");

        appendInitialAction(xml, stepOf(statuses, workflow.getInitial()));

        if (hasGlobal(transitions)) {
            xml.append("  <global-actions>\n");
            for (int i = 0; i < transitions.size(); i++) {
                if (transitions.get(i).isFromAny()) {
                    appendTransition(xml, "    ", actionId(i), transitions.get(i), stepOf(statuses, transitions.get(i).getTo()));
                }
            }
            xml.append("  </global-actions>\n");
        }

        xml.append("  <steps>\n");
        for (WorkflowStatus status : statuses) {
            xml.append("    <step id=\"").append(stepOf(statuses, status)).append("\" name=\"")
                    .append(escape(status.getName())).append("\">\n")
                    .append("      <meta name=\"jira.status.id\">").append(escape(statusIds.get(status)))
                    .append("</meta>\n");
            appendStepActions(xml, statuses, transitions, status);
            xml.append("    </step>\n");
        }
        xml.append("  </steps>\n</workflow>\n");
        return xml.toString();
    }

    private static void appendInitialAction(StringBuilder xml, int firstStep) {
        xml.append("  <initial-actions>\n")
                .append("    <action id=\"").append(INITIAL_ACTION_ID).append("\" name=\"Create\">\n")
                .append("      <meta name=\"jira.i18n.submit\">common.forms.create</meta>\n")
                .append("      <meta name=\"jira.i18n.title\">common.forms.create</meta>\n")
                .append("      <validators>\n")
                .append("        <validator name=\"\" type=\"class\">\n")
                .append("          <arg name=\"permission\">Create Issue</arg>\n")
                .append("          <arg name=\"class.name\">com.atlassian.jira.workflow.validator.PermissionValidator</arg>\n")
                .append("        </validator>\n")
                .append("      </validators>\n")
                .append("      <results>\n")
                .append("        <unconditional-result old-status=\"null\" status=\"open\" step=\"").append(firstStep).append("\">\n")
                .append("          <post-functions>\n")
                .append("            <function type=\"class\">\n")
                .append("              <arg name=\"class.name\">com.atlassian.jira.workflow.function.issue.IssueCreateFunction</arg>\n")
                .append("            </function>\n")
                .append("            <function type=\"class\">\n")
                .append("              <arg name=\"class.name\">com.atlassian.jira.workflow.function.issue.IssueReindexFunction</arg>\n")
                .append("            </function>\n")
                .append("            <function type=\"class\">\n")
                .append("              <arg name=\"eventTypeId\">").append(CREATED_EVENT_ID).append("</arg>\n")
                .append("              <arg name=\"class.name\">com.atlassian.jira.workflow.function.event.FireIssueEventFunction</arg>\n")
                .append("            </function>\n")
                .append("          </post-functions>\n")
                .append("        </unconditional-result>\n")
                .append("      </results>\n")
                .append("    </action>\n")
                .append("  </initial-actions>\n");
    }

    private static void appendStepActions(StringBuilder xml, List<WorkflowStatus> statuses,
                                          List<WorkflowTransition> transitions, WorkflowStatus source) {
        boolean opened = false;
        for (int i = 0; i < transitions.size(); i++) {
            WorkflowTransition transition = transitions.get(i);
            if (transition.getFrom() != source) {
                continue;
            }
            if (!opened) {
                xml.append("      <actions>\n");
                opened = true;
            }
            appendTransition(xml, "        ", actionId(i), transition, stepOf(statuses, transition.getTo()));
        }
        if (opened) {
            xml.append("      </actions>\n");
        }
    }

    private static void appendTransition(StringBuilder xml, String indent, int id, WorkflowTransition transition,
                                         int targetStep) {
        xml.append(indent).append("<action id=\"").append(id).append("\" name=\"").append(escape(transition.getName()))
                .append(transition.getScreen() != null ? "\" view=\"fieldscreen\">\n" : "\">\n")
                .append(indent).append("  <meta name=\"jira.description\"></meta>\n");
        if (transition.getScreen() != null) {
            xml.append(indent).append("  <meta name=\"jira.fieldscreen.id\">")
                    .append(transition.getScreen().getId()).append("</meta>\n");
        }
        xml.append(indent).append("  <results>\n")
                .append(indent).append("    <unconditional-result old-status=\"Not Done\" status=\"Done\" step=\"")
                .append(targetStep).append("\">\n")
                .append(indent).append("      <post-functions>\n");
        appendFunction(xml, indent + "        ", null, "com.atlassian.jira.plugin.system.workflowupdateissuestatus-function",
                "com.atlassian.jira.workflow.function.issue.UpdateIssueStatusFunction");
        appendFunction(xml, indent + "        ", null, "com.atlassian.jira.plugin.system.workflowcreatecomment-function",
                "com.atlassian.jira.workflow.function.misc.CreateCommentFunction");
        if (!"PRESERVE".equals(transition.getResolution().getAction())) {
            xml.append(indent).append("        <function type=\"class\">\n")
                    .append(indent).append("          <arg name=\"class.name\">com.atlassian.jira.workflow.function.issue.UpdateIssueFieldFunction</arg>\n")
                    .append(indent).append("          <arg name=\"field.name\">resolution</arg>\n")
                    .append(indent).append("          <arg name=\"field.value\">")
                    .append("CLEAR".equals(transition.getResolution().getAction()) ? "null"
                            : escape(transition.getResolution().getValue())).append("</arg>\n")
                    .append(indent).append("        </function>\n");
        }
        appendFunction(xml, indent + "        ", null, "com.atlassian.jira.plugin.system.workflowgeneratechangehistory-function",
                "com.atlassian.jira.workflow.function.issue.GenerateChangeHistoryFunction");
        appendFunction(xml, indent + "        ", null, "com.atlassian.jira.plugin.system.workflowreindexissue-function",
                "com.atlassian.jira.workflow.function.issue.IssueReindexFunction");
        appendFunction(xml, indent + "        ", GENERIC_EVENT_ID, "com.atlassian.jira.plugin.system.workflowfireevent-function",
                "com.atlassian.jira.workflow.function.event.FireIssueEventFunction");
        xml.append(indent).append("      </post-functions>\n")
                .append(indent).append("    </unconditional-result>\n")
                .append(indent).append("  </results>\n")
                .append(indent).append("</action>\n");
    }

    private static void appendFunction(StringBuilder xml, String indent, Integer eventTypeId, String moduleKey,
                                       String className) {
        xml.append(indent).append("<function type=\"class\">\n");
        if (eventTypeId != null) {
            xml.append(indent).append("  <arg name=\"eventTypeId\">").append(eventTypeId).append("</arg>\n");
        }
        xml.append(indent).append("  <arg name=\"full.module.key\">").append(moduleKey).append("</arg>\n")
                .append(indent).append("  <arg name=\"class.name\">").append(className).append("</arg>\n")
                .append(indent).append("</function>\n");
    }

    private static boolean hasGlobal(List<WorkflowTransition> transitions) {
        for (WorkflowTransition transition : transitions) {
            if (transition.isFromAny()) {
                return true;
            }
        }
        return false;
    }

    /** Step numbers start at 1, in the order of the request. */
    private static int stepOf(List<WorkflowStatus> statuses, WorkflowStatus status) {
        return statuses.indexOf(status) + 1;
    }

    private static int actionId(int transitionIndex) {
        return FIRST_TRANSITION_ID + TRANSITION_ID_STEP * transitionIndex;
    }

    static String escape(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&':
                    escaped.append("&amp;");
                    break;
                case '<':
                    escaped.append("&lt;");
                    break;
                case '>':
                    escaped.append("&gt;");
                    break;
                case '"':
                    escaped.append("&quot;");
                    break;
                case '\'':
                    escaped.append("&apos;");
                    break;
                default:
                    escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
