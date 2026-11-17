package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.ofbiz.OfBizListIterator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.EntityFieldMap;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesRequest.TypeMapping;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.ErrorCode;
import ru.rasulov.issuetypes.api.dto.SetProjectIssueTypesResponse.MappingView;
import ru.rasulov.common.api.dto.issuetype.SchemeAction;
import ru.rasulov.common.api.dto.StatusMapping;
import ru.rasulov.common.api.dto.StatusUsage;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ru.rasulov.common.impl.Normalizer.normalize;

/** Builds the complete issue-type and issue-migration plan without writing Jira. */
@Named
public class IssueTypeChangePlanner {
    public static final int MAX_ISSUE_TYPES = 500;
    static final int MAX_ISSUES_TO_MIGRATE = 10000;

    private final IssueTypeSchemeManager typeSchemeManager;
    private final WorkflowSchemeManager workflowSchemeManager;
    private final WorkflowManager workflowManager;
    private final IssueTypeManager issueTypeManager;
    private final StatusManager statusManager;
    private final IssueTypeResolver resolver;
    private final OfBizDelegator delegator;

    @Inject
    public IssueTypeChangePlanner(@ComponentImport IssueTypeSchemeManager typeSchemeManager,
                                  @ComponentImport WorkflowSchemeManager workflowSchemeManager,
                                  @ComponentImport WorkflowManager workflowManager,
                                  @ComponentImport IssueTypeManager issueTypeManager,
                                  @ComponentImport StatusManager statusManager,
                                  IssueTypeResolver resolver,
                                  @ComponentImport OfBizDelegator delegator) {
        this.typeSchemeManager = typeSchemeManager; this.workflowSchemeManager = workflowSchemeManager;
        this.workflowManager = workflowManager; this.issueTypeManager = issueTypeManager; this.statusManager = statusManager;
        this.resolver = resolver; this.delegator = delegator;
    }

    IssueTypeChangePlan plan(Project project, SetProjectIssueTypesRequest request) {
        return plan(project, request, Collections.<IssueTypeRef>emptyList(), null);
    }

    /**
     * @param pending       types of the wanted set that do not exist yet (no id): shown as added, never assigned by this plan
     * @param explicitDefaultId id of a type of the set that must become the default, or {@code null} for the usual rule
     */
    IssueTypeChangePlan plan(Project project, SetProjectIssueTypesRequest request, List<IssueTypeRef> pending,
                             String explicitDefaultId) {
        if (request.getIssueTypes().isEmpty() && pending.isEmpty()) {
            throw new IssueTypeChangeFailure(ErrorCode.VALIDATION_ERROR,
                    "issueTypes must contain the complete non-empty set wanted in the project.");
        }
        if (request.getIssueTypes().size() + pending.size() > MAX_ISSUE_TYPES) {
            throw new IssueTypeChangeFailure(ErrorCode.VALIDATION_ERROR,
                    "One call accepts at most " + MAX_ISSUE_TYPES + " issue types.");
        }
        List<IssueType> desired = resolveUnique(request.getIssueTypes(), "issueTypes");
        FieldConfigScheme source = typeSchemeManager.getConfigScheme(project);
        if (source == null) throw new IssueTypeChangeFailure(ErrorCode.JIRA_ERROR,
                "Project " + project.getKey() + " has no issue type scheme.");

        List<IssueType> current = new ArrayList<IssueType>(typeSchemeManager.getIssueTypesForProject(project));
        Set<String> desiredIds = ids(desired);
        Set<String> currentIds = ids(current);
        boolean addOnly = desiredIds.containsAll(currentIds) && request.getMappings().isEmpty();
        Map<String, Map<String, Integer>> usage = addOnly ? Collections.emptyMap() : usage(project);
        Map<String, IssueType> knownSources = byId(current);
        for (String id : usage.keySet()) {
            IssueType orphan = issueTypeManager.getIssueType(id);
            if (orphan != null) knownSources.put(id, orphan);
        }
        List<IssueTypeRef> added = refs(difference(desired, currentIds));
        added.addAll(pending);
        List<IssueType> leaving = difference(new ArrayList<IssueType>(knownSources.values()), desiredIds);
        List<IssueTypeRef> removed = refs(leaving);

        AssignableWorkflowScheme workflows = workflowSchemeManager.getWorkflowSchemeObj(project);
        Map<String, TypeMapping> requestsBySource = mappingRequests(request.getMappings());
        List<IssueTypeChangePlan.Mapping> mappings = new ArrayList<IssueTypeChangePlan.Mapping>();
        List<MappingView> views = new ArrayList<MappingView>();
        int issueTotal = 0;
        for (IssueType from : leaving) {
            int issues = total(usage.get(from.getId()));
            TypeMapping mappingRequest = requestsBySource.remove(from.getId());
            if (issues > 0 && mappingRequest == null) {
                throw new IssueTypeChangeFailure(ErrorCode.MAPPING_REQUIRED, issues + " issue(s) have type '"
                        + from.getName() + "', which leaves the project. Map it to one of the desired types.", names(desired));
            }
            if (mappingRequest == null) continue;
            IssueType to = resolve(mappingRequest.getTo());
            if (!desiredIds.contains(to.getId())) {
                throw new IssueTypeChangeFailure(ErrorCode.INVALID_MAPPING, "Mapping target '" + to.getName()
                        + "' is not in issueTypes.", names(desired));
            }
            if (from.isSubTask() != to.isSubTask()) {
                throw new IssueTypeChangeFailure(ErrorCode.INVALID_MAPPING, "Issue type '" + from.getName()
                        + "' cannot be mapped to '" + to.getName() + "': a sub-task must stay a sub-task and a "
                        + "standard issue must stay standard.");
            }
            JiraWorkflow fromWorkflow = workflowManager.getWorkflow(workflows.getActualWorkflow(from.getId()));
            JiraWorkflow toWorkflow = workflowManager.getWorkflow(workflows.getActualWorkflow(to.getId()));
            Map<String, Status> statuses = statusMappings(mappingRequest.getStatusMappings(), toWorkflow,
                    usage.get(from.getId()));
            List<StatusUsage> statusViews = statusViews(usage.get(from.getId()), toWorkflow, statuses);
            mappings.add(new IssueTypeChangePlan.Mapping(from, to, fromWorkflow, toWorkflow, statuses, issues));
            views.add(new MappingView(ref(from), ref(to), issues, fromWorkflow.getName(), toWorkflow.getName(), statusViews));
            issueTotal += issues;
        }
        if (!requestsBySource.isEmpty()) {
            TypeMapping unused = requestsBySource.values().iterator().next();
            throw new IssueTypeChangeFailure(ErrorCode.INVALID_MAPPING, "Mapping source '" + unused.getFrom()
                    + "' does not leave the project, or is mapped more than once.", names(leaving));
        }
        if (issueTotal > MAX_ISSUES_TO_MIGRATE) {
            throw new IssueTypeChangeFailure(ErrorCode.TOO_MANY_ISSUES, issueTotal + " issues would change type; one "
                    + "call changes at most " + MAX_ISSUES_TO_MIGRATE + ". Nothing was changed.");
        }

        boolean same = desiredIds.equals(currentIds) && leaving.isEmpty() && pending.isEmpty();
        List<String> others = otherProjects(source, project);
        SchemeAction action = same ? SchemeAction.NONE
                : typeSchemeManager.isDefaultIssueTypeScheme(source) || !others.isEmpty()
                ? SchemeAction.CREATE : SchemeAction.UPDATE;
        String name = action == SchemeAction.CREATE ? freeName(project.getKey() + ": Issue Type Scheme") : null;
        IssueType previousDefault = typeSchemeManager.getDefaultIssueType(project);
        List<IssueTypeRef> desiredViews = refs(desired);
        desiredViews.addAll(pending);
        return new IssueTypeChangePlan(project, source, action, name, desired, new ArrayList<String>(currentIds),
                previousDefault == null ? null : previousDefault.getId(), desiredViews, added, removed, mappings,
                views, others, explicitDefaultId);
    }

    private List<IssueType> resolveUnique(List<String> inputs, String field) {
        List<IssueType> result = new ArrayList<IssueType>();
        Set<String> seen = new HashSet<String>();
        for (String input : inputs) {
            IssueType type = resolve(input);
            if (!seen.add(type.getId())) throw new IssueTypeChangeFailure(ErrorCode.VALIDATION_ERROR,
                    "Issue type '" + type.getName() + "' appears more than once in " + field + ".");
            result.add(type);
        }
        return result;
    }

    private IssueType resolve(String input) {
        try { return resolver.resolve(input); }
        catch (LookupFailure e) { throw new IssueTypeChangeFailure(ErrorCode.UNKNOWN_ISSUE_TYPE, e.getMessage(), e.getSuggestions()); }
    }

    private Map<String, TypeMapping> mappingRequests(List<TypeMapping> requests) {
        Map<String, TypeMapping> result = new LinkedHashMap<String, TypeMapping>();
        for (TypeMapping request : requests) {
            if (request == null || request.getFrom() == null || request.getFrom().trim().isEmpty()
                    || request.getTo() == null || request.getTo().trim().isEmpty()) {
                throw new IssueTypeChangeFailure(ErrorCode.VALIDATION_ERROR,
                        "Every mappings item needs non-blank from and to issue types.");
            }
            IssueType source = resolve(request.getFrom());
            if (result.put(source.getId(), request) != null) throw new IssueTypeChangeFailure(ErrorCode.INVALID_MAPPING,
                    "Issue type '" + source.getName() + "' is mapped more than once.");
        }
        return result;
    }

    private Map<String, Status> statusMappings(List<StatusMapping> requested, JiraWorkflow target,
                                                Map<String, Integer> occupied) {
        Map<String, Status> all = new HashMap<String, Status>();
        for (Status status : statusManager.getStatuses()) all.put(normalize(status.getName()), status);
        Map<String, Status> targetByName = new HashMap<String, Status>();
        for (Status status : target.getLinkedStatusObjects()) targetByName.put(normalize(status.getName()), status);
        Map<String, Status> result = new LinkedHashMap<String, Status>();
        for (StatusMapping mapping : requested) {
            String from = mapping == null ? null : trim(mapping.getFrom());
            String to = mapping == null ? null : trim(mapping.getTo());
            Status source = from == null ? null : all.get(normalize(from));
            Status destination = to == null ? null : targetByName.get(normalize(to));
            if (source == null || destination == null) throw new IssueTypeChangeFailure(ErrorCode.INVALID_MAPPING,
                    "Every status mapping needs an existing source status and a target status in workflow '"
                            + target.getName() + "'.", statusNames(target));
            if (result.put(source.getId(), destination) != null) throw new IssueTypeChangeFailure(ErrorCode.INVALID_MAPPING,
                    "Status '" + source.getName() + "' is mapped more than once.");
        }
        if (occupied != null) {
            for (String statusId : occupied.keySet()) {
                if (!target.getLinkedStatusIds().contains(statusId) && !result.containsKey(statusId)) {
                    Status missing = statusManager.getStatus(statusId);
                    throw new IssueTypeChangeFailure(ErrorCode.STATUS_CONFLICT, occupied.get(statusId)
                            + " issue(s) are in status '" + (missing == null ? statusId : missing.getName())
                            + "', absent from target workflow '" + target.getName() + "'. Add an explicit statusMappings "
                            + "entry inside this issue-type mapping.", statusNames(target));
                }
            }
        }
        for (String id : result.keySet()) {
            if (occupied == null || !occupied.containsKey(id) || target.getLinkedStatusIds().contains(id)) {
                Status unused = statusManager.getStatus(id);
                throw new IssueTypeChangeFailure(ErrorCode.INVALID_MAPPING, "Status mapping from '"
                        + (unused == null ? id : unused.getName()) + "' is not needed for this issue-type mapping.");
            }
        }
        return result;
    }

    private List<StatusUsage> statusViews(Map<String, Integer> occupied, JiraWorkflow target, Map<String, Status> maps) {
        List<StatusUsage> result = new ArrayList<StatusUsage>();
        if (occupied == null) return result;
        for (Map.Entry<String, Integer> entry : occupied.entrySet()) {
            Status status = statusManager.getStatus(entry.getKey());
            Status mapped = maps.get(entry.getKey());
            result.add(new StatusUsage(status == null ? entry.getKey() : status.getName(), entry.getValue(),
                    target.getLinkedStatusIds().contains(entry.getKey()), mapped == null ? null : mapped.getName()));
        }
        return result;
    }

    private Map<String, Map<String, Integer>> usage(Project project) {
        Map<String, Map<String, Integer>> result = new LinkedHashMap<String, Map<String, Integer>>();
        EntityFieldMap condition = new EntityFieldMap(Collections.singletonMap("project", project.getId()), EntityOperator.AND);
        try (OfBizListIterator rows = delegator.findListIteratorByCondition("Issue", condition, null,
                Arrays.asList("type", "status"), null, null)) {
            GenericValue row;
            while ((row = rows.next()) != null) {
                Map<String, Integer> statuses = result.get(row.getString("type"));
                if (statuses == null) { statuses = new LinkedHashMap<String, Integer>(); result.put(row.getString("type"), statuses); }
                Integer count = statuses.get(row.getString("status"));
                statuses.put(row.getString("status"), count == null ? 1 : count + 1);
            }
        }
        return result;
    }

    private List<String> otherProjects(FieldConfigScheme scheme, Project project) {
        List<String> result = new ArrayList<String>();
        for (Project user : scheme.getAssociatedProjectObjects()) if (!user.getId().equals(project.getId())) result.add(user.getKey());
        Collections.sort(result);
        return result;
    }

    private String freeName(String base) {
        Set<String> used = new HashSet<String>();
        for (FieldConfigScheme scheme : typeSchemeManager.getAllSchemes()) used.add(normalize(scheme.getName()));
        if (!used.contains(normalize(base))) return base;
        for (int version = 2; ; version++) {
            String candidate = base + " v" + version;
            if (!used.contains(normalize(candidate))) return candidate;
        }
    }

    private static List<IssueType> sorted(Collection<IssueType> values) {
        List<IssueType> result = new ArrayList<IssueType>(values);
        Collections.sort(result, Comparator.comparing(IssueType::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }
    private static Map<String, IssueType> byId(List<IssueType> values) {
        Map<String, IssueType> result = new LinkedHashMap<String, IssueType>();
        for (IssueType value : values) result.put(value.getId(), value);
        return result;
    }
    private static Set<String> ids(List<IssueType> values) {
        Set<String> result = new LinkedHashSet<String>(); for (IssueType value : values) result.add(value.getId()); return result;
    }
    private static List<IssueType> difference(List<IssueType> values, Set<String> excluded) {
        List<IssueType> result = new ArrayList<IssueType>(); for (IssueType value : values) if (!excluded.contains(value.getId())) result.add(value); return result;
    }
    private static List<IssueTypeRef> refs(List<IssueType> values) {
        List<IssueTypeRef> result = new ArrayList<IssueTypeRef>(); for (IssueType value : values) result.add(ref(value)); return result;
    }
    private static IssueTypeRef ref(IssueType value) { return new IssueTypeRef(value.getId(), value.getName()); }
    private static List<String> names(List<IssueType> values) {
        List<String> result = new ArrayList<String>(); for (IssueType value : values) result.add(value.getName()); return result;
    }
    private static List<String> statusNames(JiraWorkflow workflow) {
        List<String> result = new ArrayList<String>(); for (Status value : workflow.getLinkedStatusObjects()) result.add(value.getName()); Collections.sort(result); return result;
    }
    private static int total(Map<String, Integer> values) { int result = 0; if (values != null) for (int value : values.values()) result += value; return result; }
    private static String trim(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
}
