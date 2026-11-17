package ru.rasulov.customfields.impl.context;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.jira.component.ComponentAccessor;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.ReindexMessageManager;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItem;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItemService;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.context.JiraContextNode;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
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
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.TestLinks;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextRequest;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextResponse;
import ru.rasulov.customfields.impl.CustomFieldOptionsWriter;
import ru.rasulov.customfields.impl.FieldJournalTestSupport;
import ru.rasulov.customfields.impl.JiraMocks;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.customfields.impl.validation.FieldFailureException;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.customfields.impl.filefield.FileFieldConfiguration;
import ru.rasulov.customfields.impl.undo.CustomFieldUndoHandler;
import ru.rasulov.customfields.impl.undo.FieldMutationJournal;
import ru.rasulov.customfields.impl.validation.OptionsValidator;
import ru.rasulov.customfields.impl.validation.TypeSuggester;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real plans, final-set validation, snapshots, journaled native write and re-read; Jira's managers are the only mocks. */
public class FieldContextCreatorImplTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ComponentAccessor.Worker components = mock(ComponentAccessor.Worker.class);
    private ComponentAccessor.Worker previousComponents;

    @Before public void initializeComponents() { previousComponents = ComponentAccessor.initialiseWorker(components); }

    @After public void closeJournals() {
        try { FieldJournalTestSupport.close(); }
        finally { ComponentAccessor.initialiseWorker(previousComponents); }
    }

    @Test public void createsSeparateContextForOneProjectAndThreeTypesWithoutTask() throws Exception {
        Fixture f = new Fixture();
        JsonNode preview = f.json(f.service.create(f.request("QA QABUG", "QABUG", "Bug", "Sub-bug", "Sub-task")));
        assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
        assertNotNull(preview.path("planId").textValue());
        assertEquals("customfield_123", preview.path("field").path("id").asText());
        assertEquals("SYSTEM", preview.path("field").path("typeClass").asText());
        assertEquals(Arrays.asList("1"), ids(preview.path("context").path("scope").path("projects")));
        assertEquals(Arrays.asList("1", "2", "3"), ids(preview.path("context").path("scope").path("issueTypes")));
        assertEquals(1, preview.path("unchangedContexts").size());
        assertEquals(3, preview.path("effectiveChange").path("newlyApplicable").path("count").asInt());
        assertFalse(preview.path("effectiveChange").path("existingBehaviorChanged").asBoolean());
        assertEquals("NONE", preview.path("settings").path("options").path("created").asText());
        assertFalse(preview.path("settings").path("defaultValue").path("copiedFromExistingContext").asBoolean());
        // a preview writes nothing and notifies nobody
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
        verifyNoInteractions(f.reindex);
        assertEquals(1, f.contexts.size());

        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "CREATED", applied.path("status").asText());
        assertEquals(3L, applied.path("context").path("contextId").asLong());
        assertEquals(Arrays.asList(93L), longs(applied.path("context").path("configIds")));
        assertEquals(Arrays.asList("1"), ids(applied.path("context").path("actual").path("projects")));
        assertEquals(Arrays.asList("1", "2", "3"), ids(applied.path("context").path("actual").path("issueTypes")));
        assertEquals("FIELD_CONTEXT", applied.path("report").path("created").get(0).path("kind").asText());
        assertEquals("3", applied.path("report").path("created").get(0).path("id").asText());
        assertEquals(1, applied.path("unchangedContexts").size());

        ArgumentCaptor<List<JiraContextNode>> nodes = listCaptor();
        ArgumentCaptor<List<IssueType>> types = listCaptor();
        verify(f.schemes, times(1)).createFieldConfigScheme(any(), nodes.capture(), types.capture(), eq(f.field));
        assertEquals(1, nodes.getValue().size());
        assertEquals(Long.valueOf(1), nodes.getValue().get(0).getProjectId());
        assertEquals(Arrays.asList("1", "2", "3"), typeIds(types.getValue()));
        verify(f.factory).create("customfield_123", "QA QABUG", "");
        // only a new context: the existing one is never written, renamed or removed
        verify(f.schemes, never()).updateFieldConfigScheme(any());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
        verify(f.schemes, never()).removeFieldConfigScheme(anyLong());
        verify(f.db, never()).createValue(anyString(), anyMap());
        verify(f.reindex, times(1)).pushMessage(f.admin, "admin.notifications.task.custom.fields");
        assertTrue(applied.path("reindexRecommended").asBoolean());
        assertTrue(applied.path("reindexNotificationDisplayed").asBoolean());
    }

    @Test public void existingContextsKeepScopeConfigAndStoredSettings() throws Exception {
        Fixture f = new Fixture();
        f.generic(91L, "<long>501</long>");
        JsonNode applied = f.apply(f.service.create(f.request("QA QABUG", "QABUG", "Bug")));
        assertEquals(applied.toString(), "CREATED", applied.path("status").asText());
        JsonNode untouched = applied.path("unchangedContexts").get(0);
        assertEquals(1L, untouched.path("contextId").asLong());
        assertEquals(Arrays.asList(91L), longs(untouched.path("configIds")));
        assertEquals(Arrays.asList("2"), ids(untouched.path("scope").path("projects")));
        // the new config is empty: nothing of the existing default is copied
        verify(f.db, never()).createValue(anyString(), anyMap());
    }

    @Test public void projectAlreadyInAnotherProjectContextIsReportedWithOwnerAndNeverFixed() throws Exception {
        Fixture f = new Fixture();
        JsonNode result = f.preview(f.request("QA PROD", "PROD", "Bug"));
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("PROJECT_IN_OTHER_CONTEXT", result.path("errorCode").asText());
        assertEquals(1L, result.path("conflict").path("contextId").asLong());
        assertEquals("Context 1", result.path("conflict").path("contextName").asText());
        assertEquals("PROD", result.path("conflict").path("project").path("key").asText());
        assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
        verify(f.schemes, never()).updateFieldConfigScheme(any(), anyList(), any());
    }

    @Test public void sameProjectWithDisjointIssueTypesIsRefusedLikeInTheOtherTwoTools() throws Exception {
        Fixture f = new Fixture();
        f.scope(f.first, Collections.singletonList(f.project(2)), "4"); // PROD already has a context for Task only
        JsonNode result = f.preview(f.request("QA PROD Bug", "PROD", "Bug"));
        assertEquals("FAILED", result.path("status").asText());
        assertEquals("PROJECT_IN_OTHER_CONTEXT", result.path("errorCode").asText());
        assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void secondGlobalContextIsRefusedNamingTheExistingOne() throws Exception {
        Fixture f = new Fixture();
        f.addGlobal(5, 95, 4);
        JsonNode result = f.preview(f.globalRequest("QA global"));
        assertEquals("GLOBAL_CONTEXT_EXISTS", result.path("errorCode").asText());
        assertEquals(5L, result.path("conflict").path("contextId").asLong());
        assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void projectContextOverGlobalIsPreviewedAsAConfigSwitchNotOnlyAnAddition() throws Exception {
        Fixture f = new Fixture();
        f.generic(95L, "<string>default</string>");
        f.addGlobal(5, 95, 4); // the global context covers Task only
        JsonNode preview = f.preview(f.request("QA QABUG", "QABUG", "Bug", "Task"));
        assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
        JsonNode effect = preview.path("effectiveChange");
        assertTrue(effect.path("existingBehaviorChanged").asBoolean());
        JsonNode swap = effect.path("configSwitches").get(0);
        assertEquals(5L, swap.path("contextId").asLong());
        assertEquals(Arrays.asList(95L), longs(swap.path("configIds")));
        assertEquals(1, swap.path("combinations").path("count").asInt());
        assertEquals("QABUG", swap.path("combinations").path("combinations").get(0).path("project").asText());
        assertEquals("4", swap.path("combinations").path("combinations").get(0).path("issueType").asText());
        assertEquals(1, swap.path("storedSettingRowsNotCopied").asInt());
        // Bug becomes newly applicable; Task only changes its config
        assertEquals(1, effect.path("newlyApplicable").path("count").asInt());
        assertEquals("1", effect.path("newlyApplicable").path("combinations").get(0).path("issueType").asText());
        assertTrue(effect.path("newlyApplicableExact").asBoolean());
        assertTrue(codes(preview.path("warnings")).contains("OVERRIDES_GLOBAL_CONTEXT"));
        assertEquals(2, preview.path("unchangedContexts").size());
        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "CREATED", applied.path("status").asText());
        assertEquals(2, applied.path("unchangedContexts").size());
    }

    @Test public void globalContextOverAProjectContextKeepsTheProjectContextPriority() throws Exception {
        Fixture f = new Fixture();
        JsonNode preview = f.preview(f.globalRequest("QA global"));
        assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
        JsonNode effect = preview.path("effectiveChange");
        assertFalse(effect.path("existingBehaviorChanged").asBoolean());
        assertFalse(effect.path("newlyApplicableExact").asBoolean());
        assertEquals(1L, effect.path("keptByExistingContexts").get(0).path("contextId").asLong());
        assertTrue(codes(preview.path("warnings")).contains("EXISTING_PROJECT_CONTEXTS_KEEP_PRIORITY"));
        assertTrue(codes(preview.path("warnings")).contains("GLOBAL_SCOPE"));
        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "CREATED", applied.path("status").asText());
        ArgumentCaptor<List<JiraContextNode>> nodes = listCaptor();
        verify(f.schemes).createFieldConfigScheme(any(), nodes.capture(), anyList(), eq(f.field));
        assertNull(nodes.getValue().get(0).getProjectId());
    }

    @Test public void projectSchemeRemovesGlobalApplicabilityOutsideItsIssueTypes() throws Exception {
        // Jira 10.3 selects the scheme by project before checking issue types; there is no global fallback.
        for (boolean allGlobalTypes : Arrays.asList(false, true)) {
            Fixture f = new Fixture();
            f.addGlobal(5, 95, 4);
            if (allGlobalTypes) when(f.contexts.get(1).isAllIssueTypes()).thenReturn(true);
            JsonNode preview = f.preview(f.request("QA Bug", "QABUG", "Bug"));
            assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
            JsonNode effect = preview.path("effectiveChange");
            JsonNode lost = effect.path("lostApplicability");
            assertTrue(effect.path("existingBehaviorChanged").asBoolean());
            assertTrue(codes(preview.path("warnings")).contains("GLOBAL_APPLICABILITY_REMOVED"));
            assertEquals(1, lost.path("count").asInt());
            JsonNode row = lost.path("combinations").get(0);
            assertEquals("QABUG", row.path("project").asText());
            if (allGlobalTypes) {
                assertEquals("*", row.path("issueType").asText());
                assertEquals("1", row.path("excludedIssueTypes").get(0).asText());
                assertEquals(1, effect.path("configSwitches").size());
            } else {
                assertEquals("4", row.path("issueType").asText());
                assertEquals(0, effect.path("configSwitches").size());
            }
            verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
        }
    }

    @Test public void newGlobalCannotFillMissingTypesOfAnExistingProjectContext() throws Exception {
        Fixture f = new Fixture();
        f.scope(f.first, Collections.singletonList(f.project(2)), "4");
        CreateFieldContextRequest request = f.raw("customfield_123", "Global Bug", true, null, null,
                Collections.singletonList("Bug"));
        JsonNode preview = f.preview(request);
        assertEquals("WOULD_CREATE", preview.path("status").asText());
        JsonNode effect = preview.path("effectiveChange");
        assertFalse(effect.path("newlyApplicableExact").asBoolean());
        JsonNode masked = effect.path("keptByExistingContexts").get(0).path("combinations").path("combinations").get(0);
        assertEquals("PROD", masked.path("project").asText());
        assertEquals("1", masked.path("issueType").asText());
        assertEquals(0, effect.path("configSwitches").size());
    }

    @Test public void invalidShapesAndNamesAreRefusedBeforeAnyPlanOrWrite() throws Exception {
        Fixture f = new Fixture();
        List<CreateFieldContextRequest> invalid = Arrays.asList(
                f.raw("customfield_123", "QA", null, null, null, Arrays.asList("Bug")),
                f.raw("customfield_123", "QA", null, Arrays.asList("QABUG"), null, null),
                f.raw("customfield_123", "QA", true, Arrays.asList("QABUG"), null, Arrays.asList("Bug")),
                f.raw("customfield_123", "QA", false, null, null, Arrays.asList("Bug")),
                f.raw("customfield_123", "QA", null, Collections.<String>emptyList(), null, Arrays.asList("Bug")),
                f.raw("customfield_123", "QA", null, Arrays.asList("QABUG"), null, Collections.<String>emptyList()),
                f.raw("customfield_123", "QA", null, Arrays.asList("QABUG", "qabug"), null, Arrays.asList("Bug")),
                f.raw("customfield_123", "QA", null, Arrays.asList("QABUG"), null, Arrays.asList("Bug", "1")),
                f.raw("customfield_123", "QA", null, Collections.nCopies(101, "QABUG"), null, Arrays.asList("Bug")),
                f.raw("customfield_123", "  ", null, Arrays.asList("QABUG"), null, Arrays.asList("Bug")),
                f.raw("customfield_123", null, null, Arrays.asList("QABUG"), null, Arrays.asList("Bug")),
                f.raw("customfield_123", new String(new char[256]).replace('\0', 'x'), null,
                        Arrays.asList("QABUG"), null, Arrays.asList("Bug")),
                f.raw("QA", "QA", null, Arrays.asList("QABUG"), null, Arrays.asList("Bug")),
                f.raw("123", "QA", null, Arrays.asList("QABUG"), null, Arrays.asList("Bug")),
                new CreateFieldContextRequest(true, null, "customfield_123", "QA",
                        new String(new char[256]).replace('\0', 'x'), null, Arrays.asList("QABUG"), null,
                        Arrays.asList("Bug"), null));
        for (CreateFieldContextRequest request : invalid) {
            JsonNode result = f.json(f.service.create(request));
            assertEquals(result.toString(), "VALIDATION_ERROR", result.path("errorCode").asText());
            assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
        }
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void unknownFieldProjectIssueTypeAndExistingNameAreNamedErrors() throws Exception {
        Fixture f = new Fixture();
        assertEquals("UNKNOWN_FIELD", f.preview(f.raw("customfield_999", "QA", null,
                Arrays.asList("QABUG"), null, Arrays.asList("Bug"))).path("errorCode").asText());
        JsonNode project = f.preview(f.request("QA", "QABU", "Bug"));
        assertEquals("UNKNOWN_PROJECT", project.path("errorCode").asText());
        assertEquals("QABUG", project.path("suggestions").get(0).asText());
        JsonNode type = f.preview(f.request("QA", "QABUG", "Bugg"));
        assertEquals("UNKNOWN_ISSUE_TYPE", type.path("errorCode").asText());
        assertEquals("Bug", type.path("suggestions").get(0).asText());
        JsonNode name = f.preview(f.request(" context 1 ", "QABUG", "Bug"));
        assertEquals("CONTEXT_NAME_EXISTS", name.path("errorCode").asText());
        assertEquals(1L, name.path("conflict").path("contextId").asLong());
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void lockedFieldAndUnsupportedTypesAreRefusedBeforeAnyWrite() throws Exception {
        Fixture locked = new Fixture();
        ManagedConfigurationItem item = mock(ManagedConfigurationItem.class);
        when(locked.managed.getManagedCustomField(locked.field)).thenReturn(item);
        when(locked.managed.doesUserHavePermission(locked.admin, item)).thenReturn(false);
        assertEquals("FIELD_LOCKED", locked.preview(locked.request("QA", "QABUG", "Bug")).path("errorCode").asText());

        MultipleSettableCustomFieldType<?, ?> vendorSelect = mock(MultipleSettableCustomFieldType.class);
        when(vendorSelect.getKey()).thenReturn("vendor:select");
        List<CustomFieldType<?, ?>> unsupported = new ArrayList<>();
        unsupported.add(vendorSelect);
        for (String key : Arrays.asList(AssetsFieldConfiguration.TYPE_KEY, FileFieldConfiguration.TYPE_KEY,
                "vendor:custom", "com.atlassian.jira.plugin.system.customfieldtypes:somefuturetype")) {
            CustomFieldType<?, ?> other = mock(CustomFieldType.class);
            when(other.getKey()).thenReturn(key);
            unsupported.add(other);
        }
        for (CustomFieldType<?, ?> type : unsupported) {
            Fixture f = new Fixture();
            f.useType(type);
            JsonNode result = f.preview(f.request("QA", "QABUG", "Bug"));
            assertEquals(type.getKey(), "UNSUPPORTED_FIELD_TYPE", result.path("errorCode").asText());
            assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
            verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
        }
        verify(locked.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    private static final String USER_AQL = "objectType = \"User\"";

    private static org.mockito.ArgumentMatcher<AssetsFieldSettings> assetsSettings(int schemaId, String aql) {
        return s -> s != null && Integer.valueOf(schemaId).equals(s.getObjectSchemaId()) && aql.equals(s.getFilterScopeAql());
    }

    @Test public void assetsContextPreviewShowsSchemaAndAqlAndApplyStoresThemOnTheNewContextConfig() throws Exception {
        Fixture f = new Fixture();
        f.assetsType();
        AssetsFieldSettings settings = new AssetsFieldSettings(3, USER_AQL);
        // The plugin stores what configure() wrote; the creator reads it back to confirm the new context.
        when(f.assets.settings(any())).thenReturn(settings);
        JsonNode preview = f.preview(f.assetsRequest("QA", settings));
        assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
        JsonNode shown = preview.path("settings").path("pluginSettings").path("assets");
        assertEquals(preview.toString(), 3, shown.path("objectSchemaId").asInt());
        assertEquals(USER_AQL, shown.path("filterScopeAql").asText());
        verify(f.assets).validate(eq(AssetsFieldConfiguration.TYPE_KEY), argThat(assetsSettings(3, USER_AQL)));
        verify(f.assets, never()).configure(anyLong(), anyLong(), any());
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());

        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "CREATED", applied.path("status").asText());
        assertEquals(93L, applied.path("context").path("configIds").get(0).asLong());
        verify(f.assets).configure(eq(93L), eq(123L), argThat(assetsSettings(3, USER_AQL)));
    }

    @Test public void assetsWriteFailureAfterTheNativeContextWasCreatedIsReportedAsPartial() throws Exception {
        Fixture f = new Fixture();
        f.assetsType();
        doThrow(new IllegalStateException("assets write refused")).when(f.assets).configure(anyLong(), anyLong(), any());
        JsonNode preview = f.preview(f.assetsRequest("QA", new AssetsFieldSettings(3, USER_AQL)));
        assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "PARTIAL", applied.path("status").asText());
        assertEquals("JIRA_ERROR", applied.path("errorCode").asText());
        assertTrue(applied.toString(), applied.path("message").asText().contains("assets write refused"));
        assertEquals(1, f.creates.get());
    }

    @Test public void invalidAssetsSettingsAreRefusedBeforeAnyPlanOrWrite() throws Exception {
        // A field that is not an Assets object field: the real adapter decides, no plugin is needed.
        Fixture other = new Fixture(new AssetsFieldConfiguration(mock(com.atlassian.plugin.PluginAccessor.class)));
        JsonNode wrongType = other.preview(other.assetsRequest("QA", new AssetsFieldSettings(3, USER_AQL)));
        assertEquals(wrongType.toString(), "VALIDATION_ERROR", wrongType.path("errorCode").asText());
        assertTrue(wrongType.path("planId").isNull() || wrongType.path("planId").isMissingNode());
        verify(other.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());

        // The adapter refuses the schema or AQL of an Assets field: its message reaches the caller unchanged.
        Fixture assets = new Fixture();
        assets.assetsType();
        doThrow(new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR, "Assets schema 99 does not exist.", null))
                .when(assets.assets).validate(any(), any());
        JsonNode missing = assets.preview(assets.assetsRequest("QA", new AssetsFieldSettings(99, USER_AQL)));
        assertEquals(missing.toString(), "VALIDATION_ERROR", missing.path("errorCode").asText());
        assertTrue(missing.toString(), missing.path("message").asText().contains("Assets schema 99 does not exist."));
        assertTrue(missing.path("planId").isNull() || missing.path("planId").isMissingNode());
        verify(assets.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
        verify(assets.assets, never()).configure(anyLong(), anyLong(), any());
    }

    @Test public void assetsThatThePluginDidNotStoreAsApprovedMakeTheCreationPartial() throws Exception {
        // configure() returned normally, but what the plugin now holds differs from the approved settings.
        for (AssetsFieldSettings stored : Arrays.asList(new AssetsFieldSettings(9, USER_AQL),
                new AssetsFieldSettings(3, "objectType = \"Host\""), null)) {
            Fixture f = new Fixture();
            f.assetsType();
            when(f.assets.settings(any())).thenReturn(stored);
            JsonNode preview = f.preview(f.assetsRequest("QA", new AssetsFieldSettings(3, USER_AQL)));
            JsonNode applied = f.apply(preview);
            assertEquals(applied.toString(), "PARTIAL", applied.path("status").asText());
            assertEquals("JIRA_ERROR", applied.path("errorCode").asText());
            assertTrue(applied.toString(), applied.path("message").asText().contains("stored Assets settings"));
            assertEquals(1, f.creates.get());
        }
        // A missing AQL is stored as an empty string by the plugin: that is the approved setting, not a difference.
        Fixture f = new Fixture();
        f.assetsType();
        when(f.assets.settings(any())).thenReturn(new AssetsFieldSettings(3, ""));
        JsonNode applied = f.apply(f.preview(f.assetsRequest("QA", new AssetsFieldSettings(3, null))));
        assertEquals(applied.toString(), "CREATED", applied.path("status").asText());
    }

    @Test public void assetsForAFieldWhoseTypeIsUnavailableReportTheUnavailableTypeNotAWrongType() throws Exception {
        // The real adapter judges the type key; a mock would accept anything and hide the ordering problem.
        Fixture f = new Fixture(new AssetsFieldConfiguration(mock(com.atlassian.plugin.PluginAccessor.class)));
        f.useType(null);
        JsonNode result = f.preview(f.assetsRequest("QA", new AssetsFieldSettings(3, USER_AQL)));
        assertEquals(result.toString(), "UNSUPPORTED_FIELD_TYPE", result.path("errorCode").asText());
        assertTrue(result.toString(), result.path("message").asText().contains("not available"));
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void everyBuiltInTypeWithoutPerContextSettingsIsAccepted() throws Exception {
        for (String key : Arrays.asList("textfield", "textarea", "datepicker", "datetime", "float", "url", "labels",
                "userpicker", "multiuserpicker", "grouppicker", "multigrouppicker", "project", "version", "multiversion")) {
            Fixture f = new Fixture();
            f.type("com.atlassian.jira.plugin.system.customfieldtypes:" + key);
            assertEquals(key, "WOULD_CREATE", f.preview(f.request("QA", "QABUG", "Bug")).path("status").asText());
        }
    }

    @Test public void selectContextGetsExactlyTheRequestedOptionsInItsOwnConfigOnly() throws Exception {
        Fixture f = new Fixture();
        f.select("select");
        f.storedOption(91L, 1L, null, 0L, "Old"); // the existing context's own option must stay as it is
        JsonNode preview = f.preview(f.optionsRequest("D2LOT Incident",
                options("Заря R&D", "Заря-С", "Зарница R&D")));
        assertEquals(preview.toString(), "WOULD_CREATE", preview.path("status").asText());
        JsonNode planned = preview.path("settings").path("options");
        assertEquals("REQUESTED", planned.path("created").asText());
        assertEquals(3, planned.path("count").asInt());
        assertEquals("Заря-С", planned.path("values").get(1).path("value").asText());
        assertFalse(planned.path("copiedFromExistingContext").asBoolean(true));
        verify(f.optionsManager, never()).createOption(any(), any(), anyLong(), anyString());

        JsonNode applied = f.apply(preview);
        assertEquals(applied.toString(), "CREATED", applied.path("status").asText());
        assertEquals(Arrays.asList(93L), longs(applied.path("context").path("configIds")));
        verify(f.optionsManager).createOption(f.config(93), null, 0L, "Заря R&D");
        verify(f.optionsManager).createOption(f.config(93), null, 1L, "Заря-С");
        verify(f.optionsManager).createOption(f.config(93), null, 2L, "Зарница R&D");
        verify(f.optionsManager, times(3)).createOption(any(), any(), anyLong(), anyString());
        JsonNode created = applied.path("report").path("created");
        assertEquals("OPTION", created.get(1).path("kind").asText());
        assertEquals(3, created.get(1).path("count").asInt());
        assertEquals(1, f.optionRows.get(91L).size());
    }

    @Test public void cascadingContextStoresChildrenUnderTheirParents() throws Exception {
        Fixture f = new Fixture();
        f.select("cascadingselect");
        List<OptionDefinition> requested = Arrays.asList(
                new OptionDefinition("A", options("A1", "A2")), new OptionDefinition("B", null));
        JsonNode applied = f.apply(f.preview(f.optionsRequest("Cascade", requested)));
        assertEquals(applied.toString(), "CREATED", applied.path("status").asText());
        verify(f.optionsManager).createOption(f.config(93), null, 0L, "A");
        verify(f.optionsManager).createOption(f.config(93), 1000L, 0L, "A1");
        verify(f.optionsManager).createOption(f.config(93), 1000L, 1L, "A2");
        verify(f.optionsManager).createOption(f.config(93), null, 1L, "B");
    }

    @Test public void optionListsAreRequiredForSelectsAndRefusedForOtherTypesBeforeAnyPlan() throws Exception {
        for (String key : Arrays.asList("select", "radiobuttons", "multiselect", "multicheckboxes", "cascadingselect")) {
            for (List<OptionDefinition> invalid : Arrays.asList(null, Collections.<OptionDefinition>emptyList(),
                    options("A", " a "), options("A", " "), Arrays.asList(new OptionDefinition("A", options("A1"))))) {
                if ("cascadingselect".equals(key) && invalid != null && invalid.size() == 1) continue;
                Fixture f = new Fixture();
                f.select(key);
                JsonNode result = f.preview(f.optionsRequest("QA", invalid));
                assertEquals(key + " " + invalid + ": " + result, "VALIDATION_ERROR", result.path("errorCode").asText());
                assertTrue(result.path("planId").isNull() || result.path("planId").isMissingNode());
            }
        }
        Fixture text = new Fixture();
        text.type("com.atlassian.jira.plugin.system.customfieldtypes:textfield");
        assertEquals("VALIDATION_ERROR", text.preview(text.optionsRequest("QA", options("A"))).path("errorCode").asText());
        verify(text.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void optionsThatJiraStoresDifferentlyOrOnlyPartlyAreNeverReportedAsCreated() throws Exception {
        for (String fault : Arrays.asList("rejected", "otherValue", "disabled", "noConfig")) {
            Fixture f = new Fixture();
            f.select("select");
            CreateFieldContextResponse preview = f.service.create(f.optionsRequest("QA", options("A", "B")));
            switch (fault) {
                case "rejected": f.rejectOption = "B"; break;
                case "otherValue": f.storeValue = value -> value + " "; break;
                case "disabled": f.storeDisabled = "Y"; break;
                case "noConfig": f.afterWrite = created -> when(created.getOneAndOnlyConfig()).thenReturn(null); break;
                default: fail();
            }
            JsonNode result = f.apply(preview);
            assertEquals(fault + ": " + result, "PARTIAL", result.path("status").asText());
            assertEquals("JIRA_ERROR", result.path("errorCode").asText());
            assertEquals(3L, result.path("context").path("contextId").asLong());
            verify(f.schemes, times(1)).createFieldConfigScheme(any(), anyList(), anyList(), any());
            verify(f.schemes, never()).removeFieldConfigScheme(anyLong());
        }
    }

    @Test public void onlyAdministratorsMayPreviewOrApply() throws Exception {
        Fixture f = new Fixture();
        when(f.guard.requireAdministrator()).thenThrow(new IllegalStateException("not an administrator"));
        try { f.service.create(f.request("QA", "QABUG", "Bug")); fail(); }
        catch (IllegalStateException expected) { assertEquals("not an administrator", expected.getMessage()); }
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void applyAcceptsOnlyTheModeAndPlanAndPreviewRefusesAPlan() {
        Fixture f = new Fixture();
        for (CreateFieldContextRequest request : Arrays.asList(
                new CreateFieldContextRequest(false, "plan", "customfield_123", null, null, null, null, null, null, null),
                new CreateFieldContextRequest(false, "plan", null, "QA", null, null, null, null, null, null),
                new CreateFieldContextRequest(false, "plan", null, null, "text", null, null, null, null, null),
                new CreateFieldContextRequest(false, "plan", null, null, null, true, null, null, null, null),
                new CreateFieldContextRequest(false, "plan", null, null, null, null, Arrays.asList("A"), null, null, null),
                new CreateFieldContextRequest(false, "plan", null, null, null, null, null, true, null, null),
                new CreateFieldContextRequest(false, "plan", null, null, null, null, null, null, Arrays.asList("Bug"), null),
                new CreateFieldContextRequest(false, "plan", null, null, null, null, null, null, null, options("A")),
                new CreateFieldContextRequest(false, " ", null, null, null, null, null, null, null, null),
                new CreateFieldContextRequest(null, null, "customfield_123", "QA", null, null, null, null, null, null),
                new CreateFieldContextRequest(true, "plan", "customfield_123", "QA", null, null,
                        Arrays.asList("QABUG"), null, Arrays.asList("Bug"), null),
                new CreateFieldContextRequest(false, "plan", null, null, null, null, null, null, null, null,
                        new AssetsFieldSettings(3, USER_AQL)))) {
            try { f.service.create(request); fail(); } catch (InvalidRequestException expected) { }
        }
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void planIsSingleUseAndASecondApplyCreatesNothing() throws Exception {
        Fixture f = new Fixture();
        CreateFieldContextResponse preview = f.service.create(f.request("QA QABUG", "QABUG", "Bug"));
        assertEquals("CREATED", f.apply(preview).path("status").asText());
        JsonNode again = f.apply(preview);
        assertEquals("PREVIEW_STALE", again.path("errorCode").asText());
        verify(f.schemes, times(1)).createFieldConfigScheme(any(), anyList(), anyList(), any());
        assertEquals(2, f.contexts.size());
        assertEquals("PREVIEW_STALE", f.json(f.service.create(new CreateFieldContextRequest(false, "forged", null,
                null, null, null, null, null, null, null))).path("errorCode").asText());
    }

    @Test public void anyChangeToTheFieldOrItsContextsAfterPreviewMakesThePlanStaleWithoutWriting() throws Exception {
        for (String change : Arrays.asList("scope", "newContext", "storedSetting", "name")) {
            Fixture f = new Fixture();
            CreateFieldContextResponse preview = f.service.create(f.request("QA QABUG", "QABUG", "Bug"));
            assertNotNull(preview.getPlanId());
            switch (change) {
                case "scope": f.scope(f.first, Collections.singletonList(f.project(3)), "4"); break;
                case "newContext": f.contexts.add(f.context(9, 99, "Late", Collections.singletonList(f.project(3)), "4")); break;
                case "storedSetting": f.generic(91L, "<long>2</long>"); break;
                case "name": when(f.first.getName()).thenReturn("Renamed"); break;
                default: fail();
            }
            assertEquals(change, "PREVIEW_STALE", f.apply(preview).path("errorCode").asText());
            verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
            verifyNoInteractions(f.reindex);
        }
    }

    @Test public void exceptionFromNativeCreateDoesNotProveThatNothingWasWritten() throws Exception {
        Fixture f = new Fixture();
        f.failBefore = true;
        JsonNode result = f.apply(f.service.create(f.request("QA QABUG", "QABUG", "Bug")));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertEquals("JIRA_ERROR", result.path("errorCode").asText());
        assertTrue(result.path("message").asText().contains("No retry"));
        assertTrue(result.path("context").isNull());
        assertTrue(result.path("reindexRecommended").asBoolean());
        verify(f.reindex).pushMessage(f.admin, "admin.notifications.task.custom.fields");
        verify(f.schemes, times(1)).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void failureBeforeEnteringNativeWriteIsFailedWithoutAClaimedContext() throws Exception {
        Fixture f = new Fixture();
        CreateFieldContextResponse preview = f.service.create(f.request("QA Bug", "QABUG", "Bug"));
        when(f.factory.create(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("factory failed"));
        JsonNode result = f.apply(preview);
        assertEquals("FAILED", result.path("status").asText());
        assertTrue(result.path("context").isNull());
        assertFalse(result.path("reindexRecommended").asBoolean());
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
        verifyNoInteractions(f.reindex);
    }

    @Test public void errorAfterJiraStoredTheContextIsPartialWithTheRealContextAndNoRetry() throws Exception {
        Fixture f = new Fixture();
        f.failAfter = true;
        JsonNode result = f.apply(f.service.create(f.request("QA QABUG", "QABUG", "Bug")));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertEquals("JIRA_ERROR", result.path("errorCode").asText());
        assertEquals(3L, result.path("context").path("contextId").asLong());
        assertEquals(Arrays.asList(93L), longs(result.path("context").path("configIds")));
        assertTrue(result.path("reindexRecommended").asBoolean());
        verify(f.reindex, times(1)).pushMessage(f.admin, "admin.notifications.task.custom.fields");
        verify(f.schemes, times(1)).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void storedStateThatDiffersFromTheApprovedOneIsNeverReportedAsCreated() throws Exception {
        for (String corruption : Arrays.asList("scope", "name", "options", "storedSetting", "existingContext",
                "existingSetting", "extraContext")) {
            Fixture f = new Fixture();
            f.afterWrite = created -> {
                switch (corruption) {
                    case "scope": f.scope(created, Collections.singletonList(f.project(3)), "1"); break;
                    case "name": when(created.getName()).thenReturn("Other"); break;
                    case "options": f.storedOption(93L, 700L, 0L, 0L, "Option"); break;
                    case "storedSetting": f.generic(93L, "<long>1</long>"); break;
                    case "existingContext": f.scope(f.first, Collections.singletonList(f.project(3)), "4"); break;
                    case "existingSetting": f.generic(91L, "<long>77</long>"); break;
                    case "extraContext": f.contexts.add(f.context(8, 98, "Extra", Collections.singletonList(f.project(3)), "4")); break;
                    default: fail();
                }
            };
            JsonNode result = f.apply(f.service.create(f.request("QA QABUG", "QABUG", "Bug")));
            assertEquals(corruption + ": " + result, "PARTIAL", result.path("status").asText());
            assertEquals("JIRA_ERROR", result.path("errorCode").asText());
        }
    }

    @Test public void unreadablePostStateIsNeverLabelledCreated() throws Exception {
        Fixture f = new Fixture();
        f.afterWrite = created -> doThrow(new IllegalStateException("refresh unavailable"))
                .when(f.fields).refreshConfigurationSchemes(123L);
        JsonNode result = f.apply(f.service.create(f.request("QA QABUG", "QABUG", "Bug")));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertEquals(3L, result.path("context").path("contextId").asLong());
        assertTrue(result.path("context").path("actual").isNull());
        assertEquals("3", result.path("report").path("created").get(0).path("id").asText());
        assertTrue(result.path("message").asText().contains("could not be read"));
        assertEquals(0, result.path("unchangedContexts").size());
        verify(f.reindex, times(1)).pushMessage(f.admin, "admin.notifications.task.custom.fields");
    }

    @Test public void unreadableStoredSettingsRetainNativeIdentityWithoutClaimingVerifiedScope() throws Exception {
        Fixture f = new Fixture();
        f.afterWrite = created -> when(f.db.findByAnd(eq("GenericConfiguration"), anyMap(), anyList()))
                .thenThrow(new IllegalStateException("settings unavailable"));
        JsonNode result = f.apply(f.service.create(f.request("QA Bug", "QABUG", "Bug")));
        assertEquals("PARTIAL", result.path("status").asText());
        assertEquals(3L, result.path("context").path("contextId").asLong());
        assertTrue(result.path("context").path("actual").isNull());
        assertEquals("3", result.path("report").path("created").get(0).path("id").asText());
        verify(f.schemes, times(1)).createFieldConfigScheme(any(), anyList(), anyList(), any());
        verify(f.schemes, never()).removeFieldConfigScheme(anyLong());
    }

    @Test public void journalPoststateFailureRetainsKnownIdentityAndPreparedIntent() throws Exception {
        Fixture f = new Fixture();
        String[] operation = new String[1];
        f.afterWrite = created -> {
            operation[0] = f.journal.currentOperationId();
            when(f.field.getConfigurationSchemes()).thenThrow(new IllegalStateException("poststate unavailable"));
        };
        JsonNode result = f.apply(f.service.create(f.request("QA Bug", "QABUG", "Bug")));
        assertEquals(result.toString(), "PARTIAL", result.path("status").asText());
        assertEquals(3L, result.path("context").path("contextId").asLong());
        assertTrue(result.path("context").path("actual").isNull());
        assertEquals("3", result.path("report").path("created").get(0).path("id").asText());
        JsonNode persisted = JSON.valueToTree(f.journal.getOperation(operation[0], "admin"));
        assertEquals("PREPARED", persisted.path("steps").get(0).path("status").asText());
        assertEquals(3L, persisted.path("result").path("context").path("contextId").asLong());
        verify(f.schemes, times(1)).createFieldConfigScheme(any(), anyList(), anyList(), any());
        verify(f.schemes, never()).removeFieldConfigScheme(anyLong());
    }

    @Test public void userPickerFiltersAreNotCopiedAndDisabledInitialRowsAreAccepted() throws Exception {
        Fixture f = new Fixture();
        f.filter(91L, true);
        f.afterWrite = created -> f.filter(93L, false);
        JsonNode result = f.apply(f.service.create(f.request("QA Bug", "QABUG", "Bug")));
        assertEquals(result.toString(), "CREATED", result.path("status").asText());
        assertEquals(Boolean.TRUE, f.filterRows.get(91L).getAllFields().get("enabled"));
        assertEquals(Boolean.FALSE, f.filterRows.get(93L).getAllFields().get("enabled"));
    }

    @Test public void changedOrUnreadableUserPickerSettingsNeverBecomeSuccess() throws Exception {
        for (String change : Arrays.asList("existing", "new", "unreadable")) {
            Fixture f = new Fixture();
            f.filter(91L, false);
            f.afterWrite = created -> {
                if ("unreadable".equals(change))
                    when(f.db.findByAnd(eq("UserPickerFilter"), anyMap(), anyList()))
                            .thenThrow(new IllegalStateException("filters unavailable"));
                else f.filter("existing".equals(change) ? 91L : 93L, true);
            };
            JsonNode result = f.apply(f.service.create(f.request("QA Bug", "QABUG", "Bug")));
            assertEquals(change + ": " + result, "PARTIAL", result.path("status").asText());
            assertEquals(3L, result.path("context").path("contextId").asLong());
            assertEquals("JIRA_ERROR", result.path("errorCode").asText());
            verify(f.schemes, never()).removeFieldConfigScheme(anyLong());
        }
    }

    @Test public void changingUserPickerFilterAfterPreviewInvalidatesThePlanBeforeWriting() throws Exception {
        Fixture f = new Fixture();
        f.filter(91L, false);
        CreateFieldContextResponse preview = f.service.create(f.request("QA Bug", "QABUG", "Bug"));
        f.filter(91L, true);
        JsonNode result = f.apply(preview);
        assertEquals("PREVIEW_STALE", result.path("errorCode").asText());
        verify(f.schemes, never()).createFieldConfigScheme(any(), anyList(), anyList(), any());
    }

    @Test public void failedNotificationDoesNotHideTheCreatedContext() throws Exception {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("notification unavailable")).when(f.reindex).pushMessage(any(), anyString());
        JsonNode result = f.apply(f.service.create(f.request("QA QABUG", "QABUG", "Bug")));
        assertEquals(result.toString(), "CREATED", result.path("status").asText());
        assertFalse(result.path("reindexNotificationDisplayed").asBoolean());
        assertTrue(result.path("message").asText().contains("could not display"));
        assertTrue(result.path("note").asText().contains("не смогла"));
    }

    private static List<OptionDefinition> options(String... values) {
        List<OptionDefinition> options = new ArrayList<>();
        for (String value : values) options.add(new OptionDefinition(value, null));
        return options;
    }
    private static List<String> ids(JsonNode array) {
        List<String> ids = new ArrayList<>();
        for (JsonNode item : array) ids.add(item.path("id").asText());
        return ids;
    }
    private static List<Long> longs(JsonNode array) {
        List<Long> values = new ArrayList<>();
        for (JsonNode item : array) values.add(item.asLong());
        return values;
    }
    private static List<String> codes(JsonNode warnings) {
        List<String> codes = new ArrayList<>();
        for (JsonNode warning : warnings) codes.add(warning.path("code").asText());
        return codes;
    }
    private static List<String> typeIds(List<IssueType> types) {
        List<String> ids = new ArrayList<>();
        for (IssueType type : types) ids.add(type.getId());
        return ids;
    }
    @SuppressWarnings("unchecked")
    private static <T> ArgumentCaptor<T> listCaptor() { return ArgumentCaptor.forClass((Class<T>) List.class); }

    final class Fixture {
        final AdministratorGuard guard = mock(AdministratorGuard.class);
        final ApplicationUser admin = mock(ApplicationUser.class);
        final CustomFieldManager fields = mock(CustomFieldManager.class);
        final FieldManager fieldManager = mock(FieldManager.class);
        final FieldConfigSchemeManager schemes = mock(FieldConfigSchemeManager.class);
        final ProjectManager projectManager = mock(ProjectManager.class);
        final ManagedConfigurationItemService managed = mock(ManagedConfigurationItemService.class);
        final IssueTypeManager typeManager = mock(IssueTypeManager.class);
        final ReindexMessageManager reindex = mock(ReindexMessageManager.class);
        final OfBizDelegator db = mock(OfBizDelegator.class);
        final OptionsManager optionsManager = mock(OptionsManager.class);
        final ContextSchemeFactory factory = mock(ContextSchemeFactory.class);
        final CustomField field = mock(CustomField.class);
        final CustomFieldType<?, ?> type = mock(CustomFieldType.class);
        final Map<Long, Project> projectById = new HashMap<>();
        final Map<String, IssueType> typeById = new LinkedHashMap<>();
        final Map<Long, FieldConfig> configById = new HashMap<>();
        final Map<Long, GenericValue> genericRows = new HashMap<>();
        final Map<Long, List<GenericValue>> optionRows = new HashMap<>();
        final Map<Long, GenericValue> filterRows = new HashMap<>();
        final List<FieldConfigScheme> contexts = new ArrayList<>();
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger optionIds = new AtomicInteger(1000);
        final FieldConfigScheme first;
        final FieldContextCreatorImpl service;
        final ru.rasulov.common.impl.ActionPlanStore plans = InMemoryActionPlans.create();
        final ru.rasulov.common.impl.undo.ChangeJournal journal;
        boolean failBefore, failAfter;
        String rejectOption, storeDisabled = "N";
        java.util.function.UnaryOperator<String> storeValue = value -> value;
        java.util.function.Consumer<FieldConfigScheme> afterWrite = created -> { };

        final AssetsFieldConfiguration assets;

        Fixture() { this(mock(AssetsFieldConfiguration.class)); }

        Fixture(AssetsFieldConfiguration assets) {
            this.assets = assets;
            when(components.getComponent(ProjectManager.class)).thenReturn(projectManager);
            when(guard.requireAdministrator()).thenReturn(admin);
            when(admin.getKey()).thenReturn("admin"); when(admin.getUsername()).thenReturn("admin");
            when(field.getId()).thenReturn("customfield_123");
            when(field.getName()).thenReturn("QA");
            when(field.getCustomFieldType()).thenAnswer(call -> currentType);
            useType(type);
            type("com.atlassian.jira.plugin.system.customfieldtypes:userpicker");
            when(type.getName()).thenReturn("User Picker (single user)");
            when(fields.getCustomFieldObject("customfield_123")).thenReturn(field);
            when(field.getConfigurationSchemes()).thenAnswer(call -> contexts);
            String[][] names = {{"1", "Bug"}, {"2", "Sub-bug"}, {"3", "Sub-task"}, {"4", "Task"}};
            for (String[] row : names) {
                IssueType issueType = mock(IssueType.class);
                when(issueType.getId()).thenReturn(row[0]); when(issueType.getName()).thenReturn(row[1]);
                typeById.put(row[0], issueType);
                when(typeManager.getIssueType(row[0])).thenReturn(issueType);
            }
            when(typeManager.getIssueTypes()).thenReturn(new ArrayList<>(typeById.values()));
            String[] keys = {"QABUG", "PROD", "OPS"};
            for (long id = 1; id <= 3; id++) {
                Project project = mock(Project.class);
                when(project.getId()).thenReturn(id); when(project.getKey()).thenReturn(keys[(int) id - 1]);
                when(project.getName()).thenReturn("Project " + keys[(int) id - 1]);
                when(project.getIssueTypes()).thenReturn(new ArrayList<>(typeById.values()));
                projectById.put(id, project);
                when(projectManager.getProjectByCurrentKeyIgnoreCase(keys[(int) id - 1])).thenReturn(project);
                when(projectManager.getProjectByCurrentKeyIgnoreCase(keys[(int) id - 1].toLowerCase())).thenReturn(project);
            }
            when(projectManager.getProjects()).thenReturn(new ArrayList<>(projectById.values()));
            first = context(1, 91, "Context 1", Collections.singletonList(project(2)), null);
            contexts.add(first);

            when(db.findByAnd(eq("CustomFieldOption"), anyMap(), anyList())).thenAnswer(call -> new ArrayList<>(
                    optionRows.getOrDefault(Long.valueOf(String.valueOf(
                            ((Map<?, ?>) call.getArgument(1)).values().iterator().next())), Collections.emptyList())));
            when(optionsManager.createOption(any(), any(), anyLong(), anyString())).thenAnswer(call -> {
                String value = call.getArgument(3);
                if (value.equals(rejectOption)) throw new IllegalStateException("option refused");
                FieldConfig config = call.getArgument(0);
                long id = optionIds.getAndIncrement();
                storedOption(config.getId(), id, call.getArgument(1), call.getArgument(2), storeValue.apply(value));
                Option option = mock(Option.class);
                when(option.getOptionId()).thenReturn(id);
                return option;
            });
            when(db.findByAnd(eq("GenericConfiguration"), anyMap(), anyList())).thenAnswer(call ->
                    rows(genericRows, ((Map<?, ?>) call.getArgument(1)).values().iterator().next()));
            when(db.findByAnd(eq("UserPickerFilter"), anyMap(), anyList())).thenAnswer(call ->
                    rows(filterRows, ((Map<?, ?>) call.getArgument(1)).values().iterator().next()));
            when(factory.create(anyString(), anyString(), anyString())).thenAnswer(call -> {
                FieldConfigScheme requested = mock(FieldConfigScheme.class);
                when(requested.getName()).thenReturn(call.getArgument(1));
                when(requested.getDescription()).thenReturn(call.getArgument(2));
                return requested;
            });
            when(schemes.createFieldConfigScheme(any(), anyList(), anyList(), eq(field))).thenAnswer(call -> {
                creates.incrementAndGet();
                if (failBefore) throw new IllegalStateException("native refused");
                FieldConfigScheme requested = call.getArgument(0);
                List<JiraContextNode> nodes = call.getArgument(1);
                List<IssueType> requestedTypes = call.getArgument(2);
                boolean global = nodes.size() == 1 && nodes.get(0).getProjectId() == null;
                List<Project> selected = new ArrayList<>();
                if (!global) for (JiraContextNode node : nodes) selected.add(project(node.getProjectId()));
                boolean allTypes = requestedTypes == FieldConfigSchemeManager.ALL_ISSUE_TYPES
                        || requestedTypes.contains(null);
                long id = 2 + creates.get();
                FieldConfigScheme created = context(id, 90 + id, requested.getName(), selected, null);
                String description = requested.getDescription();
                when(created.getDescription()).thenReturn(description);
                if (allTypes) scope(created, selected, null);
                else {
                    doReturn(selected).when(created).getAssociatedProjectObjects();
                    doReturn(requestedTypes).when(created).getAssociatedIssueTypes();
                    when(created.isAllIssueTypes()).thenReturn(false);
                }
                when(created.isAllProjects()).thenReturn(global);
                contexts.add(created);
                afterWrite.accept(created);
                if (failAfter) throw new IllegalStateException("native failed after storing");
                return created;
            });
            CustomFieldUndoHandler undo = FieldJournalTestSupport.fields(fields, optionsManager, schemes, factory);
            journal = FieldJournalTestSupport.journal("admin", undo);
            FieldMutationJournal mutations = new FieldMutationJournal(journal, undo);
            service = new FieldContextCreatorImpl(guard, new NamedLock(JiraMocks.lockService(mock(ClusterLock.class))),
                    new FieldSelector(fields), managed, new ProjectResolver(projectManager),
                    new IssueTypeResolver(typeManager), fields, fieldManager,
                    new ContextWriter(projectManager, schemes, factory, mutations), reindex,
                    new FieldContextSnapshots(undo, db, assets),
                    new ru.rasulov.common.impl.PreviewPlanExecutor(plans, new ActionPlanCodec()), new ActionPlanCodec(), journal, TestLinks.links(),
                    new OptionsValidator(new TypeSuggester(fields)), new CustomFieldOptionsWriter(optionsManager, mutations), assets);
        }

        CustomFieldType<?, ?> currentType;
        void useType(CustomFieldType<?, ?> value) { currentType = value; }
        void type(String key) { when(type.getKey()).thenReturn(key); }
        /** Switches the field to a built-in select-like type, which keeps its own options per context. */
        void select(String key) {
            MultipleSettableCustomFieldType<?, ?> select = mock(MultipleSettableCustomFieldType.class);
            when(select.getKey()).thenReturn("com.atlassian.jira.plugin.system.customfieldtypes:" + key);
            when(select.getName()).thenReturn(key);
            useType(select);
        }
        Project project(long id) { return projectById.get(id); }

        private List<GenericValue> rows(Map<Long, GenericValue> table, Object key) {
            GenericValue row = table.get(Long.valueOf(String.valueOf(key)));
            return row == null ? Collections.<GenericValue>emptyList() : Collections.singletonList(row);
        }
        void generic(long configId, String xml) {
            GenericValue row = mock(GenericValue.class);
            when(row.getLong("id")).thenReturn(600 + configId); when(row.getString("datatype")).thenReturn("DefaultValue");
            when(row.getString("datakey")).thenReturn(String.valueOf(configId)); when(row.getString("xmlvalue")).thenReturn(xml);
            genericRows.put(configId, row);
        }
        void storedOption(long configId, long id, Long parent, Long sequence, String value) {
            GenericValue row = mock(GenericValue.class);
            when(row.getLong("id")).thenReturn(id); when(row.getLong("parentoptionid")).thenReturn(parent);
            when(row.getLong("sequence")).thenReturn(sequence); when(row.getString("value")).thenReturn(value);
            when(row.getString("disabled")).thenReturn(storeDisabled);
            optionRows.computeIfAbsent(configId, key -> new ArrayList<>()).add(row);
        }
        void filter(long configId, boolean enabled) {
            GenericValue row = mock(GenericValue.class);
            Map<String, Object> values = new TreeMap<>();
            values.put("id", 800 + configId); values.put("customfieldconfig", configId); values.put("enabled", enabled);
            when(row.getLong("id")).thenReturn(800 + configId);
            when(row.getAllFields()).thenReturn(values);
            filterRows.put(configId, row);
        }
        FieldConfig config(long id) {
            return configById.computeIfAbsent(id, key -> {
                FieldConfig value = mock(FieldConfig.class);
                when(value.getId()).thenReturn(key);
                when(value.getCustomField()).thenReturn(field);
                return value;
            });
        }
        FieldConfigScheme context(long id, long configId, String name, List<Project> selected, String typeId) {
            FieldConfigScheme scheme = mock(FieldConfigScheme.class);
            when(scheme.getId()).thenReturn(id); when(scheme.getName()).thenReturn(name);
            doReturn(field).when(scheme).getField();
            doReturn(Collections.singletonMap(typeId, config(configId))).when(scheme).getConfigs();
            when(scheme.getOneAndOnlyConfig()).thenReturn(config(configId));
            scope(scheme, selected, typeId);
            return scheme;
        }
        /** A global context of the given issue type; null type means all. */
        void addGlobal(long id, long configId, long typeId) {
            FieldConfigScheme global = context(id, configId, "Global " + id, Collections.<Project>emptyList(), String.valueOf(typeId));
            when(global.isAllProjects()).thenReturn(true);
            contexts.add(global);
        }
        void scope(FieldConfigScheme scheme, List<Project> selected, String typeId) {
            doReturn(selected).when(scheme).getAssociatedProjectObjects();
            when(scheme.isAllProjects()).thenReturn(false);
            when(scheme.isAllIssueTypes()).thenReturn(typeId == null);
            doReturn(typeId == null ? Collections.emptyList() : Collections.singletonList(typeById.get(typeId)))
                    .when(scheme).getAssociatedIssueTypes();
        }
        CreateFieldContextRequest assetsRequest(String name, AssetsFieldSettings settings) {
            return new CreateFieldContextRequest(true, null, "customfield_123", name, null, null,
                    Collections.singletonList("QABUG"), null, Collections.singletonList("Bug"), null, settings);
        }
        void assetsType() {
            CustomFieldType<?, ?> assetsType = mock(CustomFieldType.class);
            when(assetsType.getKey()).thenReturn(AssetsFieldConfiguration.TYPE_KEY);
            when(assetsType.getName()).thenReturn("Assets object");
            useType(assetsType);
        }
        CreateFieldContextRequest request(String name, String project, String... issueTypes) {
            return new CreateFieldContextRequest(true, null, "customfield_123", name, null, null,
                    Collections.singletonList(project), null, Arrays.asList(issueTypes), null);
        }
        CreateFieldContextRequest optionsRequest(String name, List<OptionDefinition> options) {
            return new CreateFieldContextRequest(true, null, "customfield_123", name, null, null,
                    Collections.singletonList("QABUG"), null, Collections.singletonList("Bug"), options);
        }
        CreateFieldContextRequest globalRequest(String name) {
            return new CreateFieldContextRequest(true, null, "customfield_123", name, null, true, null, true, null, null);
        }
        CreateFieldContextRequest raw(String fieldId, String name, Boolean allProjects, List<String> projects,
                                      Boolean allTypes, List<String> issueTypes) {
            return new CreateFieldContextRequest(true, null, fieldId, name, null, allProjects, projects, allTypes,
                    issueTypes, null);
        }
        JsonNode preview(CreateFieldContextRequest request) throws Exception { return json(service.create(request)); }
        JsonNode apply(CreateFieldContextResponse preview) throws Exception {
            return json(service.create(new CreateFieldContextRequest(false, preview.getPlanId(), null, null, null, null,
                    null, null, null, null)));
        }
        JsonNode apply(JsonNode preview) throws Exception {
            return json(service.create(new CreateFieldContextRequest(false, preview.path("planId").textValue(), null,
                    null, null, null, null, null, null, null)));
        }
        JsonNode json(CreateFieldContextResponse response) throws Exception {
            return JSON.readTree(JSON.writeValueAsString(response));
        }
    }
}
