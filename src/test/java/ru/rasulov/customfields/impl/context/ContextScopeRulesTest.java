package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import org.junit.Test;
import ru.rasulov.customfields.impl.context.ContextScopeRules.Scope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static ru.rasulov.customfields.impl.JiraMocks.issueType;
import static ru.rasulov.customfields.impl.JiraMocks.project;

public class ContextScopeRulesTest {
    private final IssueType bug = issueType("14900", "Bug", false);
    private final IssueType task = issueType("14901", "Task", false);
    private final Project lot = project(10, "LOT", "Local", bug, task);
    private final Project d2 = project(20, "D2LOT", "Second", bug, task);

    @Test
    public void addingAProjectAddsOnlyItsRequestedTypeCombination() {
        Map<String, Object> delta = delta(scope(lot), scope(lot, d2));
        assertCombinations(delta, "addedCombinations", row("D2LOT", "14900"));
        assertCombinations(delta, "removedCombinations");
        assertEquals(Collections.singletonList(projectView(d2)), delta.get("addedProjects"));
        assertEquals(Collections.emptyList(), delta.get("removedProjects"));
        assertEquals(Collections.emptyList(), delta.get("addedIssueTypes"));
        assertFalse(codes(delta).contains("EXTRA_COMBINATIONS"));
        assertFalse(codes(delta).contains("NARROWING"));
    }

    @Test
    public void addingAProjectToTwoTypesExplainsTheCrossProduct() {
        Map<String, Object> delta = delta(specific(Arrays.asList(lot), bug, task),
                specific(Arrays.asList(lot, d2), bug, task));
        assertCombinations(delta, "addedCombinations", row("D2LOT", "14900"), row("D2LOT", "14901"));
        assertTrue(codes(delta).contains("EXTRA_COMBINATIONS"));
    }

    @Test
    public void addingAProjectToAllTypesAlsoWarnsAboutExtraCombinations() {
        Map<String, Object> delta = delta(new Scope(false, Arrays.asList(lot), true, null),
                new Scope(false, Arrays.asList(lot, d2), true, null));
        assertCombinations(delta, "addedCombinations", row("D2LOT", "*"));
        assertEquals("SYMBOLIC_COMBINATIONS", combinations(delta, "addedCombinations").get("countUnit"));
        assertTrue(codes(delta).contains("EXTRA_COMBINATIONS"));
    }

    @Test
    public void changingBothAxesDoesNotCountTheirIntersectionTwice() {
        Map<String, Object> delta = delta(scope(lot), specific(Arrays.asList(lot, d2), bug, task));
        assertCombinations(delta, "addedCombinations", row("LOT", "14901"), row("D2LOT", "14900"), row("D2LOT", "14901"));
        assertCombinations(delta, "removedCombinations");
    }

    @Test
    public void removingATypeWarnsAboutNarrowingAndKeepsUnchangedPairsOutOfDelta() {
        Map<String, Object> delta = delta(specific(Arrays.asList(lot, d2), bug, task), scope(lot, d2));
        assertCombinations(delta, "removedCombinations", row("LOT", "14901"), row("D2LOT", "14901"));
        assertCombinations(delta, "addedCombinations");
        assertTrue(codes(delta).contains("NARROWING"));
    }

    @Test
    public void switchingToGlobalProjectsExcludesTheAlreadyCoveredProjectSymbolically() {
        Map<String, Object> delta = delta(scope(lot), new Scope(true, null, false, Arrays.asList(bug)));
        Map<String, Object> added = row("*", "14900");
        added.put("excludedProjects", Arrays.asList("LOT"));
        assertCombinations(delta, "addedCombinations", added);
        assertCombinations(delta, "removedCombinations");
        assertEquals("SYMBOLIC_COMBINATIONS", combinations(delta, "addedCombinations").get("countUnit"));
        assertTrue(codes(delta).contains("GLOBAL_SCOPE"));
        assertFalse(codes(delta).contains("NARROWING"));
    }

    @Test
    public void switchingFromGlobalProjectsRemovesOnlyTheExcludedRemainder() {
        Map<String, Object> delta = delta(new Scope(true, null, false, Arrays.asList(bug)), scope(lot));
        Map<String, Object> removed = row("*", "14900");
        removed.put("excludedProjects", Arrays.asList("LOT"));
        assertCombinations(delta, "removedCombinations", removed);
        assertCombinations(delta, "addedCombinations");
        assertTrue(codes(delta).contains("NARROWING"));
    }

    @Test
    public void switchingToAllTypesExcludesAlreadyCoveredTypesSymbolically() {
        Map<String, Object> delta = delta(scope(lot), new Scope(false, Arrays.asList(lot), true, null));
        Map<String, Object> added = row("LOT", "*");
        added.put("excludedIssueTypes", Arrays.asList("14900"));
        assertCombinations(delta, "addedCombinations", added);
        assertCombinations(delta, "removedCombinations");
        assertFalse(codes(delta).contains("NARROWING"));
    }

    @Test
    public void switchingFromAllTypesRemovesOnlyTypesOutsideTheRetainedSet() {
        Map<String, Object> delta = delta(new Scope(false, Arrays.asList(lot), true, null), scope(lot));
        Map<String, Object> removed = row("LOT", "*");
        removed.put("excludedIssueTypes", Arrays.asList("14900"));
        assertCombinations(delta, "removedCombinations", removed);
        assertCombinations(delta, "addedCombinations");
        assertTrue(codes(delta).contains("NARROWING"));
    }

    @Test
    public void expandingBothAxesUsesDisjointSymbolicRegions() {
        Map<String, Object> delta = delta(scope(lot), new Scope(true, null, true, null));
        Map<String, Object> otherProjects = row("*", "*");
        otherProjects.put("excludedProjects", Arrays.asList("LOT"));
        Map<String, Object> otherTypes = row("LOT", "*");
        otherTypes.put("excludedIssueTypes", Arrays.asList("14900"));
        assertCombinations(delta, "addedCombinations", otherProjects, otherTypes);
        assertCombinations(delta, "removedCombinations");
    }

    @Test
    public void crossedWildcardTransitionsHaveOnlyRealAdditionsAndRemovals() {
        Map<String, Object> delta = delta(new Scope(true, null, false, Arrays.asList(bug)),
                new Scope(false, Arrays.asList(lot), true, null));
        Map<String, Object> added = row("LOT", "*");
        added.put("excludedIssueTypes", Arrays.asList("14900"));
        Map<String, Object> removed = row("*", "14900");
        removed.put("excludedProjects", Arrays.asList("LOT"));
        assertCombinations(delta, "addedCombinations", added);
        assertCombinations(delta, "removedCombinations", removed);
    }

    @Test
    public void symbolicSemanticsMatchSetDifferenceForEveryWildcardTransition() {
        List<Scope> scopes = Arrays.asList(scope(lot), scope(d2), scope(lot, d2),
                specific(Arrays.asList(lot), task), new Scope(true, null, false, Arrays.asList(bug)),
                new Scope(false, Arrays.asList(lot), true, null), new Scope(true, null, true, null));
        for (Scope before : scopes) for (Scope after : scopes) {
            Map<String, Object> delta = delta(before, after);
            for (String p : Arrays.asList("LOT", "D2LOT", "OTHER")) for (String t : Arrays.asList("14900", "14901", "OTHER")) {
                assertEquals(contains(after, p, t) && !contains(before, p, t), matches(delta, "addedCombinations", p, t));
                assertEquals(contains(before, p, t) && !contains(after, p, t), matches(delta, "removedCombinations", p, t));
            }
        }
    }

    @Test
    public void finiteCombinationCountRemainsExactWhenTheListIsCapped() {
        List<Project> projects = new ArrayList<Project>();
        projects.add(lot);
        for (int i = 21; i <= 50; i++) projects.add(project(i, "P" + i, "P" + i, bug, task));
        Map<String, Object> result = combinations(delta(specific(Arrays.asList(lot), bug, task),
                specific(projects, bug, task)), "addedCombinations");
        assertEquals(60L, ((Number) result.get("count")).longValue());
        assertEquals(50, ((List<?>) result.get("combinations")).size());
        assertEquals(Boolean.FALSE, result.get("combinationsComplete"));
        assertFalse(result.containsKey("countUnit"));
    }

    @Test
    public void unchangedScopesUseIdsNotObjectIdentityNamesOrInputOrder() {
        Project renamed = project(10, "NEWKEY", "Renamed", bug);
        IssueType renamedBug = issueType("14900", "Renamed bug", false);
        Scope before = specific(Arrays.asList(d2, lot), task, bug);
        Scope after = specific(Arrays.asList(renamed, d2), renamedBug, task);
        assertEquals(before, after);
        assertEquals(before.hashCode(), after.hashCode());
        assertCombinations(delta(before, after), "addedCombinations");
        assertCombinations(delta(before, after), "removedCombinations");
    }

    @Test
    public void scopeCopiesSortsAndFreezesItsLists() {
        List<Project> projects = new ArrayList<Project>(Arrays.asList(d2, lot));
        List<IssueType> types = new ArrayList<IssueType>(Arrays.asList(task, bug));
        Scope scope = new Scope(false, projects, false, types);
        projects.clear(); types.clear();
        assertEquals(Arrays.asList(lot, d2), scope.getProjects());
        assertEquals(Arrays.asList(bug, task), scope.getIssueTypes());
        try { scope.getProjects().clear(); fail("scope list must be immutable"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test
    public void scopeFromReadsActualSchemeAxesAndNotConfigGuesses() {
        FieldConfigScheme scheme = mock(FieldConfigScheme.class);
        when(scheme.getAssociatedProjectObjects()).thenReturn(Arrays.asList(d2, lot));
        when(scheme.getAssociatedIssueTypes()).thenReturn(Arrays.asList(task, bug));
        assertEquals(specific(Arrays.asList(lot, d2), bug, task), ContextScopeRules.from(scheme));
        when(scheme.isAllProjects()).thenReturn(true);
        when(scheme.isAllIssueTypes()).thenReturn(true);
        Scope global = ContextScopeRules.from(scheme);
        assertTrue(global.isAllProjects());
        assertTrue(global.isAllIssueTypes());
        assertTrue(global.getProjects().isEmpty());
        assertTrue(global.getIssueTypes().isEmpty());
        verify(scheme, never()).getConfigs();
    }

    @Test
    public void scopeViewSerializesOnlyFullReferencesNotJiraObjects() throws Exception {
        ObjectMapper json = new ObjectMapper().configure(MapperFeature.AUTO_DETECT_GETTERS, false);
        Map<String, Object> view = specific(Arrays.asList(d2, lot), task, bug).toView();
        com.fasterxml.jackson.databind.JsonNode tree = json.readTree(json.writeValueAsString(view));
        assertFalse(tree.get("allProjects").asBoolean());
        assertEquals(10L, tree.get("projects").get(0).get("id").asLong());
        assertEquals("LOT", tree.get("projects").get(0).get("key").asText());
        assertEquals("Local", tree.get("projects").get(0).get("name").asText());
        assertEquals("14900", tree.get("issueTypes").get(0).get("id").asText());
        assertEquals("Bug", tree.get("issueTypes").get(0).get("name").asText());
    }

    @Test
    public void sameProjectInTwoContextsIsRefusedEvenForDisjointTypes() {
        Map<Long, Scope> finalSet = new LinkedHashMap<Long, Scope>();
        finalSet.put(20L, specific(Arrays.asList(lot), task));
        finalSet.put(10L, scope(lot));
        ContextScopeRules.Failure failure = failure(finalSet, 20L);
        assertEquals("PROJECT_IN_OTHER_CONTEXT", failure.getCode());
        assertEquals(Long.valueOf(10), failure.getContextId());
        assertEquals("First", failure.getContextName());
        assertEquals(Long.valueOf(10), failure.getProjectId());
    }

    @Test
    public void conflictNamesTheUntouchedOwnerEvenWhenTheExtendedContextHasTheLowerId() {
        Map<Long, Scope> finalSet = new LinkedHashMap<Long, Scope>();
        finalSet.put(10L, scope(lot, d2));
        finalSet.put(20L, scope(d2));
        ContextScopeRules.Failure failure = failure(finalSet, 10L);
        assertEquals("PROJECT_IN_OTHER_CONTEXT", failure.getCode());
        assertEquals(Long.valueOf(20), failure.getContextId());
        assertEquals("Second", failure.getContextName());
        assertEquals(Long.valueOf(20), failure.getProjectId());
        assertTrue(failure.getMessage().contains("context 20 (Second)"));
    }

    @Test
    public void secondGlobalNamesTheUntouchedGlobalEvenWhenTheChangedContextHasTheLowerId() {
        Map<Long, Scope> finalSet = new LinkedHashMap<Long, Scope>();
        finalSet.put(10L, new Scope(true, null, true, null));
        finalSet.put(20L, new Scope(true, null, false, Arrays.asList(bug)));
        ContextScopeRules.Failure failure = failure(finalSet, 10L);
        assertEquals("GLOBAL_CONTEXT_EXISTS", failure.getCode());
        assertEquals(Long.valueOf(20), failure.getContextId());
        assertEquals("Second", failure.getContextName());
    }

    @Test
    public void singleGlobalMayCoexistWithSpecificContexts() {
        Map<Long, Scope> finalSet = new HashMap<Long, Scope>();
        finalSet.put(10L, new Scope(true, null, true, null));
        finalSet.put(20L, scope(lot));
        finalSet.put(30L, scope(d2));
        ContextScopeRules.validate(finalSet, names(), new HashSet<Long>(Arrays.asList(10L, 20L, 30L)));
    }

    @Test
    public void secondGlobalIsRefusedEvenForDisjointTypes() {
        Map<Long, Scope> finalSet = new HashMap<Long, Scope>();
        finalSet.put(20L, new Scope(true, null, false, Arrays.asList(task)));
        finalSet.put(10L, new Scope(true, null, false, Arrays.asList(bug)));
        ContextScopeRules.Failure failure = failure(finalSet, 20L);
        assertEquals("GLOBAL_CONTEXT_EXISTS", failure.getCode());
        assertEquals(Long.valueOf(10), failure.getContextId());
        assertEquals("First", failure.getContextName());
    }

    @Test
    public void wholeFinalSetAllowsAProjectTransferWhenOldOwnerReleasesIt() {
        Map<Long, Scope> finalSet = new HashMap<Long, Scope>();
        finalSet.put(10L, scope(d2));
        finalSet.put(20L, scope(lot));
        ContextScopeRules.validate(finalSet, names(), new HashSet<Long>(Arrays.asList(10L, 20L)));
    }

    @Test
    public void typeNotInProjectIsReportedForEachUnavailablePairWithoutCatalogs() {
        Project restricted = project(30, "LIMITED", "Limited", bug);
        Map<String, Object> delta = delta(scope(lot), specific(Arrays.asList(lot, restricted), bug, task));
        List<Map<String, Object>> warnings = warnings(delta);
        int count = 0;
        for (Map<String, Object> warning : warnings) if ("TYPE_NOT_IN_PROJECT".equals(warning.get("code"))) {
            count++;
            assertEquals("LIMITED", warning.get("project"));
            assertEquals("14901", warning.get("issueType"));
        }
        assertEquals(1, count);
        verify(restricted).getIssueTypes();
    }

    private Scope scope(Project... projects) { return specific(Arrays.asList(projects), bug); }
    private Scope specific(List<Project> projects, IssueType... types) { return new Scope(false, projects, false, Arrays.asList(types)); }
    private Map<String, Object> delta(Scope before, Scope after) { return ContextScopeRules.delta(before, after); }
    private static Map<Long, String> names() {
        Map<Long, String> names = new HashMap<Long, String>(); names.put(10L, "First"); names.put(20L, "Second"); return names;
    }
    private static ContextScopeRules.Failure failure(Map<Long, Scope> scopes, Long... requested) {
        try {
            ContextScopeRules.validate(scopes, names(), new HashSet<Long>(Arrays.asList(requested)));
            fail("expected exclusivity failure"); return null;
        }
        catch (ContextScopeRules.Failure failure) { return failure; }
    }
    private static Map<String, Object> projectView(Project project) {
        Map<String, Object> view = new LinkedHashMap<String, Object>();
        view.put("id", project.getId()); view.put("key", project.getKey()); view.put("name", project.getName()); return view;
    }
    private static Map<String, Object> row(String project, String type) {
        Map<String, Object> row = new LinkedHashMap<String, Object>(); row.put("project", project); row.put("issueType", type); return row;
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> combinations(Map<String, Object> delta, String key) { return (Map<String, Object>) delta.get(key); }
    @SafeVarargs
    private static void assertCombinations(Map<String, Object> delta, String key, Map<String, Object>... rows) {
        Map<String, Object> result = combinations(delta, key);
        assertEquals((long) rows.length, ((Number) result.get("count")).longValue());
        assertEquals(Arrays.asList(rows), result.get("combinations"));
        assertEquals(Boolean.TRUE, result.get("combinationsComplete"));
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> warnings(Map<String, Object> delta) { return (List<Map<String, Object>>) delta.get("warnings"); }
    private static List<String> codes(Map<String, Object> delta) {
        List<String> codes = new ArrayList<String>();
        for (Map<String, Object> warning : warnings(delta)) codes.add((String) warning.get("code")); return codes;
    }
    private static boolean contains(Scope scope, String project, String type) {
        boolean p = scope.isAllProjects(), t = scope.isAllIssueTypes();
        for (Project candidate : scope.getProjects()) p |= candidate.getKey().equals(project);
        for (IssueType candidate : scope.getIssueTypes()) t |= candidate.getId().equals(type);
        return p && t;
    }
    @SuppressWarnings("unchecked")
    private static boolean matches(Map<String, Object> delta, String key, String project, String type) {
        int matches = 0;
        for (Map<String, Object> row : (List<Map<String, Object>>) combinations(delta, key).get("combinations")) {
            if (!("*".equals(row.get("project")) || project.equals(row.get("project")))) continue;
            if (!("*".equals(row.get("issueType")) || type.equals(row.get("issueType")))) continue;
            if (row.containsKey("excludedProjects") && ((List<String>) row.get("excludedProjects")).contains(project)) continue;
            if (row.containsKey("excludedIssueTypes") && ((List<String>) row.get("excludedIssueTypes")).contains(type)) continue;
            matches++;
        }
        assertTrue("symbolic regions must be disjoint", matches <= 1);
        return matches == 1;
    }
}
