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
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.junit.Before;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.customfields.api.dto.screen.Recommendation;
import ru.rasulov.customfields.api.dto.screen.ScreenCandidate;
import ru.rasulov.customfields.api.dto.screen.ScreenOperation;
import ru.rasulov.customfields.api.dto.screen.ScreenPlan;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.issueType;
import static ru.rasulov.customfields.impl.JiraMocks.project;

/**
 * The plan: from the contexts of a field to screens and how far each reaches. Jira's chain (project, issue type
 * screen scheme, screen scheme, screen) is mocked; each test builds the little world it needs.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class ScreenPlannerTest {

    private static final String FIELD = "customfield_10500";

    private ProjectManager projectManager;
    private FieldScreenSchemeManager screenSchemeManager;
    private IssueTypeScreenSchemeManager issueTypeScreenSchemeManager;
    private ScreenPlanner planner;
    private IssueType bug;
    private IssueType task;
    private Project a;
    private Project b;
    private CustomField field;
    private long nextScreenId = 100;

    @Before
    public void setUp() {
        projectManager = mock(ProjectManager.class);
        screenSchemeManager = mock(FieldScreenSchemeManager.class);
        issueTypeScreenSchemeManager = mock(IssueTypeScreenSchemeManager.class);
        planner = new ScreenPlanner(projectManager, screenSchemeManager, issueTypeScreenSchemeManager);

        bug = issueType("1", "Bug", false);
        task = issueType("2", "Task", false);
        a = project(1, "A", "Project A", bug, task);
        b = project(2, "B", "Project B", bug, task);
        when(projectManager.getProjects()).thenReturn(Arrays.asList(a, b));
        when(projectManager.getProjectObj(1L)).thenReturn(a);
        when(projectManager.getProjectObj(2L)).thenReturn(b);

        field = mock(CustomField.class);
        when(field.getId()).thenReturn(FIELD);
        when(field.getUntranslatedName()).thenReturn("Severity");
    }

    // --- the chain from context to screens ---------------------------------------------------------------------

    @Test
    public void privateScreensOfTheProjectAreRecommendedWithTheirOperations() {
        FieldScreen create = screen("A create");
        FieldScreen editView = screen("A edit/view");
        Chain chain = chainFor(a, create, editView, editView);
        chain.usedBy(create, chain.screenScheme, a);
        chain.usedBy(editView, chain.screenScheme, a);
        context(false, Arrays.asList(a), true, null);

        ScreenPlan plan = plan();

        assertEquals(2, plan.getTotal());
        ScreenCandidate created = candidate(plan, "A create");
        ScreenCandidate edited = candidate(plan, "A edit/view");
        assertEquals(Arrays.asList(ScreenOperation.CREATE), created.getOperations());
        assertEquals(Arrays.asList(ScreenOperation.EDIT, ScreenOperation.VIEW), edited.getOperations());
        for (ScreenCandidate candidate : plan.getScreens()) {
            assertEquals(Recommendation.RECOMMENDED, candidate.getRecommendation());
            assertEquals(1, candidate.getProjectsInContextTotal());
            assertEquals("A", candidate.getProjectsInContext().get(0).getKey());
            assertEquals(0, candidate.getProjectsOutsideContextTotal());
        }
        assertEquals(2, plan.getSummary().getRecommended());
    }

    @Test
    public void aScreenSharedWithAProjectOutsideTheContextIsACaution() {
        FieldScreen shared = screen("Shared");
        Chain chain = chainFor(a, shared, shared, shared);
        chain.usedBy(shared, chain.screenScheme, a, b);
        context(false, Arrays.asList(a), true, null);

        ScreenCandidate candidate = plan().getScreens().get(0);

        assertEquals(Recommendation.CAUTION, candidate.getRecommendation());
        assertEquals(1, candidate.getProjectsInContextTotal());
        assertEquals(1, candidate.getProjectsOutsideContextTotal());
        assertEquals("B", candidate.getProjectsOutsideContext().get(0).getKey());
        assertEquals(Arrays.asList(ScreenOperation.CREATE, ScreenOperation.EDIT, ScreenOperation.VIEW),
                candidate.getOperations());
    }

    @Test
    public void theDefaultScreenIsNotRecommendedEvenWhenNoOtherProjectUsesIt() {
        FieldScreen defaultScreen = screen("Default Screen");
        when(defaultScreen.getId()).thenReturn(FieldScreen.DEFAULT_SCREEN_ID);
        Chain chain = chainFor(a, defaultScreen, defaultScreen, defaultScreen);
        chain.usedBy(defaultScreen, chain.screenScheme, a);
        context(false, Arrays.asList(a), true, null);

        ScreenCandidate candidate = plan().getScreens().get(0);

        assertEquals(Recommendation.NOT_RECOMMENDED, candidate.getRecommendation());
        assertTrue(candidate.isDefaultScreen());
    }

    @Test
    public void aScreenThatAlreadyHasTheFieldIsAlreadyPresent() {
        FieldScreen create = screen("A create");
        when(create.containsField(FIELD)).thenReturn(true);
        Chain chain = chainFor(a, create, screen("A edit"), screen("A view"));
        chain.usedBy(create, chain.screenScheme, a);
        context(false, Arrays.asList(a), true, null);

        ScreenPlan plan = plan();

        assertEquals(Recommendation.ALREADY_PRESENT, candidate(plan, "A create").getRecommendation());
        assertTrue(candidate(plan, "A create").isAlreadyHasField());
        assertEquals(1, plan.getSummary().getAlreadyPresent());
        assertEquals(2, plan.getSummary().getRecommended());
    }

    @Test
    public void onlyTheIssueTypesOfTheContextAreFollowed() {
        FieldScreen bugScreen = screen("Bug screen");
        FieldScreen taskScreen = screen("Task screen");
        Chain chain = new Chain(a);
        FieldScreenScheme bugScheme = chain.mapType(bug, bugScreen, bugScreen, bugScreen);
        chain.mapType(task, taskScreen, taskScreen, taskScreen);
        chain.usedBy(bugScreen, bugScheme, a);
        context(false, Arrays.asList(a), false, Arrays.asList(bug));

        ScreenPlan plan = plan();

        assertEquals(1, plan.getTotal());
        assertEquals("Bug screen", plan.getScreens().get(0).getName());
        assertEquals("1", plan.getScreens().get(0).getIssueTypes().get(0).getId());
    }

    @Test
    public void anIssueTypeOfTheContextThatTheProjectDoesNotHaveIsSkipped() {
        IssueType epic = issueType("3", "Epic", false);
        FieldScreen screen = screen("A screen");
        chainFor(a, screen, screen, screen);
        context(false, Arrays.asList(a), false, Arrays.asList(epic));

        assertEquals(0, plan().getTotal());
    }

    @Test
    public void allProjectsMeansEveryActiveProject() {
        Project archived = project(3, "OLD", "Old");
        when(archived.isArchived()).thenReturn(true);
        when(projectManager.getProjects()).thenReturn(Arrays.asList(a, b, archived));
        FieldScreen screenA = screen("A screen");
        FieldScreen screenB = screen("B screen");
        Chain chainA = chainFor(a, screenA, screenA, screenA);
        Chain chainB = chainFor(b, screenB, screenB, screenB);
        chainA.usedBy(screenA, chainA.screenScheme, a);
        chainB.usedBy(screenB, chainB.screenScheme, b);
        context(true, null, true, null);

        ScreenPlan plan = plan();

        assertEquals(2, plan.getTotal());
        assertEquals(Arrays.asList("A screen", "B screen"), names(plan));
    }

    @Test
    public void anArchivedProjectOfTheContextIsLeftOut() {
        Project archived = project(3, "OLD", "Old");
        when(archived.isArchived()).thenReturn(true);
        FieldScreen screen = screen("Old screen");
        chainFor(archived, screen, screen, screen);
        context(false, Arrays.asList(archived), true, null);

        assertEquals(0, plan().getTotal());
    }

    @Test
    public void aProjectWithoutAScreenSchemeIsSkipped() {
        when(issueTypeScreenSchemeManager.getIssueTypeScreenScheme(a)).thenReturn(null);
        context(false, Arrays.asList(a), true, null);

        assertEquals(0, plan().getTotal());
    }

    // --- several contexts, order, size -------------------------------------------------------------------------

    @Test
    public void screensOfSeveralContextsAreMergedAndSafeScreensComeFirst() {
        FieldScreen safe = screen("Z safe");
        FieldScreen shared = screen("A shared");
        Chain chainA = chainFor(a, safe, safe, safe);
        Chain chainB = chainFor(b, shared, shared, shared);
        chainA.usedBy(safe, chainA.screenScheme, a);
        Project c = project(3, "C", "Project C");
        chainB.usedBy(shared, chainB.screenScheme, b, c);
        when(projectManager.getProjectObj(3L)).thenReturn(c);
        context(false, Arrays.asList(a), true, null);
        context(false, Arrays.asList(b), true, null);

        ScreenPlan plan = plan();

        assertEquals(Arrays.asList("Z safe", "A shared"), names(plan));
        assertEquals(Recommendation.RECOMMENDED, plan.getScreens().get(0).getRecommendation());
        assertEquals(Recommendation.CAUTION, plan.getScreens().get(1).getRecommendation());
        assertEquals(1, plan.getSummary().getRecommended());
        assertEquals(1, plan.getSummary().getCaution());
    }

    @Test
    public void aLongPlanIsCutButTheSummaryCountsEverything() {
        int screens = Limits.MAX_SCREENS_IN_PLAN + 5;
        List<Project> projects = new ArrayList<Project>();
        for (int i = 0; i < screens; i++) {
            Project project = project(1000 + i, "P" + i, "Project " + i, bug);
            projects.add(project);
            FieldScreen screen = screen("Screen " + i);
            Chain chain = chainFor(project, screen, screen, screen);
            chain.usedBy(screen, chain.screenScheme, project);
        }
        when(projectManager.getProjects()).thenReturn(projects);
        context(true, null, true, null);

        ScreenPlan plan = plan();

        assertEquals(screens, plan.getTotal());
        assertEquals(Limits.MAX_SCREENS_IN_PLAN, plan.getScreens().size());
        assertTrue(plan.isTruncated());
        assertEquals(screens, plan.getSummary().getRecommended());
    }

    @Test
    public void all205RecommendedScreensCanBeDiscovered() throws Exception {
        List<Project> projects = new ArrayList<Project>();
        for (int i = 0; i < 205; i++) {
            Project project = project(1000 + i, "P" + i, "Project " + i, bug);
            projects.add(project);
            FieldScreen screen = screen("Screen " + i);
            Chain chain = chainFor(project, screen, screen, screen);
            chain.usedBy(screen, chain.screenScheme, project);
        }
        when(projectManager.getProjects()).thenReturn(projects);
        context(true, null, true, null);
        ScreenPlan first = plan();
        assertEquals(205, first.getSummary().getRecommended());
        assertEquals(200, first.getScreens().size());
        com.fasterxml.jackson.databind.JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(first);
        assertTrue("Five recommended screens are inaccessible without a continuation cursor",
                json.has("nextCursor") && json.get("nextCursor").isTextual()
                        && !json.get("nextCursor").textValue().isEmpty());
        ScreenPlan second = planner.plan(field,
                Collections.<ru.rasulov.common.api.dto.field.context.ContextSummary>emptyList(),
                first.getNextCursor(), null);
        assertEquals(5, second.getScreens().size());
        assertFalse(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(second).has("nextCursor"));
        java.util.Set<Long> discovered = new java.util.HashSet<Long>();
        for (ScreenCandidate candidate : first.getScreens()) assertTrue(discovered.add(candidate.getScreenId()));
        for (ScreenCandidate candidate : second.getScreens()) assertTrue(discovered.add(candidate.getScreenId()));
        java.util.Set<Long> expected = new java.util.HashSet<Long>();
        for (long id = 100; id < 100 + 205 * 3; id += 3) expected.add(id);
        assertEquals(expected, discovered);
    }

    @Test
    public void projectNamesAreCappedButTheyAreAllCounted() {
        FieldScreen shared = screen("Shared");
        Chain chain = chainFor(a, shared, shared, shared);
        List<Project> users = new ArrayList<Project>();
        users.add(a);
        for (int i = 0; i < 14; i++) {
            Project other = project(500 + i, String.format("X%02d", i), "Other " + i);
            when(projectManager.getProjectObj(500L + i)).thenReturn(other);
            users.add(other);
        }
        chain.usedBy(shared, chain.screenScheme, users.toArray(new Project[0]));
        context(false, Arrays.asList(a), true, null);

        ScreenCandidate candidate = plan().getScreens().get(0);

        assertEquals(14, candidate.getProjectsOutsideContextTotal());
        assertEquals(Limits.MAX_PROJECTS_SHOWN, candidate.getProjectsOutsideContext().size());
        assertEquals("X00", candidate.getProjectsOutsideContext().get(0).getKey());
    }

    @Test
    public void aSchemeSharedByManyScreensIsReadOnce() {
        FieldScreen create = screen("create");
        FieldScreen edit = screen("edit");
        FieldScreen view = screen("view");
        Chain chain = chainFor(a, create, edit, view);
        chain.usedBy(create, chain.screenScheme, a);
        chain.usedBy(edit, chain.screenScheme, a);
        chain.usedBy(view, chain.screenScheme, a);
        context(false, Arrays.asList(a), true, null);

        plan();

        verify(issueTypeScreenSchemeManager, times(1)).getProjects(chain.itss);
    }


    @Test
    public void recommendedFilterDoesNotSpendPageSlotsOnOtherRecommendations() {
        List<FieldScreen> screens = manyScreens(207, "Same");
        when(screens.get(0).containsField(FIELD)).thenReturn(true);
        when(screens.get(1).getId()).thenReturn(FieldScreen.DEFAULT_SCREEN_ID);
        ScreenPlan first = filtered(null);
        assertEquals(207, first.getTotal());
        assertEquals(205, first.getSummary().getRecommended());
        assertEquals(1, first.getSummary().getAlreadyPresent());
        assertEquals(1, first.getSummary().getNotRecommended());
        assertEquals(200, first.getScreens().size());
        ScreenPlan last = filtered(first.getNextCursor());
        assertEquals(5, last.getScreens().size());
        assertEquals(null, last.getNextCursor());
        for (ScreenCandidate candidate : first.getScreens()) assertEquals(Recommendation.RECOMMENDED, candidate.getRecommendation());
        for (ScreenCandidate candidate : last.getScreens()) assertEquals(Recommendation.RECOMMENDED, candidate.getRecommendation());
    }

    @Test
    public void equalNamesAndIterationChangesKeepTheSameCursorOrder() {
        List<FieldScreen> screens = manyScreens(205, "Same");
        when(screens.get(0).getName()).thenReturn("same");
        ScreenPlan first = filtered(null);
        java.util.List<Project> projects = new ArrayList<Project>(projectManager.getProjects());
        Collections.reverse(projects);
        when(projectManager.getProjects()).thenReturn(projects);
        ScreenPlan last = filtered(first.getNextCursor());
        for (int i = 0; i < first.getScreens().size(); i++) {
            assertEquals(screens.get(i).getId().longValue(), first.getScreens().get(i).getScreenId());
        }
        for (int i = 0; i < last.getScreens().size(); i++) {
            assertEquals(screens.get(200 + i).getId().longValue(), last.getScreens().get(i).getScreenId());
        }
    }

    @Test
    public void changedSelectionCannotBeJoinedToPreviousPages() {
        List<FieldScreen> screens = manyScreens(205, "Same");
        String cursor = filtered(null).getNextCursor();
        when(screens.get(204).getName()).thenReturn("Changed");
        assertInvalidCursor(cursor, Recommendation.RECOMMENDED);
        when(screens.get(204).getName()).thenReturn("Same");
        when(screens.get(204).containsField(FIELD)).thenReturn(true);
        assertInvalidCursor(cursor, Recommendation.RECOMMENDED);
        when(screens.get(204).containsField(FIELD)).thenReturn(false);
        when(field.getUntranslatedName()).thenReturn("Renamed field");
        assertInvalidCursor(cursor, Recommendation.RECOMMENDED);
    }

    @Test
    public void cursorIsBoundToFieldAndFilterAndRejectsMalformedInput() {
        manyScreens(205, "Same");
        String cursor = filtered(null).getNextCursor();
        assertInvalidCursor(cursor, null);
        when(field.getId()).thenReturn("customfield_99999");
        assertInvalidCursor(cursor, Recommendation.RECOMMENDED);
        assertInvalidCursor("", Recommendation.RECOMMENDED);
        assertInvalidCursor("not-a-cursor", Recommendation.RECOMMENDED);
        assertInvalidCursor(cursor, Recommendation.CAUTION);
    }

    @Test
    public void emptyRecommendedSelectionHasNoContinuation() {
        List<FieldScreen> screens = manyScreens(1, "Present");
        when(screens.get(0).containsField(FIELD)).thenReturn(true);
        ScreenPlan result = filtered(null);
        assertEquals(1, result.getTotal());
        assertEquals(Collections.emptyList(), result.getScreens());
        assertEquals(null, result.getNextCursor());
    }

    @Test
    public void insertedOrRemovedCandidatesRequireRestart() {
        manyScreens(205, "Same");
        String cursor = filtered(null).getNextCursor();
        List<Project> original = new ArrayList<Project>(projectManager.getProjects());
        List<Project> fewer = new ArrayList<Project>(original);
        fewer.remove(fewer.size() - 1);
        when(projectManager.getProjects()).thenReturn(fewer);
        assertInvalidCursor(cursor, Recommendation.RECOMMENDED);
        Project late = project(9000, "LATE", "Late", bug);
        FieldScreen screen = screen("Late screen");
        Chain chain = chainFor(late, screen, screen, screen);
        chain.usedBy(screen, chain.screenScheme, late);
        original.add(late);
        when(projectManager.getProjects()).thenReturn(original);
        assertInvalidCursor(cursor, Recommendation.RECOMMENDED);
    }

    @Test
    public void tabOrderAndContextChangesRequireRestart() {
        List<FieldScreen> screens = manyScreens(205, "Same");
        FieldScreenTab first = mock(FieldScreenTab.class);
        FieldScreenTab second = mock(FieldScreenTab.class);
        when(first.getId()).thenReturn(1L);
        when(first.getName()).thenReturn("First");
        when(second.getId()).thenReturn(2L);
        when(second.getName()).thenReturn("Second");
        when(screens.get(204).getTabs()).thenReturn(Arrays.asList(first, second));
        String cursor = filtered(null).getNextCursor();
        when(screens.get(204).getTabs()).thenReturn(Arrays.asList(second, first));
        assertInvalidCursor(cursor, Recommendation.RECOMMENDED);
        when(screens.get(204).getTabs()).thenReturn(Arrays.asList(first, second));
        context(false, Collections.singletonList(a), true, null);
        assertInvalidCursor(cursor, Recommendation.RECOMMENDED);
    }

    private ScreenPlan filtered(String cursor) {
        return planner.plan(field, Collections.<ru.rasulov.common.api.dto.field.context.ContextSummary>emptyList(),
                cursor, Recommendation.RECOMMENDED);
    }

    private void assertInvalidCursor(String cursor, Recommendation filter) {
        try {
            planner.plan(field, Collections.<ru.rasulov.common.api.dto.field.context.ContextSummary>emptyList(), cursor, filter);
            org.junit.Assert.fail("Continuation must be rejected");
        } catch (ru.rasulov.common.api.exception.InvalidRequestException expected) {
            assertTrue(expected.getMessage().contains("cursor") || expected.getMessage().contains("changed")
                    || expected.getMessage().contains("recommendation"));
        }
    }

    private List<FieldScreen> manyScreens(int count, String name) {
        List<Project> projects = new ArrayList<Project>();
        List<FieldScreen> screens = new ArrayList<FieldScreen>();
        for (int i = 0; i < count; i++) {
            Project project = project(1000 + i, "P" + i, "Project " + i, bug);
            projects.add(project);
            FieldScreen screen = screen(name);
            screens.add(screen);
            Chain chain = chainFor(project, screen, screen, screen);
            chain.usedBy(screen, chain.screenScheme, project);
        }
        when(projectManager.getProjects()).thenReturn(projects);
        context(true, null, true, null);
        return screens;
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private ScreenPlan plan() {
        return planner.plan(field, Collections.<ru.rasulov.common.api.dto.field.context.ContextSummary>emptyList());
    }

    private FieldScreen screen(String name) {
        FieldScreen screen = mock(FieldScreen.class);
        when(screen.getId()).thenReturn(nextScreenId++);
        when(screen.getName()).thenReturn(name);
        when(screen.getTabs()).thenReturn(Collections.<FieldScreenTab>emptyList());
        return screen;
    }

    /** A project whose issue types all use one screen scheme, that maps create, edit and view to the given screens. */
    private Chain chainFor(Project project, FieldScreen create, FieldScreen edit, FieldScreen view) {
        Chain chain = new Chain(project);
        chain.screenScheme = chain.mapType(bug, create, edit, view);
        chain.itssUses(task, chain.screenScheme);
        return chain;
    }

    /** The mocked Jira chain of one project. */
    private final class Chain {
        final Project project;
        final IssueTypeScreenScheme itss = mock(IssueTypeScreenScheme.class);
        FieldScreenScheme screenScheme;

        Chain(Project project) {
            this.project = project;
            when(itss.getId()).thenReturn(nextScreenId++);
            when(issueTypeScreenSchemeManager.getIssueTypeScreenScheme(project)).thenReturn(itss);
        }

        /** The issue type gets a screen scheme of its own that maps the operations to the given screens. */
        FieldScreenScheme mapType(IssueType type, FieldScreen create, FieldScreen edit, FieldScreen view) {
            FieldScreenScheme scheme = mock(FieldScreenScheme.class);
            when(scheme.getId()).thenReturn(nextScreenId++);
            when(scheme.getFieldScreen(IssueOperations.CREATE_ISSUE_OPERATION)).thenReturn(create);
            when(scheme.getFieldScreen(IssueOperations.EDIT_ISSUE_OPERATION)).thenReturn(edit);
            when(scheme.getFieldScreen(IssueOperations.VIEW_ISSUE_OPERATION)).thenReturn(view);
            itssUses(type, scheme);
            return scheme;
        }

        void itssUses(IssueType type, FieldScreenScheme scheme) {
            when(itss.getEffectiveFieldScreenScheme(type)).thenReturn(scheme);
        }

        /** Says which projects use the screen (through this chain's schemes). */
        void usedBy(FieldScreen screen, FieldScreenScheme scheme, Project... projects) {
            List<FieldScreenScheme> schemes = new ArrayList<FieldScreenScheme>();
            schemes.add(scheme);
            when(screenSchemeManager.getFieldScreenSchemes(screen)).thenReturn(schemes);
            when(issueTypeScreenSchemeManager.getIssueTypeScreenSchemes(scheme)).thenReturn(Arrays.asList(itss));
            List<GenericValue> users = new ArrayList<GenericValue>();
            for (Project user : projects) {
                GenericValue value = mock(GenericValue.class);
                Long id = user.getId();
                when(value.getLong("id")).thenReturn(id);
                users.add(value);
            }
            when(issueTypeScreenSchemeManager.getProjects(itss)).thenReturn((Collection) users);
        }
    }

    private void context(boolean allProjects, List<Project> projects, boolean allIssueTypes, List<IssueType> types) {
        FieldConfigScheme context = mock(FieldConfigScheme.class);
        when(context.isAllProjects()).thenReturn(allProjects);
        when(context.getAssociatedProjectObjects()).thenReturn(projects == null ? Collections.<Project>emptyList() : projects);
        when(context.isAllIssueTypes()).thenReturn(allIssueTypes);
        when(context.getAssociatedIssueTypes()).thenReturn(types == null ? Collections.<IssueType>emptyList() : types);
        List<FieldConfigScheme> all = new ArrayList<FieldConfigScheme>(field.getConfigurationSchemes() == null
                ? Collections.<FieldConfigScheme>emptyList() : field.getConfigurationSchemes());
        all.add(context);
        when(field.getConfigurationSchemes()).thenReturn(all);
    }

    private static ScreenCandidate candidate(ScreenPlan plan, String name) {
        for (ScreenCandidate candidate : plan.getScreens()) {
            if (name.equals(candidate.getName())) {
                return candidate;
            }
        }
        throw new AssertionError("no screen named " + name + " in " + names(plan));
    }

    private static List<String> names(ScreenPlan plan) {
        List<String> names = new ArrayList<String>();
        for (ScreenCandidate candidate : plan.getScreens()) {
            names.add(candidate.getName());
        }
        return names;
    }

    // --- one plan reads each catalog entry once ---------------------------------------------------------------

    @Test
    public void allProjectsContextsShareOneProjectCatalogRead() {
        FieldScreen screenA = screen("A");
        FieldScreen screenB = screen("B");
        Chain chainA = chainFor(a, screenA, screenA, screenA);
        chainA.usedBy(screenA, chainA.screenScheme, a);
        Chain chainB = chainFor(b, screenB, screenB, screenB);
        chainB.usedBy(screenB, chainB.screenScheme, b);
        context(true, null, true, null);
        context(true, null, false, Arrays.asList(bug));

        assertEquals(2, plan().getTotal());

        verify(projectManager, org.mockito.Mockito.times(1)).getProjects();
    }

    @Test
    public void anOutsideProjectSharedByManyScreensIsLookedUpOnce() {
        FieldScreen first = screen("First");
        FieldScreen second = screen("Second");
        Chain chain = chainFor(a, first, second, second);
        chain.usedBy(first, chain.screenScheme, a, b);
        chain.usedBy(second, chain.screenScheme, a, b);
        context(false, Arrays.asList(a), true, null);

        ScreenPlan plan = plan();

        assertEquals(1, candidate(plan, "First").getProjectsOutsideContextTotal());
        assertEquals(1, candidate(plan, "Second").getProjectsOutsideContextTotal());
        verify(projectManager, org.mockito.Mockito.times(1)).getProjectObj(2L);
    }

    @Test
    public void projectsSharingAnIssueTypeScreenSchemeAreResolvedOncePerIssueType() {
        FieldScreen only = screen("Only");
        Chain chain = chainFor(a, only, only, only);
        List<Project> all = new ArrayList<Project>(Arrays.asList(a, b));
        for (int i = 0; i < 6; i++) {
            all.add(project(10 + i, "E" + i, "Extra " + i, bug, task));
        }
        for (Project each : all) {
            when(issueTypeScreenSchemeManager.getIssueTypeScreenScheme(each)).thenReturn(chain.itss);
        }
        when(projectManager.getProjects()).thenReturn(all);
        chain.usedBy(only, chain.screenScheme, all.toArray(new Project[0]));
        context(true, null, true, null);

        assertEquals(1, plan().getTotal());

        int reads = 0;
        for (org.mockito.invocation.Invocation call : org.mockito.Mockito.mockingDetails(chain.itss).getInvocations()) {
            if (call.getMethod().getName().equals("getEffectiveFieldScreenScheme")) reads++;
        }
        assertEquals("one read per issue type, not per project", 2, reads);
    }
}
