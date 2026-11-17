package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.operation.IssueOperations;
import com.atlassian.jira.issue.operation.ScreenableIssueOperation;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowScheme;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.edit.ValidatorFieldRef;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The warnings of the preview about fields nobody could fill in, per pair of project and issue type that uses
 * the workflow: on no screen at all, or hidden in the field configuration.
 */
public class ValidatorFieldPlacementTest {

    private static final String WORKFLOW = "Support";

    private final WorkflowSchemeManager workflowSchemes = mock(WorkflowSchemeManager.class);
    private final IssueTypeScreenSchemeManager screenSchemes = mock(IssueTypeScreenSchemeManager.class);
    private final FieldLayoutManager layouts = mock(FieldLayoutManager.class);
    private final FieldScreenManager screens = mock(FieldScreenManager.class);
    private final JiraWorkflow main = mock(JiraWorkflow.class);
    private final ValidatorFieldPlacement placement = new ValidatorFieldPlacement(workflowSchemes, screenSchemes, layouts, screens);

    private final List<WorkflowScheme> schemes = new ArrayList<WorkflowScheme>();
    private final Map<String, Set<String>> hiddenIn = new HashMap<String, Set<String>>();
    private Project abc;
    private Project xyz;
    private IssueType abcBug;
    private IssueType abcTask;
    private IssueType abcStory;
    private IssueType xyzBug;
    private AssignableWorkflowScheme scheme;

    private static final ValidatorFieldRef DESCRIPTION = new ValidatorFieldRef("description", "Description");
    private static final ValidatorFieldRef TEAM = new ValidatorFieldRef("customfield_10200", "Team");

    @Before
    public void setUp() {
        when(main.getName()).thenReturn(WORKFLOW);
        when(workflowSchemes.getSchemesForWorkflowIncludingDrafts(main)).thenAnswer(call -> schemes);

        abcBug = type("1", "Bug");
        abcTask = type("2", "Task");
        abcStory = type("3", "Story");
        xyzBug = type("1", "Bug");
        abc = project(1L, "ABC", abcBug, abcTask, abcStory);
        xyz = project(2L, "XYZ", xyzBug);

        scheme = mock(AssignableWorkflowScheme.class);
        when(scheme.getActualWorkflow("1")).thenReturn(WORKFLOW);
        when(scheme.getActualWorkflow("2")).thenReturn(WORKFLOW);
        when(scheme.getActualWorkflow("3")).thenReturn("Other");
        schemes.add(scheme);
        when(workflowSchemes.getProjectsUsing(scheme)).thenReturn(Arrays.asList(abc, xyz));

        // by default no screen of any pair holds any field
        FieldScreen empty = screen();
        for (Project project : Arrays.asList(abc, xyz)) {
            IssueTypeScreenScheme top = mock(IssueTypeScreenScheme.class);
            when(screenSchemes.getIssueTypeScreenScheme(project)).thenReturn(top);
            for (Object type : project.getIssueTypes()) {
                FieldScreenScheme emptyScheme = screenScheme(empty, empty, empty, null);
                when(top.getEffectiveFieldScreenScheme((IssueType) type)).thenReturn(emptyScheme);
            }
        }
        when(layouts.getFieldLayout(any(Project.class), anyString())).thenAnswer(call -> {
            Project p = call.getArgument(0);
            String key = p.getKey() + "/" + call.getArgument(1);
            FieldLayout layout = mock(FieldLayout.class);
            when(layout.isFieldHidden(anyString())).thenAnswer(inner ->
                    hiddenIn.getOrDefault(key, Collections.<String>emptySet()).contains(inner.<String>getArgument(0)));
            return layout;
        });
    }

    // ---- not on any screen -------------------------------------------------------------------------------------

    @Test
    public void aFieldOnNoScreenOfThePairsAndNotOnTheTransitionScreenIsWarnedAboutWithEveryPair() {
        List<String> warnings = placement.warnings(main, transitionScreen(screen()), Arrays.asList(DESCRIPTION));

        assertEquals(warnings.toString(), 1, warnings.size());
        String warning = warnings.get(0);
        assertTrue(warning, warning.contains("Description"));
        assertTrue(warning, warning.contains("ABC / Bug"));
        assertTrue(warning, warning.contains("ABC / Task"));
        assertTrue(warning, warning.contains("XYZ / Bug"));
        assertFalse("an issue type that uses another workflow is not a pair of this one: " + warning,
                warning.contains("ABC / Story"));
    }

    @Test
    public void aFieldOnTheTransitionScreenIsNotWarnedAboutEvenIfNoPairScreenHoldsIt() {
        assertTrue(placement.warnings(main, transitionScreen(screen("description")), Arrays.asList(DESCRIPTION)).isEmpty());
    }

    @Test
    public void aFieldOnOneScreenOfAPairIsNotWarnedAboutForThatPairOnly() {
        usingScreens(abc, abcBug, screenScheme(screen(), screen("description"), screen(), null));

        List<String> warnings = placement.warnings(main, transitionScreen(screen()), Arrays.asList(DESCRIPTION));

        assertEquals(warnings.toString(), 1, warnings.size());
        assertFalse(warnings.get(0), warnings.get(0).contains("ABC / Bug"));
        assertTrue(warnings.get(0), warnings.get(0).contains("ABC / Task"));
        assertTrue(warnings.get(0), warnings.get(0).contains("XYZ / Bug"));
    }

    @Test
    public void createEditAndViewScreensAreEachEnoughToSilenceThePair() {
        usingScreens(abc, abcBug, screenScheme(screen("description"), screen(), screen(), null));
        usingScreens(abc, abcTask, screenScheme(screen(), screen(), screen("description"), null));
        usingScreens(xyz, xyzBug, screenScheme(screen(), screen("description"), screen(), null));

        assertTrue(placement.warnings(main, transitionScreen(screen()), Arrays.asList(DESCRIPTION)).isEmpty());
    }

    @Test
    public void theDefaultItemOfAScreenSchemeCountsForAnOperationWithoutItsOwnItem() {
        FieldScreen withField = screen("description");
        for (Object[] pair : new Object[][] {{abc, abcBug}, {abc, abcTask}, {xyz, xyzBug}}) {
            FieldScreenScheme onlyDefault = mock(FieldScreenScheme.class);
            List<FieldScreenSchemeItem> defaultItem = Arrays.asList(item(null, withField));
            when(onlyDefault.getFieldScreenSchemeItems()).thenReturn(defaultItem);
            usingScreens((Project) pair[0], (IssueType) pair[1], onlyDefault);
        }

        assertTrue(placement.warnings(main, transitionScreen(screen()), Arrays.asList(DESCRIPTION)).isEmpty());
    }

    @Test
    public void aTransitionWithoutAScreenIsTreatedAsAFieldNotOnItsScreen() {
        List<String> warnings = placement.warnings(main, null, Arrays.asList(DESCRIPTION));

        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains("ABC / Bug"));
    }

    @Test
    public void onlyTheAddedFieldThatIsMissingIsNamedInTheWarning() {
        usingScreens(abc, abcBug, screenScheme(screen("description"), screen(), screen(), null));
        usingScreens(abc, abcTask, screenScheme(screen("description"), screen(), screen(), null));
        usingScreens(xyz, xyzBug, screenScheme(screen("description"), screen(), screen(), null));

        List<String> warnings = placement.warnings(main, transitionScreen(screen()), Arrays.asList(DESCRIPTION, TEAM));

        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains("Team"));
        assertFalse(warnings.get(0), warnings.get(0).contains("Description"));
    }

    // ---- hidden in the field configuration ---------------------------------------------------------------------

    @Test
    public void aFieldHiddenInTheFieldConfigurationOfAPairIsWarnedAboutWithThatPairOnly() {
        usingEverywhere("description");
        hiddenIn.put("ABC/2", Collections.singleton("description"));

        List<String> warnings = placement.warnings(main, transitionScreen(screen("description")), Arrays.asList(DESCRIPTION));

        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).toLowerCase().contains("hidden"));
        assertTrue(warnings.get(0), warnings.get(0).contains("ABC / Task"));
        assertFalse(warnings.get(0), warnings.get(0).contains("ABC / Bug"));
        assertFalse(warnings.get(0), warnings.get(0).contains("XYZ / Bug"));
    }

    @Test
    public void aFieldThatIsNotHiddenAnywhereGivesNoHiddenWarning() {
        usingEverywhere("description");
        hiddenIn.put("ABC/2", Collections.singleton("customfield_10200"));

        assertTrue(placement.warnings(main, transitionScreen(screen("description")), Arrays.asList(DESCRIPTION)).isEmpty());
    }

    @Test
    public void aFieldThatIsBothOffEveryScreenAndHiddenGetsBothWarnings() {
        hiddenIn.put("XYZ/1", Collections.singleton("description"));

        List<String> warnings = placement.warnings(main, transitionScreen(screen()), Arrays.asList(DESCRIPTION));

        assertEquals(warnings.toString(), 2, warnings.size());
        assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.toLowerCase().contains("hidden") && w.contains("XYZ / Bug")));
        assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> !w.toLowerCase().contains("hidden") && w.contains("ABC / Task")));
    }

    // ---- a workflow nobody uses --------------------------------------------------------------------------------

    @Test
    public void aWorkflowWithoutAssignmentsIsCheckedAgainstTheTransitionScreenOnly() {
        schemes.clear();

        List<String> warnings = placement.warnings(main, transitionScreen(screen()), Arrays.asList(DESCRIPTION));

        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).contains("Description"));
        assertTrue(warnings.get(0), warnings.get(0).toLowerCase().contains("not assigned"));
        assertTrue(placement.warnings(main, transitionScreen(screen("description")), Arrays.asList(DESCRIPTION)).isEmpty());
        verifyNoInteractions(layouts);
        verifyNoInteractions(screenSchemes);
    }

    @Test
    public void aSchemeDraftIsNotAnAssignmentOfAProject() {
        schemes.clear();
        schemes.add(mock(WorkflowScheme.class));

        List<String> warnings = placement.warnings(main, transitionScreen(screen()), Arrays.asList(DESCRIPTION));

        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.get(0), warnings.get(0).toLowerCase().contains("not assigned"));
    }

    // ---- what cannot be read ------------------------------------------------------------------------------------

    @Test
    public void aPairWhoseScreensCannotBeReadIsReportedAsUncheckedAndNeverStopsThePreview() {
        when(screenSchemes.getIssueTypeScreenScheme(xyz)).thenReturn(null);

        List<String> warnings = placement.warnings(main, transitionScreen(screen()), Arrays.asList(DESCRIPTION));

        assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.contains("XYZ / Bug") && w.toLowerCase().contains("could not be")));
        assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.contains("ABC / Bug") && w.contains("Description")));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private String transitionScreen(FieldScreen screen) {
        when(screens.getFieldScreen(9L)).thenReturn(screen);
        return "9";
    }

    private void usingEverywhere(String fieldId) {
        FieldScreen holding = screen(fieldId);
        for (Object[] pair : new Object[][] {{abc, abcBug}, {abc, abcTask}, {xyz, xyzBug}}) {
            usingScreens((Project) pair[0], (IssueType) pair[1], screenScheme(holding, holding, holding, null));
        }
    }

    private void usingScreens(Project project, IssueType type, FieldScreenScheme scheme) {
        IssueTypeScreenScheme top = screenSchemes.getIssueTypeScreenScheme(project);
        when(top.getEffectiveFieldScreenScheme(type)).thenReturn(scheme);
    }

    private static FieldScreen screen(String... fieldIds) {
        FieldScreen screen = mock(FieldScreen.class);
        Set<String> held = new HashSet<String>(Arrays.asList(fieldIds));
        when(screen.containsField(anyString())).thenAnswer(call -> held.contains(call.<String>getArgument(0)));
        return screen;
    }

    private static FieldScreenScheme screenScheme(FieldScreen create, FieldScreen edit, FieldScreen view, FieldScreen fallback) {
        FieldScreenScheme scheme = mock(FieldScreenScheme.class);
        List<FieldScreenSchemeItem> items = new ArrayList<FieldScreenSchemeItem>();
        items.add(item(IssueOperations.CREATE_ISSUE_OPERATION, create));
        items.add(item(IssueOperations.EDIT_ISSUE_OPERATION, edit));
        items.add(item(IssueOperations.VIEW_ISSUE_OPERATION, view));
        if (fallback != null) items.add(item(null, fallback));
        when(scheme.getFieldScreenSchemeItems()).thenReturn(items);
        return scheme;
    }

    private static FieldScreenSchemeItem item(ScreenableIssueOperation operation, FieldScreen screen) {
        FieldScreenSchemeItem item = mock(FieldScreenSchemeItem.class);
        when(item.getIssueOperation()).thenReturn(operation);
        when(item.getFieldScreen()).thenReturn(screen);
        return item;
    }

    private static IssueType type(String id, String name) {
        IssueType type = mock(IssueType.class);
        when(type.getId()).thenReturn(id);
        when(type.getName()).thenReturn(name);
        return type;
    }

    private static Project project(long id, String key, IssueType... types) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getKey()).thenReturn(key);
        Collection<IssueType> list = Arrays.asList(types);
        when(project.getIssueTypes()).thenReturn(list);
        return project;
    }
}
