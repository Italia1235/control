package ru.rasulov.workflow.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.workflow.api.WorkflowLimits;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.WorkflowErrorCode;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowStatus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static ru.rasulov.workflow.impl.WorkflowMocks.fromAny;
import static ru.rasulov.workflow.impl.WorkflowMocks.move;
import static ru.rasulov.workflow.impl.WorkflowMocks.request;
import static ru.rasulov.workflow.impl.WorkflowMocks.simple;
import static ru.rasulov.workflow.impl.WorkflowMocks.status;

public class WorkflowRequestValidatorTest {

    private final ApplicationUser user = mock(ApplicationUser.class);
    private final StatusCatalog catalog = mock(StatusCatalog.class);
    private final TransitionScreenCatalog screens = mock(TransitionScreenCatalog.class);
    private final WorkflowRequestValidator validator = new WorkflowRequestValidator(catalog, screens, WorkflowMocks.resolutions());
    private Status open;
    private Status done;

    /** Jira has "Open" (to do) and "Done" (done); "In Progress" is new. */
    @Before
    public void jiraHasOpenAndDone() {
        open = status("1", "Open", "new");
        done = status("2", "Done", "done");
        when(catalog.find("Open")).thenReturn(open);
        when(catalog.find("Done")).thenReturn(done);
    }

    @Test
    public void existingStatusesAreReusedWithTheNameJiraUses() {
        when(catalog.find("open")).thenReturn(open);
        WorkflowDefinition request = request("W",
                Arrays.asList(status("open", null), status("Done", null)), "OPEN",
                Collections.singletonList(move("Go", "open", "done")));

        ResolvedWorkflow resolved = validator.validate(request, user);

        assertSame(open, resolved.getStatuses().get(0).getExisting());
        assertEquals("Open", resolved.getStatuses().get(0).getName());
        assertEquals("TO_DO", resolved.getStatuses().get(0).getCategory());
        assertEquals("Open", resolved.getInitial().getName());
        assertSame(resolved.getStatuses().get(1), resolved.getTransitions().get(0).getTo());
    }

    @Test
    public void transitionScreenIsResolvedByTheCatalog() {
        FieldScreen screen = mock(FieldScreen.class);
        when(screens.resolve("Release screen", "transitions[0].screen ('Release screen')")).thenReturn(screen);
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Collections.singletonList(new TransitionDefinition(
                        "Finish", "Open", false, "Done", "Release screen", WorkflowMocks.fixed())));

        ResolvedWorkflow resolved = validator.validate(request, user);

        assertSame(screen, resolved.getTransitions().get(0).getScreen());
    }

    @Test
    public void aStatusJiraLacksIsNewAndKeepsItsCategory() {
        ResolvedWorkflow resolved = validator.validate(simple(), user);

        WorkflowStatus inProgress = resolved.getStatuses().get(1);
        assertTrue(inProgress.isNew());
        assertEquals("IN_PROGRESS", inProgress.getCategory());
        assertFalse(resolved.getStatuses().get(0).isNew());
    }

    @Test
    public void categoryOfANewStatusIsRequiredAndSuggestsTheThree() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Review", null)), "Open",
                Collections.singletonList(move("Go", "Open", "Review")));

        WorkflowFailureException e = failure(request);

        assertEquals(WorkflowErrorCode.CATEGORY_REQUIRED, e.getErrorCode());
        assertEquals(Arrays.asList("TO_DO", "IN_PROGRESS", "DONE"), e.getSuggestions());
    }

    @Test
    public void unknownCategoryIsRejectedWithTheValidOnes() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Review", "SOON")), "Open",
                Collections.singletonList(move("Go", "Open", "Review")));

        WorkflowFailureException e = failure(request);

        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, e.getErrorCode());
        assertEquals(Arrays.asList("TO_DO", "IN_PROGRESS", "DONE"), e.getSuggestions());
    }

    @Test
    public void categoryOfAnExistingStatusIsIgnoredWithAWarning() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", "DONE"), status("Done", "DONE")), "Open",
                Collections.singletonList(move("Go", "Open", "Done")));

        ResolvedWorkflow resolved = validator.validate(request, user);

        assertEquals("TO_DO", resolved.getStatuses().get(0).getCategory());
        assertTrue(resolved.getWarnings().toString(), resolved.getWarnings().get(0).contains("already exists in category TO_DO"));
    }

    @Test
    public void jiraRefusingANewStatusFailsTheWorkflowWithJirasReason() {
        when(catalog.creationProblems(eq(user), eq("In Progress"), anyString(), any())).thenReturn("name is reserved");

        WorkflowFailureException e = failure(simple());

        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().contains("name is reserved"));
    }

    @Test
    public void nameIsRequiredAndLimited() {
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(withName("  ")).getErrorCode());
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(withName(null)).getErrorCode());
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR,
                failure(withName(repeat('x', WorkflowLimits.MAX_NAME_LENGTH + 1))).getErrorCode());
        validator.validate(withName(repeat('x', WorkflowLimits.MAX_NAME_LENGTH)), user);
    }

    @Test
    public void nameWithALineBreakIsRejected() {
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(withName("a\nb")).getErrorCode());
    }

    @Test
    public void nameIsTrimmed() {
        assertEquals("Bug flow", validator.validate(withName("  Bug flow "), user).getName());
    }

    @Test
    public void atLeastTwoStatusesAndNoMoreThanTheLimit() {
        WorkflowDefinition one = request("W", Collections.singletonList(status("Open", null)), "Open",
                Collections.singletonList(move("Go", "Open", "Open")));
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(one).getErrorCode());

        List<StatusDefinition> many = new ArrayList<StatusDefinition>();
        for (int i = 0; i <= WorkflowLimits.MAX_STATUSES; i++) {
            many.add(status("S" + i, "TO_DO"));
        }
        WorkflowDefinition tooMany = request("W", many, "S0",
                Collections.singletonList(move("Go", "S0", "S1")));
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(tooMany).getErrorCode());
        assertTrue(failure(tooMany).getMessage().contains(String.valueOf(WorkflowLimits.MAX_STATUSES)));

        validator.validate(request("W", many.subList(0, WorkflowLimits.MAX_STATUSES), "S0",
                Collections.singletonList(move("Go", "S0", "S1"))), user);
    }

    @Test
    public void statusNameIsLimitedToJirasLimitForStatuses() {
        String tooLong = repeat('s', WorkflowLimits.MAX_STATUS_NAME_LENGTH + 1);
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status(tooLong, "DONE")), "Open",
                Collections.singletonList(move("Go", "Open", tooLong)));

        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(request).getErrorCode());
    }

    @Test
    public void aStatusListedTwiceIsRejectedEvenWithDifferentCase() {
        when(catalog.find("done")).thenReturn(done);
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null), status("done", null)), "Open",
                Collections.singletonList(move("Go", "Open", "Done")));

        WorkflowFailureException e = failure(request);

        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, e.getErrorCode());
        assertTrue(e.getMessage(), e.getMessage().contains("more than once"));
    }

    @Test
    public void initialStatusMustBeListedAndSuggestsTheListed() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Backlog",
                Collections.singletonList(move("Go", "Open", "Done")));

        WorkflowFailureException e = failure(request);

        assertEquals(WorkflowErrorCode.UNKNOWN_STATUS, e.getErrorCode());
        assertEquals(Arrays.asList("Open", "Done"), e.getSuggestions());
    }

    @Test
    public void initialStatusIsRequired() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), " ",
                Collections.singletonList(move("Go", "Open", "Done")));

        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(request).getErrorCode());
    }

    @Test
    public void transitionToOrFromAnUnlistedStatusIsUnknownStatus() {
        List<StatusDefinition> statuses = Arrays.asList(status("Open", null), status("Done", null));

        WorkflowFailureException to = failure(request("W", statuses, "Open",
                Collections.singletonList(move("Go", "Open", "Review"))));
        WorkflowFailureException from = failure(request("W", statuses, "Open",
                Collections.singletonList(move("Go", "Review", "Done"))));
        WorkflowFailureException noTarget = failure(request("W", statuses, "Open",
                Collections.singletonList(move("Go", "Open", null))));

        for (WorkflowFailureException e : Arrays.asList(to, from, noTarget)) {
            assertEquals(WorkflowErrorCode.UNKNOWN_STATUS, e.getErrorCode());
            assertEquals(Arrays.asList("Open", "Done"), e.getSuggestions());
        }
        assertTrue(to.getMessage().contains("transitions[0].to"));
        assertTrue(from.getMessage().contains("transitions[0].from"));
    }

    @Test
    public void aTransitionNeedsExactlyOneOfFromAndFromAny() {
        List<StatusDefinition> statuses = Arrays.asList(status("Open", null), status("Done", null));

        TransitionDefinition neither = new TransitionDefinition("Go", null, false, "Done");
        TransitionDefinition both = new TransitionDefinition("Go", "Open", true, "Done");
        TransitionDefinition blankFrom = new TransitionDefinition("Go", "  ", false, "Done");

        for (TransitionDefinition definition : Arrays.asList(neither, both, blankFrom)) {
            WorkflowFailureException e = failure(request("W", statuses, "Open",
                    Collections.singletonList(definition)));
            assertEquals(WorkflowErrorCode.VALIDATION_ERROR, e.getErrorCode());
            assertTrue(e.getMessage(), e.getMessage().contains("exactly one of 'from'"));
        }
    }

    @Test
    public void fromAnyTransitionIsResolvedWithoutASource() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(move("Finish", "Open", "Done"), fromAny("Reopen", "Open")));

        ResolvedWorkflow resolved = validator.validate(request, user);

        assertTrue(resolved.getTransitions().get(1).isFromAny());
        assertNull(resolved.getTransitions().get(1).getFrom());
        assertEquals("Open", resolved.getTransitions().get(1).getTo().getName());
    }

    @Test
    public void transitionsAreRequiredAndLimited() {
        List<StatusDefinition> statuses = Arrays.asList(status("Open", null), status("Done", null));
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR,
                failure(request("W", statuses, "Open", Collections.<TransitionDefinition>emptyList())).getErrorCode());

        List<TransitionDefinition> many = new ArrayList<TransitionDefinition>();
        for (int i = 0; i <= WorkflowLimits.MAX_TRANSITIONS; i++) {
            many.add(move("T" + i, "Open", "Done"));
        }
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(request("W", statuses, "Open", many)).getErrorCode());
    }

    @Test
    public void transitionNameIsRequired() {
        List<StatusDefinition> statuses = Arrays.asList(status("Open", null), status("Done", null));
        WorkflowFailureException e = failure(request("W", statuses, "Open",
                Collections.singletonList(move(" ", "Open", "Done"))));
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, e.getErrorCode());
        assertTrue(e.getMessage().contains("transitions[0].name"));
    }

    @Test
    public void graphKeysKeepArrowBearingStatusPairsDistinct() {
        ResolvedWorkflow resolved = validator.validate(request("W",
                Arrays.asList(status("A->B", "TO_DO"), status("C", "DONE"),
                        status("A", "TO_DO"), status("B->C", "DONE")), "A->B",
                Arrays.asList(new TransitionDefinition("First", "A->B", false, "C", null, WorkflowMocks.fixed()),
                        new TransitionDefinition("Second", "A", false, "B->C", null, WorkflowMocks.fixed()))), user);

        assertEquals("A->B", resolved.getTransitions().get(0).getFrom().getName());
        assertEquals("C", resolved.getTransitions().get(0).getTo().getName());
        assertEquals("A", resolved.getTransitions().get(1).getFrom().getName());
        assertEquals("B->C", resolved.getTransitions().get(1).getTo().getName());
    }

    @Test
    public void graphKeysKeepLiteralStarSourceDistinctFromGlobal() {
        ResolvedWorkflow resolved = validator.validate(request("W",
                Arrays.asList(status("*", "TO_DO"), status("Done", null)), "*",
                Arrays.asList(move("Local", "*", "Done"), fromAny("Global", "Done"))), user);

        assertFalse(resolved.getTransitions().get(0).isFromAny());
        assertEquals("*", resolved.getTransitions().get(0).getFrom().getName());
        assertTrue(resolved.getTransitions().get(1).isFromAny());
        assertTrue(String.join(" ", resolved.getWarnings()).contains("Redundant: 'Local'"));
    }

    @Test
    public void theSameNormalizedNameSourceAndTargetTwiceIsRejectedForLocalAndGlobalTransitions() {
        List<StatusDefinition> statuses = Arrays.asList(status("Open", null), status("Done", null));

        WorkflowFailureException ordinary = failure(request("W", statuses, "Open",
                Arrays.asList(move("Finish", "Open", "Done"), move("  fINISH  ", "open", "DONE"))));
        WorkflowFailureException global = failure(request("W", statuses, "Open",
                Arrays.asList(fromAny("Reopen", "Open"), fromAny("  rEOPEN  ", " open "))));

        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, ordinary.getErrorCode());
        assertEquals(WorkflowErrorCode.VALIDATION_ERROR, global.getErrorCode());
    }

    @Test
    public void differentNamesMayShareTheSameSourceAndTargetForLocalAndGlobalTransitions() {
        ResolvedWorkflow resolved = validator.validate(request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(move("Finish", "Open", "Done"), move("Close", "open", "DONE"),
                        fromAny("Reopen", "Open"), fromAny("Restart", "open"))), user);

        assertEquals(4, resolved.getTransitions().size());
        assertEquals("Finish", resolved.getTransitions().get(0).getName());
        assertEquals("Close", resolved.getTransitions().get(1).getName());
        assertEquals("Reopen", resolved.getTransitions().get(2).getName());
        assertEquals("Restart", resolved.getTransitions().get(3).getName());
    }

    @Test
    public void aLoopIsAllowed() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(move("Finish", "Open", "Done"), move("Comment", "Open", "Open")));

        ResolvedWorkflow resolved = validator.validate(request, user);

        assertSame(resolved.getTransitions().get(1).getFrom(), resolved.getTransitions().get(1).getTo());
    }

    @Test
    public void unreachableStatusAndDeadEndAreWarningsNotErrors() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null), status("Review", "IN_PROGRESS"),
                        status("Parked", "IN_PROGRESS")), "Open",
                Arrays.asList(move("Finish", "Open", "Done"), move("Back", "Review", "Open")));

        ResolvedWorkflow resolved = validator.validate(request, user);

        String warnings = String.join(" ", resolved.getWarnings());
        assertTrue(warnings, warnings.contains("No transition leads to 'Review', 'Parked'"));
        assertTrue(warnings, warnings.contains("No transition leaves 'Open'") == false);
        assertTrue(warnings, warnings.contains("No transition leaves 'Parked'"));
    }

    @Test
    public void aStatusInCategoryDoneMayBeADeadEnd() {
        ResolvedWorkflow resolved = validator.validate(request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Collections.singletonList(move("Finish", "Open", "Done"))), user);

        assertTrue(resolved.getWarnings().toString(), resolved.getWarnings().isEmpty());
    }

    @Test
    public void aFromAnyTransitionMakesItsTargetReachableAndEveryStatusLeavable() {
        // Review has no ordinary way in or out, but "from any -> Review" gets in and "from any -> Open" gets out.
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null), status("Review", "IN_PROGRESS")), "Open",
                Arrays.asList(move("Finish", "Open", "Done"), fromAny("Review", "Review"), fromAny("Reopen", "Open")));

        ResolvedWorkflow resolved = validator.validate(request, user);

        assertTrue(resolved.getWarnings().toString(), resolved.getWarnings().isEmpty());
    }

    @Test
    public void aStatusWhoseOnlyWayOutIsALoopIsADeadEnd() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Stuck", "IN_PROGRESS")), "Open",
                Arrays.asList(move("Go", "Open", "Stuck"), move("Wait", "Stuck", "Stuck")));

        String warnings = String.join(" ", validator.validate(request, user).getWarnings());

        assertTrue(warnings, warnings.contains("No transition leaves 'Stuck'"));
    }

    @Test
    public void anOrdinaryTransitionToAStatusThatHasAFromAnyTransitionIsRedundant() {
        WorkflowDefinition request = request("W",
                Arrays.asList(status("Open", null), status("Done", null)), "Open",
                Arrays.asList(move("Finish", "Open", "Done"), move("Reopen", "Done", "Open"), fromAny("Back", "Open")));

        String warnings = String.join(" ", validator.validate(request, user).getWarnings());

        assertTrue(warnings, warnings.contains("Redundant: 'Reopen' ('Done' to 'Open')"));
    }

    @Test
    public void resolutionTableUsesActualCategoriesIncludingLoopsAndGlobalActions() {
        List<StatusDefinition> statuses = Arrays.asList(status("Open", null), status("Done", null),
                status("Cancelled", "DONE"), status("Review", "IN_PROGRESS"));
        List<TransitionDefinition> transitions = Arrays.asList(
                new TransitionDefinition("Finish", "Open", false, "Done", null, WorkflowMocks.fixed()),
                new TransitionDefinition("Reopen", "Done", false, "Open"),
                new TransitionDefinition("Cancel", "Done", false, "Cancelled", null, WorkflowMocks.fixed()),
                new TransitionDefinition("Review", "Open", false, "Review"),
                new TransitionDefinition("Keep", "Done", false, "Done"),
                new TransitionDefinition("Change outcome", "Cancelled", false, "Cancelled", null, WorkflowMocks.fixed()),
                new TransitionDefinition("Global finish", null, true, "Done", null, WorkflowMocks.fixed()),
                new TransitionDefinition("Global reopen", null, true, "Open"));
        ResolvedWorkflow resolved = validator.validate(new WorkflowDefinition("W", null, statuses,
                "Open", transitions), user);
        String[] actions = {"SET", "CLEAR", "SET", "PRESERVE", "PRESERVE", "SET", "SET", "CLEAR"};
        for (int i = 0; i < actions.length; i++) {
            assertEquals(actions[i], resolved.getTransitions().get(i).getResolution().getAction());
        }
        assertEquals("Fixed", resolved.getTransitions().get(0).getResolution().getName());
        assertEquals("Work completed", resolved.getTransitions().get(0).getResolution().getDescription());
    }

    @Test
    public void missingRequiredSetsAndContradictoryOrInvalidActionsAreRejected() {
        List<StatusDefinition> statuses = Arrays.asList(status("Open", null), status("Done", null),
                status("Cancelled", "DONE"));
        List<TransitionDefinition> invalid = Arrays.asList(
                new TransitionDefinition("Missing", "Open", false, "Done"),
                new TransitionDefinition("Missing global", null, true, "Done"),
                new TransitionDefinition("Missing new outcome", "Done", false, "Cancelled"),
                new TransitionDefinition("Clear done", "Open", false, "Done", null,
                        new ru.rasulov.workflow.api.dto.TransitionResolution("CLEAR", null)),
                new TransitionDefinition("Set open", "Done", false, "Open", null, WorkflowMocks.fixed()),
                new TransitionDefinition("Clear with value", "Done", false, "Open", null,
                        new ru.rasulov.workflow.api.dto.TransitionResolution("CLEAR", "1")));
        for (TransitionDefinition transition : invalid) {
            assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(new WorkflowDefinition("W", null,
                    statuses, "Open", Collections.singletonList(transition))).getErrorCode());
        }
        for (String id : Arrays.asList(null, "", " ", " 1", "1 ", "Fixed", "999")) {
            assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(new WorkflowDefinition("W", null,
                    statuses, "Open", Collections.singletonList(new TransitionDefinition("Bad ID", "Open", false,
                    "Done", null, new ru.rasulov.workflow.api.dto.TransitionResolution("SET", id))))).getErrorCode());
        }
        for (String action : Arrays.asList(null, "", "set", "PRESERVE", "UNKNOWN")) {
            assertEquals(WorkflowErrorCode.VALIDATION_ERROR, failure(new WorkflowDefinition("W", null,
                    statuses, "Open", Collections.singletonList(new TransitionDefinition("Bad action", "Open", false,
                    "Done", null, new ru.rasulov.workflow.api.dto.TransitionResolution(action, "1"))))).getErrorCode());
        }
    }

    @Test
    public void explicitClearIsAllowedIntoAnyUnfinishedStatusWhileOmissionPreserves() throws Exception {
        List<StatusDefinition> statuses = Arrays.asList(status("QA", "IN_PROGRESS"), status("Building", "IN_PROGRESS"));
        ResolvedWorkflow resolved = validator.validate(new WorkflowDefinition("W", null, statuses, "Building",
                Arrays.asList(new TransitionDefinition("Back to build", "QA", false, "Building", null,
                                new ru.rasulov.workflow.api.dto.TransitionResolution("CLEAR", null)),
                        new TransitionDefinition("To QA", "Building", false, "QA"))), user);
        assertEquals("CLEAR", resolved.getTransitions().get(0).getResolution().getAction());
        assertEquals("PRESERVE", resolved.getTransitions().get(1).getResolution().getAction());
    }

    private WorkflowFailureException failure(WorkflowDefinition request) {
        try {
            validator.validate(request, user);
        } catch (WorkflowFailureException e) {
            return e;
        }
        fail("expected the request to be rejected");
        return null;
    }

    private static WorkflowDefinition withName(String name) {
        WorkflowDefinition base = simple();
        return new WorkflowDefinition(name, null, base.getStatuses(), base.getInitialStatus(),
                base.getTransitions());
    }

    private static String repeat(char c, int times) {
        char[] chars = new char[times];
        Arrays.fill(chars, c);
        return new String(chars);
    }
}
