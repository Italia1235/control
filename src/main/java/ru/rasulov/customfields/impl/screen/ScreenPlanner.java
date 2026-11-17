package ru.rasulov.customfields.impl.screen;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperation;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.common.api.dto.field.context.ContextSummary;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.customfields.api.dto.screen.Recommendation;
import ru.rasulov.customfields.api.dto.screen.ScreenCandidate;
import ru.rasulov.customfields.api.dto.screen.ScreenOperation;
import ru.rasulov.customfields.api.dto.screen.ScreenPlan;
import ru.rasulov.customfields.api.dto.screen.ScreenSummary;
import ru.rasulov.customfields.api.dto.screen.TabRef;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out where a field could be put on screens, from the contexts it really has.
 * <pre>
 * context -> projects and issue types
 *         -> project's issue type screen scheme -> screen scheme for the issue type -> screen for create / edit / view
 * </pre>
 * and for every screen found, how far it reaches (see {@link ScreenReach}). Reads only.
 */
@Named
public class ScreenPlanner {

    private static final Map<ScreenOperation, IssueOperation> OPERATIONS = operations();

    private final ProjectManager projectManager;
    private final FieldScreenSchemeManager screenSchemeManager;
    private final IssueTypeScreenSchemeManager issueTypeScreenSchemeManager;

    @Inject
    public ScreenPlanner(@ComponentImport ProjectManager projectManager,
                         @ComponentImport FieldScreenSchemeManager screenSchemeManager,
                         @ComponentImport IssueTypeScreenSchemeManager issueTypeScreenSchemeManager) {
        this.projectManager = projectManager;
        this.screenSchemeManager = screenSchemeManager;
        this.issueTypeScreenSchemeManager = issueTypeScreenSchemeManager;
    }

    /**
     * @param contexts the contexts of the field as shown to the caller; only echoed into the plan
     */
    public ScreenPlan plan(CustomField field, List<ContextSummary> contexts) {
        return plan(field, contexts, null, null);
    }

    public ScreenPlan plan(CustomField field, List<ContextSummary> contexts, String cursor,
                           Recommendation recommendation) {
        if (recommendation != null && recommendation != Recommendation.RECOMMENDED) {
            throw new ru.rasulov.common.api.exception.InvalidRequestException(
                    "'recommendation' must be RECOMMENDED or omitted.");
        }
        Reads reads = new Reads();
        Map<Long, ScreenUsage> usages = findScreens(field, reads);
        ScreenReach reach = new ScreenReach(screenSchemeManager, issueTypeScreenSchemeManager);

        List<ScreenCandidate> candidates = new ArrayList<ScreenCandidate>(usages.size());
        for (ScreenUsage usage : usages.values()) {
            candidates.add(candidate(field.getId(), usage, reach, reads));
        }
        Collections.sort(candidates, BY_RECOMMENDATION_THEN_NAME);

        ScreenSummary summary = summarize(candidates);
        List<ScreenCandidate> selected = candidates;
        if (recommendation != null) {
            selected = new ArrayList<ScreenCandidate>(summary.getRecommended());
            for (ScreenCandidate candidate : candidates) {
                if (candidate.getRecommendation() == recommendation) selected.add(candidate);
            }
        }
        String fingerprint = cursor != null || selected.size() > Limits.MAX_SCREENS_IN_PLAN
                ? ScreenPlanCursor.fingerprint(new ScreenPlan(field.getId(), field.getUntranslatedName(),
                        contexts, candidates.size(), false, summary, candidates),
                        cursorMembership(field, usages, reach)) : null;
        int start = cursor == null ? 0 : ScreenPlanCursor.start(cursor, field.getId(), recommendation,
                fingerprint, selected);
        int end = Math.min(start + Limits.MAX_SCREENS_IN_PLAN, selected.size());
        List<ScreenCandidate> shown = selected.subList(start, end);
        String next = end < selected.size() ? ScreenPlanCursor.next(field.getId(), recommendation,
                fingerprint, selected.get(end - 1).getScreenId()) : null;
        return new ScreenPlan(field.getId(), field.getUntranslatedName(), contexts, candidates.size(),
                candidates.size() > shown.size(), summary, shown, next);
    }

    /** Include uncapped membership: swapping an unseen sharing project must invalidate continuation too. */
    private static Object cursorMembership(CustomField field, Map<Long, ScreenUsage> usages, ScreenReach reach) {
        Map<String, Object> membership = new java.util.TreeMap<String, Object>();
        Map<Long, Object> screens = new java.util.TreeMap<Long, Object>();
        for (ScreenUsage usage : usages.values()) {
            Map<String, Object> one = new java.util.TreeMap<String, Object>();
            one.put("inContext", new ArrayList<Long>(usage.projects().keySet()));
            one.put("users", new ArrayList<Long>(reach.projectIdsUsing(usage.screen())));
            screens.put(usage.screen().getId(), one);
        }
        membership.put("screens", screens);
        List<Object> contexts = new ArrayList<Object>();
        for (FieldConfigScheme context : field.getConfigurationSchemes()) {
            Map<String, Object> one = new java.util.TreeMap<String, Object>();
            one.put("allProjects", context.isAllProjects());
            one.put("allIssueTypes", context.isAllIssueTypes());
            List<Long> projects = new ArrayList<Long>();
            if (!context.isAllProjects()) {
                for (Project project : context.getAssociatedProjectObjects()) projects.add(project.getId());
            }
            List<String> types = new ArrayList<String>();
            if (!context.isAllIssueTypes()) {
                for (IssueType type : context.getAssociatedIssueTypes()) types.add(type.getId());
            }
            one.put("projects", projects);
            one.put("issueTypes", types);
            contexts.add(one);
        }
        membership.put("contexts", contexts);
        return membership;
    }

    /** What one plan has read from the project catalog; a later plan reads it again and sees the current state. */
    private final class Reads {
        private Collection<Project> all;
        private final Map<Long, Project> byId = new java.util.HashMap<Long, Project>();

        Collection<Project> allProjects() {
            if (all == null) {
                all = projectManager.getProjects();
            }
            return all;
        }

        private final Map<String, FieldScreenScheme> effective = new java.util.HashMap<String, FieldScreenScheme>();

        /** The screen scheme an issue type gets from an issue type screen scheme; projects sharing one share it. */
        FieldScreenScheme effectiveScheme(IssueTypeScreenScheme scheme, IssueType issueType) {
            String key = scheme.getId() + ":" + issueType.getId();
            if (!effective.containsKey(key)) {
                effective.put(key, scheme.getEffectiveFieldScreenScheme(issueType));
            }
            return effective.get(key);
        }

        Project project(Long id) {
            if (!byId.containsKey(id)) {
                byId.put(id, projectManager.getProjectObj(id));
            }
            return byId.get(id);
        }
    }

    /** Every screen used, for create, edit or view, by a project and issue type of any context of the field. */
    private Map<Long, ScreenUsage> findScreens(CustomField field, Reads reads) {
        Map<Long, ScreenUsage> usages = new LinkedHashMap<Long, ScreenUsage>();
        for (FieldConfigScheme context : field.getConfigurationSchemes()) {
            Collection<IssueType> contextTypes = context.isAllIssueTypes() ? null : context.getAssociatedIssueTypes();
            for (Project project : projectsOf(context, reads)) {
                IssueTypeScreenScheme scheme = issueTypeScreenSchemeManager.getIssueTypeScreenScheme(project);
                if (scheme == null) {
                    continue;
                }
                for (IssueType issueType : project.getIssueTypes()) {
                    if (contextTypes != null && !containsType(contextTypes, issueType)) {
                        continue;
                    }
                    collect(usages, reads.effectiveScheme(scheme, issueType), project, issueType);
                }
            }
        }
        return usages;
    }

    private void collect(Map<Long, ScreenUsage> usages, FieldScreenScheme screenScheme, Project project,
                         IssueType issueType) {
        if (screenScheme == null) {
            return;
        }
        for (Map.Entry<ScreenOperation, IssueOperation> operation : OPERATIONS.entrySet()) {
            FieldScreen screen = screenScheme.getFieldScreen(operation.getValue());
            if (screen == null) {
                continue;
            }
            ScreenUsage usage = usages.get(screen.getId());
            if (usage == null) {
                usage = new ScreenUsage(screen);
                usages.put(screen.getId(), usage);
            }
            usage.add(operation.getKey(), project, issueType);
        }
    }

    /** Active projects of the context: all of them for "all projects", else the listed ones. */
    private Collection<Project> projectsOf(FieldConfigScheme context, Reads reads) {
        Collection<Project> candidates = context.isAllProjects()
                ? reads.allProjects() : context.getAssociatedProjectObjects();
        List<Project> active = new ArrayList<Project>();
        for (Project project : candidates) {
            if (!project.isArchived()) {
                active.add(project);
            }
        }
        return active;
    }

    private ScreenCandidate candidate(String fieldId, ScreenUsage usage, ScreenReach reach, Reads reads) {
        FieldScreen screen = usage.screen();
        boolean alreadyHasField = screen.containsField(fieldId);
        boolean defaultScreen = FieldScreen.DEFAULT_SCREEN_ID.equals(screen.getId());

        List<Project> outside = outsideProjects(reach.projectIdsUsing(screen), usage.projects(), reads);
        ScreenRecommender.Advice advice = ScreenRecommender.advise(alreadyHasField, defaultScreen, outside.size());

        return new ScreenCandidate(screen.getId(), screen.getName(), new ArrayList<ScreenOperation>(usage.operations()),
                tabsOf(screen), alreadyHasField, defaultScreen, usage.projects().size(),
                refs(usage.projects().values()), outside.size(), refs(outside), issueTypeRefs(usage),
                advice.recommendation, advice.reasons);
    }

    /** Projects that use the screen without being among the projects of the context. */
    private List<Project> outsideProjects(Set<Long> allUsers, Map<Long, Project> inContext, Reads reads) {
        List<Project> outside = new ArrayList<Project>();
        for (Long id : allUsers) {
            if (inContext.containsKey(id)) {
                continue;
            }
            Project project = reads.project(id);
            if (project != null && !project.isArchived()) {
                outside.add(project);
            }
        }
        return outside;
    }

    private static List<TabRef> tabsOf(FieldScreen screen) {
        List<TabRef> tabs = new ArrayList<TabRef>();
        for (FieldScreenTab tab : screen.getTabs()) {
            tabs.add(new TabRef(tab.getId(), tab.getName()));
        }
        return tabs;
    }

    /** By key, at most {@link Limits#MAX_PROJECTS_SHOWN}; the caller has the total next to the list. */
    private static List<ProjectRef> refs(Collection<Project> projects) {
        List<Project> sorted = new ArrayList<Project>(projects);
        Collections.sort(sorted, new Comparator<Project>() {
            @Override
            public int compare(Project a, Project b) {
                return a.getKey().compareTo(b.getKey());
            }
        });
        List<ProjectRef> refs = new ArrayList<ProjectRef>();
        for (Project project : sorted.subList(0, Math.min(sorted.size(), Limits.MAX_PROJECTS_SHOWN))) {
            refs.add(new ProjectRef(project.getKey(), project.getName()));
        }
        return refs;
    }

    private static List<IssueTypeRef> issueTypeRefs(ScreenUsage usage) {
        List<IssueTypeRef> refs = new ArrayList<IssueTypeRef>();
        for (IssueType issueType : usage.issueTypes().values()) {
            refs.add(new IssueTypeRef(issueType.getId(), issueType.getName()));
        }
        return refs;
    }

    private static boolean containsType(Collection<IssueType> types, IssueType wanted) {
        for (IssueType type : types) {
            if (type.getId().equals(wanted.getId())) {
                return true;
            }
        }
        return false;
    }

    private static ScreenSummary summarize(List<ScreenCandidate> candidates) {
        int recommended = 0;
        int caution = 0;
        int notRecommended = 0;
        int alreadyPresent = 0;
        for (ScreenCandidate candidate : candidates) {
            switch (candidate.getRecommendation()) {
                case RECOMMENDED:
                    recommended++;
                    break;
                case CAUTION:
                    caution++;
                    break;
                case NOT_RECOMMENDED:
                    notRecommended++;
                    break;
                default:
                    alreadyPresent++;
            }
        }
        return new ScreenSummary(recommended, caution, notRecommended, alreadyPresent);
    }

    private static final Comparator<ScreenCandidate> BY_RECOMMENDATION_THEN_NAME = new Comparator<ScreenCandidate>() {
        @Override
        public int compare(ScreenCandidate a, ScreenCandidate b) {
            int byRecommendation = rank(a.getRecommendation()) - rank(b.getRecommendation());
            if (byRecommendation != 0) return byRecommendation;
            int byName = a.getName().compareToIgnoreCase(b.getName());
            return byName != 0 ? byName : Long.compare(a.getScreenId(), b.getScreenId());
        }

        private int rank(Recommendation recommendation) {
            return recommendation.ordinal();
        }
    };

    private static Map<ScreenOperation, IssueOperation> operations() {
        Map<ScreenOperation, IssueOperation> operations = new LinkedHashMap<ScreenOperation, IssueOperation>();
        operations.put(ScreenOperation.CREATE, IssueOperations.CREATE_ISSUE_OPERATION);
        operations.put(ScreenOperation.EDIT, IssueOperations.EDIT_ISSUE_OPERATION);
        operations.put(ScreenOperation.VIEW, IssueOperations.VIEW_ISSUE_OPERATION);
        return operations;
    }
}
