package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.status.category.StatusCategory;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.configure.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The status read-back is compared after a symmetric description normalization (trim, null to empty).
 * Real Jira returns a null description for a status created without one; the fixture models that by altering the stored status
 * right after the native createStatus call.
 */
public class StatusDescriptionReadBackTest {
    private static ConfigureWorkflowResponse run(WorkflowConfigurationFixture f, String requested, Consumer<Status> readBack) {
        WorkflowDefinition graph = new WorkflowDefinition("StatusNorm", "", List.of(new StatusDefinition("Open", null, null), new StatusDefinition("Review", "TO_DO", requested)), "Open",
                List.of(new TransitionDefinition("review", "Review", "Open", false, "Review", null, null)));
        ConfigureWorkflowResponse preview = f.preview(new ConfigureWorkflowRequest(true, graph, null, null, null, null));
        assertNotNull(preview.getMessage(), preview.getPlanId());
        AtomicBoolean altered = new AtomicBoolean();
        f.afterNative = key -> {
            if (key.equals("status") && altered.compareAndSet(false, true)) readBack.accept(f.statusStore.values().stream().reduce((first, last) -> last).orElseThrow());
        };
        return f.apply(preview);
    }

    private static void matched(WorkflowConfigurationFixture f, ConfigureWorkflowResponse result) {
        assertEquals(result.getMessage(), "CREATED", result.getStatus());
        Map<String, Object> status = WorkflowConfigurationNativeTest.step(result, "status:1");
        assertEquals("APPLIED", status.get("writeStatus"));
        assertEquals(status.toString(), "MATCH", status.get("verification"));
        assertEquals("APPLIED", WorkflowConfigurationNativeTest.step(result, "workflow-copy").get("writeStatus"));
        assertEquals("APPLIED", WorkflowConfigurationNativeTest.step(result, "workflow-save").get("writeStatus"));
        assertTrue(f.nativeCalls.contains("workflow-save"));
        assertTrue(f.main.containsKey("StatusNorm"));
    }

    private static void stopped(WorkflowConfigurationFixture f, ConfigureWorkflowResponse result) {
        assertEquals(result.getMessage(), "PARTIAL", result.getStatus());
        Map<String, Object> status = WorkflowConfigurationNativeTest.step(result, "status:1");
        assertEquals("APPLIED", status.get("writeStatus"));
        assertEquals("MISMATCH", status.get("verification"));
        assertEquals("NOT_ATTEMPTED", WorkflowConfigurationNativeTest.step(result, "workflow-copy").get("writeStatus"));
        assertEquals("NOT_ATTEMPTED", WorkflowConfigurationNativeTest.step(result, "workflow-save").get("writeStatus"));
        assertFalse(f.nativeCalls.contains("workflow-copy"));
        assertFalse(f.nativeCalls.contains("workflow-save"));
        assertFalse(f.main.containsKey("StatusNorm"));
        verify(f.statusService, times(1)).createStatus(any(), anyString(), anyString(), anyString(), any());
    }

    private static void category(Status status, String jiraKey) {
        StatusCategory category = mock(StatusCategory.class);
        when(category.getKey()).thenReturn(jiraKey);
        when(status.getStatusCategory()).thenReturn(category);
    }

    @Test public void absentDescriptionReadBackAsNullMatchesAndTheWorkflowIsSaved() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            matched(f, run(f, null, s -> when(s.getDescription()).thenReturn(null)));
        }
    }

    @Test public void emptyDescriptionReadBackAsNullMatches() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            matched(f, run(f, "", s -> when(s.getDescription()).thenReturn(null)));
        }
    }

    @Test public void absentDescriptionReadBackAsWhitespaceMatches() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            matched(f, run(f, null, s -> when(s.getDescription()).thenReturn("   ")));
        }
    }

    @Test public void whitespaceDescriptionReadBackAsNullMatches() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            matched(f, run(f, "  ", s -> when(s.getDescription()).thenReturn(null)));
        }
    }

    @Test public void descriptionWithTextReadBackUnchangedMatches() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            matched(f, run(f, "abc", s -> { }));
        }
    }

    @Test public void descriptionWithTextReadBackPaddedMatches() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            matched(f, run(f, "abc", s -> when(s.getDescription()).thenReturn(" abc ")));
        }
    }

    @Test public void differentTextStopsWithMismatch() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            stopped(f, run(f, "abc", s -> when(s.getDescription()).thenReturn("abd")));
        }
    }

    @Test public void emptyAgainstTextStopsWithMismatch() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            stopped(f, run(f, null, s -> when(s.getDescription()).thenReturn("x")));
        }
    }

    @Test public void textAgainstNullStopsWithMismatch() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            stopped(f, run(f, "abc", s -> when(s.getDescription()).thenReturn(null)));
        }
    }

    @Test public void differentNameStopsWithMismatch() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            stopped(f, run(f, null, s -> when(s.getName()).thenReturn("review")));
        }
    }

    @Test public void differentCategoryStopsWithMismatch() {
        try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
            stopped(f, run(f, null, s -> category(s, "done")));
        }
    }
}
