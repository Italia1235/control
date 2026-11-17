package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowException;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowScheme;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.workflow.api.WorkflowAssignLimits;
import ru.rasulov.workflow.api.WorkflowReader;
import ru.rasulov.workflow.api.dto.edit.WorkflowView;
import ru.rasulov.workflow.impl.edit.DescriptorFacts.Kind;
import ru.rasulov.workflow.impl.edit.DescriptorFacts.Located;
import ru.rasulov.workflow.impl.edit.DescriptorFacts.ResolutionRule;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/** Reads one version of a workflow into the loss-aware model of {@code get_workflow}; never changes anything. */
@ExportAsService(WorkflowReader.class)
@Named
public class WorkflowInspector implements WorkflowReader {

    /** Steps and transitions together that a view returns; a larger workflow is refused, never cut. */
    public static final int MAX_VIEW_ELEMENTS = 1000;

    private final WorkflowManager workflowManager;
    private final WorkflowSchemeManager schemeManager;
    private final ConstantsManager constants;
    private final FieldScreenManager screens;
    private final OfBizDelegator delegator;
    private final AdministratorGuard guard;

    @Inject
    public WorkflowInspector(@ComponentImport WorkflowManager workflowManager,
                             @ComponentImport WorkflowSchemeManager schemeManager,
                             @ComponentImport ConstantsManager constants,
                             @ComponentImport FieldScreenManager screens,
                             @ComponentImport OfBizDelegator delegator, AdministratorGuard guard) {
        this.guard = guard;
        this.workflowManager = workflowManager;
        this.schemeManager = schemeManager;
        this.constants = constants;
        this.screens = screens;
        this.delegator = delegator;
    }

    /** The two versions of a workflow as they are now. {@code draft} is a private copy, {@code main} is Jira's cached object: read only. */
    public record Snapshot(String name, JiraWorkflow main, JiraWorkflow draft, boolean active, boolean system,
                           String revision) { }

    /** @return {@code null} when no workflow has this exact name */
    public Snapshot snapshot(String requested) {
        JiraWorkflow main = requested == null ? null : workflowManager.getWorkflow(requested.trim());
        if (main == null) return null;
        try {
            boolean active = workflowManager.isActive(main);
            boolean system = main.isSystemWorkflow();
            JiraWorkflow draft = active ? workflowManager.getDraftWorkflow(main.getName()) : null;
            Map<String, Object> state = new TreeMap<String, Object>();
            state.put("name", main.getName());
            state.put("active", active);
            state.put("system", system);
            state.put("main", WorkflowSnapshots.workflowFingerprint(main, delegator));
            state.put("draft", draft == null ? null
                    : WorkflowSnapshots.hash(WorkflowSnapshots.json(WorkflowSnapshots.workflow(draft))));
            return new Snapshot(main.getName(), main, draft, active, system,
                    WorkflowSnapshots.hash(WorkflowSnapshots.json(state)));
        } catch (WorkflowException e) {
            throw new IllegalStateException("Could not read the state of workflow '" + main.getName() + "'", e);
        }
    }

    @Override
    public WorkflowView read(String workflow, String version) {
        guard.requireAdministrator();
        String name = workflow == null ? "" : workflow.trim();
        if (name.isEmpty()) throw new InvalidRequestException("'workflow' is blank. Give the exact workflow name.");
        if (!"ACTIVE".equals(version) && !"DRAFT".equals(version)) {
            throw new InvalidRequestException("'version' must be ACTIVE or DRAFT.");
        }
        Snapshot s = snapshot(name);
        if (s == null) {
            throw new InvalidRequestException("No workflow is named '" + name + "'. Names are exact; use "
                    + "get_project_workflows to find them.");
        }
        JiraWorkflow shown = "DRAFT".equals(version) ? s.draft() : s.main();
        if (shown == null) {
            throw new InvalidRequestException("The workflow '" + s.name() + "' has no draft"
                    + (s.active() ? "" : " (it is not active, so it has only its saved version)")
                    + ". Read version ACTIVE instead; no other version is returned in place of DRAFT.");
        }
        WorkflowDescriptor d = shown.getDescriptor();
        int elements = DescriptorFacts.steps(d).size() + DescriptorFacts.locate(d).size();
        if (elements > MAX_VIEW_ELEMENTS) {
            throw new InvalidRequestException("The workflow has " + elements + " steps and transitions; get_workflow "
                    + "returns at most " + MAX_VIEW_ELEMENTS + " and does not cut a graph. Nothing was read.");
        }
        Consumers consumers = consumers(s.main());
        return new WorkflowView(s.name(), version, s.revision(), nativeId(s.main()), shown.getDescription(),
                s.system(), s.active(), s.draft() != null, steps(d), transitions(d), capabilities(s, version),
                consumers.view(), s.draft() == null ? Collections.<String>emptyList()
                : DescriptorDiff.lines(s.main().getDescriptor(), s.draft().getDescriptor()));
    }

    private Long nativeId(JiraWorkflow main) {
        try {
            Object id = WorkflowSnapshots.workflow(main, delegator).get("id");
            return id == null ? null : WorkflowSnapshots.number(id);
        } catch (IllegalStateException e) {
            return null;
        }
    }

    private List<WorkflowView.StepView> steps(WorkflowDescriptor d) {
        TreeSet<Integer> initialTargets = new TreeSet<Integer>();
        List<ActionDescriptor> initial = DescriptorFacts.initialActions(d);
        for (ActionDescriptor a : initial) initialTargets.addAll(DescriptorFacts.targets(a));
        List<WorkflowView.StepView> steps = new ArrayList<WorkflowView.StepView>();
        for (StepDescriptor step : DescriptorFacts.steps(d)) {
            String statusId = DescriptorFacts.statusId(step);
            Status status = statusId == null ? null : constants.getStatus(statusId);
            steps.add(new WorkflowView.StepView(step.getId(), statusId, status == null ? step.getName() : status.getName(),
                    status == null || status.getStatusCategory() == null ? null : status.getStatusCategory().getKey(),
                    status == null ? null : status.getDescription(), initialTargets.contains(step.getId())));
        }
        return steps;
    }

    private List<WorkflowView.TransitionView> transitions(WorkflowDescriptor d) {
        List<WorkflowView.TransitionView> list = new ArrayList<WorkflowView.TransitionView>();
        for (Located l : DescriptorFacts.locate(d).values()) {
            ResolutionRule rule = DescriptorFacts.rule(l.action());
            Resolution resolution = "SET".equals(rule.action()) ? constants.getResolution(rule.value()) : null;
            List<String> unsupported = new ArrayList<String>();
            String blocker = DescriptorFacts.structureBlocker(l);
            if (blocker != null) unsupported.add("source, target, resolution, removal: " + blocker);
            if (l.duplicated() || l.kind() == Kind.INITIAL) unsupported.add("name, screen: " + blocker);
            list.add(new WorkflowView.TransitionView(l.id(), l.kind().name(), l.action().getName(), l.sources(),
                    DescriptorFacts.targets(l.action()), DescriptorFacts.meta(l.action()).get(DescriptorFacts.SCREEN_META),
                    new WorkflowView.Rule(rule.action(), rule.value(), resolution == null ? null : resolution.getName(),
                            rule.reason()), unsupported));
        }
        return list;
    }

    private List<String> capabilities(Snapshot s, String version) {
        List<String> lines = new ArrayList<String>();
        if (s.system()) {
            lines.add("NO EDIT: SYSTEM_WORKFLOW. The built-in workflow cannot be edited in place; copy it first.");
            return lines;
        }
        if (!s.active()) {
            lines.add("version ACTIVE: an edit is saved in the workflow itself (UPDATED_INACTIVE); it is not used by "
                    + "any project. Every step may be replaced or removed.");
            lines.add("rename_workflow is possible: the workflow is inactive.");
        } else if (s.draft() == null) {
            lines.add("version ACTIVE: an edit creates the draft and saves in it (DRAFT_SAVED); the active version is "
                    + "unchanged until publish_workflow_draft.");
        } else {
            lines.add("version ACTIVE: an edit is refused (DRAFT_EXISTS); read version DRAFT and edit it with its "
                    + "revision. The draft is shared by all administrators.");
        }
        if (s.active()) {
            TreeSet<Integer> inActive = DescriptorDiff.stepIds(s.main().getDescriptor());
            lines.add("REPLACE_STATUS: refused (MIGRATION_REQUIRED) for steps " + inActive
                    + " of the active version, even without issues; allowed for steps added in a draft. "
                    + "REMOVE_STATUS: allowed for an active-version step only while no issue is in it.");
            lines.add("rename_workflow: refused for an active workflow (Jira renames only workflows that are not "
                    + "used).");
        }
        lines.add("UNSUPPORTED_DESCRIPTOR: common actions (name and screen only), conditional results, joins/splits, "
                + "UNKNOWN resolution writers: see each transition's 'unsupported'.");
        return lines;
    }

    // ---- consumers ----------------------------------------------------------------------------------------------

    /** Everything that uses the workflow, complete; the view is a bounded rendering of it. */
    public Consumers consumers(JiraWorkflow main) {
        TreeSet<Long> schemeIds = new TreeSet<Long>();
        Map<Long, String> schemeLines = new TreeMap<Long, String>();
        Map<String, String> projects = new TreeMap<String, String>();
        for (WorkflowScheme scheme : schemeManager.getSchemesForWorkflowIncludingDrafts(main)) {
            schemeIds.add(scheme.getId());
            schemeLines.put(scheme.getId(), schemeLine(scheme, main.getName()));
            if (scheme instanceof AssignableWorkflowScheme) {
                for (Project project : schemeManager.getProjectsUsing((AssignableWorkflowScheme) scheme)) {
                    projects.put(project.getKey(), project.getName());
                }
            }
        }
        return new Consumers(schemeIds, new ArrayList<String>(schemeLines.values()), projects);
    }

    private String schemeLine(WorkflowScheme scheme, String workflow) {
        List<String> types = new ArrayList<String>();
        for (Map.Entry<String, String> e : scheme.getMappings().entrySet()) {
            if (e.getKey() != null && workflow.equals(e.getValue())) {
                IssueType type = constants.getIssueType(e.getKey());
                types.add(type == null ? e.getKey() : type.getName());
            }
        }
        Collections.sort(types, String.CASE_INSENSITIVE_ORDER);
        if (workflow.equals(scheme.getConfiguredDefaultWorkflow())) types.add("all unmapped issue types");
        return scheme.getName() + " [" + scheme.getId() + "]" + (scheme.isDraft() ? " (scheme draft)" : "") + ": "
                + String.join(", ", types);
    }

    public record Consumers(TreeSet<Long> schemeIds, List<String> schemeLines, Map<String, String> projects) {

        WorkflowView.Consumers view() {
            List<String> names = new ArrayList<String>();
            for (Map.Entry<String, String> e : projects.entrySet()) names.add(e.getKey() + " (" + e.getValue() + ")");
            int shown = WorkflowAssignLimits.MAX_PROJECTS_SHOWN;
            boolean truncated = names.size() > shown || schemeLines.size() > shown;
            return new WorkflowView.Consumers(schemeIds.size(), projects.size(),
                    schemeLines.size() > shown ? schemeLines.subList(0, shown) : schemeLines,
                    names.size() > shown ? names.subList(0, shown) : names, truncated);
        }

        /** What a plan is bound to: all of it, not the shown part. */
        Map<String, Object> fingerprint() {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("schemes", new ArrayList<Long>(schemeIds));
            m.put("schemeLines", schemeLines);
            m.put("projects", new ArrayList<String>(projects.keySet()));
            return m;
        }
    }

    // ---- catalog ------------------------------------------------------------------------------------------------

    /** What the patcher may ask Jira, read live. */
    public WorkflowDescriptorPatcher.Catalog catalog() {
        return new WorkflowDescriptorPatcher.Catalog() {
            @Override
            public WorkflowDescriptorPatcher.StatusInfo status(String id) {
                Status s = constants.getStatus(id);
                return s == null ? null : new WorkflowDescriptorPatcher.StatusInfo(s.getId(), s.getName(),
                        s.getStatusCategory() == null ? null : s.getStatusCategory().getKey());
            }

            @Override
            public boolean screenExists(long id) {
                return screens.getFieldScreen(id) != null;
            }

            @Override
            public WorkflowDescriptorPatcher.ResolutionInfo resolution(String id) {
                Resolution r = constants.getResolution(id);
                return r == null || !id.equals(r.getId()) ? null
                        : new WorkflowDescriptorPatcher.ResolutionInfo(r.getId(), r.getName());
            }
        };
    }

    /** Properties of the referenced statuses, screens and resolutions a plan depends on. */
    public Map<String, Object> referenced(WorkflowDescriptor patched) {
        Map<String, Object> m = new TreeMap<String, Object>();
        Map<String, Object> statuses = new TreeMap<String, Object>();
        for (StepDescriptor step : DescriptorFacts.steps(patched)) {
            String id = DescriptorFacts.statusId(step);
            Status s = id == null ? null : constants.getStatus(id);
            statuses.put(String.valueOf(id), s == null ? null : WorkflowSnapshots.status(s));
        }
        m.put("statuses", statuses);
        Map<String, Object> resolutions = new TreeMap<String, Object>();
        Map<String, Object> screenRows = new TreeMap<String, Object>();
        for (Located l : DescriptorFacts.locate(patched).values()) {
            ResolutionRule rule = DescriptorFacts.rule(l.action());
            if (rule.value() != null) {
                Resolution r = constants.getResolution(rule.value());
                resolutions.put(rule.value(), r == null ? null : r.getName());
            }
            String screen = DescriptorFacts.meta(l.action()).get(DescriptorFacts.SCREEN_META);
            if (screen != null) {
                Long id = parse(screen);
                screenRows.put(screen, id == null || screens.getFieldScreen(id) == null ? null
                        : screens.getFieldScreen(id).getName());
            }
        }
        m.put("resolutions", resolutions);
        m.put("screens", screenRows);
        return m;
    }

    private static Long parse(String text) {
        try {
            return Long.valueOf(text.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
