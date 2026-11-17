package ru.rasulov.common.impl.tools;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.applies.ApplyEvent;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.undo.UndoTestFixture;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real journal storage and executor; the apply journal is a recording double. */
public class OperationRunnerApplyJournalTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private UndoTestFixture db;
    private ApplyJournal applies;
    private OperationRunner runner;
    private Semaphore finished;
    private final AtomicReference<ToolCall> tool = new AtomicReference<ToolCall>();

    @Before
    public void setUp() {
        db = UndoTestFixture.create("admin");
        ApplicationUser actor = db.currentActor.get();
        when(actor.getDisplayName()).thenReturn("Admin Display");
        applies = mock(ApplyJournal.class);
        finished = new Semaphore(0);
        JiraThreadLocalUtil threadLocals = mock(JiraThreadLocalUtil.class);
        doAnswer(call -> { finished.release(); return null; }).when(threadLocals).postCall(any(org.slf4j.Logger.class));
        ToolProvider domain = () -> {
            Map<String, ToolCall> calls = new LinkedHashMap<String, ToolCall>();
            calls.put("create_custom_fields", args -> tool.get().call(args));
            calls.put("create_screen", args -> tool.get().call(args));
            calls.put("edit_screen_layout", args -> tool.get().call(args));
            return calls;
        };
        ToolRegistry registry = new ToolRegistry(Collections.singletonList(domain));
        runner = new OperationRunner(db.journal, db.guard, () -> registry, new ToolCatalog(mock(PluginAccessor.class)),
                mock(JiraAuthenticationContext.class), threadLocals, db.plans, applies);
    }

    @After
    public void tearDown() throws Exception {
        runner.destroy();
        db.close();
    }

    private Map<String, Object> apply(String request) throws Exception {
        return apply("create_screen", request);
    }

    private Map<String, Object> apply(String toolName, String request) throws Exception {
        ActionPlanStore.Plan plan = db.plans.issue(toolName, "admin", request, "fp");
        return runner.start(toolName, MAPPER.readTree("{\"dryRun\":false,\"planId\":\"" + plan.getId() + "\"}"), null, null);
    }

    private ApplyEvent onlyEvent() throws Exception {
        assertTrue("The apply did not finish", finished.tryAcquire(10, TimeUnit.SECONDS));
        ArgumentCaptor<ApplyEvent> event = ArgumentCaptor.forClass(ApplyEvent.class);
        verify(applies, times(1)).record(event.capture());
        return event.getValue();
    }

    @Test
    public void completedApplyIsRecordedOnceWithResultTargetsAndLinks() throws Exception {
        tool.set(args -> {
            Map<String, Object> created = new LinkedHashMap<String, Object>();
            created.put("kind", "SCREEN");
            created.put("id", "10");
            created.put("name", "AJ screen");
            created.put("url", "http://j/secure/admin/ConfigureFieldScreen.jspa?id=10");
            Map<String, Object> report = Collections.<String, Object>singletonMap("created", Collections.singletonList(created));
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "CREATED");
            result.put("report", report);
            return result;
        });
        Map<String, Object> started = apply("{\"name\":\"AJ screen\",\"project\":\"PAY\"}");
        ApplyEvent event = onlyEvent();
        String id = (String) started.get("operationId");
        assertEquals("APPLY:" + id, event.eventKey);
        assertEquals("APPLY", event.eventType);
        assertEquals("admin", event.actorKey);
        assertEquals("Admin Display", event.actorName);
        assertEquals("create_screen", event.toolName);
        assertEquals("Создание отдельного экрана", event.toolTitle);
        assertEquals(id, event.operationRef);
        assertEquals("COMPLETED", event.operationState);
        assertEquals("CREATED", event.domainStatus);
        assertNull(event.errorCode);
        assertNull(event.errorMessage);
        assertFalse(event.rollbackRequested);
        assertEquals(db.journal.getOperation(id, "admin").get("finishedAt"), event.recordedAt);
        // The request's {"name":"AJ screen"} names the reported screen again and is not a second target.
        assertEquals(2, event.targets.size());
        assertEquals("SCREEN", event.targets.get(0).type);
        assertEquals("AJ screen", event.targets.get(0).label);
        assertEquals("PROJECT", event.targets.get(1).type);
        assertEquals(1, event.links.size());
    }

    /** The write happened; storing the result fails. The operation must not read as "nothing happened" and must not run twice. */
    private void assertResultNotStoredAfterAWrite(java.util.function.Supplier<Object> resultFactory) throws Exception {
        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        tool.set(args -> { writes.incrementAndGet(); return resultFactory.get(); });
        ActionPlanStore.Plan plan = db.plans.issue("edit_screen_layout", "admin", "{\"screenId\":10}", "fp");
        com.fasterxml.jackson.databind.JsonNode arguments = MAPPER.readTree("{\"dryRun\":false,\"planId\":\"" + plan.getId() + "\"}");
        Map<String, Object> started = runner.start("edit_screen_layout", arguments, null, null);
        ApplyEvent event = onlyEvent();
        String id = (String) started.get("operationId");
        Map<String, Object> stored = db.journal.getOperation(id, "admin");
        assertEquals("FAILED", stored.get("status"));
        assertNull(stored.get("result"));
        assertTrue(String.valueOf(stored.get("error")), String.valueOf(stored.get("error")).contains("Jira may have been changed"));
        assertEquals("FAILED", event.operationState);
        Map<String, Object> replay = runner.start("edit_screen_layout", arguments, null, null);
        assertEquals(id, replay.get("operationId"));
        assertEquals(true, replay.get("alreadyStarted"));
        assertEquals("the write is never repeated", 1, writes.get());
    }

    @Test
    public void anOversizedResultAfterAForwardWriteIsFailedWithADiagnosticThatJiraMayHaveChanged() throws Exception {
        assertResultNotStoredAfterAWrite(() -> Collections.singletonMap("blob",
                new String(new char[ru.rasulov.common.impl.undo.ChangeJournal.RESULT_JSON_LIMIT + 1]).replace('\0', 'x')));
    }

    @Test
    public void aResultThatCannotBeSerializedAfterAForwardWriteIsFailedWithTheSameDiagnostic() throws Exception {
        assertResultNotStoredAfterAWrite(() -> Collections.singletonMap("broken", new Object() {
            public String getBoom() { throw new IllegalStateException("cannot serialize"); }
        }));
    }

    private static Map<String, Object> layoutResult(boolean available) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("fieldId", "customfield_12345");
        item.put("action", "deleted");
        Map<String, Object> changes = new LinkedHashMap<String, Object>();
        changes.put("available", available);
        changes.put("items", available ? Collections.singletonList(item) : Collections.emptyList());
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("status", "APPLIED");
        result.put("fieldChanges", changes);
        return result;
    }

    @Test
    public void partialFieldResultKeepsTheCreatedFieldIdAndActualContextsInThePersistedOperation() throws Exception {
        ru.rasulov.common.api.dto.field.context.ContextSummary actual =
                new ru.rasulov.common.api.dto.field.context.ContextSummary("PAY", false,
                        Collections.singletonList(new ru.rasulov.common.api.dto.ProjectRef("SHOP", "Webshop")), true,
                        Collections.<ru.rasulov.common.api.dto.IssueTypeRef>emptyList());
        tool.set(args -> new ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse(false, Collections.singletonList(
                ru.rasulov.common.api.dto.field.FieldCreationResult.partial("Field", "customfield_10800",
                        Collections.singletonList(actual), "planned PAY but Jira reports SHOP"))));
        Map<String, Object> started = apply("create_custom_fields", "{\"fields\":[]}");
        ApplyEvent event = onlyEvent();

        assertEquals("COMPLETED", event.operationState);
        assertEquals("PARTIAL", event.domainStatus);
        String id = (String) started.get("operationId");
        Map<?, ?> result = (Map<?, ?>) db.journal.getOperation(id, "admin").get("result");
        Map<?, ?> field = (Map<?, ?>) ((java.util.List<?>) result.get("results")).get(0);
        assertEquals("PARTIAL", field.get("status"));
        assertEquals("CONTEXT_MISMATCH", field.get("errorCode"));
        assertEquals("customfield_10800", field.get("customFieldId"));
        Map<?, ?> context = (Map<?, ?>) ((java.util.List<?>) field.get("contexts")).get(0);
        assertEquals("SHOP", ((Map<?, ?>) ((java.util.List<?>) context.get("projects")).get(0)).get("key"));
    }

    @Test
    public void layoutMembershipChangesAreTakenFromThePersistedResultInOneApplyEvent() throws Exception {
        tool.set(args -> layoutResult(true));
        Map<String, Object> started = apply("edit_screen_layout", "{\"screenId\":10}");
        ApplyEvent event = onlyEvent();
        Map<?, ?> persisted = (Map<?, ?>) db.journal.getOperation((String) started.get("operationId"), "admin").get("result");
        assertTrue(((Map<?, ?>) persisted.get("fieldChanges")).containsKey("items"));
        assertTrue(event.fieldChanges.available);
        assertEquals(1, event.fieldChanges.items.size());
        assertEquals("customfield_12345", event.fieldChanges.items.get(0).fieldId);
        assertEquals("deleted", event.fieldChanges.items.get(0).action);
    }

    @Test
    public void unknownLayoutMembershipIsNotReportedAsKnownEmpty() throws Exception {
        tool.set(args -> layoutResult(false));
        apply("edit_screen_layout", "{\"screenId\":10}");
        ApplyEvent event = onlyEvent();
        assertFalse(event.fieldChanges.available);
        assertTrue(event.fieldChanges.items.isEmpty());
    }

    @Test
    public void missingLayoutReadbackIsUnavailableAndRequestIsNotUsedToInventChanges() throws Exception {
        tool.set(args -> Collections.singletonMap("status", "FAILED"));
        apply("edit_screen_layout", "{\"screenId\":10,\"remove\":[\"customfield_12345\"]}");
        ApplyEvent event = onlyEvent();
        assertFalse(event.fieldChanges.available);
        assertTrue(event.fieldChanges.items.isEmpty());
    }

    @Test
    public void layoutExceptionWithoutResultMakesMembershipDetailsUnavailable() throws Exception {
        tool.set(args -> { throw new IllegalStateException("write result lost"); });
        apply("edit_screen_layout", "{\"screenId\":10,\"remove\":[\"customfield_12345\"]}");
        ApplyEvent event = onlyEvent();
        assertFalse(event.fieldChanges.available);
        assertTrue(event.fieldChanges.items.isEmpty());
    }

    @Test
    public void anotherToolCannotSupplyLayoutMembershipChanges() throws Exception {
        tool.set(args -> layoutResult(true));
        apply("{}");
        assertNull(onlyEvent().fieldChanges);
    }

    @Test
    public void domainErrorCodeOfAResultIsRecordedAsIs() throws Exception {
        tool.set(args -> {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            result.put("status", "PREVIEW_STALE");
            result.put("errorCode", "PREVIEW_STALE");
            return result;
        });
        apply("{}");
        ApplyEvent event = onlyEvent();
        assertEquals("COMPLETED", event.operationState);
        assertEquals("PREVIEW_STALE", event.domainStatus);
        assertEquals("PREVIEW_STALE", event.errorCode);
    }

    @Test
    public void perItemStatusesGiveTheCommonStatusOrMixed() {
        // create_custom_fields has no top-level status, only results[].status per field.
        assertEquals("CREATED", OperationRunner.domainStatus(results("CREATED", "CREATED")));
        assertEquals("MIXED", OperationRunner.domainStatus(results("CREATED", "EXISTS")));
        assertNull(OperationRunner.domainStatus(results()));
        Map<String, Object> both = results("CREATED");
        both.put("status", "PARTIAL");
        assertEquals("PARTIAL", OperationRunner.domainStatus(both));
    }

    @Test
    public void perFieldPerScreenStatusesOfAddFieldToScreensAreNeverACleanSuccessWhenAnItemIsNotOne() {
        // add_field_to_screens has no top-level status: fields[].results[].status per screen.
        assertEquals("ADDED", OperationRunner.domainStatus(perField(new String[] {"ADDED", "ADDED"}, new String[] {"ADDED"})));
        assertEquals("benign differences stay MIXED", "MIXED",
                OperationRunner.domainStatus(perField(new String[] {"ADDED", "ALREADY_PRESENT"})));
        assertEquals("success next to UNKNOWN/NOT_ATTEMPTED is PARTIAL", "PARTIAL",
                OperationRunner.domainStatus(perField(new String[] {"ADDED", "UNKNOWN", "NOT_ATTEMPTED"})));
        assertEquals("a failure next to successes (even in another field) is PARTIAL", "PARTIAL",
                OperationRunner.domainStatus(perField(new String[] {"ADDED"}, new String[] {"FAILED"})));
        assertEquals("no success and an unknown write is UNKNOWN", "UNKNOWN",
                OperationRunner.domainStatus(perField(new String[] {"UNKNOWN", "NOT_ATTEMPTED"})));
        assertEquals("no success and nothing unknown is FAILED", "FAILED",
                OperationRunner.domainStatus(perField(new String[] {"FAILED", "NOT_ATTEMPTED"})));
        assertEquals("one status only is reported as it is", "NOT_ATTEMPTED",
                OperationRunner.domainStatus(perField(new String[] {"NOT_ATTEMPTED", "NOT_ATTEMPTED"})));
        assertNull(OperationRunner.domainStatus(perField()));
    }

    private static Map<String, Object> perField(String[]... statusesByField) {
        java.util.List<Object> fields = new java.util.ArrayList<Object>();
        for (String[] statuses : statusesByField) fields.add(results(statuses));
        Map<String, Object> top = new LinkedHashMap<String, Object>();
        top.put("fields", fields);
        return top;
    }

    private static Map<String, Object> results(String... statuses) {
        java.util.List<Object> items = new java.util.ArrayList<Object>();
        for (String status : statuses) items.add(Collections.singletonMap("status", status));
        Map<String, Object> top = new LinkedHashMap<String, Object>();
        top.put("results", items);
        return top;
    }

    @Test
    public void failureInTheToolIsRecordedWithTheStatePersistedByTheJournalAndItsMessage() throws Exception {
        tool.set(args -> { throw new IllegalStateException("tool exploded"); });
        Map<String, Object> started = apply("{\"project\":\"PAY\"}");
        ApplyEvent event = onlyEvent();
        Map<String, Object> persisted = db.journal.getOperation((String) started.get("operationId"), "admin");
        assertEquals(persisted.get("status"), event.operationState);
        assertTrue("state " + event.operationState, java.util.Arrays.asList("FAILED", "ROLLED_BACK", "PARTIAL", "BLOCKED")
                .contains(event.operationState));
        assertEquals(persisted.get("error"), event.errorMessage);
        assertTrue(String.valueOf(event.errorMessage), String.valueOf(event.errorMessage).contains("tool exploded"));
        assertNull(event.domainStatus);
        assertEquals("PAY", event.targets.get(0).label);
    }

    @Test
    public void applyWithoutALivePlanIsRejectedWithoutAnOperationOrApplyEvent() throws Exception {
        tool.set(args -> { throw new AssertionError("A stale preview must not invoke the tool"); });
        Map<String, Object> result = runner.start("create_screen",
                MAPPER.readTree("{\"dryRun\":false,\"planId\":\"gone\"}"), null, null);
        assertEquals("PREVIEW_STALE", result.get("errorCode"));
        assertEquals("NOT_FOUND", result.get("staleReason"));
        assertFalse(result.containsKey("operationId"));
        assertEquals(0, db.entityManager.count(ru.rasulov.common.impl.undo.JournalOperation.class));
        assertEquals(0, db.entityManager.count(ru.rasulov.common.impl.undo.JournalSession.class));
        verifyNoInteractions(applies);
    }

    @Test
    public void planIsReadWithoutBeingConsumed() throws Exception {
        ActionPlanStore.Plan plan = db.plans.issue("create_screen", "admin", "{\"project\":\"PAY\"}", "fp");
        assertEquals("{\"project\":\"PAY\"}", db.plans.peekRequest("create_screen", "admin", plan.getId()));
        assertEquals("{\"project\":\"PAY\"}", db.plans.peekRequest("create_screen", "admin", plan.getId()));
        assertNull(db.plans.peekRequest("create_screen", "someone-else", plan.getId()));
        assertNull(db.plans.peekRequest("create_project", "admin", plan.getId()));
        assertNull(db.plans.peekRequest("create_screen", "admin", "unknown"));
        assertNull(db.plans.peekRequest("create_screen", "admin", null));
        assertNotNull(db.plans.find("create_screen", "admin", plan.getId()));
    }

    @Test
    public void executorRefusalIsRecordedAsFailedAndStillThrows() throws Exception {
        tool.set(args -> { throw new AssertionError("must not run"); });
        Field executor = OperationRunner.class.getDeclaredField("executor");
        executor.setAccessible(true);
        ((ThreadPoolExecutor) executor.get(runner)).shutdownNow();
        try {
            apply("{\"project\":\"PAY\"}");
            fail("A refused task must surface");
        } catch (RuntimeException expected) {
            // the original rejection reaches the caller
        }
        ArgumentCaptor<ApplyEvent> event = ArgumentCaptor.forClass(ApplyEvent.class);
        verify(applies, times(1)).record(event.capture());
        assertEquals("FAILED", event.getValue().operationState);
        assertEquals("PROJECT", event.getValue().targets.get(0).type);
        assertTrue(event.getValue().errorMessage.contains("executor"));
    }

    @Test
    public void failingApplyJournalChangesNeitherTheStartResultNorTheOperationNorTheSlots() throws Exception {
        doThrow(new IllegalStateException("journal down")).when(applies).record(any(ApplyEvent.class));
        tool.set(args -> Collections.singletonMap("status", "CREATED"));
        // More applies than the 34 slots: a leaked slot would end in BusyException.
        for (int i = 0; i < 40; i++) {
            Map<String, Object> started = apply("{\"project\":\"PAY\"}");
            assertNotNull(started.get("operationId"));
            assertTrue(finished.tryAcquire(10, TimeUnit.SECONDS));
            Map<String, Object> done = db.journal.getOperation((String) started.get("operationId"), "admin");
            assertEquals("COMPLETED", done.get("status"));
        }
        verify(applies, times(40)).record(any(ApplyEvent.class));
    }
}
