package ru.rasulov.customfields.impl.context;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.ReindexMessageManager;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItem;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItemService;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.context.JiraContextNode;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.FieldConfigSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.user.ApplicationUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.api.dto.context.OptionsChange;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsRequest;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsRequest.ContextUpdate;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsResponse;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.customfields.impl.validation.FieldFailureException;
import ru.rasulov.customfields.impl.FieldJournalTestSupport;
import ru.rasulov.customfields.impl.JiraMocks;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.customfields.impl.filefield.FileFieldConfiguration;
import ru.rasulov.customfields.impl.undo.CustomFieldUndoHandler;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises real plans, scope validation, snapshots and native writes, not a mocked planner/writer. */
public class FieldContextScopeServiceImplTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ComponentAccessor.Worker components = mock(ComponentAccessor.Worker.class);
    private ComponentAccessor.Worker previousComponents;

    @org.junit.Before public void initializeNativeBuilder() {
        previousComponents = ComponentAccessor.initialiseWorker(components);
    }

    @After public void closeJournals() {
        try { FieldJournalTestSupport.close(); }
        finally { ComponentAccessor.initialiseWorker(previousComponents); }
    }

    @Test public void validatesExactFieldIdAxesDuplicateIdsAndMaximumWithoutPlansOrWrites() throws Exception {
        Fixture f = new Fixture();
        List<UpdateFieldContextsRequest> invalid = Arrays.asList(
                new UpdateFieldContextsRequest(true, null, "Severity", Collections.singletonList(change(1, "P2"))),
                new UpdateFieldContextsRequest(true, null, "123", Collections.singletonList(change(1, "P2"))),
                f.request(new ContextUpdate(1L, false, null, true, null)),
                f.request(new ContextUpdate(1L, true, Collections.singletonList("P2"), true, null)),
                f.request(new ContextUpdate(1L, null, Collections.emptyList(), true, null)),
                f.request(new ContextUpdate(1L, true, null, true, Collections.emptyList())),
                f.request(new ContextUpdate(1L, true, null, false, null)),
                f.request(change(1, "P2"), change(1, "P2")),
                f.request(new ContextUpdate[0]),
                f.request(Collections.nCopies(11, change(1, "P2")).toArray(new ContextUpdate[0])));
        for (UpdateFieldContextsRequest request : invalid) {
            JsonNode result = f.json(f.service.update(request));
            assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
            assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        }
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
        verifyNoInteractions(f.reindex);
    }

    @Test public void rejectsForeignContextsMultipleConfigsAndLockedButAllowsManagedAdministrator() throws Exception {
        Fixture f = new Fixture();
        assertEquals("UNKNOWN_CONTEXT", f.preview(change(99, "P2")).path("errorCode").asText());
        Map<String, FieldConfig> multiple = new LinkedHashMap<>();
        multiple.put("10", f.config(91)); multiple.put("20", f.config(92));
        when(f.first.getConfigs()).thenReturn(multiple);
        assertEquals("MULTIPLE_CONFIGS", f.preview(change(1, "P2")).path("errorCode").asText());
        when(f.first.getConfigs()).thenReturn(Collections.singletonMap("10", f.config(91)));
        ManagedConfigurationItem item = mock(ManagedConfigurationItem.class);
        when(item.isManaged()).thenReturn(true);
        when(f.managed.getManagedCustomField(f.field)).thenReturn(item);
        when(f.managed.doesUserHavePermission(f.admin, item)).thenReturn(false);
        assertEquals("FIELD_LOCKED", f.preview(change(1, "P2")).path("errorCode").asText());
        when(f.managed.doesUserHavePermission(f.admin, item)).thenReturn(true);
        assertEquals("WOULD_UPDATE", f.preview(change(1, "P2")).path("status").asText());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
    }

    @Test public void noOpHasNoPlanAndReportsUntouchedContexts() throws Exception {
        Fixture f = new Fixture();
        JsonNode result = f.preview(change(1, "P1"));
        assertEquals("UNCHANGED", result.path("status").asText());
        assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        assertFalse(result.path("reindexRecommended").asBoolean());
        assertEquals(2L, result.path("unchangedContexts").get(0).path("contextId").asLong());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
    }

    @Test public void validatesWholeFinalScopeAndPreservesUnknownLookupSuggestions() throws Exception {
        Fixture f = new Fixture();
        assertEquals("PROJECT_IN_OTHER_CONTEXT", f.preview(change(1, "P3")).path("errorCode").asText());
        JsonNode swap = f.json(f.service.update(f.request(change(1, "P3"), change(2, "P1"))));
        assertEquals("WOULD_UPDATE", swap.path("status").asText());
        JsonNode missing = f.preview(change(1, "P2missing"));
        assertEquals("UNKNOWN_PROJECT", missing.path("errorCode").asText());
        assertEquals("P2", missing.path("suggestions").get(0).asText());
        JsonNode type = f.preview(new ContextUpdate(1L, true, null, null, Collections.singletonList("Taskmissing")));
        assertEquals("UNKNOWN_ISSUE_TYPE", type.path("errorCode").asText());
        assertEquals("Task", type.path("suggestions").get(0).asText());
    }

    @Test public void previewBecomesStaleOnPersistedOptionsDefaultScopeOrPluginChanges() throws Exception {
        for (String change : Arrays.asList("options", "default", "scope", "assets", "file")) {
            Fixture f = new Fixture();
            if ("assets".equals(change)) f.type(AssetsFieldConfiguration.TYPE_KEY);
            if ("file".equals(change)) f.type(FileFieldConfiguration.TYPE_KEY);
            UpdateFieldContextsResponse preview = f.service.update(f.request(change(1, "P2")));
            assertNotNull(preview.getPlanId());
            switch (change) {
                case "options": when(f.option.getString("value")).thenReturn("changed"); break;
                case "default": when(f.genericRow.getString("xmlvalue")).thenReturn("<long>2</long>"); break;
                case "scope": f.scope(f.first, Collections.singletonList(f.project(4)), "10"); break;
                case "assets": when(f.assets.snapshot(91L)).thenReturn("changed"); break;
                case "file": when(f.genericRow.getString("xmlvalue")).thenReturn("file changed"); break;
                default: fail();
            }
            assertEquals(change, "PREVIEW_STALE", f.apply(preview).path("errorCode").asText());
            verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
        }
    }

    @Test public void unreadableSnapshotFailsBeforeAnyMutation() throws Exception {
        Fixture f = new Fixture();
        when(f.db.findByAnd(eq("GenericConfiguration"), anyMap(), anyList()))
                .thenThrow(new IllegalStateException("cannot read default"));
        JsonNode result = f.preview(change(1, "P2"));
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("JIRA_ERROR", result.path("errorCode").asText());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
    }

    @Test public void updatesBothAxesWithSameConfigAndFullOptionTreeAndDefaultPreserved() throws Exception {
        Fixture f = new Fixture();
        UpdateFieldContextsResponse preview = f.service.update(f.request(
                new ContextUpdate(1L, true, null, true, null)));
        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertTrue(applied.path("contexts").get(0).path("actual").path("allProjects").asBoolean());
        assertTrue(applied.path("contexts").get(0).path("actual").path("allIssueTypes").asBoolean());
        assertEquals(91L, f.first.getConfigs().get(null).getId().longValue());
        verify(f.fields, atLeastOnce()).refreshConfigurationSchemes(123L);
        verify(f.fieldManager, atLeastOnce()).refresh();
        verify(f.fields, never()).clear();
        verify(f.reindex, times(1)).pushMessage(f.admin, "admin.notifications.task.custom.fields");
        assertTrue(applied.path("reindexRecommended").asBoolean());
        assertTrue(applied.path("note").asText().contains("переиндексация не запускалась"));
        verify(f.db, never()).createValue(anyString(), anyMap());
        // An id-only comparison would miss these post-write corruptions.
        for (String changed : Arrays.asList("id", "value", "sequence", "parentoptionid", "disabled", "default", "config")) {
            Fixture bad = new Fixture();
            bad.afterWrite = () -> {
                if ("default".equals(changed)) when(bad.genericRow.getString("xmlvalue")).thenReturn("changed");
                else if ("config".equals(changed)) doReturn(Collections.singletonMap("10", bad.config(999))).when(bad.first).getConfigs();
                else if ("id".equals(changed) || "sequence".equals(changed) || "parentoptionid".equals(changed))
                    when(bad.option.getLong(changed)).thenReturn(999L);
                else when(bad.option.getString(changed)).thenReturn("changed");
            };
            JsonNode failure = bad.apply(bad.service.update(bad.request(change(1, "P2"))));
            assertEquals(changed + ": " + failure, "PARTIAL", failure.path("status").asText());
            assertEquals(1L, failure.path("failedContextId").asLong());
        }
    }

    @Test public void failedFirstWriteWithoutChangesReturnsFailedWithoutReindex() throws Exception {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("native failed before write"))
                .when(f.schemes).updateFieldConfigScheme(any(), anyList(), eq(f.field));
        JsonNode result = f.apply(f.service.update(f.request(change(1, "P2"), change(2, "P4"))));
        assertEquals(result.toString(), "FAILED", result.path("status").asText());
        assertEquals("JIRA_ERROR", result.path("errorCode").asText());
        assertEquals(1L, result.path("failedContextId").asLong());
        assertEquals("FAILED", result.path("contexts").get(0).path("verdict").asText());
        assertEquals("NOT_ATTEMPTED", result.path("contexts").get(1).path("verdict").asText());
        assertEquals("P1", actualProject(result, 0));
        assertEquals("P3", actualProject(result, 1));
        assertFalse(result.path("reindexRecommended").asBoolean());
        verifyNoInteractions(f.reindex);
        verify(f.schemes, times(1)).updateFieldConfigScheme(any(), anyList(), eq(f.field));
    }

    @Test public void failedFirstWriteWithObservedChangesStillReturnsPartialAndReindexes() throws Exception {
        Fixture f = new Fixture();
        f.failAt = 1;
        JsonNode result = f.apply(f.service.update(f.request(change(1, "P2"), change(2, "P4"))));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertEquals(1L, result.path("failedContextId").asLong());
        assertEquals("P2", actualProject(result, 0));
        assertEquals("P3", actualProject(result, 1));
        assertEquals("NOT_ATTEMPTED", result.path("contexts").get(1).path("verdict").asText());
        assertTrue(result.path("reindexRecommended").asBoolean());
        verify(f.reindex, times(1)).pushMessage(f.admin, "admin.notifications.task.custom.fields");
        assertEquals(1, f.writes.get());
    }

    @Test public void failedSecondWriteReturnsObservedMutatedScopeAndUntouchedRemainingEntries() throws Exception {
        Fixture f = new Fixture();
        FieldConfigScheme third = f.context(3, 93, 5);
        f.contexts.add(third);
        f.failAt = 2;
        JsonNode result = f.apply(f.service.update(f.request(change(1, "P2"), change(2, "P4"), change(3, "P6"))));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertEquals(2L, result.path("failedContextId").asLong());
        assertEquals("UPDATED", result.path("contexts").get(0).path("verdict").asText());
        assertEquals("FAILED", result.path("contexts").get(1).path("verdict").asText());
        assertEquals("NOT_ATTEMPTED", result.path("contexts").get(2).path("verdict").asText());
        assertEquals("P2", actualProject(result, 0));
        assertEquals("P4", actualProject(result, 1));
        assertEquals("P5", actualProject(result, 2));
        assertTrue(result.path("reindexRecommended").asBoolean());
        verify(f.reindex, times(1)).pushMessage(f.admin, "admin.notifications.task.custom.fields");
        assertEquals(2, f.writes.get());
    }

    @Test public void knownPluginChangedAfterWriteFailsWhileThirdPartyGetsWarning() throws Exception {
        Fixture known = new Fixture();
        known.type(AssetsFieldConfiguration.TYPE_KEY);
        known.afterWrite = () -> when(known.assets.snapshot(91L)).thenReturn("mutated settings");
        JsonNode result = known.apply(known.service.update(known.request(change(1, "P2"))));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertEquals("P2", actualProject(result, 0));
        Fixture thirdParty = new Fixture();
        thirdParty.type("vendor:custom");
        JsonNode preview = thirdParty.preview(change(1, "P2"));
        boolean found = false;
        for (JsonNode warning : preview.path("contexts").get(0).path("warnings"))
            found |= "PLUGIN_SETTINGS_UNVERIFIED".equals(warning.path("code").asText());
        assertTrue(found);
    }

    @Test public void notificationFailureReportsActualUpdateWithoutClaimingMessageWasDisplayed() throws Exception {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("notification unavailable")).when(f.reindex).pushMessage(any(), anyString());
        JsonNode result = f.apply(f.service.update(f.request(change(1, "P2"))));
        assertEquals(result.toString(), "UPDATED", result.path("status").asText());
        assertEquals("P2", actualProject(result, 0));
        assertTrue(result.path("reindexRecommended").asBoolean());
        assertFalse(result.path("reindexNotificationDisplayed").asBoolean());
        assertTrue(result.path("note").asText().contains("не смогла"));
        assertTrue(result.path("message").asText().contains("could not display"));
        verify(f.reindex, times(1)).pushMessage(f.admin, "admin.notifications.task.custom.fields");
        assertEquals(1, f.writes.get());
    }

    @Test public void refreshFailureNeverLabelsCachedScopeAsActual() throws Exception {
        Fixture f = new Fixture();
        f.afterWrite = () -> doThrow(new IllegalStateException("refresh unavailable"))
                .when(f.fields).refreshConfigurationSchemes(123L);
        JsonNode result = f.apply(f.service.update(f.request(change(1, "P2"))));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertTrue(result.path("contexts").get(0).path("actual").isNull());
        assertTrue(result.path("contexts").get(0).path("message").asText().contains("refresh unavailable"));
        assertEquals(1, f.writes.get());
        verify(f.reindex, times(1)).pushMessage(f.admin, "admin.notifications.task.custom.fields");
    }

    @Test public void nameResolutionReadsEachCatalogOnceAcrossAllRequestedContexts() throws Exception {
        Fixture f = new Fixture();
        JsonNode result = f.json(f.service.update(f.request(
                new ContextUpdate(1L, null, Collections.singletonList("Project 2"), null, Collections.singletonList("Task")),
                new ContextUpdate(2L, null, Collections.singletonList("Project 4"), null, Collections.singletonList("Task")))));
        assertEquals("WOULD_UPDATE", result.path("status").asText());
        verify(f.projectManager, times(1)).getProjects();
        verify(f.typeManager, times(1)).getIssueTypes();
    }


    // Final-state policy shared with create_custom_fields and create_field_context. Contexts: 1 = P1/Task, 2 = P3/Task.

    @Test public void sameProjectWithDifferentIssueTypesIsRefusedAndNothingIsWritten() throws Exception {
        Fixture f = new Fixture();
        JsonNode result = f.preview(new ContextUpdate(1L, null, Collections.singletonList("P3"), null,
                Collections.singletonList("Bug")));
        assertEquals(result.toString(), "FAILED", result.path("status").asText());
        assertEquals("PROJECT_IN_OTHER_CONTEXT", result.path("errorCode").asText());
        assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
    }

    @Test public void secondGlobalContextIsRefusedAndNothingIsWritten() throws Exception {
        Fixture f = new Fixture();
        JsonNode result = f.json(f.service.update(f.request(
                new ContextUpdate(1L, true, null, null, Collections.singletonList("Task")),
                new ContextUpdate(2L, true, null, null, Collections.singletonList("Bug")))));
        assertEquals(result.toString(), "FAILED", result.path("status").asText());
        assertEquals("GLOBAL_CONTEXT_EXISTS", result.path("errorCode").asText());
        assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
    }

    @Test public void oneGlobalAndAProjectContextWithOverlappingTypesAreAllowedWithAWarning() throws Exception {
        Fixture f = new Fixture();
        JsonNode preview = f.preview(new ContextUpdate(1L, true, null, null, Collections.singletonList("Task")));
        assertEquals(preview.toString(), "WOULD_UPDATE", preview.path("status").asText());
        assertFalse(preview.path("planId").asText().isEmpty());
        assertTrue(preview.toString(), preview.path("contexts").get(0).path("warnings").size() > 0);
    }

    @Test public void disjointProjectsWithTheirOwnTypesAreAllowed() throws Exception {
        Fixture f = new Fixture();
        JsonNode preview = f.preview(new ContextUpdate(1L, null, Collections.singletonList("P2"), null,
                Collections.singletonList("Bug")));
        assertEquals(preview.toString(), "WOULD_UPDATE", preview.path("status").asText());
        assertFalse(preview.path("planId").asText().isEmpty());
    }

    private static final AssetsFieldSettings OLD_ASSETS = new AssetsFieldSettings(3, "objectType = \"User\"");
    private static final AssetsFieldSettings NEW_ASSETS = new AssetsFieldSettings(7, "objectType = \"Host\"");

    private static org.mockito.ArgumentMatcher<AssetsFieldSettings> newAssets() {
        return s -> s != null && Integer.valueOf(7).equals(s.getObjectSchemaId())
                && "objectType = \"Host\"".equals(s.getFilterScopeAql());
    }
    private static ContextUpdate assetsOnly(long context) {
        return new ContextUpdate(context, null, null, null, null, NEW_ASSETS);
    }
    /** What the fake plugin currently stores; every state it can be in is a real JSON snapshot of the Assets bean. */
    private static final class AssetsState {
        final java.util.concurrent.atomic.AtomicReference<String> current = new java.util.concurrent.atomic.AtomicReference<>();
        final Map<String, AssetsFieldSettings> known = new HashMap<>();
        String define(AssetsFieldSettings settings, String otherProperty) {
            Map<String, Object> bean = new TreeMap<>();
            bean.put("getFoo", otherProperty);
            bean.put("getObjectSchemaId", settings.getObjectSchemaId());
            bean.put("getScopeIql", settings.getFilterScopeAql());
            Map<String, Object> state = new TreeMap<>();
            state.put("configId", 91); state.put("bean", bean);
            try {
                String json = JSON.writeValueAsString(state);
                known.put(json, settings);
                return json;
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }
    /** An Assets field whose stored settings really change when configure runs, as in the plugin. */
    private static AssetsState assetsField(Fixture f) {
        f.type(AssetsFieldConfiguration.TYPE_KEY);
        AssetsState state = new AssetsState();
        state.current.set(state.define(OLD_ASSETS, "keep"));
        when(f.assets.snapshot(anyLong())).thenAnswer(call -> state.current.get());
        when(f.assets.initialized(anyString())).thenReturn(true);
        when(f.assets.settings(anyString())).thenAnswer(call -> state.known.get((String) call.getArgument(0)));
        doAnswer(call -> { state.current.set(state.define(NEW_ASSETS, "keep")); return null; })
                .when(f.assets).configure(anyLong(), anyLong(), any());
        return state;
    }

    @Test public void assetsThatThePluginDidNotStoreAsApprovedAreReportedAndNeverCalledUpdated() throws Exception {
        Fixture noop = new Fixture();
        assetsField(noop);
        doAnswer(call -> null).when(noop.assets).configure(anyLong(), anyLong(), any());
        JsonNode ignored = noop.apply(noop.service.update(noop.request(assetsOnly(1))));
        assertEquals(ignored.toString(), "FAILED", ignored.path("status").asText());
        assertTrue(ignored.toString(), ignored.path("contexts").get(0).path("message").asText().contains("Persisted Assets settings differ"));

        Fixture other = new Fixture();
        AssetsState state = assetsField(other);
        AssetsFieldSettings third = new AssetsFieldSettings(9, "objectType = \"Other\"");
        doAnswer(call -> { state.current.set(state.define(third, "keep")); return null; })
                .when(other.assets).configure(anyLong(), anyLong(), any());
        JsonNode changed = other.apply(other.service.update(other.request(assetsOnly(1))));
        assertEquals(changed.toString(), "PARTIAL", changed.path("status").asText());
        assertTrue(changed.toString(), changed.path("contexts").get(0).path("message").asText().contains("Persisted Assets settings differ"));
    }

    @Test public void otherPropertiesOfTheAssetsConfigurationChangedByTheWriteAreDetected() throws Exception {
        Fixture f = new Fixture();
        AssetsState state = assetsField(f);
        doAnswer(call -> { state.current.set(state.define(NEW_ASSETS, "changed by the plugin")); return null; })
                .when(f.assets).configure(anyLong(), anyLong(), any());
        JsonNode result = f.apply(f.service.update(f.request(assetsOnly(1))));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertTrue(result.toString(), result.path("contexts").get(0).path("message").asText().contains("plugin settings changed"));
    }

    @Test public void assetsOnlyUpdateNeverRequestsAReindexButScopeMovesDo() throws Exception {
        Fixture only = new Fixture();
        assetsField(only);
        JsonNode applied = only.apply(only.service.update(only.request(assetsOnly(1))));
        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals(3, applied.path("contexts").get(0).path("assets").path("before").path("objectSchemaId").asInt());
        assertFalse(applied.path("reindexRecommended").asBoolean());
        verifyNoInteractions(only.reindex);

        // The scope write succeeded, then the Assets write failed: a scope really moved, so the reindex message is due.
        Fixture mixed = new Fixture();
        assetsField(mixed);
        doThrow(new IllegalStateException("assets write refused")).when(mixed.assets).configure(anyLong(), anyLong(), any());
        JsonNode partial = mixed.apply(mixed.service.update(mixed.request(new ContextUpdate(1L, null,
                Collections.singletonList("P2"), null, Collections.singletonList("10"), NEW_ASSETS))));
        assertEquals(partial.toString(), "PARTIAL", partial.path("status").asText());
        assertEquals("P2", actualProject(partial, 0));
        assertTrue(partial.path("reindexRecommended").asBoolean());
        verify(mixed.reindex, times(1)).pushMessage(mixed.admin, "admin.notifications.task.custom.fields");
    }

    @Test public void anAssetsWriteThatFailsAfterChangingTheStoredSettingsIsPartialNotFailed() throws Exception {
        Fixture f = new Fixture();
        AssetsState state = assetsField(f);
        doAnswer(call -> {
            state.current.set(state.define(NEW_ASSETS, "keep"));
            throw new IllegalStateException("assets write interrupted");
        }).when(f.assets).configure(anyLong(), anyLong(), any());
        JsonNode result = f.apply(f.service.update(f.request(assetsOnly(1))));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertEquals(1L, result.path("failedContextId").asLong());
        assertFalse("an assets-only write never asks for a reindex", result.path("reindexRecommended").asBoolean());
        verifyNoInteractions(f.reindex);
    }

    @Test public void anAssetsContextWithoutAnyConfigurationCanBeInitializedBySettingItsAssets() throws Exception {
        Fixture f = new Fixture();
        AssetsState state = assetsField(f);
        // The context exists but the plugin holds no configuration for it yet (pluginSettings ABSENT).
        state.current.set("{\"bean\":null,\"configId\":91}");
        when(f.assets.initialized(anyString())).thenAnswer(call -> !((String) call.getArgument(0)).contains("\"bean\":null"));
        UpdateFieldContextsResponse preview = f.service.update(f.request(assetsOnly(1)));
        JsonNode before = f.json(preview).path("contexts").get(0).path("assets").path("before");
        assertEquals(f.json(preview).toString(), "WOULD_UPDATE", f.json(preview).path("status").asText());
        assertTrue(before.toString(), before.isNull() || before.isMissingNode());
        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals(7, applied.path("contexts").get(0).path("assets").path("after").path("objectSchemaId").asInt());
        verify(f.assets, times(1)).configure(eq(91L), eq(123L), argThat(newAssets()));
    }

    @Test public void assetsWithAConflictingScopeAreRefusedWithoutWritingAndAChangedSnapshotMakesThePlanStale() throws Exception {
        Fixture conflict = new Fixture();
        assetsField(conflict);
        JsonNode refused = conflict.preview(new ContextUpdate(1L, null, Collections.singletonList("P3"), null,
                Collections.singletonList("10"), NEW_ASSETS));
        assertEquals(refused.toString(), "PROJECT_IN_OTHER_CONTEXT", refused.path("errorCode").asText());
        assertTrue(refused.path("planId").isNull() || refused.path("planId").isMissingNode());
        verify(conflict.assets, never()).configure(anyLong(), anyLong(), any());

        Fixture f = new Fixture();
        AssetsState state = assetsField(f);
        UpdateFieldContextsResponse preview = f.service.update(f.request(assetsOnly(1)));
        state.current.set(state.define(new AssetsFieldSettings(5, "objectType = \"Foreign\""), "keep"));
        assertEquals("PREVIEW_STALE", f.apply(preview).path("errorCode").asText());
        verify(f.assets, never()).configure(anyLong(), anyLong(), any());
    }

    @Test public void assetsOnlyUpdateChangesOneContextsSettingsAndNeverTouchesScope() throws Exception {
        Fixture f = new Fixture();
        assetsField(f);
        UpdateFieldContextsResponse preview = f.service.update(f.request(assetsOnly(1)));
        JsonNode shown = f.json(preview).path("contexts").get(0);
        assertEquals(f.json(preview).toString(), "WOULD_UPDATE", f.json(preview).path("status").asText());
        assertFalse("assets-only preview must not announce a reindex", f.json(preview).path("reindexRecommended").asBoolean());
        assertEquals(3, shown.path("assets").path("before").path("objectSchemaId").asInt());
        assertEquals("objectType = \"User\"", shown.path("assets").path("before").path("filterScopeAql").asText());
        assertEquals(7, shown.path("assets").path("after").path("objectSchemaId").asInt());
        assertEquals("objectType = \"Host\"", shown.path("assets").path("after").path("filterScopeAql").asText());
        verify(f.assets).validate(eq(AssetsFieldConfiguration.TYPE_KEY), argThat(newAssets()));
        verify(f.assets, never()).configure(anyLong(), anyLong(), any());

        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals("UPDATED", applied.path("contexts").get(0).path("verdict").asText());
        assertEquals(7, applied.path("contexts").get(0).path("assets").path("after").path("objectSchemaId").asInt());
        verify(f.assets, times(1)).configure(eq(91L), eq(123L), argThat(newAssets()));
        verify(f.assets, never()).configure(eq(92L), anyLong(), any());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
        assertEquals(2L, applied.path("unchangedContexts").get(0).path("contextId").asLong());
    }

    @Test public void assetsEqualToTheStoredOnesAreANoOpWithoutPlan() throws Exception {
        Fixture f = new Fixture();
        assetsField(f);
        JsonNode result = f.preview(new ContextUpdate(1L, null, null, null, null, OLD_ASSETS));
        assertEquals(result.toString(), "UNCHANGED", result.path("status").asText());
        assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        verify(f.assets, never()).configure(anyLong(), anyLong(), any());
    }

    @Test public void assetsAndScopeTogetherAreAppliedFromOnePlan() throws Exception {
        Fixture f = new Fixture();
        assetsField(f);
        ContextUpdate both = new ContextUpdate(1L, null, Collections.singletonList("P2"), null,
                Collections.singletonList("10"), NEW_ASSETS);
        JsonNode applied = f.apply(f.service.update(f.request(both)));
        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals("P2", actualProject(applied, 0));
        verify(f.schemes, times(1)).updateFieldConfigScheme(any(), anyList(), eq(f.field));
        verify(f.assets, times(1)).configure(eq(91L), eq(123L), argThat(newAssets()));
    }

    @Test public void assetsWriteFailureIsReportedForItsContextWithoutRetry() throws Exception {
        Fixture f = new Fixture();
        assetsField(f);
        doThrow(new IllegalStateException("assets write refused")).when(f.assets).configure(anyLong(), anyLong(), any());
        UpdateFieldContextsResponse preview = f.service.update(f.request(assetsOnly(1)));
        assertEquals(f.json(preview).toString(), "WOULD_UPDATE", f.json(preview).path("status").asText());
        assertFalse(f.json(preview).path("reindexRecommended").asBoolean());
        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "FAILED", applied.path("status").asText());
        assertEquals("JIRA_ERROR", applied.path("errorCode").asText());
        assertEquals(1L, applied.path("failedContextId").asLong());
        assertEquals("FAILED", applied.path("contexts").get(0).path("verdict").asText());
        assertTrue(applied.toString(), applied.path("contexts").get(0).path("message").asText().contains("assets write refused"));
        verify(f.assets, times(1)).configure(anyLong(), anyLong(), any());
        assertFalse(applied.path("reindexRecommended").asBoolean());
        verifyNoInteractions(f.reindex);
    }

    @Test public void assetsForAFieldWhoseTypeIsUnavailableNameTheUnavailableTypeAndWriteNothing() throws Exception {
        Fixture f = new Fixture();
        when(f.field.getCustomFieldType()).thenReturn(null);
        JsonNode result = f.preview(assetsOnly(1));
        assertEquals(result.toString(), "VALIDATION_ERROR", result.path("errorCode").asText());
        assertTrue(result.toString(), result.path("message").asText().contains("not available"));
        assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        verify(f.assets, never()).configure(anyLong(), anyLong(), any());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
    }

    @Test public void invalidAssetsUpdatesAreRefusedBeforeAnyPlanOrWrite() throws Exception {
        // Not an Assets object field: the real adapter decides, no plugin is needed.
        Fixture other = new Fixture(new AssetsFieldConfiguration(mock(com.atlassian.plugin.PluginAccessor.class)));
        for (ContextUpdate bad : Arrays.asList(assetsOnly(1), new ContextUpdate(1L, null,
                Collections.singletonList("P2"), null, Collections.singletonList("10"), NEW_ASSETS))) {
            JsonNode result = other.preview(bad);
            assertEquals(result.toString(), "VALIDATION_ERROR", result.path("errorCode").asText());
            assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        }
        verify(other.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());

        Fixture f = new Fixture();
        assetsField(f);
        for (ContextUpdate bad : Arrays.asList(
                new ContextUpdate(1L, null, null, null, null, null),
                new ContextUpdate(1L, true, null, null, null, NEW_ASSETS),
                new ContextUpdate(1L, null, null, true, null, NEW_ASSETS))) {
            JsonNode result = f.preview(bad);
            assertEquals(result.toString(), "VALIDATION_ERROR", result.path("errorCode").asText());
            assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        }
        doThrow(new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR, "Assets schema 7 does not exist.", null))
                .when(f.assets).validate(any(), any());
        JsonNode missing = f.preview(assetsOnly(1));
        assertEquals(missing.toString(), "VALIDATION_ERROR", missing.path("errorCode").asText());
        assertTrue(missing.toString(), missing.path("message").asText().contains("Assets schema 7 does not exist."));
        assertTrue(missing.path("planId").isNull() || missing.path("planId").isMissingNode());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
        verify(f.assets, never()).configure(anyLong(), anyLong(), any());
    }

    // --- options of an existing context ----------------------------------------------------------------

    private static OptionsChange opts(List<OptionsChange.Add> add, List<OptionsChange.Rename> rename, List<Long> disable,
                                      List<Long> enable, List<OptionsChange.Order> order) {
        return new OptionsChange(add, rename, disable, enable, order);
    }
    private static List<OptionsChange.Add> adds(String... values) {
        List<OptionsChange.Add> result = new ArrayList<>();
        for (String value : values) result.add(new OptionsChange.Add(value, null));
        return result;
    }
    private static List<OptionsChange.Rename> renames(Object... idAndValue) {
        List<OptionsChange.Rename> result = new ArrayList<>();
        for (int i = 0; i < idAndValue.length; i += 2)
            result.add(new OptionsChange.Rename(((Number) idAndValue[i]).longValue(), (String) idAndValue[i + 1]));
        return result;
    }
    private static List<Long> ids(Long... values) { return Arrays.asList(values); }
    private static List<OptionsChange.Order> order(Long parent, Long... ids) {
        return Collections.singletonList(new OptionsChange.Order(parent, Arrays.asList(ids)));
    }
    private static ContextUpdate optionsOf(long context, OptionsChange change) {
        return new ContextUpdate(context, null, null, null, null, null, change);
    }
    private static JsonNode rowsOf(JsonNode response, int context) { return response.path("contexts").get(context).path("options").path("rows"); }

    /** Context 1 (config 91): High, Medium, Low (disabled). Context 2 (config 92): Red, Green. */
    private Fixture optionsFixture() {
        Fixture f = new Fixture();
        f.store.option(1, 91, null, 0, "High", false).option(2, 91, null, 1, "Medium", false).option(3, 91, null, 2, "Low", true)
                .option(51, 92, null, 0, "Red", false).option(52, 92, null, 1, "Green", false);
        f.useStore();
        return f;
    }
    private static void assertNoNativeOptionWrites(Fixture f) {
        verify(f.optionsManager, never()).createOption(any(), any(), anyLong(), anyString());
        verify(f.optionsManager, never()).setValue(any(), anyString());
        verify(f.optionsManager, never()).disableOption(any());
        verify(f.optionsManager, never()).enableOption(any());
        verify(f.optionsManager, never()).updateOptions(anyCollection());
        assertEquals(0, f.store.writes);
    }
    private static void assertNeverDeletesOrTouchesDefaults(Fixture f) {
        verify(f.optionsManager, never()).deleteOptionAndChildren(any());
        verify(f.optionsManager, never()).removeCustomFieldConfigOptions(any());
        verify(f.optionsManager, never()).removeCustomFieldOptions(any());
        verify(f.optionsManager, never()).setRootOptions(any(), any());
        verify(f.db, never()).createValue(anyString(), anyMap());
        verify(f.db, never()).removeByAnd(anyString(), anyMap());
    }

    @Test public void previewShowsBeforeAfterScopeUsageDefaultAndConsequencesForEveryAffectedOptionAndWritesNothing() throws Exception {
        Fixture f = optionsFixture();
        when(f.type.getDefaultValue(any(FieldConfig.class))).thenAnswer(call -> f.store.optionObject(f.store.rec(2)));
        Map<Long, OptionUsage> counted = new LinkedHashMap<>();
        counted.put(1L, OptionUsage.count(12)); counted.put(2L, OptionUsage.unknown("search is slow"));
        when(f.usage.count(eq(f.admin), eq(f.field), anyMap())).thenReturn(counted);

        JsonNode result = f.preview(optionsOf(1, opts(null, renames(1, "Urgent"), ids(2L), null, null)));

        assertEquals(result.toString(), "WOULD_UPDATE", result.path("status").asText());
        assertTrue(result.path("planId").isTextual());
        JsonNode context = result.path("contexts").get(0);
        assertEquals("P1", context.path("before").path("projects").get(0).path("key").asText());
        assertEquals(context.path("before"), context.path("after"));
        assertFalse(result.path("reindexRecommended").asBoolean());
        JsonNode rows = rowsOf(result, 0);
        assertEquals(rows.toString(), 2, rows.size());
        assertEquals(1L, rows.get(0).path("optionId").asLong());
        assertEquals("RENAMED", rows.get(0).path("changes").get(0).asText());
        assertEquals("High", rows.get(0).path("before").path("value").asText());
        assertEquals("Urgent", rows.get(0).path("after").path("value").asText());
        assertEquals("COUNT", rows.get(0).path("usage").path("status").asText());
        assertEquals(12, rows.get(0).path("usage").path("issues").asInt());
        assertEquals(2L, rows.get(1).path("optionId").asLong());
        assertEquals("DISABLED", rows.get(1).path("changes").get(0).asText());
        assertFalse(rows.get(1).path("before").path("disabled").asBoolean());
        assertTrue(rows.get(1).path("after").path("disabled").asBoolean());
        assertTrue(rows.get(1).path("default").asBoolean());
        assertEquals("UNKNOWN", rows.get(1).path("usage").path("status").asText());
        assertEquals("search is slow", rows.get(1).path("usage").path("reason").asText());
        Set<String> warnings = new HashSet<>();
        for (JsonNode warning : context.path("options").path("warnings")) warnings.add(warning.path("code").asText());
        assertTrue(warnings.toString(), warnings.containsAll(Arrays.asList("RENAME_CHANGES_ALL_ISSUES",
                "DISABLE_KEEPS_ISSUE_VALUES", "DEFAULT_OPTION_DISABLED")));
        Map<Long, Long> asked = new LinkedHashMap<>();
        asked.put(1L, null); asked.put(2L, null);
        verify(f.usage, times(1)).count(f.admin, f.field, asked);
        assertNoNativeOptionWrites(f);
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
    }

    @Test public void aFailingUsageSearchNeverFailsThePreview() throws Exception {
        Fixture f = optionsFixture();
        when(f.usage.count(any(), any(), anyMap())).thenThrow(new IllegalStateException("index unavailable"));

        JsonNode result = f.preview(optionsOf(1, opts(null, renames(1, "Urgent"), null, null, null)));

        assertEquals(result.toString(), "WOULD_UPDATE", result.path("status").asText());
        assertTrue(result.path("planId").isTextual());
        assertEquals("UNKNOWN", rowsOf(result, 0).get(0).path("usage").path("status").asText());
        assertTrue(rowsOf(result, 0).get(0).path("usage").path("reason").asText().contains("index unavailable"));
    }

    @Test public void anAddOnlyPreviewAsksForNoUsage() throws Exception {
        Fixture f = optionsFixture();

        JsonNode result = f.preview(optionsOf(1, opts(adds("Urgent"), null, null, null, null)));

        assertEquals(result.toString(), "WOULD_UPDATE", result.path("status").asText());
        assertEquals("ADDED", rowsOf(result, 0).get(0).path("changes").get(0).asText());
        assertEquals("Urgent", rowsOf(result, 0).get(0).path("after").path("value").asText());
        assertEquals(3, rowsOf(result, 0).get(0).path("after").path("position").asInt());
        assertFalse(rowsOf(result, 0).get(0).has("usage"));
        verifyNoInteractions(f.usage);
    }

    @Test public void applyWritesAddRenameEnableDisableAndOrderInThatSequenceAndVerifiesTheApprovedFinalState() throws Exception {
        Fixture f = optionsFixture();
        UpdateFieldContextsResponse preview = f.service.update(f.request(optionsOf(1, opts(adds("Urgent"),
                renames(2, "Normal"), ids(1L), ids(3L), order(null, 3L, 2L, 1L)))));
        assertNotNull(preview.getPlanId());
        assertNoNativeOptionWrites(f);
        String otherContextBefore = f.store.dump(92);

        JsonNode applied = f.apply(preview);

        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals("UPDATED", applied.path("contexts").get(0).path("verdict").asText());
        assertEquals(Arrays.asList("Low", "Normal", "High!", "Urgent"), f.store.level(91, null));
        assertEquals(otherContextBefore, f.store.dump(92));
        assertFalse(applied.path("reindexRecommended").asBoolean());
        assertTrue(applied.path("reindexNotificationDisplayed").isMissingNode() || applied.path("reindexNotificationDisplayed").isNull());
        verifyNoInteractions(f.reindex);
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
        List<String> log = f.store.log;
        int create = indexStarting(log, "create"), rename = indexStarting(log, "setValue"), update = indexStarting(log, "updateOptions");
        int enable = indexStarting(log, "enable"), disable = indexStarting(log, "disable");
        assertTrue(log.toString(), create >= 0 && create < rename);
        assertTrue(log.toString(), rename < Math.min(enable, disable));
        assertTrue(log.toString(), Math.max(enable, disable) < update);
        assertEquals(log.toString(), log.size() - 1, update);
        assertEquals("create null:3:Urgent", log.get(create));
        assertEquals("updateOptions 3,1", log.get(update));
        assertNeverDeletesOrTouchesDefaults(f);
        // a consumed plan is refused
        JsonNode again = f.apply(preview);
        assertEquals("PREVIEW_STALE", again.path("errorCode").asText());
        assertEquals(log.size(), f.store.log.size());
    }
    private static int indexStarting(List<String> log, String prefix) {
        for (int i = 0; i < log.size(); i++) if (log.get(i).startsWith(prefix)) return i;
        return -1;
    }

    @Test public void anOptionsOnlyEntryLeavesTheScopeAndAnEntryWithAxesChangesBothUnderOnePlan() throws Exception {
        Fixture only = optionsFixture();
        JsonNode preview = only.preview(optionsOf(1, opts(adds("Urgent"), null, null, null, null)));
        assertEquals(preview.path("contexts").get(0).path("before"), preview.path("contexts").get(0).path("after"));
        assertEquals(2L, preview.path("unchangedContexts").get(0).path("contextId").asLong());
        assertFalse(preview.path("reindexRecommended").asBoolean());
        JsonNode done = only.json(only.service.update(new UpdateFieldContextsRequest(false, preview.path("planId").asText(), null, null)));
        assertEquals(done.toString(), "UPDATED", done.path("status").asText());
        assertEquals("P1", actualProject(done, 0));
        verify(only.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
        verifyNoInteractions(only.reindex);

        Fixture both = optionsFixture();
        UpdateFieldContextsResponse bothPreview = both.service.update(both.request(new ContextUpdate(1L, null,
                Collections.singletonList("P2"), null, Collections.singletonList("10"), null,
                opts(null, renames(1, "Urgent"), null, null, null))));
        assertEquals("WOULD_UPDATE", bothPreview.getStatus().name());
        assertTrue(bothPreview.isReindexRecommended());
        JsonNode bothDone = both.apply(bothPreview);
        assertEquals(bothDone.toString(), "UPDATED", bothDone.path("status").asText());
        assertEquals("P2", actualProject(bothDone, 0));
        assertEquals(Arrays.asList("Urgent", "Medium", "Low!"), both.store.level(91, null));
        verify(both.reindex, times(1)).pushMessage(both.admin, "admin.notifications.task.custom.fields");
        verify(both.schemes, times(1)).updateFieldConfigScheme(any(), anyList(), eq(both.field));
    }

    @Test public void anEntryWithoutAxesAssetsOrOperationsIsStillASchemaError() throws Exception {
        Fixture f = optionsFixture();
        for (ContextUpdate bad : Arrays.asList(new ContextUpdate(1L, null, null, null, null),
                optionsOf(1, opts(null, null, null, null, null)))) {
            JsonNode result = f.preview(bad);
            assertEquals(result.toString(), "VALIDATION_ERROR", result.path("errorCode").asText());
            assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        }
        assertNoNativeOptionWrites(f);
    }

    @Test public void rejectedRequestsCarryTheirCodeAndWriteNothing() throws Exception {
        Fixture f = optionsFixture();
        Map<String, ContextUpdate> cases = new LinkedHashMap<>();
        cases.put("OPTION_NOT_IN_CONTEXT", optionsOf(1, opts(null, null, ids(51L), null, null)));          // option of context 2
        cases.put("OPTION_DUPLICATE", optionsOf(1, opts(adds("high"), null, null, null, null)));
        cases.put("OPTION_VALUE_INVALID", optionsOf(1, opts(adds("  "), null, null, null, null)));
        cases.put("OPTION_ORDER_INVALID", optionsOf(1, opts(null, null, null, null, order(null, 1L, 2L))));
        cases.put("OPTION_CONFLICT", optionsOf(1, opts(null, null, ids(1L), ids(1L), null)));
        for (Map.Entry<String, ContextUpdate> entry : cases.entrySet()) {
            JsonNode result = f.preview(entry.getValue());
            assertEquals(result.toString(), entry.getKey(), result.path("errorCode").asText());
            assertEquals("FAILED", result.path("status").asText());
            assertFalse(result.path("message").asText().isEmpty());
            assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        }
        assertNoNativeOptionWrites(f);
        verifyNoInteractions(f.usage);
    }

    @Test public void aFieldTypeWithoutOptionsRefusesOptionChanges() throws Exception {
        for (String key : Arrays.asList("com.atlassian.jira.plugin.system.customfieldtypes:textfield",
                AssetsFieldConfiguration.TYPE_KEY, FileFieldConfiguration.TYPE_KEY, "org.example:thirdparty")) {
            Fixture f = optionsFixture();
            f.type(key);
            JsonNode result = f.preview(optionsOf(1, opts(adds("Urgent"), null, null, null, null)));
            assertEquals(key + " " + result, "OPTIONS_NOT_SUPPORTED", result.path("errorCode").asText());
            assertNoNativeOptionWrites(f);
        }
    }

    @Test public void theTotalOfAllNamedOptionsOfAllContextsIsLimitedPerCall() throws Exception {
        Fixture f = new Fixture();
        f.type("com.atlassian.jira.plugin.system.customfieldtypes:cascadingselect");
        f.store.nextId = 100_000;
        Map<Long, List<Long>> parents = new LinkedHashMap<>();
        for (long[] context : new long[][] {{91, 1}, {92, 1001}}) {
            List<Long> roots = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                long parent = context[1] + i * 5L;
                roots.add(parent);
                f.store.option(parent, context[0], null, i, "p" + parent, false);
                for (int child = 1; child <= 4; child++) f.store.option(parent + child, context[0], parent, child - 1, "c" + (parent + child), false);
            }
            parents.put(context[0], roots);
        }
        f.useStore();
        // 500 renames + 500 disables + 100 ordered ids = 1100 named options of one context
        java.util.function.Function<Long, ContextUpdate> everything = contextId -> {
            long config = contextId == 1 ? 91 : 92;
            List<OptionsChange.Rename> renames = new ArrayList<>();
            List<Long> disables = new ArrayList<>();
            for (OptionStoreFake.Rec rec : f.store.recs) if (rec.configId == config) {
                renames.add(new OptionsChange.Rename(rec.id, "n" + rec.id)); disables.add(rec.id);
            }
            return optionsOf(contextId, opts(null, renames, disables, null,
                    Collections.singletonList(new OptionsChange.Order(null, parents.get(config)))));
        };

        JsonNode one = f.preview(everything.apply(1L));
        assertEquals(one.toString().substring(0, Math.min(400, one.toString().length())), "WOULD_UPDATE", one.path("status").asText());

        JsonNode two = f.json(f.service.update(f.request(everything.apply(1L), everything.apply(2L))));
        assertEquals("OPTIONS_LIMIT", two.path("errorCode").asText());
        assertTrue(two.path("planId").isNull() || two.path("planId").isMissingNode());
        assertEquals(0, f.store.writes);
    }

    /** Context 1 (config 91) holds 150 top-level options, over MAX_ROOT_OPTIONS, as if made in the Jira UI; option 150 is disabled. */
    private Fixture overLimitFixture() {
        Fixture f = new Fixture();
        for (int i = 1; i <= 150; i++) f.store.option(i, 91, null, i - 1, "r" + i, i == 150);
        f.store.option(51, 92, null, 0, "Red", false);
        f.useStore();
        return f;
    }

    @Test public void aContextOverTheLimitStillAllowsDisableRenameEnableAndOrderWithoutAdd() throws Exception {
        List<Long> reversed = new ArrayList<>();
        for (long id = 150; id >= 1; id--) reversed.add(id);
        List<OptionsChange> changes = Arrays.asList(
                opts(null, null, ids(1L), null, null),
                opts(null, renames(2, "renamed"), null, null, null),
                opts(null, null, null, ids(150L), null),
                opts(null, null, null, null, order(null, reversed.toArray(new Long[0]))));
        for (OptionsChange change : changes) {
            Fixture f = overLimitFixture();
            String otherContextBefore = f.store.dump(92);
            JsonNode preview = f.preview(optionsOf(1, change));
            assertEquals(preview.toString().substring(0, Math.min(400, preview.toString().length())), "WOULD_UPDATE", preview.path("status").asText());
            assertNoNativeOptionWrites(f);

            JsonNode applied = f.json(f.service.update(new UpdateFieldContextsRequest(false, preview.path("planId").asText(), null, null)));

            assertEquals(applied.toString().substring(0, Math.min(400, applied.toString().length())), "UPDATED", applied.path("status").asText());
            assertEquals("UPDATED", applied.path("contexts").get(0).path("verdict").asText());
            assertEquals(150, f.store.level(91, null).size());
            assertEquals(otherContextBefore, f.store.dump(92));
            assertTrue(f.store.writes > 0);
        }
    }

    @Test public void anAddInAContextOverTheLimitIsRefusedAndWritesNothing() throws Exception {
        Fixture f = overLimitFixture();
        JsonNode result = f.preview(optionsOf(1, opts(adds("one more"), renames(2, "renamed"), null, null, null)));
        assertEquals(result.toString(), "OPTIONS_LIMIT", result.path("errorCode").asText());
        assertEquals("FAILED", result.path("status").asText());
        assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        assertNoNativeOptionWrites(f);
        verifyNoInteractions(f.usage);
    }

    @Test public void disablingTheDefaultIsMarkedAllowedAndNeverTouchesTheDefault() throws Exception {
        Fixture f = optionsFixture();
        when(f.type.getDefaultValue(any(FieldConfig.class))).thenAnswer(call -> f.store.optionObject(f.store.rec(2)));
        UpdateFieldContextsResponse preview = f.service.update(f.request(optionsOf(1, opts(null, null, ids(2L), null, null))));
        JsonNode shown = f.json(preview);
        assertEquals(shown.toString(), "WOULD_UPDATE", shown.path("status").asText());
        assertTrue(rowsOf(shown, 0).get(0).path("default").asBoolean());

        JsonNode applied = f.apply(preview);

        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals(Arrays.asList("High", "Medium!", "Low!"), f.store.level(91, null));
        assertNeverDeletesOrTouchesDefaults(f);
        verify(f.genericRow, never()).set(anyString(), any());
    }

    @Test public void unreadableOptionDefaultRefusesPreviewAndInvalidatesAnExistingPlan() throws Exception {
        for (boolean atApply : Arrays.asList(false, true)) {
            Fixture f = optionsFixture();
            ContextUpdate change = optionsOf(1, opts(null, null, ids(2L), null, null));
            UpdateFieldContextsResponse preview = atApply ? f.service.update(f.request(change)) : null;
            if (atApply) assertNotNull(preview.getPlanId());
            when(f.type.getDefaultValue(any(FieldConfig.class)))
                    .thenThrow(new IllegalStateException("default unavailable"));

            JsonNode result = atApply ? f.apply(preview) : f.preview(change);

            assertEquals(result.toString(), atApply ? "PREVIEW_STALE" : "JIRA_ERROR", result.path("errorCode").asText());
            assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
            assertNoNativeOptionWrites(f);
            assertNeverDeletesOrTouchesDefaults(f);
            verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
        }
    }

    @Test public void aChangeOfTheOptionsBetweenPreviewAndApplyMakesThePreviewStaleAndWritesNothing() throws Exception {
        List<java.util.function.Consumer<OptionStoreFake>> changes = Arrays.asList(
                store -> store.option(77, 91, null, 3, "Added elsewhere", false),
                store -> store.rec(1).value = "Renamed elsewhere",
                store -> store.rec(2).disabled = true,
                store -> { store.rec(1).sequence = 5; });
        for (java.util.function.Consumer<OptionStoreFake> change : changes) {
            Fixture f = optionsFixture();
            UpdateFieldContextsResponse preview = f.service.update(f.request(optionsOf(1, opts(adds("Urgent"), null, null, null, null))));
            change.accept(f.store);
            JsonNode result = f.apply(preview);
            assertEquals(result.toString(), "PREVIEW_STALE", result.path("errorCode").asText());
            assertNoNativeOptionWrites(f);
        }
    }

    @Test public void onlyTheUsageNumbersAreOutsideTheFingerprintAndNoSearchRunsAtApply() throws Exception {
        Fixture f = optionsFixture();
        Map<Long, OptionUsage> first = new LinkedHashMap<>();
        first.put(1L, OptionUsage.count(12));
        when(f.usage.count(any(), any(), anyMap())).thenReturn(first);
        UpdateFieldContextsResponse preview = f.service.update(f.request(optionsOf(1, opts(null, renames(1, "Urgent"), null, null, null))));
        assertEquals(12, f.json(preview).path("contexts").get(0).path("options").path("rows").get(0).path("usage").path("issues").asInt());
        // the number is now different (or unknown): the plan must still apply
        when(f.usage.count(any(), any(), anyMap())).thenReturn(Collections.singletonMap(1L, OptionUsage.unknown("later")));

        JsonNode applied = f.apply(preview);

        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        verify(f.usage, times(1)).count(any(), any(), anyMap());
        assertFalse(applied.toString().contains("\"usage\""));
    }

    @Test public void requestsWithoutOptionsKeepTheirResponseFreeOfOptionFields() throws Exception {
        Fixture f = optionsFixture();

        JsonNode preview = f.preview(change(1, "P2"));

        assertEquals("WOULD_UPDATE", preview.path("status").asText());
        assertFalse(preview.toString().contains("\"options\""));
        // the fingerprint is taken over the plain serialization of the response, which must not mention options either
        String plain = new ObjectMapper().writeValueAsString(f.service.update(f.request(change(1, "P2"))));
        assertFalse(plain, plain.contains("\"options\""));
        verifyNoInteractions(f.usage);
    }

    @Test public void editingOneContextLeavesTheOptionsOfTheOtherContextsOfTheFieldUntouched() throws Exception {
        Fixture f = optionsFixture();
        String other = f.store.dump(92);

        JsonNode applied = f.apply(f.service.update(f.request(optionsOf(1, opts(adds("Urgent"), renames(1, "Top"), ids(3L), null, null)))));

        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals(other, f.store.dump(92));
        for (String entry : f.store.log) assertFalse(entry, entry.contains("5"));
        try {
            f.service.update(new UpdateFieldContextsRequest(false, "some-plan", "customfield_123", null));
            fail("apply must carry only the planId");
        } catch (ru.rasulov.common.api.exception.InvalidRequestException expected) { }
        try {
            f.service.update(new UpdateFieldContextsRequest(false, null, null, null));
            fail("apply needs a planId");
        } catch (ru.rasulov.common.api.exception.InvalidRequestException expected) { }
    }

    @Test public void aWriteThatChangesNothingFailsWithoutRetryOrRollbackAndAWriteThatBreaksHalfwayIsPartial() throws Exception {
        OptionsChange change = opts(adds("Urgent"), renames(1, "Top"), null, null, null);

        Fixture lost = optionsFixture();
        UpdateFieldContextsResponse lostPreview = lost.service.update(lost.request(optionsOf(1, change)));
        lost.store.ignoreWrites = true;
        JsonNode failed = lost.apply(lostPreview);
        assertEquals(failed.toString(), "FAILED", failed.path("status").asText());
        assertEquals(1L, failed.path("failedContextId").asLong());
        assertEquals("JIRA_ERROR", failed.path("errorCode").asText());
        assertEquals("FAILED", failed.path("contexts").get(0).path("verdict").asText());
        assertEquals(2, lost.store.writes);
        verifyNoInteractions(lost.reindex);

        Fixture broken = optionsFixture();
        UpdateFieldContextsResponse brokenPreview = broken.service.update(broken.request(optionsOf(1, change)));
        broken.store.failWrite = 2;
        JsonNode partial = broken.apply(brokenPreview);
        assertEquals(partial.toString(), "PARTIAL", partial.path("status").asText());
        assertEquals(1L, partial.path("failedContextId").asLong());
        assertTrue(partial.path("contexts").get(0).path("message").asText().contains("native option write failed"));
        assertEquals(Arrays.asList("High", "Medium", "Low!", "Urgent"), broken.store.level(91, null));
        assertEquals(2, broken.store.writes);
        verifyNoInteractions(broken.reindex);
        assertNeverDeletesOrTouchesDefaults(broken);

        Fixture different = optionsFixture();
        UpdateFieldContextsResponse differentPreview = different.service.update(different.request(optionsOf(1, change)));
        different.store.storeValue = String::toUpperCase;
        JsonNode mismatch = different.apply(differentPreview);
        assertEquals(mismatch.toString(), "PARTIAL", mismatch.path("status").asText());
        assertEquals(1L, mismatch.path("failedContextId").asLong());
        assertEquals(2, different.store.writes);
    }

    @Test public void withoutOptionsAConfigChangedDuringTheWriteStillFailsTheUnchangedConfigCheck() throws Exception {
        Fixture f = optionsFixture();
        f.afterWrite = () -> f.store.rec(1).value = "changed by someone else";

        JsonNode result = f.apply(f.service.update(f.request(change(1, "P2"))));

        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertTrue(result.path("contexts").get(0).path("message").asText().contains("full option tree"));
        assertEquals(0, f.store.writes);
    }

    @Test public void aRequestWhoseOperationsAllChangeNothingHasNoPlanAndAMixSkipsTheNoOps() throws Exception {
        Fixture f = optionsFixture();

        JsonNode nothing = f.preview(optionsOf(1, opts(null, renames(1, "High"), ids(3L), ids(2L), null)));

        assertEquals(nothing.toString(), "OPTIONS_NO_CHANGE", nothing.path("errorCode").asText());
        assertEquals("FAILED", nothing.path("status").asText());
        assertTrue(nothing.path("planId").isNull() || nothing.path("planId").isMissingNode());
        verifyNoInteractions(f.usage);

        UpdateFieldContextsResponse mixed = f.service.update(f.request(optionsOf(1, opts(null, renames(1, "Urgent"), ids(3L), null, null))));
        JsonNode shown = f.json(mixed);
        assertEquals(1, rowsOf(shown, 0).size());
        assertTrue(shown.toString(), shown.path("contexts").get(0).path("options").path("unchanged").get(0).asText().contains("без изменений"));
        JsonNode applied = f.apply(mixed);
        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals(Arrays.asList("Urgent", "Medium", "Low!"), f.store.level(91, null));
        assertEquals(1, f.store.writes);
    }

    @Test public void renameWithDisableOfOneOptionAndACaseOnlyChangeAreApplied() throws Exception {
        Fixture f = optionsFixture();
        UpdateFieldContextsResponse preview = f.service.update(f.request(optionsOf(1, opts(null, renames(1, "Urgent", 2, "MEDIUM"),
                ids(1L), null, null))));
        JsonNode shown = f.json(preview);
        assertEquals("High → Urgent, отключена", rowsOf(shown, 0).get(0).path("summary").asText());

        JsonNode applied = f.apply(preview);

        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals(Arrays.asList("Urgent!", "MEDIUM", "Low!"), f.store.level(91, null));
    }

    @Test public void aParentOfACascadingSelectIsDisabledOnlyWithItsChildren() throws Exception {
        Fixture f = new Fixture();
        f.type("com.atlassian.jira.plugin.system.customfieldtypes:cascadingselect");
        f.store.option(10, 91, null, 0, "EU", false).option(11, 91, 10L, 0, "DE", false).option(12, 91, 10L, 1, "FR", false);
        f.useStore();

        JsonNode refused = f.preview(optionsOf(1, opts(null, null, ids(10L), null, null)));
        assertEquals(refused.toString(), "OPTION_PARENT_HAS_ENABLED_CHILDREN", refused.path("errorCode").asText());
        assertTrue(refused.path("message").asText().contains("DE"));

        JsonNode applied = f.apply(f.service.update(f.request(optionsOf(1, opts(adds("IT"), null, ids(10L, 11L, 12L), null, null)))));
        assertEquals("OPTION_PARENT_DISABLED", f.preview(optionsOf(1, opts(Collections.singletonList(new OptionsChange.Add("IT", 10L)),
                null, ids(10L, 11L, 12L), null, null))).path("errorCode").asText());
        assertEquals(applied.toString(), "UPDATED", applied.path("status").asText());
        assertEquals(Arrays.asList("EU!", "IT"), f.store.level(91, null));
        assertEquals(Arrays.asList("DE!", "FR!"), f.store.level(91, 10L));
    }

    private static String actualProject(JsonNode response, int index) {
        return response.path("contexts").get(index).path("actual").path("projects").get(0).path("key").asText();
    }
    private static ContextUpdate change(long context, String project) {
        return new ContextUpdate(context, null, Collections.singletonList(project), null, Collections.singletonList("10"));
    }

    private final class Fixture {
        final AdministratorGuard guard = mock(AdministratorGuard.class);
        final ApplicationUser admin = mock(ApplicationUser.class);
        final CustomFieldManager fields = mock(CustomFieldManager.class);
        final FieldManager fieldManager = mock(FieldManager.class);
        final FieldConfigSchemeManager schemes = mock(FieldConfigSchemeManager.class);
        final ProjectManager projectManager = mock(ProjectManager.class);
        final ManagedConfigurationItemService managed = mock(ManagedConfigurationItemService.class);
        final com.atlassian.jira.config.IssueTypeManager typeManager = mock(com.atlassian.jira.config.IssueTypeManager.class);
        final ProjectResolver projects = new ProjectResolver(projectManager);
        final IssueTypeResolver issueTypes = new IssueTypeResolver(typeManager);
        final ReindexMessageManager reindex = mock(ReindexMessageManager.class);
        final OfBizDelegator db = mock(OfBizDelegator.class);
        final AssetsFieldConfiguration assets;
        final CustomFieldUndoHandler undo = mock(CustomFieldUndoHandler.class);
        final OptionsManager optionsManager = mock(OptionsManager.class);
        final OptionUsageCounter usage = mock(OptionUsageCounter.class);
        final OptionStoreFake store = new OptionStoreFake();
        final CustomField field = mock(CustomField.class);
        final CustomFieldType type = mock(CustomFieldType.class);
        final GenericValue option = mock(GenericValue.class), genericRow = mock(GenericValue.class);
        final Map<Long, Project> projectById = new HashMap<>();
        final Map<Long, FieldConfig> configById = new HashMap<>();
        final List<FieldConfigScheme> contexts = new ArrayList<>();
        final AtomicInteger writes = new AtomicInteger();
        final FieldConfigScheme first;
        final FieldContextScopeServiceImpl service;
        int failAt;
        Runnable afterWrite = () -> {};

        Fixture() { this(mock(AssetsFieldConfiguration.class)); }

        Fixture(AssetsFieldConfiguration assets) {
            this.assets = assets;
            when(components.getComponent(ProjectManager.class)).thenReturn(projectManager);
            when(guard.requireAdministrator()).thenReturn(admin);
            when(admin.getKey()).thenReturn("admin");
            when(field.getId()).thenReturn("customfield_123");
            when(field.getName()).thenReturn("Severity");
            when(field.getCustomFieldType()).thenReturn(type);
            type("com.atlassian.jira.plugin.system.customfieldtypes:select");
            when(fields.getCustomFieldObject("customfield_123")).thenReturn(field);
            when(field.getConfigurationSchemes()).thenAnswer(call -> contexts);
            IssueType task = mock(IssueType.class);
            when(task.getId()).thenReturn("10"); when(task.getName()).thenReturn("Task");
            when(typeManager.getIssueType("10")).thenReturn(task);
            IssueType bug = mock(IssueType.class);
            when(bug.getId()).thenReturn("11"); when(bug.getName()).thenReturn("Bug");
            when(typeManager.getIssueType("11")).thenReturn(bug);
            when(typeManager.getIssueTypes()).thenReturn(Arrays.asList(task, bug));
            for (long id = 1; id <= 6; id++) {
                Project project = mock(Project.class);
                when(project.getId()).thenReturn(id); when(project.getKey()).thenReturn("P" + id);
                when(project.getName()).thenReturn("Project " + id);
                when(project.getIssueTypes()).thenReturn(Collections.singletonList(task));
                projectById.put(id, project);
                when(projectManager.getProjectByCurrentKeyIgnoreCase("P" + id)).thenReturn(project);
            }
            when(projectManager.getProjects()).thenReturn(new ArrayList<>(projectById.values()));
            first = context(1, 91, 1); contexts.add(first); contexts.add(context(2, 92, 3));
            when(undo.persistedSnapshot("Severity", "customfield_123")).thenReturn("persisted-field");
            if (mockingDetails(assets).isMock()) when(assets.snapshot(anyLong())).thenReturn("assets-settings");
            when(option.getLong("id")).thenReturn(501L); when(option.getLong("sequence")).thenReturn(1L);
            when(option.getString("value")).thenReturn("Option"); when(option.getString("disabled")).thenReturn("Y");
            when(genericRow.getLong("id")).thenReturn(601L); when(genericRow.getString("datatype")).thenReturn("DefaultValue");
            when(genericRow.getString("datakey")).thenReturn("91"); when(genericRow.getString("xmlvalue")).thenReturn("<long>501</long>");
            when(db.findByAnd(eq("CustomFieldOption"), anyMap(), anyList())).thenReturn(Collections.singletonList(option));
            when(db.findByAnd(eq("GenericConfiguration"), anyMap(), anyList())).thenReturn(Collections.singletonList(genericRow));
            when(schemes.updateFieldConfigScheme(any(), anyList(), eq(field))).thenAnswer(call -> {
                FieldConfigScheme built = call.getArgument(0);
                FieldConfigScheme current = contexts.stream().filter(c -> c.getId().equals(built.getId())).findFirst().get();
                List<JiraContextNode> nodes = call.getArgument(1);
                List<Project> selected = new ArrayList<>();
                boolean global = nodes.size() == 1 && nodes.get(0).getProjectId() == null;
                if (!global) for (JiraContextNode node : nodes) selected.add(project(node.getProjectId()));
                scope(current, selected, built.getConfigs().containsKey(null) ? null : "10");
                when(current.isAllProjects()).thenReturn(global);
                when(current.getConfigs()).thenReturn(built.getConfigs());
                afterWrite.run();
                if (writes.incrementAndGet() == failAt) throw new IllegalStateException("native failed after partial write");
                return current;
            });
            service = new FieldContextScopeServiceImpl(guard,
                    new NamedLock(JiraMocks.lockService(mock(ClusterLock.class))), new FieldSelector(fields), managed,
                    projects, issueTypes, fields, fieldManager, schemes, projectManager, reindex,
                    new FieldContextSnapshots(undo, db, assets),
                    new ru.rasulov.common.impl.PreviewPlanExecutor(InMemoryActionPlans.create(), new ActionPlanCodec()), new ActionPlanCodec(),
                    FieldJournalTestSupport.journal("admin"), assets, optionsManager, usage);
        }
        /** Backs OptionsManager and the persisted option rows with {@link #store}; call after filling it. */
        void useStore() { store.install(optionsManager, db); }
        void type(String key) { when(type.getKey()).thenReturn(key); }
        Project project(long id) { return projectById.get(id); }
        FieldConfig config(long id) {
            return configById.computeIfAbsent(id, key -> { FieldConfig value = mock(FieldConfig.class); when(value.getId()).thenReturn(key); return value; });
        }
        FieldConfigScheme context(long id, long configId, long project) {
            FieldConfigScheme scheme = mock(FieldConfigScheme.class);
            when(scheme.getId()).thenReturn(id); when(scheme.getName()).thenReturn("Context " + id);
            doReturn(field).when(scheme).getField();
            doReturn(Collections.singletonMap("10", config(configId))).when(scheme).getConfigs();
            scope(scheme, Collections.singletonList(project(project)), "10");
            return scheme;
        }
        void scope(FieldConfigScheme scheme, List<Project> selected, String typeId) {
            doReturn(selected).when(scheme).getAssociatedProjectObjects();
            when(scheme.isAllProjects()).thenReturn(false);
            when(scheme.isAllIssueTypes()).thenReturn(typeId == null);
            doReturn(typeId == null ? Collections.emptyList() : Collections.singletonList(issueTypes.resolve(typeId)))
                    .when(scheme).getAssociatedIssueTypes();
        }
        UpdateFieldContextsRequest request(ContextUpdate... changes) {
            return new UpdateFieldContextsRequest(true, null, "customfield_123", Arrays.asList(changes));
        }
        JsonNode preview(ContextUpdate change) throws Exception { return json(service.update(request(change))); }
        JsonNode apply(UpdateFieldContextsResponse preview) throws Exception {
            return json(service.update(new UpdateFieldContextsRequest(false, preview.getPlanId(), null, null)));
        }
        JsonNode json(UpdateFieldContextsResponse response) throws Exception { return JSON.readTree(JSON.writeValueAsString(response)); }
    }
}
