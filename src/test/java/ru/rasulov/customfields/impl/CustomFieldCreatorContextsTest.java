package ru.rasulov.customfields.impl;

import com.atlassian.jira.issue.context.GlobalIssueContext;
import com.atlassian.jira.issue.context.JiraContextNode;
import com.atlassian.jira.issue.context.ProjectContext;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.FieldConfigSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;
import ru.rasulov.common.api.dto.field.context.ContextSummary;
import ru.rasulov.customfields.impl.context.CreatedFieldReadback;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.customfields.impl.validation.OptionsValidator;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.SYSTEM;
import static ru.rasulov.customfields.impl.JiraMocks.TEXTFIELD;
import static ru.rasulov.customfields.impl.JiraMocks.TEXT_SEARCHER;
import static ru.rasulov.customfields.impl.JiraMocks.field;
import static ru.rasulov.customfields.impl.JiraMocks.issueType;
import static ru.rasulov.customfields.impl.JiraMocks.project;
import static ru.rasulov.customfields.impl.JiraMocks.searcher;
import static ru.rasulov.customfields.impl.JiraMocks.type;

/** Context scope, names, options, and failure propagation into durable recovery. */
@SuppressWarnings({"unchecked", "rawtypes"})
public class CustomFieldCreatorContextsTest {
    @org.junit.After
    public void closeUndoDatabases() { JiraMocks.closeJournals(); }

    private JiraMocks.Wiring w;
    private IssueType bug;
    private IssueType task;
    private IssueType subtask;
    private Project pay;
    private Project shop;
    private CustomField created;
    private CustomFieldType<?, ?> textfield;
    private CustomFieldType<?, ?> select;
    private CustomFieldSearcher textSearcher;

    @Before
    public void setUp() throws Exception {
        w = new JiraMocks.Wiring();
        bug = issueType("10004", "Bug", false);
        task = issueType("10002", "Task", false);
        subtask = issueType("10003", "Sub-task", true);
        pay = project(1, "PAY", "Payments", bug, task);
        shop = project(2, "SHOP", "Webshop", bug);
        Project ops = project(3, "OPS", "Operations", bug, task);
        when(w.projectManager.getProjects()).thenReturn(Arrays.asList(pay, shop, ops));
        when(w.projectManager.getProjectByCurrentKeyIgnoreCase("PAY")).thenReturn(pay);
        when(w.projectManager.getProjectByCurrentKeyIgnoreCase("SHOP")).thenReturn(shop);
        when(w.projectManager.getProjectByCurrentKeyIgnoreCase("OPS")).thenReturn(ops);
        when(w.issueTypeManager.getIssueTypes()).thenReturn(Arrays.asList(bug, task, subtask));

        textfield = type(CustomFieldType.class, TEXTFIELD, "Text Field (single line)");
        select = type(MultipleSettableCustomFieldType.class, SYSTEM + "select", "Select List");
        textSearcher = searcher(TEXT_SEARCHER, TEXTFIELD);
        when(w.manager.getCustomFieldType(TEXTFIELD)).thenReturn((CustomFieldType) textfield);
        when(w.manager.getCustomFieldType(SYSTEM + "select")).thenReturn((CustomFieldType) select);
        when(w.manager.getDefaultSearcher((CustomFieldType) textfield)).thenReturn(textSearcher);
        when(w.manager.getDefaultSearcher((CustomFieldType) select)).thenReturn(textSearcher);

        created = JiraMocks.createdField("customfield_10800");
        when(w.manager.createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList())).thenReturn(created);
    }

    // --- what is handed to Jira --------------------------------------------------------------------------------

    @Test
    public void projectsAndIssueTypesBecomeProjectContextsAndIssueTypesForTheNewField() throws Exception {
        FieldCreationResult result = create("textfield", ctx(false, list("PAY", "SHOP"), false, list("Bug", "Sub-task")));

        assertEquals(CreationStatus.CREATED, result.getStatus());
        ArgumentCaptor<List> nodes = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List> issueTypes = ArgumentCaptor.forClass(List.class);
        verify(w.manager).createCustomField(eq("Field"), eq(""), eq(textfield), eq(textSearcher), nodes.capture(),
                issueTypes.capture());
        assertEquals(2, nodes.getValue().size());
        for (Object node : nodes.getValue()) {
            assertEquals(ProjectContext.class, node.getClass());
        }
        assertEquals(Arrays.asList(1L, 2L), projectIds(nodes.getValue()));
        assertEquals(Arrays.asList(bug, subtask), issueTypes.getValue());
    }

    @Test
    public void allProjectsAndAllIssueTypesBecomeTheGlobalContextAndJirasAllTypesMarker() throws Exception {
        create("textfield", ctx(true, none(), true, none()));

        ArgumentCaptor<List> nodes = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List> issueTypes = ArgumentCaptor.forClass(List.class);
        verify(w.manager).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), nodes.capture(), issueTypes.capture());
        assertEquals(1, nodes.getValue().size());
        assertSame(GlobalIssueContext.getInstance(), nodes.getValue().get(0));
        assertSame(FieldConfigSchemeManager.ALL_ISSUE_TYPES, issueTypes.getValue());
    }


    // --- no context, no field ----------------------------------------------------------------------------------

    @Test
    public void aFieldWithoutContextsIsNeverCreatedNorPreviewed() throws Exception {
        FieldCreationResult created = createWithContexts("textfield", false, Collections.<ContextDefinition>emptyList());
        FieldCreationResult previewed = createWithContexts("textfield", true, Collections.<ContextDefinition>emptyList());

        assertEquals(CustomFieldErrorCode.CONTEXT_REQUIRED, created.getErrorCode());
        assertEquals(CustomFieldErrorCode.CONTEXT_REQUIRED, previewed.getErrorCode());
        verify(w.manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    @Test
    public void anUnknownProjectFailsTheFieldBeforeAnythingIsCreated() throws Exception {
        FieldCreationResult result = create("textfield", ctx(false, list("NOPE"), true, none()));

        assertEquals(CustomFieldErrorCode.UNKNOWN_PROJECT, result.getErrorCode());
        verify(w.manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    // --- several contexts --------------------------------------------------------------------------------------

    @Test
    public void everyContextAfterTheFirstIsCreatedAsItsOwnConfigurationScheme() throws Exception {
        FieldConfigScheme second = mock(FieldConfigScheme.class);
        when(second.getOneAndOnlyConfig()).thenReturn(mock(FieldConfig.class));
        when(w.schemeFactory.create(eq("customfield_10800"), anyString())).thenReturn(second);
        when(w.schemeManager.createFieldConfigScheme(any(FieldConfigScheme.class), anyList(), anyList(),
                any(CustomField.class))).thenReturn(second);

        FieldCreationResult result = create("textfield",
                ctx(false, list("PAY"), true, none()), ctx(false, list("SHOP"), true, none()));

        assertEquals(CreationStatus.CREATED, result.getStatus());
        assertEquals(2, result.getContexts().size());
        ArgumentCaptor<List> nodes = ArgumentCaptor.forClass(List.class);
        verify(w.schemeFactory).create("customfield_10800", "SHOP");
        verify(w.schemeManager).createFieldConfigScheme(eq(second), nodes.capture(), any(List.class), eq(created));
        assertEquals(Collections.singletonList(2L), projectIds(nodes.getValue()));
    }

    @Test
    public void everyContextGetsItsOwnOptionsAndTheFieldsOptionsFillTheRest() throws Exception {
        FieldConfig secondConfig = mock(FieldConfig.class);
        when(secondConfig.getCustomField()).thenReturn(created);
        when(secondConfig.getFieldId()).thenReturn("customfield_10800");
        when(secondConfig.getId()).thenReturn(108801L);
        FieldConfigScheme second = mock(FieldConfigScheme.class);
        when(second.getOneAndOnlyConfig()).thenReturn(secondConfig);
        when(w.schemeFactory.create(anyString(), anyString())).thenReturn(second);
        when(w.schemeManager.createFieldConfigScheme(any(FieldConfigScheme.class), anyList(), anyList(),
                any(CustomField.class))).thenReturn(second);
        FieldConfig firstConfig = created.getConfigurationSchemes().get(0).getOneAndOnlyConfig();
        when(w.optionsManager.createOption(any(FieldConfig.class), any(), any(), anyString()))
                .thenReturn(mock(Option.class));

        FieldCreationResult result = createSelect(Arrays.asList(new OptionDefinition("Shared", null)),
                new ContextDefinition(null, false, list("PAY"), true, null,
                        Arrays.asList(new OptionDefinition("Card", null), new OptionDefinition("Wire", null))),
                ctx(false, list("SHOP"), true, none()));

        assertEquals(CreationStatus.CREATED, result.getStatus());
        verify(w.optionsManager).createOption(firstConfig, null, 0L, "Card");
        verify(w.optionsManager).createOption(firstConfig, null, 1L, "Wire");
        verify(w.optionsManager).createOption(secondConfig, null, 0L, "Shared");
        verify(w.optionsManager, never()).createOption(firstConfig, null, 0L, "Shared");
    }

    @Test(expected = IllegalStateException.class)
    public void uncertainSecondContextEscapesForDurableRecovery() throws Exception {
        when(w.schemeFactory.create(anyString(), anyString())).thenReturn(mock(FieldConfigScheme.class));
        when(w.schemeManager.createFieldConfigScheme(any(FieldConfigScheme.class), anyList(), anyList(),
                any(CustomField.class))).thenThrow(new IllegalStateException("db down"));
        create("textfield", ctx(false, list("PAY"), true, none()), ctx(false, list("SHOP"), true, none()));
    }

    // --- one project, one project-scoped context ----------------------------------------------------------------

    @Test
    public void oneProjectInTwoContextsIsRefusedBeforeAPlanIsIssuedAndNothingIsWritten() throws Exception {
        CreateCustomFieldsResponse preview = w.creator.createAll(new CreateCustomFieldsRequest(true,
                Collections.singletonList(new FieldDefinition("Field", null, "textfield", null, null,
                        Arrays.asList(ctx(false, list("PAY"), false, list("Bug")),
                                ctx(false, list("Payments"), false, list("Task"))), null))));

        FieldCreationResult result = preview.getResults().get(0);
        assertEquals(CreationStatus.FAILED, result.getStatus());
        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, result.getErrorCode());
        assertTrue(result.getMessage(), result.getMessage().contains("Project PAY"));
        assertNull(preview.getPlanId());
        verify(w.manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    // --- the same final-state policy as create_field_context and update_field_contexts ---------------------------

    @Test
    public void aSecondGlobalContextIsRefusedBeforeAPlanAndNothingIsWritten() throws Exception {
        CreateCustomFieldsResponse preview = previewOf(
                ctx(true, none(), false, list("Bug")), ctx(true, none(), false, list("Task")));

        assertEquals(CreationStatus.FAILED, preview.getResults().get(0).getStatus());
        assertNull(preview.getPlanId());
        verify(w.manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    @Test
    public void oneGlobalAndAProjectContextWithOverlappingTypesArePlannedWithAWarning() throws Exception {
        CreateCustomFieldsResponse preview = previewOf(
                ctx(true, none(), false, list("Bug")), ctx(false, list("PAY"), false, list("Bug", "Task")));

        FieldCreationResult result = preview.getResults().get(0);
        assertEquals(result.getMessage(), CreationStatus.WOULD_CREATE, result.getStatus());
        assertNotNull(preview.getPlanId());
        assertNotNull("coexistence is not a pure addition: the user must be warned", result.getMessage());
        assertEquals(2, result.getContexts().size());
    }

    @Test
    public void disjointProjectsArePlannedWithoutWarnings() throws Exception {
        CreateCustomFieldsResponse preview = previewOf(
                ctx(false, list("PAY"), false, list("Bug")), ctx(false, list("SHOP"), false, list("Bug")));

        FieldCreationResult result = preview.getResults().get(0);
        assertEquals(result.getMessage(), CreationStatus.WOULD_CREATE, result.getStatus());
        assertNotNull(preview.getPlanId());
        assertNull(result.getMessage());
    }

    // --- what Jira really stored: the production readback, only the Jira API is faked ----------------------------

    @Test
    public void storedScopeThatDiffersFromThePlanIsPartialWithTheRealStateAndNoCleanup() throws Exception {
        FieldConfigScheme stored = created.getConfigurationSchemes().get(0);
        when(stored.isAllProjects()).thenReturn(false);
        when(stored.getAssociatedProjectObjects()).thenReturn(Collections.singletonList(shop));
        when(stored.isAllIssueTypes()).thenReturn(false);
        when(stored.getAssociatedIssueTypes()).thenReturn(Collections.singletonList(task));

        FieldCreationResult result = createReal(null, ctx(false, list("PAY"), false, list("Bug")));

        assertEquals(result.getMessage(), CreationStatus.PARTIAL, result.getStatus());
        assertEquals(CustomFieldErrorCode.CONTEXT_MISMATCH, result.getErrorCode());
        assertEquals("customfield_10800", result.getCustomFieldId());
        assertEquals(1, result.getContexts().size());
        assertEquals("SHOP", result.getContexts().get(0).getProjects().get(0).getKey());
        assertEquals("10002", result.getContexts().get(0).getIssueTypes().get(0).getId());
        assertTrue(result.getMessage(), result.getMessage().contains("PAY"));
        assertTrue(result.getMessage(), result.getMessage().contains("Bug (10004)"));
        assertTrue(result.getMessage(), result.getMessage().contains("Task (10002)"));
        assertNothingRetriedOrCleanedUp(result);
    }

    @Test
    public void storedOptionsThatDifferFromThePlanArePartialWithTheRealOptions() throws Exception {
        FieldConfigScheme stored = created.getConfigurationSchemes().get(0);
        when(stored.isAllProjects()).thenReturn(false);
        when(stored.getAssociatedProjectObjects()).thenReturn(Collections.singletonList(pay));
        when(stored.isAllIssueTypes()).thenReturn(true);
        FieldConfig config = stored.getOneAndOnlyConfig();
        when(w.optionsManager.createOption(any(FieldConfig.class), any(), any(), anyString()))
                .thenReturn(mock(Option.class));
        Option low = mock(Option.class);
        when(low.getValue()).thenReturn("Low");
        com.atlassian.jira.issue.customfields.option.Options stoppedAfterLow =
                mock(com.atlassian.jira.issue.customfields.option.Options.class);
        when(stoppedAfterLow.getRootOptions()).thenReturn(Collections.singletonList(low));
        when(w.optionsManager.getOptions(config)).thenReturn(stoppedAfterLow);

        FieldCreationResult result = createReal(Arrays.asList(new OptionDefinition("Low", null),
                new OptionDefinition("High", null)), ctx(false, list("PAY"), true, none()));

        assertEquals(result.getMessage(), CreationStatus.PARTIAL, result.getStatus());
        assertEquals(CustomFieldErrorCode.CONTEXT_MISMATCH, result.getErrorCode());
        assertEquals("customfield_10800", result.getCustomFieldId());
        assertTrue(result.getMessage(), result.getMessage().contains("[Low, High]"));
        assertTrue(result.getMessage(), result.getMessage().contains("[Low]"));
        assertNothingRetriedOrCleanedUp(result);
    }

    @Test
    public void anUnreadableJiraAfterCreationIsNeitherCreatedNorNothingChanged() throws Exception {
        when(w.optionsManager.getOptions(any(FieldConfig.class))).thenThrow(new IllegalStateException("cache down"));

        FieldCreationResult result = createReal(null, ctx(true, none(), true, none()));

        assertEquals(result.getMessage(), CreationStatus.PARTIAL, result.getStatus());
        assertEquals(CustomFieldErrorCode.CONTEXT_MISMATCH, result.getErrorCode());
        assertEquals("customfield_10800", result.getCustomFieldId());
        assertTrue(result.getMessage(), result.getMessage().contains("WAS created"));
        assertTrue(result.getMessage(), result.getMessage().contains("cache down"));
        assertNothingRetriedOrCleanedUp(result);
    }

    private CreateCustomFieldsResponse previewOf(ContextDefinition... contexts) {
        return w.creator.createAll(new CreateCustomFieldsRequest(true, Collections.singletonList(
                new FieldDefinition("Field", null, "textfield", null, null, Arrays.asList(contexts), null))));
    }

    /** Applies through a creator wired with the production {@link CreatedFieldReadback}. */
    private FieldCreationResult createReal(List<OptionDefinition> options, ContextDefinition... contexts) {
        when(w.manager.getCustomFieldType(SYSTEM + "select")).thenReturn((CustomFieldType) select);
        return JiraMocks.previewAndApply(w.creators.apply(w.realReadback()), new CreateCustomFieldsRequest(true,
                Collections.singletonList(new FieldDefinition("Field", null, options == null ? "textfield" : "select",
                        null, options, Arrays.asList(contexts), null)))).getResults().get(0);
    }

    /** One native create, no removal, and no claim that anything was rolled back. */
    private void assertNothingRetriedOrCleanedUp(FieldCreationResult result) throws Exception {
        verify(w.manager, times(1)).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
        verify(w.manager, never()).removeCustomField(any(CustomField.class));
        verify(w.schemeManager, never()).removeFieldConfigScheme(anyLong());
        String message = result.getMessage().toLowerCase();
        assertFalse(message, message.contains("rolled back") || message.contains("rollback ")
                || message.contains("was removed") || message.contains("was deleted"));
    }

    // --- echo of the contexts ----------------------------------------------------------------------------------

    @Test
    public void thePreviewNamesTheProjectsAndIssueTypesItUnderstood() throws Exception {
        FieldCreationResult result = createWithContexts("textfield", true,
                Collections.singletonList(ctx(false, list("PAY"), false, list("bug", "Sub-task"))));

        assertEquals(CreationStatus.WOULD_CREATE, result.getStatus());
        ContextSummary summary = result.getContexts().get(0);
        assertEquals("PAY", summary.getName());
        assertEquals(false, summary.isAllProjects());
        assertEquals("PAY", summary.getProjects().get(0).getKey());
        assertEquals("Payments", summary.getProjects().get(0).getName());
        assertEquals("10004", summary.getIssueTypes().get(0).getId());
        assertEquals("Sub-task", summary.getIssueTypes().get(1).getName());
        verify(w.manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
    }

    @Test
    public void aWarningRidesAlongWithASuccessfulResult() throws Exception {
        FieldCreationResult result = createWithContexts("textfield", true,
                Collections.singletonList(ctx(false, list("SHOP"), false, list("Task"))));

        assertEquals(CreationStatus.WOULD_CREATE, result.getStatus());
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage(), result.getMessage().contains("Task"));
    }

    @Test
    public void onlyTheContextWithoutOptionsIsNamedInTheEmptyOptionsWarning() throws Exception {
        FieldCreationResult result = createWithContexts("select", true, Arrays.asList(
                new ContextDefinition("Full", false, list("PAY"), true, null, Arrays.asList(new OptionDefinition("A", null))),
                new ContextDefinition("Empty", false, list("SHOP"), true, null, null)));

        assertEquals(CreationStatus.WOULD_CREATE, result.getStatus());
        assertTrue(result.getMessage(), result.getMessage().contains("'Empty'"));
        assertTrue(result.getMessage(), !result.getMessage().contains("'Full'"));
    }

    // --- existing field ----------------------------------------------------------------------------------------

    @Test
    public void anExistingFieldReportsItsRealContextsAndSaysWhenTheRequestedOnesWereNotApplied() throws Exception {
        CustomField existing = field("customfield_10150", "Field", textfield);
        JiraMocks.withContext(existing, "SHOP", false, Collections.singletonList(shop), true,
                Collections.<IssueType>emptyList());
        when(w.manager.getCustomFieldObjectsByNameIgnoreCase("Field")).thenReturn(Collections.singletonList(existing));

        FieldCreationResult result = createWithContexts("textfield", true,
                Collections.singletonList(ctx(false, list("PAY"), true, none())));

        assertEquals(CreationStatus.EXISTS, result.getStatus());
        assertEquals("SHOP", result.getContexts().get(0).getProjects().get(0).getKey());
        assertNotNull(result.getMessage());
        assertTrue(result.getMessage(), result.getMessage().contains("contexts"));
    }

    @Test
    public void anExistingFieldWithTheRequestedScopeHasNoContextWarningWhateverTheContextsAreCalled() throws Exception {
        CustomField existing = field("customfield_10150", "Field", textfield);
        JiraMocks.withContext(existing, "Some other name", false, Arrays.asList(shop, pay), true,
                Collections.<IssueType>emptyList());
        when(w.manager.getCustomFieldObjectsByNameIgnoreCase("Field")).thenReturn(Collections.singletonList(existing));

        FieldCreationResult result = createWithContexts("textfield", true,
                Collections.singletonList(ctx(false, list("PAY", "SHOP"), true, none())));

        assertEquals(CreationStatus.EXISTS, result.getStatus());
        assertNull(result.getMessage());
    }

    // --- options on contexts -----------------------------------------------------------------------------------

    @Test
    public void omittedContextOptionsInheritButExplicitEmptyOverridesSharedOptions() throws Exception {
        String json = "{\"name\":\"Field\",\"typeKey\":\"select\",\"options\":[{\"value\":\"Shared\"}],"
                + "\"contexts\":["
                + "{\"name\":\"Inherited\",\"projects\":[\"PAY\"],\"issueTypes\":[\"Bug\"]},"
                + "{\"name\":\"Empty\",\"projects\":[\"OPS\"],\"issueTypes\":[\"Task\"],\"options\":[]},"
                + "{\"name\":\"Local\",\"projects\":[\"SHOP\"],\"issueTypes\":[\"Bug\"],"
                + "\"options\":[{\"value\":\"Local\"}]}]}";
        FieldDefinition definition = new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, FieldDefinition.class);
        ResolvedFieldDefinition resolved = w.validator.validate(definition);
        List<List<String>> effective = new ArrayList<List<String>>();
        for (ru.rasulov.customfields.impl.context.ResolvedContext context : resolved.getContexts()) {
            List<String> values = new ArrayList<String>();
            for (OptionDefinition option : context.getOptions()) values.add(option.getValue());
            effective.add(values);
        }
        assertEquals(Arrays.asList(Collections.singletonList("Shared"), Collections.<String>emptyList(),
                Collections.singletonList("Local")), effective);
    }

    @Test
    public void optionsPresenceSurvivesCodecPreviewAndApplyIntoSeparateConfigs() {
        CreateCustomFieldsRequest request = inheritanceRequest();
        ActionPlanCodec codec = new ActionPlanCodec();
        CreateCustomFieldsRequest decoded = codec.mode(codec.request(request), true, CreateCustomFieldsRequest.class);
        List<ContextDefinition> contexts = decoded.getFields().get(0).getContexts();
        assertEquals(false, contexts.get(0).hasOptions());
        assertNull(contexts.get(0).getOptions());
        assertEquals(true, contexts.get(1).hasOptions());
        assertEquals(Collections.emptyList(), contexts.get(1).getOptions());
        assertEquals("Local", contexts.get(2).getOptions().get(0).getValue());
        assertEquals(2, OptionsValidator.countOptions(decoded.getFields()));

        FieldConfig inherited = created.getConfigurationSchemes().get(0).getOneAndOnlyConfig();
        FieldConfigScheme empty = optionScheme(108801L);
        FieldConfigScheme local = optionScheme(108802L);
        when(w.schemeFactory.create(anyString(), anyString())).thenReturn(empty, local);
        when(w.schemeManager.createFieldConfigScheme(any(FieldConfigScheme.class), anyList(), anyList(),
                any(CustomField.class))).thenAnswer(invocation -> invocation.getArgument(0));
        Map<Long, List<String>> persisted = new LinkedHashMap<Long, List<String>>();
        persisted.put(inherited.getId(), new ArrayList<String>());
        persisted.put(108801L, new ArrayList<String>());
        persisted.put(108802L, new ArrayList<String>());
        when(w.optionsManager.createOption(any(FieldConfig.class), any(), any(), anyString())).thenAnswer(invocation -> {
            FieldConfig config = invocation.getArgument(0);
            String value = invocation.getArgument(3);
            persisted.get(config.getId()).add(value);
            Option option = mock(Option.class);
            Long optionId = config.getId();
            when(option.getOptionId()).thenReturn(optionId);
            when(option.getValue()).thenReturn(value);
            return option;
        });

        CreateCustomFieldsResponse preview = w.creator.createAll(decoded);
        assertEquals(CreationStatus.WOULD_CREATE, preview.getResults().get(0).getStatus());
        assertNotNull(preview.getPlanId());
        assertEquals(Arrays.asList(Collections.emptyList(), Collections.emptyList(), Collections.emptyList()),
                new ArrayList<List<String>>(persisted.values()));
        CreateCustomFieldsResponse applied = w.creator.createAll(
                new CreateCustomFieldsRequest(false, null, preview.getPlanId()));
        assertEquals(CreationStatus.CREATED, applied.getResults().get(0).getStatus());
        assertEquals(Arrays.asList(Collections.singletonList("Shared"), Collections.<String>emptyList(),
                Collections.singletonList("Local")), new ArrayList<List<String>>(persisted.values()));
    }

    @Test
    public void explicitEmptyContextsDoNotConsumeSharedOptionsAtTheCallBoundary() throws Exception {
        CustomFieldType cascading = type(MultipleSettableCustomFieldType.class, SYSTEM + "cascadingselect",
                "Cascading select");
        when(w.manager.getCustomFieldType(SYSTEM + "cascadingselect")).thenReturn(cascading);
        List<OptionDefinition> shared = new ArrayList<OptionDefinition>();
        for (int parent = 0; parent < 5; parent++) {
            List<OptionDefinition> children = new ArrayList<OptionDefinition>();
            for (int child = 0; child < 99; child++) children.add(new OptionDefinition("Child " + child, null));
            shared.add(new OptionDefinition("Parent " + parent, children));
        }
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        List<ContextDefinition> contexts = Arrays.asList(
                new ContextDefinition("Inherited", false, list("PAY"), false, list("Bug"), null),
                new ContextDefinition("Empty", false, list("OPS"), false, list("Task"), Collections.emptyList()));
        for (int i = 0; i < 4; i++)
            fields.add(new FieldDefinition("Field " + i, null, "cascadingselect", null, shared, contexts, null));
        assertEquals(2000, OptionsValidator.countOptions(fields));
        CreateCustomFieldsResponse preview = w.creator.createAll(new CreateCustomFieldsRequest(true, fields));
        for (FieldCreationResult result : preview.getResults()) assertEquals(CreationStatus.WOULD_CREATE, result.getStatus());
        fields.add(new FieldDefinition("One more", null, "select", null,
                Collections.singletonList(new OptionDefinition("Extra", null)), JiraMocks.GLOBAL, null));
        assertEquals(2001, OptionsValidator.countOptions(fields));
        try {
            w.creator.createAll(new CreateCustomFieldsRequest(true, fields));
            org.junit.Assert.fail("2001 effective options must be rejected before writes");
        } catch (ru.rasulov.common.api.exception.InvalidRequestException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("2001"));
        }
        verify(w.manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
        org.mockito.Mockito.verifyNoInteractions(w.optionsManager);
    }

    @Test
    public void explicitEmptyOverrideDoesNotChangeAnExistingFieldsContextsOrOptions() throws Exception {
        CustomField existing = field("customfield_10900", "Field", select);
        JiraMocks.withContext(existing, "Actual", true, Collections.emptyList(), true, Collections.emptyList());
        List<FieldConfigScheme> originalSchemes = existing.getConfigurationSchemes();
        when(w.manager.getCustomFieldObjectsByNameIgnoreCase("Field")).thenReturn(Collections.singletonList(existing));
        FieldCreationResult result = w.creator.createAll(inheritanceRequest()).getResults().get(0);
        assertEquals(CreationStatus.EXISTS, result.getStatus());
        assertEquals("customfield_10900", result.getCustomFieldId());
        assertEquals("Actual", result.getContexts().get(0).getName());
        assertSame(originalSchemes, existing.getConfigurationSchemes());
        verify(w.manager, never()).createCustomField(anyString(), anyString(), any(CustomFieldType.class),
                any(CustomFieldSearcher.class), anyList(), anyList());
        org.mockito.Mockito.verifyNoInteractions(w.optionsManager);
    }

    private CreateCustomFieldsRequest inheritanceRequest() {
        return new CreateCustomFieldsRequest(true, Collections.singletonList(
                new FieldDefinition("Field", null, "select", null,
                        Collections.singletonList(new OptionDefinition("Shared", null)), Arrays.asList(
                        new ContextDefinition("Inherited", false, list("PAY"), false, list("Bug"), null),
                        new ContextDefinition("Empty", false, list("OPS"), false, list("Task"), Collections.emptyList()),
                        new ContextDefinition("Local", false, list("SHOP"), false, list("Bug"),
                                Collections.singletonList(new OptionDefinition("Local", null)))), null)));
    }

    private FieldConfigScheme optionScheme(long configId) {
        FieldConfig config = mock(FieldConfig.class);
        when(config.getId()).thenReturn(configId);
        when(config.getCustomField()).thenReturn(created);
        String fieldId = created.getId();
        when(config.getFieldId()).thenReturn(fieldId);
        FieldConfigScheme scheme = mock(FieldConfigScheme.class);
        when(scheme.getId()).thenReturn(configId + 100000L);
        when(scheme.getOneAndOnlyConfig()).thenReturn(config);
        return scheme;
    }

    @Test
    public void optionsOfTheFieldAreRefusedWhenEveryContextHasItsOwn() throws Exception {
        FieldCreationResult result = createSelect(Arrays.asList(new OptionDefinition("Lost", null)),
                new ContextDefinition(null, false, list("PAY"), true, null,
                        Arrays.asList(new OptionDefinition("A", null))),
                new ContextDefinition(null, false, list("SHOP"), true, null,
                        Arrays.asList(new OptionDefinition("B", null))));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, result.getErrorCode());
    }

    @Test
    public void optionsInAContextOfATypeWithoutOptionsAreRefused() throws Exception {
        FieldCreationResult result = create("textfield", new ContextDefinition(null, false, list("PAY"), true, null,
                Arrays.asList(new OptionDefinition("A", null))));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, result.getErrorCode());
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private FieldCreationResult create(String typeKey, ContextDefinition... contexts) {
        return createWithContexts(typeKey, false, Arrays.asList(contexts));
    }

    private FieldCreationResult createWithContexts(String typeKey, boolean dryRun, List<ContextDefinition> contexts) {
        CreateCustomFieldsRequest request = new CreateCustomFieldsRequest(true, Collections.singletonList(
                new FieldDefinition("Field", null, typeKey, null, null, contexts, null)));
        return (dryRun ? w.creator.createAll(request) : JiraMocks.previewAndApply(w.creator, request))
                .getResults().get(0);
    }

    private FieldCreationResult createSelect(List<OptionDefinition> fieldOptions, ContextDefinition... contexts) {
        return JiraMocks.previewAndApply(w.creator, new CreateCustomFieldsRequest(true, Collections.singletonList(
                new FieldDefinition("Field", null, "select", null, fieldOptions, Arrays.asList(contexts), null))))
                .getResults().get(0);
    }

    private static List<Long> projectIds(List<JiraContextNode> nodes) {
        List<Long> ids = new ArrayList<Long>();
        for (JiraContextNode node : nodes) {
            ids.add(((ProjectContext) node).getProjectId());
        }
        return ids;
    }

    private static ContextDefinition ctx(boolean allProjects, List<String> projects, boolean allIssueTypes,
                                         List<String> issueTypes) {
        return new ContextDefinition(null, allProjects, projects, allIssueTypes, issueTypes, null);
    }

    private static List<String> list(String... values) {
        return Arrays.asList(values);
    }

    private static List<String> none() {
        return Collections.emptyList();
    }
}
