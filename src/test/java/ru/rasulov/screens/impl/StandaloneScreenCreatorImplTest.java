package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.api.CustomFieldCreationService;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.context.ContextSummary;
import ru.rasulov.screens.api.dto.CreateScreenFieldRequest;
import ru.rasulov.screens.api.dto.CreateScreenRequest;
import ru.rasulov.screens.api.dto.CreateScreenResponse;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class StandaloneScreenCreatorImplTest {
    @Test
    public void missingFieldWithoutATypeIsReportedAndNothingIsWritten() {
        AdministratorGuard guard = mock(AdministratorGuard.class);
        ApplicationUser user = mock(ApplicationUser.class);
        when(guard.requireAdministrator()).thenReturn(user);
        FieldManager fields = mock(FieldManager.class);
        when(fields.getOrderableFields()).thenReturn(Collections.<OrderableField>emptySet());
        FieldScreenManager screens = mock(FieldScreenManager.class);
        when(screens.screenNameExists("Release screen")).thenReturn(false);
        CustomFieldCreationService fieldCreator = mock(CustomFieldCreationService.class);
        StandaloneScreenWriter writer = mock(StandaloneScreenWriter.class);
        StandaloneScreenCreatorImpl service = new StandaloneScreenCreatorImpl(guard, mock(NamedLock.class), fields,
                screens, fieldCreator, writer, mock(ActionPlanStore.class),
                new ActionPlanCodec(), ScreenTestSupport.operations(), ru.rasulov.common.impl.TestLinks.links());
        CreateScreenRequest request = new CreateScreenRequest(true, "Release screen", null,
                Collections.singletonList(new CreateScreenFieldRequest("Acceptance", null, null, null, null)), null);

        CreateScreenResponse response = service.create(request);

        assertEquals(CreateScreenResponse.Status.FAILED, response.getStatus());
        assertEquals("MISSING_TYPE", response.getFields().get(0).getErrorCode());
        verifyNoInteractions(fieldCreator, writer);
    }

    @Test
    public void previewPlansAMissingFieldInTheGlobalContext() {
        AdministratorGuard guard = mock(AdministratorGuard.class);
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getKey()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(user);
        FieldManager fields = mock(FieldManager.class);
        when(fields.getOrderableFields()).thenReturn(Collections.<OrderableField>emptySet());
        FieldScreenManager screens = mock(FieldScreenManager.class);
        CustomFieldCreationService fieldCreator = mock(CustomFieldCreationService.class);
        ContextSummary global = new ContextSummary("Global", true, null, true, null);
        CreateCustomFieldsResponse fieldPreview = new CreateCustomFieldsResponse(true, Collections.singletonList(
                FieldCreationResult.wouldCreate("Acceptance", Collections.singletonList(global), null)))
                .withPlan("field-plan", 10L);
        when(fieldCreator.createAll(any(CreateCustomFieldsRequest.class))).thenReturn(fieldPreview);
        ActionPlanStore plans = mock(ActionPlanStore.class);
        when(plans.issue(eq(StandaloneScreenCreatorImpl.OPERATION), eq("admin"), anyString(), anyString()))
                .thenReturn(new ActionPlanStore.Plan(
                "screen-plan", StandaloneScreenCreatorImpl.OPERATION, "admin", "{}", "hash", 20L));
        StandaloneScreenCreatorImpl service = new StandaloneScreenCreatorImpl(guard, mock(NamedLock.class), fields,
                screens, fieldCreator, mock(StandaloneScreenWriter.class), plans,
                new ActionPlanCodec(), ScreenTestSupport.operations(), ru.rasulov.common.impl.TestLinks.links());

        CreateScreenResponse response = service.create(new CreateScreenRequest(true, "Release screen", null,
                Collections.singletonList(new CreateScreenFieldRequest(
                        "Acceptance", null, "textfield", null, null)), null));

        assertEquals(CreateScreenResponse.Status.WOULD_CREATE, response.getStatus());
        assertEquals("screen-plan", response.getPlanId());
        assertTrue(response.getFields().get(0).getContexts().get(0).isAllProjects());
        assertTrue(response.getFields().get(0).getContexts().get(0).isAllIssueTypes());
        ArgumentCaptor<CreateCustomFieldsRequest> request = ArgumentCaptor.forClass(CreateCustomFieldsRequest.class);
        verify(fieldCreator).createAll(request.capture());
        assertTrue(request.getValue().getFields().get(0).getContexts().get(0).isAllProjects());
        assertTrue(request.getValue().getFields().get(0).getContexts().get(0).isAllIssueTypes());
        verify(plans).remove("field-plan");
    }

    @Test
    public void partialFieldStopsTheScreenAndTheFailureNamesTheCreatedFieldAndItsState() {
        AdministratorGuard guard = mock(AdministratorGuard.class);
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getKey()).thenReturn("admin");
        when(guard.requireAdministrator()).thenReturn(user);
        FieldManager fields = mock(FieldManager.class);
        when(fields.getOrderableFields()).thenReturn(Collections.<OrderableField>emptySet());
        CustomFieldCreationService fieldCreator = mock(CustomFieldCreationService.class);
        ContextSummary global = new ContextSummary("Global", true, null, true, null);
        CreateCustomFieldsResponse fieldPreview = new CreateCustomFieldsResponse(true, Collections.singletonList(
                FieldCreationResult.wouldCreate("Acceptance", Collections.singletonList(global), null)))
                .withPlan("field-plan", 10L);
        CreateCustomFieldsResponse fieldApplied = new CreateCustomFieldsResponse(false, Collections.singletonList(
                FieldCreationResult.partial("Acceptance", "customfield_10800", Collections.singletonList(global),
                        "planned Global but Jira reports PAY")));
        when(fieldCreator.createAll(any(CreateCustomFieldsRequest.class))).thenAnswer(call ->
                ((CreateCustomFieldsRequest) call.getArgument(0)).isDryRun() ? fieldPreview : fieldApplied);
        ActionPlanStore plans = mock(ActionPlanStore.class);
        ArgumentCaptor<String> savedRequest = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> savedFingerprint = ArgumentCaptor.forClass(String.class);
        when(plans.issue(eq(StandaloneScreenCreatorImpl.OPERATION), eq("admin"), savedRequest.capture(),
                savedFingerprint.capture())).thenAnswer(call -> new ActionPlanStore.Plan("screen-plan",
                StandaloneScreenCreatorImpl.OPERATION, "admin", call.getArgument(2), call.getArgument(3), 20L));
        StandaloneScreenWriter writer = mock(StandaloneScreenWriter.class);
        NamedLock lock = new NamedLock(ru.rasulov.customfields.impl.JiraMocks.lockService(
                mock(com.atlassian.beehive.ClusterLock.class)));
        StandaloneScreenCreatorImpl service = new StandaloneScreenCreatorImpl(guard, lock, fields,
                mock(FieldScreenManager.class), fieldCreator, writer, plans,
                new ActionPlanCodec(), ScreenTestSupport.operations(), ru.rasulov.common.impl.TestLinks.links());
        service.create(new CreateScreenRequest(true, "Release screen", null, Collections.singletonList(
                new CreateScreenFieldRequest("Acceptance", null, "textfield", null, null)), null));
        when(plans.find(StandaloneScreenCreatorImpl.OPERATION, "admin", "screen-plan")).thenReturn(
                new ActionPlanStore.Plan("screen-plan", StandaloneScreenCreatorImpl.OPERATION, "admin",
                        savedRequest.getValue(), savedFingerprint.getValue(), 20L));

        try {
            service.create(new CreateScreenRequest(false, null, null, null, "screen-plan"));
            org.junit.Assert.fail("a partial field must stop the screen");
        } catch (ru.rasulov.common.api.exception.InvalidRequestException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("customfield_10800"));
            assertTrue(e.getMessage(), e.getMessage().contains("PARTIAL"));
            assertTrue(e.getMessage(), e.getMessage().contains("planned Global but Jira reports PAY"));
            assertTrue(e.getMessage(), e.getMessage().contains("screen was not created"));
        }
        org.mockito.Mockito.verify(writer, org.mockito.Mockito.never()).write(anyString(), any(), any());
    }
}
