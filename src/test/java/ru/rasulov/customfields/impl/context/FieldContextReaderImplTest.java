package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.config.managedconfiguration.ConfigurationItemAccessLevel;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItem;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItemService;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.jira.issue.customfields.manager.GenericConfigManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.user.ApplicationUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.Test;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.customfields.impl.filefield.FileFieldConfiguration;
import ru.rasulov.customfields.impl.grid.GridFieldConfiguration;
import ru.rasulov.customfields.impl.validation.TypeKeys;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class FieldContextReaderImplTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(MapperFeature.AUTO_DETECT_GETTERS, false)
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @Test
    public void readsBothScopeAxesAndRealConfigMappingsInIdOrderWithoutCatalogScans() throws Exception {
        Fixture f = new Fixture("select");
        FieldConfig other = config(92L);
        Map<String, FieldConfig> mappings = new LinkedHashMap<>();
        mappings.put("20", other);
        mappings.put("10", f.config);
        mappings.put("30", f.config);
        when(f.specific.getConfigs()).thenReturn(mappings);
        doReturn(Arrays.asList(project(8), project(2))).when(f.specific).getAssociatedProjectObjects();
        doReturn(Arrays.asList(issueType("20"), issueType("10"), issueType("30")))
                .when(f.specific).getAssociatedIssueTypes();
        when(f.field.getConfigurationSchemes()).thenReturn(Arrays.asList(f.specific, f.global));
        f.stored("91", "<long>1</long>");
        f.option(f.config, 1, false);
        f.option(other, 2, true);

        JsonNode result = f.read(null);

        assertEquals("READ", result.path("status").asText());
        assertEquals("SYSTEM", result.path("field").path("typeClass").asText());
        assertEquals("customfield_123", result.path("field").path("id").asText());
        JsonNode global = result.path("contexts").get(0);
        JsonNode specific = result.path("contexts").get(1);
        assertEquals(1L, global.path("contextId").asLong());
        assertTrue(global.path("allProjects").asBoolean());
        assertTrue(global.path("allIssueTypes").asBoolean());
        assertEquals(0, global.path("projectCount").asInt());
        assertTrue(global.path("projectsComplete").asBoolean());
        assertTrue(global.path("description").isNull());
        assertTrue(global.path("configs").get(0).path("issueTypeIds").isNull());
        assertEquals(2L, specific.path("projects").get(0).path("id").asLong());
        assertEquals("10", specific.path("issueTypes").get(0).path("id").asText());
        assertEquals(2, specific.path("configs").size());
        assertEquals(91L, specific.path("configs").get(0).path("configId").asLong());
        assertEquals("30", specific.path("configs").get(0).path("issueTypeIds").get(1).asText());
        assertEquals(92L, specific.path("configs").get(1).path("configId").asLong());
        assertEquals(2, specific.path("options").path("count").asInt());
        assertEquals(1, specific.path("options").path("disabledCount").asInt());
        assertEquals("PRESENT", specific.path("defaultValue").asText());
        assertEquals("NOT_APPLICABLE", specific.path("pluginSettings").asText());
        verify(f.options, times(1)).getOptions(f.config);
        verify(f.fields, never()).getCustomFieldObjects();
        verify(f.fields, never()).getGlobalCustomFieldObjects();
        verify(f.fields, never()).getCustomFieldTypes();
        verify(f.global, never()).getAssociatedProjectObjects();
        verify(f.global, never()).getAssociatedIssueTypes();
        verifyNoInteractions(f.projectManager);
    }

    @Test
    public void supportsGlobalProjectsWithSpecificTypesAndSpecificProjectsWithAllTypes() throws Exception {
        Fixture f = new Fixture("textfield");
        when(f.specific.isAllProjects()).thenReturn(true);

        JsonNode allProjects = f.read(2L).path("contexts").get(0);

        assertTrue(allProjects.path("allProjects").asBoolean());
        assertFalse(allProjects.path("allIssueTypes").asBoolean());
        when(f.specific.isAllProjects()).thenReturn(false);
        when(f.specific.isAllIssueTypes()).thenReturn(true);
        when(f.specific.getConfigs()).thenReturn(Collections.singletonMap(null, f.config));

        JsonNode allTypes = f.read(2L).path("contexts").get(0);

        assertFalse(allTypes.path("allProjects").asBoolean());
        assertTrue(allTypes.path("allIssueTypes").asBoolean());
    }

    @Test
    public void capsProjectSummaryAtFiftyButReturnsAllForOneContext() throws Exception {
        Fixture f = new Fixture("textfield");
        List<Project> projects = new ArrayList<>();
        for (int id = 55; id > 0; id--) {
            projects.add(project(id));
        }
        doReturn(projects).when(f.specific).getAssociatedProjectObjects();

        JsonNode summary = f.read(null).path("contexts").get(1);

        assertEquals(55, summary.path("projectCount").asInt());
        assertEquals(50, summary.path("projects").size());
        assertFalse(summary.path("projectsComplete").asBoolean());
        JsonNode detail = f.read(2L);
        assertEquals(1, detail.path("contexts").size());
        JsonNode context = detail.path("contexts").get(0);
        assertEquals(55, context.path("projects").size());
        assertTrue(context.path("projectsComplete").asBoolean());
        assertEquals(55, context.path("projects").get(54).path("id").asInt());
    }

    @Test
    public void countsCascadingChildrenAndDisabledOptionsOncePerDistinctConfig() throws Exception {
        Fixture f = new Fixture("cascadingselect");
        Option root = f.option(f.config, 1, false);
        f.option(f.config, 2, false);
        Option child = mock(Option.class);
        when(child.getOptionId()).thenReturn(3L);
        when(child.getDisabled()).thenReturn(true);
        when(root.getChildOptions()).thenReturn(Collections.singletonList(child));
        Map<String, FieldConfig> mappings = new LinkedHashMap<>();
        mappings.put("10", f.config);
        mappings.put("20", f.config);
        when(f.specific.getConfigs()).thenReturn(mappings);

        JsonNode context = f.read(2L).path("contexts").get(0);

        assertEquals(3, context.path("options").path("count").asInt());
        assertEquals(1, context.path("options").path("disabledCount").asInt());
        verify(f.options).getOptions(f.config);
    }

    @Test
    public void nonOptionTypeHasExplicitNullOptionsAndAbsentDefault() throws Exception {
        Fixture f = new Fixture("textfield");

        JsonNode context = f.read(2L).path("contexts").get(0);

        assertTrue(context.has("options"));
        assertTrue(context.path("options").isNull());
        assertEquals("ABSENT", context.path("defaultValue").asText());
    }

    @Test
    public void standardTypeDefaultIsJudgedByTheStoredRowNotByTheTypedRead() throws Exception {
        Fixture f = new Fixture("userpicker");
        // The stored row references a deleted user: the typed read comes back empty, the row is still there.
        f.stored("91", "<string>deleted-user</string>");
        when(f.type.getDefaultValue(f.config)).thenReturn(null);

        assertEquals("PRESENT", f.read(2L).path("contexts").get(0).path("defaultValue").asText());
        verify(f.type, never()).getDefaultValue(any(FieldConfig.class));
    }

    @Test
    public void standardTypeWithoutAStoredRowHasNoDefaultWhateverTheTypedReadSays() throws Exception {
        Fixture f = new Fixture("version");
        when(f.type.getDefaultValue(f.config)).thenReturn("computed");

        assertEquals("ABSENT", f.read(2L).path("contexts").get(0).path("defaultValue").asText());
        verify(f.type, never()).getDefaultValue(any(FieldConfig.class));
    }

    @Test
    public void unreadableStoredDefaultOfAStandardTypeIsUnknownNotAbsent() throws Exception {
        Fixture f = new Fixture("textfield");
        when(f.generic.retrieve("DefaultValue", "91")).thenThrow(new IllegalStateException("unreadable"));

        assertEquals("UNKNOWN", f.read(2L).path("contexts").get(0).path("defaultValue").asText());
    }

    @Test
    public void thirdPartyDefaultsCanFailWithoutLosingContextOrNativeOptions() throws Exception {
        Fixture f = new Fixture("vendor:type");
        f.option(f.config, 7, true);
        when(f.type.getDefaultValue(f.config)).thenThrow(new IllegalStateException("Unavailable plugin"));

        JsonNode result = f.read(2L);
        JsonNode context = result.path("contexts").get(0);

        assertEquals("READ", result.path("status").asText());
        assertEquals("THIRD_PARTY", result.path("field").path("typeClass").asText());
        assertEquals("UNKNOWN", context.path("defaultValue").asText());
        assertEquals("UNKNOWN", context.path("pluginSettings").asText());
        assertEquals(1, context.path("options").path("disabledCount").asInt());
        verify(f.generic, never()).retrieve(any(String.class), any(String.class));
    }

    @Test
    public void pluginTypeDefaultStillComesFromTheTypedRead() throws Exception {
        Fixture f = new Fixture("vendor:type");
        when(f.type.getDefaultValue(f.config)).thenReturn("value");

        assertEquals("PRESENT", f.read(2L).path("contexts").get(0).path("defaultValue").asText());
    }

    @Test
    public void knownPluginSettingsDistinguishPresentAbsentAndUnavailable() throws Exception {
        Fixture assets = new Fixture(AssetsFieldConfiguration.TYPE_KEY);
        when(assets.assets.snapshot(91L)).thenReturn("snapshot");
        when(assets.assets.initialized("snapshot")).thenReturn(true);
        assertEquals("PRESENT", assets.read(2L).path("contexts").get(0).path("pluginSettings").asText());
        when(assets.assets.initialized("snapshot")).thenReturn(false);
        assertEquals("ABSENT", assets.read(2L).path("contexts").get(0).path("pluginSettings").asText());
        when(assets.assets.snapshot(91L)).thenThrow(new IllegalStateException("Plugin absent"));
        assertEquals("UNKNOWN", assets.read(2L).path("contexts").get(0).path("pluginSettings").asText());

        Fixture file = new Fixture(FileFieldConfiguration.TYPE_KEY);
        when(file.files.initialized(91L)).thenReturn(true);
        assertEquals("KNOWN_PLUGIN", file.read(2L).path("field").path("typeClass").asText());
        assertEquals("PRESENT", file.read(2L).path("contexts").get(0).path("pluginSettings").asText());
        when(file.files.initialized(91L)).thenReturn(false);
        assertEquals("ABSENT", file.read(2L).path("contexts").get(0).path("pluginSettings").asText());

        Fixture grid = new Fixture(GridFieldConfiguration.TYPE_KEY);
        assertEquals("KNOWN_PLUGIN", grid.read(2L).path("field").path("typeClass").asText());
        assertEquals("UNKNOWN", grid.read(2L).path("contexts").get(0).path("pluginSettings").asText());
    }

    @Test
    public void assetsContextExposesStoredSchemaAndAqlOnlyWhenTheyWereActuallyRead() throws Exception {
        Fixture f = new Fixture(AssetsFieldConfiguration.TYPE_KEY);
        when(f.assets.snapshot(91L)).thenReturn("snapshot");
        when(f.assets.initialized("snapshot")).thenReturn(true);
        when(f.assets.settings("snapshot")).thenReturn(new AssetsFieldSettings(3, "objectType = \"User\""));
        JsonNode present = f.read(2L).path("contexts").get(0);
        assertEquals("PRESENT", present.path("pluginSettings").asText());
        assertEquals(3, present.path("assets").path("objectSchemaId").asInt());
        assertEquals("objectType = \"User\"", present.path("assets").path("filterScopeAql").asText());

        when(f.assets.initialized("snapshot")).thenReturn(false);
        when(f.assets.settings("snapshot")).thenReturn(null);
        JsonNode absent = f.read(2L).path("contexts").get(0);
        assertEquals("ABSENT", absent.path("pluginSettings").asText());
        assertTrue(absent.toString(), absent.path("assets").isMissingNode() || absent.path("assets").isNull());

        when(f.assets.snapshot(91L)).thenThrow(new IllegalStateException("Plugin absent"));
        JsonNode unknown = f.read(2L).path("contexts").get(0);
        assertEquals("UNKNOWN", unknown.path("pluginSettings").asText());
        assertTrue(unknown.toString(), unknown.path("assets").isMissingNode() || unknown.path("assets").isNull());
    }

    @Test
    public void fieldsOfOtherTypesNeverGetAssetsValuesOrTouchTheAssetsAdapter() throws Exception {
        for (String type : Arrays.asList("textfield", "vendor:type", FileFieldConfiguration.TYPE_KEY)) {
            Fixture f = new Fixture(type);
            JsonNode context = f.read(2L).path("contexts").get(0);
            assertTrue(type + ": " + context, context.path("assets").isMissingNode() || context.path("assets").isNull());
            verifyNoInteractions(f.assets);
        }
    }

    @Test
    public void aFieldWhoseTypeIsUnavailableIsReadWithUnknownPluginSettingsAndNoAssets() throws Exception {
        // The plugin of the type is disabled or removed: Jira then hands out no type for the field.
        Fixture f = new Fixture(AssetsFieldConfiguration.TYPE_KEY);
        when(f.field.getCustomFieldType()).thenReturn(null);

        JsonNode result = f.read(2L);
        JsonNode context = result.path("contexts").get(0);

        assertEquals(result.toString(), "READ", result.path("status").asText());
        assertEquals("UNKNOWN", context.path("pluginSettings").asText());
        assertTrue(context.toString(), context.path("assets").isMissingNode() || context.path("assets").isNull());
        verifyNoInteractions(f.assets);
    }

    @Test
    public void everyAssetsContextReportsItsOwnSettingsAndAContextWithSeveralConfigsReportsNone() throws Exception {
        Fixture f = new Fixture(AssetsFieldConfiguration.TYPE_KEY);
        FieldConfig globalConfig = config(93L);
        when(f.global.getConfigs()).thenReturn(Collections.singletonMap(null, globalConfig));
        when(f.assets.snapshot(91L)).thenReturn("specific");
        when(f.assets.snapshot(93L)).thenReturn("global");
        when(f.assets.initialized(any(String.class))).thenReturn(true);
        when(f.assets.settings("specific")).thenReturn(new AssetsFieldSettings(3, "objectType = \"User\""));
        when(f.assets.settings("global")).thenReturn(new AssetsFieldSettings(5, "objectType = \"Host\""));

        JsonNode contexts = f.read("customfield_123", null).path("contexts");

        assertEquals(1L, contexts.get(0).path("contextId").asLong());
        assertEquals(5, contexts.get(0).path("assets").path("objectSchemaId").asInt());
        assertEquals("objectType = \"Host\"", contexts.get(0).path("assets").path("filterScopeAql").asText());
        assertEquals(2L, contexts.get(1).path("contextId").asLong());
        assertEquals(3, contexts.get(1).path("assets").path("objectSchemaId").asInt());
        assertEquals("objectType = \"User\"", contexts.get(1).path("assets").path("filterScopeAql").asText());

        // One context spanning two configs has no single value: none is reported, never the last config's.
        Map<String, FieldConfig> two = new LinkedHashMap<>();
        two.put("10", f.config);
        two.put("20", config(92L));
        when(f.specific.getConfigs()).thenReturn(two);
        when(f.assets.snapshot(92L)).thenReturn("other");
        when(f.assets.settings("other")).thenReturn(new AssetsFieldSettings(9, "objectType = \"Other\""));
        JsonNode several = f.read(2L).path("contexts").get(0);
        assertEquals("PRESENT", several.path("pluginSettings").asText());
        assertTrue(several.toString(), several.path("assets").isMissingNode() || several.path("assets").isNull());
    }

    @Test
    public void unknownInOneConfigDoesNotMisrepresentTheWholeDefaultAsAbsent() throws Exception {
        Fixture f = new Fixture("textfield");
        FieldConfig other = config(92L);
        Map<String, FieldConfig> mappings = new LinkedHashMap<>();
        mappings.put("10", f.config);
        mappings.put("20", other);
        when(f.specific.getConfigs()).thenReturn(mappings);
        when(f.generic.retrieve("DefaultValue", "92")).thenThrow(new IllegalStateException("unreadable"));

        assertEquals("UNKNOWN", f.read(2L).path("contexts").get(0).path("defaultValue").asText());
    }

    @Test
    public void aManagedFieldWhoseAccessLevelAllowsTheAdministratorIsManagedButNotLocked() throws Exception {
        Fixture f = new Fixture("textfield");
        ManagedConfigurationItem item = managedItem(true, ConfigurationItemAccessLevel.ADMIN);
        when(f.managed.getManagedCustomField(f.field)).thenReturn(item);
        when(f.managed.doesUserHavePermission(f.admin, item)).thenReturn(true);

        JsonNode field = f.read(2L).path("field");

        assertTrue(field.path("managed").asBoolean());
        assertFalse(field.path("locked").asBoolean());
    }

    @Test
    public void aFieldTheAdministratorHasNoPermissionToEditIsLocked() throws Exception {
        Fixture f = new Fixture("textfield");
        ManagedConfigurationItem item = managedItem(true, ConfigurationItemAccessLevel.LOCKED);
        when(f.managed.getManagedCustomField(f.field)).thenReturn(item);
        when(f.managed.doesUserHavePermission(f.admin, item)).thenReturn(false);

        JsonNode field = f.read(2L).path("field");

        assertTrue(field.path("managed").asBoolean());
        assertTrue(field.path("locked").asBoolean());
    }

    @Test
    public void lockedFollowsPermissionAloneEvenForAnItemThatIsNotManaged() throws Exception {
        Fixture f = new Fixture("textfield");
        ManagedConfigurationItem item = managedItem(false, ConfigurationItemAccessLevel.SYS_ADMIN);
        when(f.managed.getManagedCustomField(f.field)).thenReturn(item);
        when(f.managed.doesUserHavePermission(f.admin, item)).thenReturn(false);

        JsonNode field = f.read(2L).path("field");

        assertFalse(field.path("managed").asBoolean());
        assertTrue(field.path("locked").asBoolean());
    }

    @Test
    public void aFieldWithoutAManagedItemIsNeitherManagedNorLocked() throws Exception {
        Fixture f = new Fixture("textfield");

        JsonNode field = f.read(2L).path("field");

        assertFalse(field.path("managed").asBoolean());
        assertFalse(field.path("locked").asBoolean());
        verify(f.managed, never()).doesUserHavePermission(any(ApplicationUser.class),
                any(ManagedConfigurationItem.class));
    }

    @Test
    public void resolvesNamesNumericIdsAndAmbiguityAndRejectsForeignContexts() throws Exception {
        Fixture f = new Fixture("textfield");
        for (String selector : Arrays.asList("123", "customfield_123", " Severity ")) {
            assertEquals("READ", f.read(selector, null).path("status").asText());
        }
        assertEquals("UNKNOWN_CONTEXT", f.read(99L).path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", f.read(0L).path("errorCode").asText());
        assertEquals("VALIDATION_ERROR", f.read(" ", null).path("errorCode").asText());
        assertEquals("UNKNOWN_FIELD", f.read("missing", null).path("errorCode").asText());
        CustomField second = mock(CustomField.class);
        when(second.getId()).thenReturn("customfield_456");
        when(second.getName()).thenReturn("Severity");
        when(second.getCustomFieldType()).thenReturn(f.type);
        when(f.fields.getCustomFieldObjectsByNameIgnoreCase("Severity")).thenReturn(Arrays.asList(second, f.field));

        JsonNode ambiguous = f.read("Severity", null);

        assertEquals("AMBIGUOUS_FIELD", ambiguous.path("errorCode").asText());
        assertEquals(2, ambiguous.path("candidates").size());
        assertEquals("customfield_123", ambiguous.path("candidates").get(0).path("id").asText());
        assertEquals(TypeKeys.SYSTEM_PLUGIN + ":textfield", ambiguous.path("candidates").get(1).path("typeKey").asText());
    }

    @Test
    public void authorizesBeforeAnyFieldRead() throws Exception {
        Fixture f = new Fixture("textfield");
        doThrow(new SecurityException("Not admin")).when(f.guard).requireAdministrator();

        try {
            f.reader.read("123", "P1", null, false);
            fail("Read must be refused");
        } catch (SecurityException expected) {
            verifyNoInteractions(f.projectManager, f.fields, f.managed, f.options, f.generic, f.assets, f.files);
        }
    }

    @Test
    public void includeOptionsListsEveryOptionWithIdValueParentDisabledDefaultAndPositionOfItsLevel() throws Exception {
        Fixture f = new Fixture("cascadingselect");
        when(f.specific.getConfigs()).thenReturn(Collections.singletonMap(null, f.config));
        Option paris = f.child(3, "Paris", 0, false);
        Option berlin = f.child(4, "Berlin", 1, true);
        Option europe = f.option(f.config, 1, "Europe", 5, false, berlin, paris);
        f.option(f.config, 2, "Asia", 1, true);
        Map<String, Option> stored = new LinkedHashMap<>();
        stored.put(null, europe); stored.put("1", paris);
        when(f.type.getDefaultValue(f.config)).thenReturn(stored);

        JsonNode context = f.read(2L, true).path("contexts").get(0);

        JsonNode list = context.path("optionList");
        assertTrue(list.path("hasOptions").asBoolean());
        JsonNode items = list.path("items");
        assertEquals(items.toString(), 4, items.size());
        long[] ids = {2, 1, 3, 4};
        for (int i = 0; i < ids.length; i++) assertEquals(items.toString(), ids[i], items.get(i).path("id").asLong());
        assertEquals("Asia", items.get(0).path("value").asText());
        assertTrue(items.get(0).path("parentId").isNull());
        assertTrue(items.get(0).path("disabled").asBoolean());
        assertFalse(items.get(0).path("default").asBoolean(true));
        assertEquals(0, items.get(0).path("position").asInt());
        assertEquals(1, items.get(1).path("position").asInt());
        assertTrue(items.get(1).path("default").asBoolean());
        assertEquals(1L, items.get(2).path("parentId").asLong());
        assertEquals("Paris", items.get(2).path("value").asText());
        assertEquals(0, items.get(2).path("position").asInt());
        assertTrue(items.get(2).path("default").asBoolean());
        assertEquals("Berlin", items.get(3).path("value").asText());
        assertEquals(1, items.get(3).path("position").asInt());
        assertTrue(items.get(3).path("disabled").asBoolean());
        assertFalse(items.get(3).path("default").asBoolean(true));
        assertEquals(91L, items.get(3).path("configId").asLong());
        assertEquals(4, context.path("options").path("count").asInt());
        assertEquals(2, context.path("options").path("disabledCount").asInt());
    }

    @Test
    public void withoutIncludeOptionsNoOptionListIsReturned() throws Exception {
        Fixture f = new Fixture("select");
        f.option(f.config, 1, "A", 0, false);

        JsonNode context = f.read(2L, false).path("contexts").get(0);

        assertFalse(context.has("optionList"));
        assertEquals(1, context.path("options").path("count").asInt());
        assertFalse(f.read(null, false).toString().contains("optionList"));
    }

    @Test
    public void includeOptionsWithoutAContextIdIsAValidationErrorAndReadsNoOptions() throws Exception {
        Fixture f = new Fixture("select");

        JsonNode result = f.read(null, true);

        assertEquals("FAILED", result.path("status").asText());
        assertEquals("VALIDATION_ERROR", result.path("errorCode").asText());
        assertTrue(result.path("message").asText().contains("contextId"));
        verify(f.options, never()).getOptions(any(FieldConfig.class));
    }

    @Test
    public void aContextOfAFieldWithoutOptionsSaysSoExplicitly() throws Exception {
        Fixture f = new Fixture("textfield");

        JsonNode list = f.read(2L, true).path("contexts").get(0).path("optionList");

        assertTrue(list.has("hasOptions"));
        assertFalse(list.path("hasOptions").asBoolean());
        assertEquals(0, list.path("items").size());
    }

    @Test
    public void anOptionFieldWithoutOptionsHasOptionsAndAnEmptyList() throws Exception {
        Fixture f = new Fixture("select");

        JsonNode list = f.read(2L, true).path("contexts").get(0).path("optionList");

        assertTrue(list.path("hasOptions").asBoolean());
        assertEquals(0, list.path("items").size());
    }

    @Test
    public void anUnreadableDefaultLeavesTheDefaultFlagOutInsteadOfClaimingNone() throws Exception {
        Fixture f = new Fixture("select");
        f.option(f.config, 1, "A", 0, false);
        when(f.type.getDefaultValue(f.config)).thenThrow(new IllegalStateException("cannot read"));

        JsonNode item = f.read(2L, true).path("contexts").get(0).path("optionList").path("items").get(0);

        assertEquals("A", item.path("value").asText());
        assertFalse(item.has("default"));
    }

    @Test
    public void projectReadIncludesGlobalAndMatchingContextsButNotAnotherProjectsContext() throws Exception {
        Fixture f = new Fixture("textfield");
        FieldConfigScheme foreign = mock(FieldConfigScheme.class);
        when(foreign.getId()).thenReturn(3L);
        doReturn(Collections.singletonList(project(2))).when(foreign).getAssociatedProjectObjects();
        when(f.field.getConfigurationSchemes()).thenReturn(Arrays.asList(foreign, f.specific, f.global));
        doReturn(project(1)).when(f.projectManager).getProjectByCurrentKeyIgnoreCase("p1");

        JsonNode result = f.readProject("customfield_123", " p1 ", null, false);

        assertEquals("READ", result.path("status").asText());
        JsonNode contexts = result.path("contexts");
        assertEquals(2, contexts.size());
        assertEquals(1L, contexts.get(0).path("contextId").asLong());
        assertTrue(contexts.get(0).path("allProjects").asBoolean());
        assertEquals(2L, contexts.get(1).path("contextId").asLong());
        assertEquals("10", contexts.get(1).path("configs").get(0).path("issueTypeIds").get(0).asText());
        verify(foreign, never()).getConfigs();
        verify(f.projectManager, never()).getProjects();
    }

    @Test
    public void projectBeyondSummaryLimitMatchesWithoutHidingTheSharedScope() throws Exception {
        Fixture f = new Fixture("textfield");
        List<Project> projects = new ArrayList<>();
        for (int id = 55; id > 0; id--) projects.add(project(id));
        doReturn(projects).when(f.specific).getAssociatedProjectObjects();
        doReturn(project(55)).when(f.projectManager).getProjectByCurrentKeyIgnoreCase("P55");

        JsonNode context = f.readProject("123", "P55", null, false).path("contexts").get(1);

        assertEquals(2L, context.path("contextId").asLong());
        assertFalse(context.path("allProjects").asBoolean());
        assertEquals(55, context.path("projectCount").asInt());
        assertEquals(50, context.path("projects").size());
        assertFalse(context.path("projectsComplete").asBoolean());
        assertEquals("P1", context.path("projects").get(0).path("key").asText());
        assertEquals("P50", context.path("projects").get(49).path("key").asText());
        JsonNode detail = f.readProject("123", "P55", 2L, false).path("contexts").get(0);
        assertEquals(55, detail.path("projects").size());
        assertTrue(detail.path("projectsComplete").asBoolean());
        assertEquals("P55", detail.path("projects").get(54).path("key").asText());
    }

    @Test
    public void projectReadKeepsEveryMatchingContextAndItsDifferentIssueTypeMappings() throws Exception {
        Fixture f = new Fixture("textfield");
        FieldConfigScheme another = mock(FieldConfigScheme.class);
        FieldConfig otherConfig = config(92);
        when(another.getId()).thenReturn(3L);
        doReturn(Collections.singletonList(project(1))).when(another).getAssociatedProjectObjects();
        doReturn(Collections.singletonList(issueType("20"))).when(another).getAssociatedIssueTypes();
        when(another.getConfigs()).thenReturn(Collections.singletonMap("20", otherConfig));
        when(f.field.getConfigurationSchemes()).thenReturn(Arrays.asList(another, f.specific));
        doReturn(project(1)).when(f.projectManager).getProjectByCurrentKeyIgnoreCase("P1");

        JsonNode contexts = f.readProject("123", "P1", null, false).path("contexts");

        assertEquals(2, contexts.size());
        assertEquals(2L, contexts.get(0).path("contextId").asLong());
        assertEquals("10", contexts.get(0).path("issueTypes").get(0).path("id").asText());
        assertEquals(91L, contexts.get(0).path("configs").get(0).path("configId").asLong());
        assertEquals("10", contexts.get(0).path("configs").get(0).path("issueTypeIds").get(0).asText());
        assertEquals(3L, contexts.get(1).path("contextId").asLong());
        assertEquals("20", contexts.get(1).path("issueTypes").get(0).path("id").asText());
        assertEquals(92L, contexts.get(1).path("configs").get(0).path("configId").asLong());
        assertEquals("20", contexts.get(1).path("configs").get(0).path("issueTypeIds").get(0).asText());
    }

    @Test
    public void noProjectMatchReturnsReadWithAnEmptyListAndDoesNotObserveExcludedConfigs() throws Exception {
        Fixture f = new Fixture("select");
        when(f.field.getConfigurationSchemes()).thenReturn(Collections.singletonList(f.specific));
        doReturn(project(2)).when(f.projectManager).getProjectByCurrentKeyIgnoreCase("P2");

        JsonNode result = f.readProject("123", "P2", null, false);

        assertEquals("READ", result.path("status").asText());
        assertEquals("customfield_123", result.path("field").path("id").asText());
        assertEquals(0, result.path("contexts").size());
        verifyNoInteractions(f.options, f.generic, f.assets, f.files);
    }

    @Test
    public void projectAndContextIdIntersectWithoutConfusingNotApplicableWithForeignContext() throws Exception {
        Fixture f = new Fixture("select");
        doReturn(project(2)).when(f.projectManager).getProjectByCurrentKeyIgnoreCase("P2");

        JsonNode excluded = f.readProject("123", "P2", 2L, true);

        assertEquals("READ", excluded.path("status").asText());
        assertEquals(0, excluded.path("contexts").size());
        verifyNoInteractions(f.options, f.generic, f.assets, f.files);
        assertEquals("UNKNOWN_CONTEXT", f.readProject("123", "P2", 99L, false).path("errorCode").asText());
        JsonNode global = f.readProject("123", "P2", 1L, false).path("contexts");
        assertEquals(1, global.size());
        assertEquals(1L, global.get(0).path("contextId").asLong());
    }

    @Test
    public void projectUsesExactNameLookupAndRefusesUnknownBlankArchivedAndAmbiguousProjects() throws Exception {
        Fixture f = new Fixture("textfield");
        Project first = project(1);
        when(f.projectManager.getProjects()).thenReturn(Collections.singletonList(first));
        JsonNode byName = f.readProject("123", "project 1", 2L, false);
        assertEquals("READ", byName.path("status").asText());
        assertEquals(2L, byName.path("contexts").get(0).path("contextId").asLong());
        JsonNode unknown = f.readProject("123", "missing", null, false);
        assertEquals("FAILED", unknown.path("status").asText());
        assertEquals("UNKNOWN_PROJECT", unknown.path("errorCode").asText());
        assertEquals(0, unknown.path("contexts").size());
        assertEquals("VALIDATION_ERROR", f.readProject("123", " ", null, false).path("errorCode").asText());
        Project archived = project(9);
        when(archived.isArchived()).thenReturn(true);
        when(f.projectManager.getProjectByCurrentKeyIgnoreCase("P9")).thenReturn(archived);
        assertEquals("VALIDATION_ERROR", f.readProject("123", "P9", null, false).path("errorCode").asText());
        Project duplicate = project(2);
        when(duplicate.getName()).thenReturn("Project 1");
        when(f.projectManager.getProjects()).thenReturn(Arrays.asList(duplicate, first));
        JsonNode ambiguous = f.readProject("123", "Project 1", null, false);
        assertEquals("FAILED", ambiguous.path("status").asText());
        assertEquals("VALIDATION_ERROR", ambiguous.path("errorCode").asText());
        assertEquals(0, ambiguous.path("contexts").size());
        assertEquals(JSON.readTree("[\"P1\",\"P2\"]"), ambiguous.path("suggestions"));
    }

    @Test
    public void projectDoesNotReplaceContextIdForOptionsAndDetailedOptionsRemainOptIn() throws Exception {
        Fixture f = new Fixture("select");
        doReturn(project(1)).when(f.projectManager).getProjectByCurrentKeyIgnoreCase("P1");
        JsonNode refused = f.readProject("123", "P1", null, true);
        assertEquals("VALIDATION_ERROR", refused.path("errorCode").asText());
        verifyNoInteractions(f.options, f.projectManager);
        f.option(f.config, 1, "A", 0, false);
        JsonNode summary = f.readProject("123", "P1", 2L, false).path("contexts").get(0);
        assertFalse(summary.has("optionList"));
        assertEquals(1, summary.path("options").path("count").asInt());
        JsonNode detailed = f.readProject("123", "P1", 2L, true).path("contexts").get(0);
        assertEquals("A", detailed.path("optionList").path("items").get(0).path("value").asText());
        assertEquals(1L, detailed.path("optionList").path("items").get(0).path("id").asLong());
    }

    private static FieldConfig config(long id) {
        FieldConfig config = mock(FieldConfig.class);
        when(config.getId()).thenReturn(id);
        return config;
    }

    private static Project project(long id) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getKey()).thenReturn("P" + id);
        when(project.getName()).thenReturn("Project " + id);
        return project;
    }

    private static IssueType issueType(String id) {
        IssueType type = mock(IssueType.class);
        when(type.getId()).thenReturn(id);
        when(type.getName()).thenReturn("Type " + id);
        return type;
    }

    private static ManagedConfigurationItem managedItem(boolean managed, ConfigurationItemAccessLevel level) {
        ManagedConfigurationItem item = mock(ManagedConfigurationItem.class);
        when(item.isManaged()).thenReturn(managed);
        when(item.getConfigurationItemAccessLevel()).thenReturn(level);
        return item;
    }

    private static final class Fixture {
        final AdministratorGuard guard = mock(AdministratorGuard.class);
        final ApplicationUser admin = mock(ApplicationUser.class);
        final CustomFieldManager fields = mock(CustomFieldManager.class);
        final ProjectManager projectManager = mock(ProjectManager.class);
        final ManagedConfigurationItemService managed = mock(ManagedConfigurationItemService.class);
        final OptionsManager options = mock(OptionsManager.class);
        final GenericConfigManager generic = mock(GenericConfigManager.class);
        final AssetsFieldConfiguration assets = mock(AssetsFieldConfiguration.class);
        final FileFieldConfiguration files = mock(FileFieldConfiguration.class);
        final CustomField field = mock(CustomField.class);
        final CustomFieldType type;
        final FieldConfig config = config(91);
        final FieldConfigScheme global = mock(FieldConfigScheme.class);
        final FieldConfigScheme specific = mock(FieldConfigScheme.class);
        final Map<FieldConfig, List<Option>> optionsByConfig = new HashMap<>();
        final FieldContextReaderImpl reader;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Fixture(String suffix) {
            String key = suffix.contains(":") ? suffix : TypeKeys.SYSTEM_PLUGIN + ":" + suffix;
            boolean optionType = Arrays.asList("select", "cascadingselect").contains(suffix);
            type = mock(CustomFieldType.class, optionType
                    ? withSettings().extraInterfaces(MultipleSettableCustomFieldType.class) : withSettings());
            when(type.getKey()).thenReturn(key);
            when(type.getName()).thenReturn("Type " + suffix);
            when(guard.requireAdministrator()).thenReturn(admin);
            when(field.getId()).thenReturn("customfield_123");
            when(field.getName()).thenReturn("Severity");
            when(field.getCustomFieldType()).thenReturn(type);
            when(fields.getCustomFieldObject("customfield_123")).thenReturn(field);
            when(fields.getCustomFieldObjectsByNameIgnoreCase("Severity")).thenReturn(Collections.singletonList(field));
            when(global.getId()).thenReturn(1L);
            when(global.getName()).thenReturn("Global");
            when(global.isAllProjects()).thenReturn(true);
            when(global.isAllIssueTypes()).thenReturn(true);
            when(global.getConfigs()).thenReturn(Collections.singletonMap(null, config));
            when(specific.getId()).thenReturn(2L);
            when(specific.getName()).thenReturn("Specific");
            when(specific.getDescription()).thenReturn("Scope");
            when(specific.getConfigs()).thenReturn(Collections.singletonMap("10", config));
            doReturn(Collections.singletonList(project(1))).when(specific).getAssociatedProjectObjects();
            doReturn(Collections.singletonList(issueType("10"))).when(specific).getAssociatedIssueTypes();
            when(field.getConfigurationSchemes()).thenReturn(Arrays.asList(global, specific));
            when(options.getOptions(any(FieldConfig.class))).thenAnswer(call -> {
                List<Option> values = optionsByConfig.getOrDefault(call.getArgument(0), Collections.<Option>emptyList());
                Options result = mock(Options.class);
                when(result.iterator()).thenAnswer(ignored -> values.iterator());
                return result;
            });
            reader = new FieldContextReaderImpl(guard, new FieldSelector(fields), new ProjectResolver(projectManager), managed, options, generic,
                    assets, files);
        }

        /** The stored "DefaultValue" row of one config, as Jira's generic configuration table holds it. */
        void stored(String configId, Object row) {
            when(generic.retrieve("DefaultValue", configId)).thenReturn(row);
        }

        Option option(FieldConfig config, long id, boolean disabled) {
            Option option = mock(Option.class);
            when(option.getOptionId()).thenReturn(id);
            when(option.getDisabled()).thenReturn(disabled);
            optionsByConfig.computeIfAbsent(config, ignored -> new ArrayList<>()).add(option);
            return option;
        }

        /** A top-level option with a value, a stored sequence and (cascading select) its children. */
        Option option(FieldConfig config, long id, String value, long sequence, boolean disabled, Option... children) {
            Option option = child(id, value, sequence, disabled);
            when(option.getChildOptions()).thenReturn(new ArrayList<>(Arrays.asList(children)));
            optionsByConfig.computeIfAbsent(config, ignored -> new ArrayList<>()).add(option);
            return option;
        }

        Option child(long id, String value, long sequence, boolean disabled) {
            Option option = mock(Option.class);
            when(option.getOptionId()).thenReturn(id);
            when(option.getValue()).thenReturn(value);
            when(option.getSequence()).thenReturn(sequence);
            when(option.getDisabled()).thenReturn(disabled);
            return option;
        }

        JsonNode read(Long contextId) throws Exception {
            return read("customfield_123", contextId, false);
        }

        JsonNode read(Long contextId, boolean includeOptions) throws Exception {
            return read("customfield_123", contextId, includeOptions);
        }

        JsonNode read(String selector, Long contextId) throws Exception {
            return read(selector, contextId, false);
        }

        JsonNode read(String selector, Long contextId, boolean includeOptions) throws Exception {
            return readProject(selector, null, contextId, includeOptions);
        }

        JsonNode readProject(String selector, String project, Long contextId, boolean includeOptions) throws Exception {
            return JSON.readTree(JSON.writeValueAsString(reader.read(selector, project, contextId, includeOptions)));
        }
    }
}
