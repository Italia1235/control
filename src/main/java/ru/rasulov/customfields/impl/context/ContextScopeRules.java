package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Pure final-set validation and scope differences. Never enumerates Jira project/type catalogs or writes Jira.
 *
 * Combination rows use project keys and issue type ids. A wildcard is "*"; excludedProjects (keys) and
 * excludedIssueTypes (ids) subtract the already covered part of that wildcard. For example LOT/14900 ->
 * wildcard-projects/14900 adds {project:"*", issueType:"14900", excludedProjects:["LOT"]}, not LOT/14900 again.
 * Differences are disjoint rectangles: (P2\\P1) x T2 plus (P2 intersect P1) x (T2\\T1).
 * count is exact for finite pairs; if any rectangle has a wildcard it counts symbolic rows and explicitly
 * carries countUnit=SYMBOLIC_COMBINATIONS, not a guessed number of Jira projects or issue types.
 * combinations contains at most 50 rows, in project-id then issue-type-id order; combinationsComplete says
 * whether that list is complete. Axis additions/removals use full refs, or "*" for a changed wildcard axis;
 * the combination exclusions and before/after scopes explain its exact difference.
 */
public final class ContextScopeRules {
    private static final int COMBINATION_LIMIT = 50;
    private static final Comparator<String> PROJECT_IDS = Comparator.comparingLong(Long::parseLong);

    private ContextScopeRules() { }

    public static final class Scope {
        private final boolean allProjects;
        private final List<Project> projects;
        private final boolean allIssueTypes;
        private final List<IssueType> issueTypes;
        private final List<Long> projectIds;
        private final List<String> issueTypeIds;

        public Scope(boolean allProjects, List<Project> projects, boolean allIssueTypes, List<IssueType> issueTypes) {
            this.allProjects = allProjects;
            this.allIssueTypes = allIssueTypes;
            TreeMap<Long, Project> sortedProjects = new TreeMap<Long, Project>();
            if (!allProjects && projects != null) for (Project project : projects) sortedProjects.put(project.getId(), project);
            TreeMap<String, IssueType> sortedTypes = new TreeMap<String, IssueType>();
            if (!allIssueTypes && issueTypes != null) for (IssueType type : issueTypes) sortedTypes.put(type.getId(), type);
            this.projects = Collections.unmodifiableList(new ArrayList<Project>(sortedProjects.values()));
            this.issueTypes = Collections.unmodifiableList(new ArrayList<IssueType>(sortedTypes.values()));
            this.projectIds = Collections.unmodifiableList(new ArrayList<Long>(sortedProjects.keySet()));
            this.issueTypeIds = Collections.unmodifiableList(new ArrayList<String>(sortedTypes.keySet()));
        }

        public boolean isAllProjects() { return allProjects; }
        public List<Project> getProjects() { return projects; }
        public boolean isAllIssueTypes() { return allIssueTypes; }
        public List<IssueType> getIssueTypes() { return issueTypes; }

        /** Full explicit references; Jira Project/IssueType implementations are never exposed to Jackson. */
        public Map<String, Object> toView() {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("allProjects", allProjects);
            List<Map<String, Object>> projectViews = new ArrayList<Map<String, Object>>(projects.size());
            for (Project project : projects) projectViews.add(projectView(project));
            view.put("projects", projectViews);
            view.put("allIssueTypes", allIssueTypes);
            List<Map<String, Object>> typeViews = new ArrayList<Map<String, Object>>(issueTypes.size());
            for (IssueType type : issueTypes) typeViews.add(typeView(type));
            view.put("issueTypes", typeViews);
            return view;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Scope)) return false;
            Scope scope = (Scope) other;
            return allProjects == scope.allProjects && allIssueTypes == scope.allIssueTypes
                    && projectIds.equals(scope.projectIds) && issueTypeIds.equals(scope.issueTypeIds);
        }
        @Override public int hashCode() { return Objects.hash(allProjects, projectIds, allIssueTypes, issueTypeIds); }
    }

    public static Scope from(FieldConfigScheme scheme) {
        return new Scope(scheme.isAllProjects(), scheme.isAllProjects() ? null
                : new ArrayList<Project>(scheme.getAssociatedProjectObjects()), scheme.isAllIssueTypes(),
                scheme.isAllIssueTypes() ? null : new ArrayList<IssueType>(scheme.getAssociatedIssueTypes()));
    }

    /**
     * Validate every context's final scope together, including contexts not changed by this request. Contexts outside
     * {@code requested} claim projects and the global slot first, so a conflict names the existing owner, not the
     * context the request is changing.
     */
    public static void validate(Map<Long, Scope> finalScopes, Map<Long, String> contextNames, Set<Long> requested) {
        Map<Long, Long> owners = new HashMap<Long, Long>();
        Long globalOwner = null;
        List<Map.Entry<Long, Scope>> ordered = new ArrayList<Map.Entry<Long, Scope>>(new TreeMap<Long, Scope>(finalScopes).entrySet());
        ordered.sort(Comparator.comparing((Map.Entry<Long, Scope> entry) -> requested.contains(entry.getKey())));
        for (Map.Entry<Long, Scope> entry : ordered) {
            Long contextId = entry.getKey();
            Scope scope = entry.getValue();
            if (scope.allProjects) {
                if (globalOwner != null) throw new Failure("GLOBAL_CONTEXT_EXISTS",
                        "Global context already exists: " + globalOwner + " (" + contextNames.get(globalOwner) + ").",
                        globalOwner, contextNames.get(globalOwner), null);
                globalOwner = contextId;
            } else for (Project project : scope.projects) {
                Long owner = owners.putIfAbsent(project.getId(), contextId);
                if (owner != null) throw new Failure("PROJECT_IN_OTHER_CONTEXT",
                        "Project " + project.getKey() + " already belongs to context " + owner
                                + " (" + contextNames.get(owner) + "). A project may belong to only one project-scoped context of this field.",
                        owner, contextNames.get(owner), project.getId());
            }
        }
    }

    public static Map<String, Object> delta(Scope before, Scope after) {
        Map<String, String> projectKeys = new HashMap<String, String>();
        for (Project project : before.projects) projectKeys.put(project.getId().toString(), project.getKey());
        for (Project project : after.projects) projectKeys.put(project.getId().toString(), project.getKey());
        Axis beforeProjects = projectAxis(before), afterProjects = projectAxis(after);
        Axis beforeTypes = typeAxis(before), afterTypes = typeAxis(after);
        Map<String, Object> added = combinations(afterProjects, afterTypes, beforeProjects, beforeTypes, projectKeys);
        Map<String, Object> removed = combinations(beforeProjects, beforeTypes, afterProjects, afterTypes, projectKeys);
        Map<String, Object> delta = new LinkedHashMap<String, Object>();
        delta.put("addedProjects", projectChanges(before, after));
        delta.put("removedProjects", projectChanges(after, before));
        delta.put("addedIssueTypes", typeChanges(before, after));
        delta.put("removedIssueTypes", typeChanges(after, before));
        delta.put("addedCombinations", added);
        delta.put("removedCombinations", removed);
        List<Map<String, Object>> warnings = new ArrayList<Map<String, Object>>();
        if (((Number) removed.get("count")).longValue() > 0) warnings.add(warning("NARROWING",
                "The field stops applying to the removed project/type combinations. Existing issue values are not deleted, but may become invisible and unsearchable until the scope is restored."));
        if (!afterProjects.difference(beforeProjects).isEmpty()
                && (after.allIssueTypes || after.issueTypes.size() > 1)) warnings.add(warning("EXTRA_COMBINATIONS",
                "Each added project receives every issue type in the final context, not only one requested project/type pair."));
        if (after.allProjects) warnings.add(warning("GLOBAL_SCOPE",
                "The context applies to all projects, including future projects; the wildcard is not a catalog enumeration."));
        if (!after.allProjects && !after.allIssueTypes) for (Project project : after.projects) {
            Set<String> offered = new HashSet<String>();
            for (IssueType type : project.getIssueTypes()) offered.add(type.getId());
            for (IssueType type : after.issueTypes) if (!offered.contains(type.getId())) {
                Map<String, Object> warning = warning("TYPE_NOT_IN_PROJECT", "Issue type '" + type.getName()
                        + "' is not used by project " + project.getKey() + "; the field will not appear for that combination.");
                warning.put("project", project.getKey()); warning.put("issueType", type.getId());
                warnings.add(warning);
            }
        }
        delta.put("warnings", warnings);
        return delta;
    }

    private static List<Object> projectChanges(Scope before, Scope after) {
        if (after.allProjects) return before.allProjects ? Collections.emptyList() : Collections.<Object>singletonList("*");
        if (before.allProjects) return Collections.emptyList();
        Set<Long> previous = new HashSet<Long>(before.projectIds);
        List<Object> changes = new ArrayList<Object>();
        for (Project project : after.projects) if (!previous.contains(project.getId())) changes.add(projectView(project));
        return changes;
    }

    private static List<Object> typeChanges(Scope before, Scope after) {
        if (after.allIssueTypes) return before.allIssueTypes ? Collections.emptyList() : Collections.<Object>singletonList("*");
        if (before.allIssueTypes) return Collections.emptyList();
        Set<String> previous = new HashSet<String>(before.issueTypeIds);
        List<Object> changes = new ArrayList<Object>();
        for (IssueType type : after.issueTypes) if (!previous.contains(type.getId())) changes.add(typeView(type));
        return changes;
    }

    private static Map<String, Object> projectView(Project project) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", project.getId()); view.put("key", project.getKey()); view.put("name", project.getName()); return view;
    }
    private static Map<String, Object> typeView(IssueType type) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", type.getId()); view.put("name", type.getName()); return view;
    }
    private static Map<String, Object> warning(String code, String message) {
        Map<String, Object> warning = new LinkedHashMap<String, Object>();
        warning.put("code", code); warning.put("message", message); return warning;
    }

    private static Axis projectAxis(Scope scope) {
        List<String> ids = new ArrayList<String>(scope.projects.size());
        for (Long id : scope.projectIds) ids.add(id.toString());
        return new Axis(scope.allProjects, ids, Collections.emptyList());
    }
    private static Axis typeAxis(Scope scope) { return new Axis(scope.allIssueTypes, scope.issueTypeIds, Collections.emptyList()); }

    /** Axes entering difference/intersection are plain scopes; exclusions occur only in the resulting rectangles. */
    private static final class Axis {
        private final boolean wildcard;
        private final List<String> ids;
        private final List<String> excluded;
        private Axis(boolean wildcard, List<String> ids, List<String> excluded) {
            this.wildcard = wildcard; this.ids = ids; this.excluded = excluded;
        }
        private boolean isEmpty() { return !wildcard && ids.isEmpty(); }
        private int size() { return wildcard ? 1 : ids.size(); }
        private List<String> tokens() { return wildcard ? Collections.singletonList("*") : ids; }
        private Axis difference(Axis other) {
            if (other.wildcard) return new Axis(false, Collections.emptyList(), Collections.emptyList());
            if (wildcard) return new Axis(true, Collections.emptyList(), other.ids);
            return select(other, false);
        }
        private Axis intersection(Axis other) {
            if (wildcard) return other;
            if (other.wildcard) return this;
            return select(other, true);
        }
        private Axis select(Axis other, boolean retain) {
            Set<String> otherIds = new HashSet<String>(other.ids);
            List<String> selected = new ArrayList<String>();
            for (String id : ids) if (otherIds.contains(id) == retain) selected.add(id);
            return new Axis(false, selected, Collections.emptyList());
        }
    }

    private static Map<String, Object> combinations(Axis projects, Axis types, Axis previousProjects,
                                                     Axis previousTypes, Map<String, String> keys) {
        return summarize(new Rectangle(projects.difference(previousProjects), types),
                new Rectangle(projects.intersection(previousProjects), types.difference(previousTypes)), keys);
    }

    /**
     * The project/type combinations both scopes cover, in the shape of {@link #delta}'s combination summaries. Used to
     * name where a new context meets an existing one, e.g. a project context over a global context.
     */
    public static Map<String, Object> overlap(Scope a, Scope b) {
        Map<String, String> keys = new HashMap<String, String>();
        for (Project project : a.projects) keys.put(project.getId().toString(), project.getKey());
        for (Project project : b.projects) keys.put(project.getId().toString(), project.getKey());
        return summarize(new Rectangle(projectAxis(a).intersection(projectAxis(b)), typeAxis(a).intersection(typeAxis(b))),
                new Rectangle(new Axis(false, Collections.emptyList(), Collections.emptyList()), typeAxis(b)), keys);
    }

    private static Map<String, Object> summarize(Rectangle first, Rectangle second, Map<String, String> keys) {
        long count = first.count() + second.count();
        List<Combination> candidates = new ArrayList<Combination>((int) Math.min(2L * COMBINATION_LIMIT, count));
        first.append(candidates); second.append(candidates);
        candidates.sort(Comparator.comparing((Combination row) -> row.project,
                Comparator.nullsFirst(PROJECT_IDS)).thenComparing(row -> row.type, Comparator.nullsFirst(Comparator.naturalOrder())));
        int shown = Math.min(COMBINATION_LIMIT, candidates.size());
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(shown);
        for (int i = 0; i < shown; i++) rows.add(candidates.get(i).toView(keys));
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        summary.put("count", count); summary.put("combinations", rows);
        summary.put("combinationsComplete", count == shown);
        if (first.symbolic() || second.symbolic()) summary.put("countUnit", "SYMBOLIC_COMBINATIONS");
        return summary;
    }

    private static final class Rectangle {
        private final Axis projects, types;
        private Rectangle(Axis projects, Axis types) { this.projects = projects; this.types = types; }
        private long count() { return (long) projects.size() * types.size(); }
        private boolean symbolic() { return count() > 0 && (projects.wildcard || types.wildcard); }
        private void append(List<Combination> rows) {
            if (count() == 0) return;
            int added = 0;
            for (String project : projects.tokens()) for (String type : types.tokens()) {
                if (added++ == COMBINATION_LIMIT) return;
                rows.add(new Combination("*".equals(project) ? null : project, "*".equals(type) ? null : type,
                        projects.excluded, types.excluded));
            }
        }
    }

    private static final class Combination {
        private final String project, type;
        private final List<String> excludedProjects, excludedTypes;
        private Combination(String project, String type, List<String> excludedProjects, List<String> excludedTypes) {
            this.project = project; this.type = type; this.excludedProjects = excludedProjects; this.excludedTypes = excludedTypes;
        }
        private Map<String, Object> toView(Map<String, String> keys) {
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("project", project == null ? "*" : keys.get(project)); row.put("issueType", type == null ? "*" : type);
            if (!excludedProjects.isEmpty()) {
                List<String> excluded = new ArrayList<String>(excludedProjects.size());
                for (String id : excludedProjects) excluded.add(keys.get(id));
                row.put("excludedProjects", excluded);
            }
            if (!excludedTypes.isEmpty()) row.put("excludedIssueTypes", excludedTypes);
            return row;
        }
    }

    public static final class Failure extends RuntimeException {
        private final String code;
        private final Long contextId, projectId;
        private final String contextName;
        private Failure(String code, String message, Long contextId, String contextName, Long projectId) {
            super(message); this.code = code; this.contextId = contextId; this.contextName = contextName; this.projectId = projectId;
        }
        public String getCode() { return code; }
        public Long getContextId() { return contextId; }
        public String getContextName() { return contextName; }
        public Long getProjectId() { return projectId; }
    }
}
