package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.status.category.StatusCategory;
import org.junit.Test;
import ru.rasulov.common.impl.undo.JournalOperation;
import ru.rasulov.common.impl.undo.WorkflowReceiptCodec;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A STATUS read-back MISMATCH names every differing field with expected/actual in the persisted receipt. */
public class StatusReadBackDiagnosticsTest {
    private static final String PREFIX = "Native write read-back differs from the confirmed state.";

    /** Runs a create request whose first status is altered right after Jira "creates" it, then reads the durable receipt. */
    private static Map<String, Object> mismatch(Consumer<Status> alter) throws Exception { return mismatch(null, (f, s) -> alter.accept(s)); }

    private static Map<String, Object> mismatch(String requested, Consumer<Status> alter) throws Exception { return mismatch(requested, (f, s) -> alter.accept(s)); }

    private static Map<String, Object> mismatch(String requested, BiConsumer<WorkflowConfigurationFixture, Status> alter) throws Exception {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            WorkflowDefinition graph = new WorkflowDefinition("StatusDiag", "", List.of(new StatusDefinition("Open", null, null), new StatusDefinition("Review", "TO_DO", requested)), "Open",
                    List.of(new TransitionDefinition("review", "Review", "Open", false, "Review", null, null)));
            ConfigureWorkflowResponse preview = f.preview(new ConfigureWorkflowRequest(true, graph, null, null, null, null));
            assertNotNull(preview.getMessage(), preview.getPlanId());
            AtomicBoolean altered = new AtomicBoolean();
            f.afterNative = key -> {
                if (key.equals("status") && altered.compareAndSet(false, true)) alter.accept(f, f.statusStore.values().stream().reduce((first, last) -> last).orElseThrow());
            };
            ConfigureWorkflowResponse result = f.apply(preview);
            assertEquals(result.getMessage(), "PARTIAL", result.getStatus());
            Map<String, Object> step = persistedStep(f, "status:1");
            assertEquals("APPLIED", step.get("writeStatus"));
            assertEquals("MISMATCH", step.get("verification"));
            assertEquals("NOT_ATTEMPTED", persistedStep(f, "workflow-copy").get("writeStatus"));
            assertFalse(f.nativeCalls.contains("workflow-copy"));
            return step;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> persistedStep(WorkflowConfigurationFixture f, String key) throws Exception {
        JournalOperation[] operations = f.journalFixture.entityManager.find(JournalOperation.class);
        assertEquals(1, operations.length);
        Map<String, Object> operation = f.journalFixture.journal.getOperation(operations[0].getOperationKey(), "admin");
        List<Map<String, Object>> steps = (List<Map<String, Object>>) ((Map<String, Object>) operation.get("execution")).get("steps");
        return steps.stream().filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow();
    }

    private static void category(Status status, String jiraKey) {
        StatusCategory category = mock(StatusCategory.class);
        when(category.getKey()).thenReturn(jiraKey);
        when(status.getStatusCategory()).thenReturn(category);
    }

    @Test public void nullActualIsShownNormalizedToEmptyNextToTheExpectedText() throws Exception {
        String error = (String) mismatch("keep", s -> when(s.getDescription()).thenReturn(null)).get("error");
        assertEquals(PREFIX + " Fields: description. expected!=actual: \"keep\"!=\"\"", error);
    }

    @Test public void emptyExpectedDescriptionAgainstActualTextIsShownQuoted() throws Exception {
        String error = (String) mismatch(s -> when(s.getDescription()).thenReturn("x")).get("error");
        assertEquals(PREFIX + " Fields: description. expected!=actual: \"\"!=\"x\"", error);
    }

    /** Read-back serves a copy of the created status whose id is {@code actualId}, so the id (never normalized, unlike description) differs from the confirmed one. */
    private static String idMismatch(String actualId) throws Exception {
        String[] expected = new String[1];
        String error = (String) mismatch(null, (f, s) -> {
            expected[0] = s.getId();
            String name = s.getName();
            Status copy = mock(Status.class);
            when(copy.getId()).thenReturn(actualId);
            when(copy.getName()).thenReturn(name);
            when(copy.getDescription()).thenReturn("");
            category(copy, "new");
            when(f.statusManager.getStatus(expected[0])).thenReturn(copy);
        }).get("error");
        return error.replace("\"" + expected[0] + "\"", "<ID>");
    }

    @Test public void nullActualOfAnotherFieldIsShownAsBareNullNotQuotedNotEmpty() throws Exception {
        assertEquals(PREFIX + " Fields: id. expected!=actual: <ID>!=null", idMismatch(null));
    }

    @Test public void emptyActualOfAnotherFieldIsShownAsQuotedEmptyNotNull() throws Exception {
        assertEquals(PREFIX + " Fields: id. expected!=actual: <ID>!=\"\"", idMismatch(""));
    }

    @Test public void allDifferingFieldsAreNamedInOrderWithExpectedAndActual() throws Exception {
        String error = (String) mismatch(s -> {
            when(s.getName()).thenReturn("review");
            when(s.getDescription()).thenReturn("x");
            category(s, "done");
        }).get("error");
        assertEquals(PREFIX + " Fields: name,description,category. expected!=actual: \"Review\"!=\"review\"; \"\"!=\"x\"; \"TO_DO\"!=\"DONE\"", error);
    }

    @Test public void longValuesAreTruncatedWithVisibleMarkerAndFieldNamesAreNeverDisplaced() throws Exception {
        String huge = "q".repeat(500);
        String error = (String) mismatch(s -> {
            when(s.getName()).thenReturn("review");
            when(s.getDescription()).thenReturn(huge);
            category(s, "done");
        }).get("error");
        assertTrue(error, error.length() <= WorkflowReceiptCodec.ERROR_LENGTH);
        assertTrue(error, error.startsWith(PREFIX + " Fields: name,description,category. expected!=actual: "));
        assertTrue(error, error.contains("\u2026"));
        assertFalse(error, error.contains("q".repeat(200)));
        assertTrue(error, error.contains("TO_DO"));
    }

    @Test public void longActualOfOneFieldStaysWithinBudgetAndKeepsNames() throws Exception {
        String error = (String) mismatch(s -> when(s.getDescription()).thenReturn("z".repeat(400))).get("error");
        assertTrue(error, error.length() <= WorkflowReceiptCodec.ERROR_LENGTH);
        assertTrue(error, error.startsWith(PREFIX + " Fields: description. expected!=actual: \"\"!=\"z"));
        assertTrue(error, error.contains("\u2026"));
    }
}
