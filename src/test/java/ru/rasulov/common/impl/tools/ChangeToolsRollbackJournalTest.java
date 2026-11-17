package ru.rasulov.common.impl.tools;

import com.atlassian.jira.user.ApplicationUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.api.exception.BusyException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.applies.ApplyEvent;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoTestFixture;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/** The confirmed rollback is journaled as one ROLLBACK row without changing what the rollback returns or throws. */
public class ChangeToolsRollbackJournalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private UndoTestFixture db;
    private ChangeJournal journal;
    private ApplyJournal applies;
    private ToolCall rollback;

    @Before
    public void setUp() {
        db = UndoTestFixture.create("admin");
        ApplicationUser actor = db.currentActor.get();
        when(actor.getDisplayName()).thenReturn("Admin Display");
        journal = mock(ChangeJournal.class);
        applies = mock(ApplyJournal.class);
        rollback = new ChangeTools(journal, db.guard, mock(OperationRunner.class), db.plans, applies).tools().get("rollback_changes");
    }

    @After
    public void tearDown() throws Exception {
        db.close();
    }

    private String plan(String request) {
        return db.plans.issue("rollback", "admin", request, "fp").getId();
    }

    private Map<String, Object> apply(String planId) throws Exception {
        return castMap(rollback.call(MAPPER.readTree("{\"dryRun\":false,\"planId\":\"" + planId + "\"}")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private ApplyEvent onlyEvent() {
        ArgumentCaptor<ApplyEvent> event = ArgumentCaptor.forClass(ApplyEvent.class);
        verify(applies, times(1)).record(event.capture());
        return event.getValue();
    }

    @Test
    public void successfulRollbackIsRecordedOnceWithItsStatusAndScope() throws Exception {
        String planId = plan("operation:op-7");
        Map<String, Object> response = Collections.<String, Object>singletonMap("status", "ROLLED_BACK");
        when(journal.rollback("admin", null, null, null, false, planId)).thenReturn(response);
        long before = System.currentTimeMillis();
        assertSame(response, apply(planId));
        ApplyEvent event = onlyEvent();
        assertEquals("ROLLBACK:" + planId, event.eventKey);
        assertEquals("ROLLBACK", event.eventType);
        assertEquals("admin", event.actorKey);
        assertEquals("Admin Display", event.actorName);
        assertEquals("rollback_changes", event.toolName);
        assertEquals("Откат изменений", event.toolTitle);
        assertEquals("op-7", event.operationRef);
        assertEquals("COMPLETED", event.operationState);
        assertEquals("ROLLED_BACK", event.domainStatus);
        assertNull(event.errorMessage);
        assertFalse(event.rollbackRequested);
        assertTrue(event.recordedAt >= before && event.recordedAt <= System.currentTimeMillis());
        assertEquals(1, event.targets.size());
        assertEquals("OPERATION", event.targets.get(0).type);
        assertEquals("op-7", event.targets.get(0).label);
    }

    @Test
    public void blockedRollbackIsStillAnAppliedRollbackWithStatusBlocked() throws Exception {
        String planId = plan("changeSet:cs-1");
        when(journal.rollback("admin", null, null, null, false, planId)).thenReturn(Collections.<String, Object>singletonMap("status", "BLOCKED"));
        apply(planId);
        ApplyEvent event = onlyEvent();
        assertEquals("COMPLETED", event.operationState);
        assertEquals("BLOCKED", event.domainStatus);
        assertEquals("CHANGE_SET", event.targets.get(0).type);
        assertEquals("cs-1", event.targets.get(0).label);
    }

    @Test
    public void sessionScopeGivesSessionTarget() throws Exception {
        String planId = plan("session:s-1");
        when(journal.rollback("admin", null, null, null, false, planId)).thenReturn(Collections.<String, Object>singletonMap("status", "PARTIAL"));
        apply(planId);
        assertEquals("SESSION", onlyEvent().targets.get(0).type);
    }

    @Test
    public void failureIsRecordedAsFailedWithItsMessageAndTheSameExceptionPropagates() throws Exception {
        String planId = plan("operation:op-9");
        InvalidRequestException failure = new InvalidRequestException("The rollback preview expired");
        when(journal.rollback("admin", null, null, null, false, planId)).thenThrow(failure);
        try {
            apply(planId);
            fail();
        } catch (InvalidRequestException thrown) {
            assertSame(failure, thrown);
        }
        ApplyEvent event = onlyEvent();
        assertEquals("FAILED", event.operationState);
        assertEquals("The rollback preview expired", event.errorMessage);
        assertNull(event.domainStatus);
        assertEquals("op-9", event.operationRef);
    }

    @Test
    public void unknownPlanWritesNoRowAndTheJournalExceptionPassesUnchanged() throws Exception {
        InvalidRequestException failure = new InvalidRequestException("Preview the rollback");
        when(journal.rollback("admin", null, null, null, false, "nope")).thenThrow(failure);
        try {
            apply("nope");
            fail();
        } catch (InvalidRequestException thrown) {
            assertSame(failure, thrown);
        }
        verifyNoInteractions(applies);
    }

    @Test
    public void foreignPlanIsNotAnApply() throws Exception {
        String planId = db.plans.issue("rollback", "someone-else", "operation:x", "fp").getId();
        when(journal.rollback("admin", null, null, null, false, planId)).thenThrow(new InvalidRequestException("no"));
        try { apply(planId); fail(); } catch (InvalidRequestException expected) { }
        verifyNoInteractions(applies);
    }

    @Test
    public void planOfAnotherToolIsNotAnApply() throws Exception {
        String planId = db.plans.issue("create_screen", "admin", "operation:x", "fp").getId();
        when(journal.rollback("admin", null, null, null, false, planId)).thenThrow(new InvalidRequestException("no"));
        try { apply(planId); fail(); } catch (InvalidRequestException expected) { }
        verifyNoInteractions(applies);
    }

    @Test
    public void successfulRetryAfterAFailureThatKeptThePreviewIsRecordedToo() throws Exception {
        String planId = plan("operation:op-3");
        Map<String, Object> response = Collections.<String, Object>singletonMap("status", "ROLLED_BACK");
        when(journal.rollback("admin", null, null, null, false, planId))
                .thenThrow(new BusyException("Nothing was changed; retry later."))
                .thenReturn(response);
        try { apply(planId); fail(); } catch (BusyException expected) { }
        assertSame(response, apply(planId));
        ArgumentCaptor<ApplyEvent> events = ArgumentCaptor.forClass(ApplyEvent.class);
        verify(applies, times(2)).record(events.capture());
        ApplyEvent failed = events.getAllValues().get(0);
        ApplyEvent completed = events.getAllValues().get(1);
        assertEquals("FAILED", failed.operationState);
        assertTrue(failed.eventKey.startsWith("ROLLBACK:" + planId + ":"));
        assertEquals("COMPLETED", completed.operationState);
        assertEquals("ROLLBACK:" + planId, completed.eventKey);
    }

    @Test
    public void failureAfterThePreviewIsConsumedTakesThePlanKey() throws Exception {
        String planId = plan("operation:op-4");
        when(journal.rollback("admin", null, null, null, false, planId)).thenAnswer(call -> {
            db.plans.remove(planId);
            throw new IllegalStateException("write failed");
        });
        try { apply(planId); fail(); } catch (IllegalStateException expected) { }
        ApplyEvent event = onlyEvent();
        assertEquals("FAILED", event.operationState);
        assertEquals("ROLLBACK:" + planId, event.eventKey);
    }

    @Test
    public void failingApplyJournalDoesNotChangeTheRollbackResult() throws Exception {
        String planId = plan("operation:op-1");
        Map<String, Object> response = Collections.<String, Object>singletonMap("status", "ROLLED_BACK");
        when(journal.rollback("admin", null, null, null, false, planId)).thenReturn(response);
        doThrow(new IllegalStateException("journal down")).when(applies).record(any(ApplyEvent.class));
        assertSame(response, apply(planId));
    }

    @Test
    public void previewIsNeverRecorded() throws Exception {
        Map<String, Object> preview = Collections.<String, Object>singletonMap("planId", "p");
        when(journal.rollback(eq("admin"), eq("op-1"), isNull(), isNull(), eq(true), isNull())).thenReturn(preview);
        assertSame(preview, rollback.call(MAPPER.readTree("{\"dryRun\":true,\"operationId\":\"op-1\"}")));
        verifyNoInteractions(applies);
    }
}
