package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;
import ru.rasulov.customfields.impl.JiraMocks;
import ru.rasulov.customfields.impl.validation.FieldFailureException;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.issueType;
import static ru.rasulov.customfields.impl.JiraMocks.project;

public class ContextValidatorTest {

    private IssueType bug;
    private IssueType task;
    private IssueType subtask;
    private IssueType story;
    private Project pay;
    private Project shop;
    private Project old;
    private List<Project> projects;
    private List<IssueType> issueTypes;
    private ContextValidator validator;

    @Before
    public void setUp() {
        bug = issueType("10004", "Bug", false);
        task = issueType("10002", "Task", false);
        subtask = issueType("10003", "Sub-task", true);
        story = issueType("10001", "Story", false);
        pay = project(1, "PAY", "Payments", bug, task);
        shop = project(2, "SHOP", "Webshop", bug);
        old = project(3, "OLD", "Old system", bug);
        when(old.isArchived()).thenReturn(true);
        projects = new ArrayList<Project>(Arrays.asList(pay, shop, old));
        issueTypes = new ArrayList<IssueType>(Arrays.asList(bug, task, subtask, story));

        ProjectManager projectManager = mock(ProjectManager.class);
        when(projectManager.getProjects()).thenAnswer(invocation -> projects);
        when(projectManager.getProjectByCurrentKeyIgnoreCase(anyString())).thenAnswer(invocation -> {
            for (Project candidate : projects) {
                if (candidate.getKey().equalsIgnoreCase((String) invocation.getArgument(0))) {
                    return candidate;
                }
            }
            return null;
        });
        IssueTypeManager issueTypeManager = mock(IssueTypeManager.class);
        when(issueTypeManager.getIssueTypes()).thenAnswer(invocation -> issueTypes);
        when(issueTypeManager.getIssueType(anyString())).thenAnswer(invocation -> {
            for (IssueType candidate : issueTypes) {
                if (candidate.getId().equals(invocation.getArgument(0))) {
                    return candidate;
                }
            }
            return null;
        });
        validator = new ContextValidator(new ProjectResolver(projectManager), new IssueTypeResolver(issueTypeManager));
    }

    // --- nothing is assumed ------------------------------------------------------------------------------------

    @Test
    public void noContextIsContextRequired() {
        assertFailure(CustomFieldErrorCode.CONTEXT_REQUIRED, Collections.<ContextDefinition>emptyList());
    }

    @Test
    public void aContextWithoutTheProjectAxisIsContextRequired() {
        assertFailure(CustomFieldErrorCode.CONTEXT_REQUIRED, ctx(false, none(), true, none()));
    }

    @Test
    public void aContextWithoutTheIssueTypeAxisIsContextRequired() {
        assertFailure(CustomFieldErrorCode.CONTEXT_REQUIRED, ctx(true, none(), false, none()));
    }

    @Test
    public void aNullContextIsContextRequired() {
        assertFailure(CustomFieldErrorCode.CONTEXT_REQUIRED, (ContextDefinition) null);
    }

    @Test
    public void allAndAListOnTheSameAxisAreRefused() {
        assertFailure(CustomFieldErrorCode.VALIDATION_ERROR, ctx(true, list("PAY"), true, none()));
        assertFailure(CustomFieldErrorCode.VALIDATION_ERROR, ctx(true, none(), true, list("Bug")));
    }

    @Test
    public void allIsAcceptedOnlyWhenWrittenOut() {
        ResolvedContext context = validator.validate(Collections.singletonList(ctx(true, none(), true, none()))).get(0);

        assertTrue(context.isAllProjects());
        assertTrue(context.isAllIssueTypes());
        assertTrue(context.getProjects().isEmpty());
        assertTrue(context.getIssueTypes().isEmpty());
    }

    // --- projects ----------------------------------------------------------------------------------------------

    @Test
    public void aProjectIsFoundByKeyIgnoringCase() {
        assertSame(pay, only(ctx(false, list("pay"), true, none())).getProjects().get(0));
    }

    @Test
    public void aProjectIsFoundByNameIgnoringCase() {
        assertSame(shop, only(ctx(false, list("webshop"), true, none())).getProjects().get(0));
    }

    @Test
    public void theKeyWinsWhenANameEqualsAnotherProjectsKey() {
        Project odd = project(4, "ODD", "SHOP");
        projects.add(odd);

        assertSame(shop, only(ctx(false, list("shop"), true, none())).getProjects().get(0));
    }

    @Test
    public void anUnknownProjectSuggestsResemblingActiveOnesOnly() {
        FieldFailureException failure = failureOf(ctx(false, list("pa"), true, none()));

        assertEquals(CustomFieldErrorCode.UNKNOWN_PROJECT, failure.getErrorCode());
        assertEquals(Collections.singletonList("PAY"), failure.getSuggestions());
    }

    @Test
    public void anArchivedProjectIsRefusedAndNotSuggested() {
        FieldFailureException refused = failureOf(ctx(false, list("OLD"), true, none()));
        FieldFailureException unknown = failureOf(ctx(false, list("ol"), true, none()));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, refused.getErrorCode());
        assertTrue(unknown.getSuggestions().isEmpty());
    }

    @Test
    public void theSameProjectByKeyAndByNameIsListedTwice() {
        assertFailure(CustomFieldErrorCode.VALIDATION_ERROR, ctx(false, list("PAY", "payments"), true, none()));
    }

    // --- issue types -------------------------------------------------------------------------------------------

    @Test
    public void anIssueTypeIsFoundByNameIgnoringCaseAndById() {
        ResolvedContext context = only(ctx(true, none(), false, list("bug", "10002")));

        assertEquals(Arrays.asList(bug, task), context.getIssueTypes());
    }

    @Test
    public void aSubtaskTypeIsAcceptedWhenNamed() {
        assertEquals(Collections.singletonList(subtask), only(ctx(true, none(), false, list("Sub-task"))).getIssueTypes());
    }

    @Test
    public void anUnknownIssueTypeSuggestsResemblingNames() {
        FieldFailureException failure = failureOf(ctx(true, none(), false, list("tas")));

        assertEquals(CustomFieldErrorCode.UNKNOWN_ISSUE_TYPE, failure.getErrorCode());
        assertEquals(Arrays.asList("Sub-task", "Task"), failure.getSuggestions());
    }

    @Test
    public void aNameSharedByTwoTypesIsRefusedWithTheirIds() {
        issueTypes.add(issueType("10099", "bug", false));

        FieldFailureException failure = failureOf(ctx(true, none(), false, list("Bug")));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        assertEquals(Arrays.asList("10004", "10099"), failure.getSuggestions());
    }

    @Test
    public void theSameIssueTypeByNameAndByIdIsListedTwice() {
        assertFailure(CustomFieldErrorCode.VALIDATION_ERROR, ctx(true, none(), false, list("Bug", "10004")));
    }

    // --- names -------------------------------------------------------------------------------------------------

    @Test
    public void theDefaultNameIsTheKeysOfTheFirstFiveProjects() {
        for (int i = 10; i < 16; i++) {
            projects.add(project(i, "P" + i, "Project " + i));
        }

        assertEquals("PAY, SHOP", only(ctx(false, list("PAY", "SHOP"), true, none())).getName());
        assertEquals("P10, P11, P12, P13, P14",
                only(ctx(false, list("P10", "P11", "P12", "P13", "P14", "P15"), true, none())).getName());
    }

    @Test
    public void allProjectsAreCalledGlobalUnlessNamed() {
        assertEquals("Global", only(ctx(true, none(), true, none())).getName());
        assertEquals("Mine", only(new ContextDefinition(" Mine ", true, null, true, null, null)).getName());
    }

    @Test
    public void twoContextsWithTheSameNameGetDistinctOnes() {
        List<ResolvedContext> contexts = validator.validate(Arrays.asList(
                new ContextDefinition("Same", false, list("PAY"), false, list("Bug"), null),
                new ContextDefinition("same", false, list("SHOP"), true, null, null)));

        assertEquals("Same", contexts.get(0).getName());
        assertEquals("same (2)", contexts.get(1).getName());
    }

    @Test
    public void aContextNameOver255CharactersIsRefused() {
        char[] chars = new char[256];
        Arrays.fill(chars, 'x');

        assertFailure(CustomFieldErrorCode.VALIDATION_ERROR,
                new ContextDefinition(new String(chars), true, null, true, null, null));
    }

    // --- several contexts --------------------------------------------------------------------------------------

    @Test
    public void contextsOnDifferentProjectsAreFine() {
        assertEquals(2, validator.validate(Arrays.asList(
                ctx(false, list("PAY"), true, none()), ctx(false, list("SHOP"), true, none()))).size());
    }

    @Test
    public void oneProjectInTwoContextsIsRefusedEvenForDisjointIssueTypes() {
        FieldFailureException failure = failureOf(
                ctx(false, list("PAY"), false, list("Bug")), ctx(false, list("PAY"), false, list("Task")));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("Project PAY is in contexts #1 (PAY) and #2 (PAY (2))"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("ONE context"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("SEPARATE fields"));
        assertTrue(failure.getMessage(), failure.getMessage().contains("do not choose"));
    }

    @Test
    public void aKeyAndANameOfOneProjectDoNotBypassTheOneContextRule() {
        FieldFailureException failure = failureOf(
                ctx(false, list("PAY"), false, list("Bug")), ctx(false, list("Payments"), false, list("Task")));

        assertTrue(failure.getMessage(), failure.getMessage().contains("Project PAY"));
    }

    @Test
    public void oneContextMayCoverSeveralIssueTypesOfOneProject() {
        assertEquals(1, validator.validate(Arrays.asList(ctx(false, list("PAY"), false, list("Bug", "Task")))).size());
    }

    @Test
    public void aSecondGlobalContextIsRefusedEvenForDisjointIssueTypes() {
        FieldFailureException failure = failureOf(
                ctx(true, none(), false, list("Bug")), ctx(true, none(), false, list("Task")));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, failure.getErrorCode());
        assertTrue(failure.getMessage(), failure.getMessage().contains("both global"));
    }

    @Test
    public void contextsSharingAProjectAndAnIssueTypeOverlap() {
        assertFailure(CustomFieldErrorCode.VALIDATION_ERROR,
                ctx(false, list("PAY"), false, list("Bug", "Task")), ctx(false, list("PAY", "SHOP"), false, list("Task")));
    }

    @Test
    public void oneGlobalContextMayCoexistWithProjectContextsButEachGetsAWarning() {
        List<ResolvedContext> resolved = validator.validate(Arrays.asList(
                ctx(true, none(), true, none()), ctx(false, list("PAY"), true, none()),
                ctx(false, list("SHOP"), false, list("Bug"))));

        assertEquals(3, resolved.size());
        assertTrue(resolved.get(0).getWarnings().isEmpty());
        assertFalse(resolved.get(1).getWarnings().isEmpty());
        assertFalse(resolved.get(2).getWarnings().isEmpty());
    }

    // --- limits ------------------------------------------------------------------------------------------------

    @Test
    public void theNumberOfContextsIsLimited() {
        List<ContextDefinition> tooMany = new ArrayList<ContextDefinition>();
        for (int i = 0; i <= Limits.MAX_CONTEXTS_PER_FIELD; i++) {
            tooMany.add(ctx(true, none(), true, none()));
        }

        assertFailure(CustomFieldErrorCode.VALIDATION_ERROR, tooMany.toArray(new ContextDefinition[0]));
    }

    @Test
    public void theNumberOfProjectsAndIssueTypesPerContextIsLimited() {
        assertFailure(CustomFieldErrorCode.VALIDATION_ERROR,
                ctx(false, numbered(Limits.MAX_PROJECTS_PER_CONTEXT + 1), true, none()));
        assertFailure(CustomFieldErrorCode.VALIDATION_ERROR,
                ctx(true, none(), false, numbered(Limits.MAX_ISSUE_TYPES_PER_CONTEXT + 1)));
    }

    // --- warnings ----------------------------------------------------------------------------------------------

    @Test
    public void anIssueTypeThatNoChosenProjectUsesIsAllowedButWarnedAbout() {
        ResolvedContext context = only(ctx(false, list("SHOP"), false, list("Bug", "Task")));

        assertEquals(1, context.getWarnings().size());
        assertTrue(context.getWarnings().get(0), context.getWarnings().get(0).contains("Task"));
    }

    @Test
    public void noWarningWhenEveryIssueTypeIsUsedOrAllAreMeant() {
        assertTrue(only(ctx(false, list("PAY"), false, list("Bug", "Task"))).getWarnings().isEmpty());
        assertTrue(only(ctx(false, list("PAY"), true, none())).getWarnings().isEmpty());
        assertTrue(only(ctx(true, none(), false, list("Story"))).getWarnings().isEmpty());
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private ResolvedContext only(ContextDefinition definition) {
        return validator.validate(Collections.singletonList(definition)).get(0);
    }

    private FieldFailureException failureOf(ContextDefinition... definitions) {
        try {
            validator.validate(Arrays.asList(definitions));
        } catch (FieldFailureException e) {
            return e;
        }
        fail("expected FieldFailureException");
        return null;
    }

    private void assertFailure(CustomFieldErrorCode code, ContextDefinition... definitions) {
        assertEquals(code, failureOf(definitions).getErrorCode());
    }

    private void assertFailure(CustomFieldErrorCode code, List<ContextDefinition> definitions) {
        assertFailure(code, definitions.toArray(new ContextDefinition[0]));
    }

    private static ContextDefinition ctx(boolean allProjects, List<String> projects, boolean allIssueTypes,
                                         List<String> issueTypes) {
        return new ContextDefinition(null, allProjects, projects, allIssueTypes, issueTypes, null);
    }

    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }

    private static List<String> none() {
        return Collections.emptyList();
    }

    private static List<String> numbered(int count) {
        List<String> values = new ArrayList<String>();
        for (int i = 0; i < count; i++) {
            values.add("v" + i);
        }
        return values;
    }
}
