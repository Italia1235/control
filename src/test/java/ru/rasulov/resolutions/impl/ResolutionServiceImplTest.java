package ru.rasulov.resolutions.impl;

import com.atlassian.jira.config.ResolutionManager;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.resolutions.api.dto.CreateResolutionRequest;
import ru.rasulov.resolutions.api.dto.CreateResolutionResponse;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static ru.rasulov.resolutions.api.dto.CreateResolutionResponse.ErrorCode.*;
import static ru.rasulov.resolutions.api.dto.CreateResolutionResponse.Status.*;

public class ResolutionServiceImplTest {
    private final ResolutionManager manager = mock(ResolutionManager.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock locks = mock(NamedLock.class);
    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ChangeJournal journal = mock(ChangeJournal.class);
    private final ApplicationUser admin = mock(ApplicationUser.class);
    private final AtomicReference<ActionPlanStore.Plan> plan = new AtomicReference<>();
    private final AtomicReference<String> planActor = new AtomicReference<>();
    private final ResolutionServiceImpl service = new ResolutionServiceImpl(manager, guard, locks,
            new ru.rasulov.common.impl.PreviewPlanExecutor(plans, new ActionPlanCodec()), new ActionPlanCodec(), journal,
            ru.rasulov.common.impl.TestLinks.links());
    private boolean locked;

    @Before @SuppressWarnings("unchecked") public void setup() {
        when(admin.getKey()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(admin);
        when(manager.getResolutions()).thenReturn(Collections.emptyList());
        when(journal.execute(anyString(), anyString(), any())).thenAnswer(call -> ((Supplier<Object>) call.getArgument(2)).get());
        when(locks.run(anyString(), anyString(), any())).thenAnswer(call -> {
            locked = true;
            try { return ((Supplier<Object>) call.getArgument(2)).get(); }
            finally { locked = false; }
        });
        when(plans.issue(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            ActionPlanStore.Plan issued = new ActionPlanStore.Plan("resolution-plan", call.getArgument(0),
                    call.getArgument(1), call.getArgument(2), call.getArgument(3), Long.MAX_VALUE);
            planActor.set(call.getArgument(1));
            plan.set(issued); return issued;
        });
        when(plans.find(anyString(), anyString(), anyString())).thenAnswer(call -> {
            ActionPlanStore.Plan saved = plan.get();
            return saved != null && planActor.get().equals(call.getArgument(1))
                    && saved.getId().equals(call.getArgument(2)) ? saved : null;
        });
        doAnswer(call -> { plan.set(null); return null; }).when(plans).remove(anyString());
    }

    @Test public void administratorReadsIdsNamesDescriptionsWithoutAnyWrites() {
        doReturn(Arrays.asList(resolution("1", "Fixed", "Implemented"), resolution("2", "Duplicate", "Repeated")))
                .when(manager).getResolutions();
        assertEquals(2, service.get(null).size());
        assertEquals("Implemented", service.get(" 1 ").get(0).getDescription());
        assertEquals("2", service.get("duplicate").get(0).getId());
        assertTrue(service.get("fix").isEmpty());
        verifyNoInteractions(journal, plans, locks);
        verify(manager, never()).createResolution(anyString(), anyString());
    }

    @Test(expected = SecurityException.class) public void nonAdministratorCannotReadCatalog() {
        when(guard.requireAdministrator()).thenThrow(new SecurityException("Forbidden"));
        try { service.get(null); }
        finally { verifyNoInteractions(manager, journal, plans, locks); }
    }

    @Test public void previewRequiresExplicitModeAndProhibitsPseudoUnresolved() {
        for (CreateResolutionRequest request : Arrays.asList(null, new CreateResolutionRequest(null,"Completed",null,null),
                preview(" ", null), preview(" Unresolved ", null), preview("НЕРЕШЕНО", null),
                new CreateResolutionRequest(true,"Completed",null,"plan"),
                new CreateResolutionRequest(false,"Completed",null,"plan"), apply(null))) {
            assertEquals(VALIDATION_ERROR, service.create(request).getErrorCode());
        }
        verifyNoInteractions(plans, journal);
        verify(manager, never()).createResolution(anyString(), anyString());
    }

    @Test public void identicalNameAndDescriptionReuseButAmbiguityOrDifferentDescriptionConflicts() {
        doReturn(Collections.singletonList(resolution("7","Completed","Finished"))).when(manager).getResolutions();
        CreateResolutionResponse exists = service.create(preview("completed", "Finished"));
        assertEquals(EXISTS, exists.getStatus());
        assertEquals("7", exists.getResolution().getId());
        assertNull(exists.getPlanId());
        assertEquals(NAME_CONFLICT, service.create(preview("Completed", "Different")).getErrorCode());
        doReturn(Arrays.asList(resolution("7","Completed","Finished"), resolution("8","COMPLETED","Finished")))
                .when(manager).getResolutions();
        assertEquals(NAME_CONFLICT, service.create(preview("Completed", "Finished")).getErrorCode());
        verifyNoInteractions(plans, journal);
        verify(manager, never()).createResolution(anyString(), anyString());
    }

    @Test public void missingAndEmptyDescriptionsAreEquivalentForReuse() {
        doReturn(Collections.singletonList(resolution("7", "Completed", null))).when(manager).getResolutions();
        assertEquals(EXISTS, service.create(preview("Completed", "")).getStatus());
    }

    @Test public void previewWarnsAndApplyUsesSavedRequestUnderLockWithJournalBeforeCreation() {
        CreateResolutionResponse preview = service.create(preview(" Completed ", "Finished"));
        assertEquals(WOULD_CREATE, preview.getStatus());
        assertTrue(preview.getMessage().contains("global for all Jira"));
        assertNotNull(preview.getPlanId());
        assertEquals(Long.valueOf(Long.MAX_VALUE), preview.getPlanExpiresAt());
        assertNull(preview.getResolution().getId());
        verify(manager, never()).createResolution(anyString(), anyString());
        UndoStep step = new UndoStep(1L, "resolution.created", "resolution-name:Completed", "{}", "{}", "PREPARED");
        when(journal.prepare(eq("resolution.created"), anyString(), anyString(), anyString())).thenReturn(step);
        when(manager.createResolution("Completed", "Finished")).thenAnswer(call -> {
            assertTrue(locked);
            verify(journal).prepare(eq("resolution.created"), anyString(), anyString(), anyString());
            return resolution("9", "Completed", "Finished");
        });
        CreateResolutionResponse created = service.create(apply(preview.getPlanId()));
        assertEquals(CREATED, created.getStatus());
        assertFalse(created.isDryRun());
        assertEquals("9", created.getResolution().getId());
        verify(journal).applied(eq(step), contains("9"));
        verify(journal).checkpoint();
        assertEquals(PREVIEW_STALE, service.create(apply(preview.getPlanId())).getErrorCode());
    }

    @Test public void foreignAdministratorCannotConsumePlan() {
        service.create(preview("Completed", "Finished"));
        ApplicationUser foreign = mock(ApplicationUser.class);
        when(foreign.getKey()).thenReturn("other");
        when(guard.requireAdministrator()).thenReturn(foreign);
        assertEquals(PREVIEW_STALE, service.create(apply("resolution-plan")).getErrorCode());
        assertNotNull(plan.get());
        verify(plans, never()).remove(anyString());
    }

    @Test public void editedRemovedOrAddedCatalogEntryMakesPreviewStale() {
        for (int change = 0; change < 3; change++) {
            doReturn(Collections.singletonList(resolution("1", "Fixed", "Original"))).when(manager).getResolutions();
            service.create(preview("Completed", "Finished"));
            List<Resolution> changed = change == 0 ? Collections.singletonList(resolution("1","Fixed","Edited"))
                    : change == 1 ? Collections.emptyList()
                    : Arrays.asList(resolution("1","Fixed","Original"), resolution("2","Other",""));
            when(manager.getResolutions()).thenAnswer(call -> { assertTrue(locked); return changed; });
            assertEquals(PREVIEW_STALE, service.create(apply("resolution-plan")).getErrorCode());
            assertNull(plan.get());
        }
        verify(manager, never()).createResolution(anyString(), anyString());
    }

    @Test public void duplicateAppearingAfterPreviewIsStaleRatherThanOverwritten() {
        service.create(preview("Completed", "Finished"));
        doReturn(Collections.singletonList(resolution("9", "Completed", "Finished"))).when(manager).getResolutions();
        assertEquals(PREVIEW_STALE, service.create(apply("resolution-plan")).getErrorCode());
        verify(manager, never()).createResolution(anyString(), anyString());
    }

    @Test(expected = SecurityException.class) public void administratorGuardPrecedesPlanning() {
        when(guard.requireAdministrator()).thenThrow(new SecurityException("Forbidden"));
        try { service.create(preview("Completed", "Finished")); }
        finally { verifyNoInteractions(manager, plans, journal); }
    }

    @Test public void previewFailureReturnsJiraErrorWithoutWriting() {
        when(manager.getResolutions()).thenThrow(new IllegalStateException("Unavailable"));
        assertEquals(JIRA_ERROR, service.create(preview("Completed", "Finished")).getErrorCode());
        verifyNoInteractions(journal, plans);
    }

    @Test public void uncertainNativeResultConsumesPlanWithoutRecordingConfirmedCreation() {
        for (Resolution result : Arrays.asList(null, resolution(null, "Completed", "Finished"),
                resolution(" ", "Completed", "Finished"))) {
            service.create(preview("Completed", "Finished"));
            doReturn(result).when(manager).createResolution("Completed", "Finished");
            try {
                service.create(apply("resolution-plan"));
                fail("Creation requires a confirmed native ID");
            } catch (IllegalStateException expected) {
                assertNull(plan.get());
            }
        }
        verify(journal, never()).applied(any(), anyString());
        verify(journal, never()).checkpoint();
    }

    static Resolution resolution(String id, String name, String description) {
        Resolution value = mock(Resolution.class);
        when(value.getId()).thenReturn(id); when(value.getName()).thenReturn(name);
        when(value.getDescription()).thenReturn(description); return value;
    }
    static CreateResolutionRequest preview(String name, String description) { return new CreateResolutionRequest(true, name, description, null); }
    static CreateResolutionRequest apply(String id) { return new CreateResolutionRequest(false, null, null, id); }
}
