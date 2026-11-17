package ru.rasulov.customfields.impl.requirement;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.EditableDefaultFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.thread.JiraThreadLocalUtil;
import com.atlassian.plugin.PluginAccessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.applies.ApplyJournal;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.tools.ChangeTools;
import ru.rasulov.common.impl.tools.McpServer;
import ru.rasulov.common.impl.tools.OperationRunner;
import ru.rasulov.common.impl.tools.ToolCatalog;
import ru.rasulov.common.impl.tools.ToolRegistry;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.customfields.api.CustomFieldCreator;
import ru.rasulov.customfields.api.FieldRequirementService;
import ru.rasulov.customfields.api.FieldScreenService;
import ru.rasulov.customfields.impl.CustomFieldTools;
import ru.rasulov.customfields.impl.FieldJournalTestSupport;
import ru.rasulov.customfields.impl.JiraMocks;
import ru.rasulov.customfields.impl.configuration.ConfigurableFieldResolver;
import ru.rasulov.customfields.impl.configuration.FieldLayoutCopyFactory;
import ru.rasulov.customfields.impl.context.FieldSelector;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real MCP -> OperationRunner -> ChangeJournal (AO/HSQL) -> FieldRequirementServiceImpl chain; only Jira managers are
 * mocks. A required preview is refused while the field is not on the effective CREATE screen, a plan made after the
 * screen was fixed is stopped when the screen changes again, and a fresh preview/apply then succeeds.
 */
public class FieldRequirementMcpSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String FIELD = "customfield_10100";
    private static final String FIELD2 = "customfield_10200";

    @After
    public void close() { FieldJournalTestSupport.close(); JiraMocks.closeJournals(); }

    @Test
    public void requiredIsRefusedUntilTheFieldIsOnCreateThenPlansAndAppliesAndStaleWhenCreateChanges() throws Exception {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(1L);
        when(project.getKey()).thenReturn("PAY");
        when(project.getName()).thenReturn("Payments");
        IssueType task = mock(IssueType.class);
        when(task.getId()).thenReturn("10001");
        when(task.getName()).thenReturn("Task");
        when(project.getIssueTypes()).thenReturn(Collections.singletonList(task));
        ProjectResolver projects = mock(ProjectResolver.class);
        when(projects.resolve("PAY")).thenReturn(project);
        IssueTypeResolver issueTypes = mock(IssueTypeResolver.class);
        when(issueTypes.resolve("Task")).thenReturn(task);

        CustomField field = mock(CustomField.class);
        CustomFieldType<?, ?> type = mock(CustomFieldType.class);
        when(type.getKey()).thenReturn("select");
        when(type.getName()).thenReturn("Select List");
        when(field.getId()).thenReturn(FIELD);
        when(field.getName()).thenReturn("Severity");
        when(field.getCustomFieldType()).thenReturn(type);
        when(field.isInScope(eq(1L), anyString())).thenReturn(true);
        CustomFieldManager customFields = mock(CustomFieldManager.class);
        when(customFields.getCustomFieldObject(FIELD)).thenReturn(field);
        CustomField field2 = mock(CustomField.class);
        when(field2.getId()).thenReturn(FIELD2);
        when(field2.getName()).thenReturn("Impact");
        when(field2.getCustomFieldType()).thenReturn(type);
        when(field2.isInScope(eq(1L), anyString())).thenReturn(true);
        when(customFields.getCustomFieldObject(FIELD2)).thenReturn(field2);

        AtomicBoolean required = new AtomicBoolean();
        EditableDefaultFieldLayout layout = mock(EditableDefaultFieldLayout.class);
        FieldLayoutItem item = mock(FieldLayoutItem.class);
        FieldLayoutItem item2 = mock(FieldLayoutItem.class);
        when(item2.getOrderableField()).thenReturn(field2);
        when(item2.isRequired()).thenReturn(false);
        when(layout.getFieldLayoutItems()).thenReturn(Arrays.asList(item, item2));
        when(item.getOrderableField()).thenReturn(field);
        when(item.isRequired()).thenAnswer(call -> required.get());
        doAnswer(call -> { required.set(true); return null; }).when(layout).makeRequired(item);
        doAnswer(call -> { required.set(false); return null; }).when(layout).makeOptional(item);
        when(layout.isDefault()).thenReturn(true);
        when(layout.getName()).thenReturn("Default Field Configuration");
        when(layout.getFieldLayoutItem(FIELD)).thenReturn(item);
        when(layout.getFieldLayoutItem(FIELD2)).thenReturn(item2);
        FieldLayoutManager layouts = mock(FieldLayoutManager.class);
        when(layouts.getFieldLayout(project, "10001")).thenReturn(layout);
        when(layouts.getEditableDefaultFieldLayout()).thenReturn(layout);
        when(layouts.getProjectsUsing(layout)).thenReturn(Collections.singletonList(project));

        // CREATE has no field; the field is only on EDIT. The DEFAULT screen mapping is what CREATE falls to.
        RequirementScreens screens = new RequirementScreens();
        FieldScreen noField = RequirementScreens.screen(7L, "Default Screen", false);
        FieldScreen withField = RequirementScreens.screen(9L, "Create with Severity", false, FIELD);
        screens.defaultScreen(noField).edit(withField);

        JiraAuthenticationContext authentication = mock(JiraAuthenticationContext.class);
        GlobalPermissionManager permissions = mock(GlobalPermissionManager.class);
        ApplicationUser admin = mock(ApplicationUser.class);
        when(authentication.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
        when(admin.getKey()).thenReturn("admin-key");
        AdministratorGuard guard = new AdministratorGuard(authentication, permissions);
        ChangeJournal journal = FieldJournalTestSupport.journal("admin-key");
        ActionPlanStore plans = InMemoryActionPlans.create();
        FieldLayoutCopyFactory copies = mock(FieldLayoutCopyFactory.class);
        FieldManager systemFields = mock(FieldManager.class);
        OrderableField attachmentField = mock(OrderableField.class);
        when(attachmentField.getId()).thenReturn("attachment");
        when(attachmentField.getName()).thenReturn("Attachment");
        when(systemFields.isOrderableField("attachment")).thenReturn(true);
        when(systemFields.getOrderableField("attachment")).thenReturn(attachmentField);
        FieldRequirementService requirements = new FieldRequirementServiceImpl(guard,
                new NamedLock(JiraMocks.lockService(mock(ClusterLock.class))), projects, issueTypes,
                new ConfigurableFieldResolver(new FieldSelector(customFields), systemFields), layouts,
                screens.manager, new ru.rasulov.common.impl.PreviewPlanExecutor(plans, new ActionPlanCodec()), new ActionPlanCodec(), journal, copies);

        Semaphore finished = new Semaphore(0);
        int submitted = 0;
        OperationRunner runner = null;
        try {
            ToolCatalog catalog = new ToolCatalog(mock(PluginAccessor.class));
            ToolRegistry[] registry = new ToolRegistry[1];
            JiraThreadLocalUtil locals = mock(JiraThreadLocalUtil.class);
            doAnswer(call -> { finished.release(); return null; }).when(locals).postCall(any(org.slf4j.Logger.class));
            ApplyJournal applies = mock(ApplyJournal.class);
            runner = new OperationRunner(journal, guard, () -> registry[0], catalog, authentication, locals, plans,
                    applies);
            CustomFieldTools tools = new CustomFieldTools(mock(CustomFieldCreator.class),
                    mock(FieldScreenService.class), requirements,
                    mock(ru.rasulov.customfields.api.FieldConfigurationCloneService.class),
                    mock(ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration.class), guard,
                    mock(ru.rasulov.customfields.api.FieldContextReader.class),
                    mock(ru.rasulov.customfields.api.FieldContextScopeService.class),
                    mock(ru.rasulov.customfields.api.FieldContextCreator.class));
            ChangeTools changes = new ChangeTools(journal, guard, runner, plans, applies);
            registry[0] = new ToolRegistry(Arrays.asList(tools, changes));
            McpServer server = new McpServer(guard, catalog, registry[0]);

            // old single-scope form: rejected as an unknown argument, no plan, no Jira read or write
            ObjectNode old = legacyArgs();
            JsonNode oldRefused = callRaw(server, "set_field_required", old);
            assertTrue(oldRefused.toString(), oldRefused.has("error") || oldRefused.path("result").path("isError").asBoolean());
            assertTrue(oldRefused.toString(), oldRefused.toString().contains("fields") || oldRefused.toString().contains("Unrecognized"));
            assertFalse(required.get());
            verify(layout, never()).makeRequired(any());
            verify(layouts, never()).refresh();
            org.mockito.Mockito.verifyNoInteractions(copies);

            // 0. system attachment + a supported field: whole batch refused at preview, no plan, no Jira writes
            ObjectNode mixed = oneRowArgs();
            com.fasterxml.jackson.databind.node.ArrayNode mixedFields = ((ObjectNode) mixed.get("rows").get(0)).putArray("fields");
            mixedFields.add(FIELD);
            mixedFields.add("attachment");
            JsonNode attachment = call(server, "set_field_required", mixed);
            assertEquals(attachment.toString(), "FAILED", attachment.path("status").asText());
            assertEquals("FIELD_REQUIRED_UNSUPPORTED", attachment.path("fieldErrors").get(0).path("errorCode").asText());
            assertEquals("attachment", attachment.path("fieldErrors").get(0).path("selector").asText());
            assertTrue(attachment.toString(), attachment.path("fieldErrors").get(0).path("message").asText()
                    .contains("Поле Attachment нельзя сделать обязательным через Field Configuration"));
            assertTrue(attachment.toString(), absent(attachment.path("planId")));
            assertFalse(required.get());
            verify(layout, never()).makeRequired(any());
            verify(layouts, never()).refresh();
            org.mockito.Mockito.verifyNoInteractions(copies);

            // 1. only on EDIT / DEFAULT without the field: refused, no plan, nothing written
            JsonNode refused = call(server, "set_field_required", oneRowArgs());
            assertEquals(refused.toString(), "FAILED", refused.path("status").asText());
            assertEquals("FIELD_NOT_ON_CREATE_SCREEN", refused.path("fieldErrors").get(0).path("errorCode").asText());
            String message = refused.path("fieldErrors").get(0).path("message").asText();
            assertTrue(message, message.contains("Task") && message.contains("'Default Screen' (7)"));
            assertTrue(refused.toString(), absent(refused.path("planId")));
            assertFalse(required.get());

            // 2. the CREATE screen is assigned correctly: a new preview passes; then CREATE changes before apply
            screens.create(withField);
            JsonNode preview = call(server, "set_field_required", oneRowArgs());
            assertEquals(preview.toString(), "WOULD_CHANGE_REQUIREMENTS", preview.path("status").asText());
            screens.defaultScreen(noField).edit(withField).create(RequirementScreens.screen(11L, "Other create", false));
            JsonNode stale = operation(server, start(server, "set_field_required", preview.path("planId").asText()));
            submitted++;
            assertEquals(stale.toString(), "COMPLETED", stale.path("status").asText());
            assertEquals(stale.toString(), "PREVIEW_STALE", stale.path("result").path("errorCode").asText());
            assertFalse("a stale plan wrote nothing", required.get());

            // 3. CREATE is right again: a fresh preview and apply write the flag
            screens.defaultScreen(noField).create(withField);
            JsonNode second = call(server, "set_field_required", oneRowArgs());
            assertEquals(second.toString(), "WOULD_CHANGE_REQUIREMENTS", second.path("status").asText());
            JsonNode applied = operation(server, start(server, "set_field_required", second.path("planId").asText()));
            submitted++;
            assertEquals(applied.toString(), "COMPLETED", applied.path("status").asText());
            assertEquals(applied.toString(), "REQUIREMENTS_CHANGED", applied.path("result").path("status").asText());
            assertTrue(required.get());

            // 4. per-row form through MCP: contradicting rows are refused without a plan; a valid row previews and applies
            ObjectNode conflict = rowsArgs(true, true);
            JsonNode refusedRows = call(server, "set_field_required", conflict);
            assertEquals(refusedRows.toString(), "FAILED", refusedRows.path("status").asText());
            assertEquals("ROW_CONFLICT", refusedRows.path("fieldErrors").get(0).path("errorCode").asText());
            assertTrue(refusedRows.toString(), absent(refusedRows.path("planId")));
            JsonNode rowsPreview = call(server, "set_field_required", rowsArgs(false, false));
            assertEquals(rowsPreview.toString(), "WOULD_CHANGE_REQUIREMENTS", rowsPreview.path("status").asText());
            assertEquals("WOULD_MAKE_OPTIONAL", rowsPreview.path("targets").get(0).path("fields").get(0)
                    .path("status").asText());
            assertTrue(required.get());
            JsonNode rowsApplied = operation(server, start(server, "set_field_required",
                    rowsPreview.path("planId").asText()));
            submitted++;
            assertEquals(rowsApplied.toString(), "REQUIREMENTS_CHANGED", rowsApplied.path("result").path("status").asText());
            assertFalse(required.get());

            // 5. several rows in one plan: FIELD required and FIELD2 optional (already) for Task, one apply
            ObjectNode two = JSON.createObjectNode();
            two.put("dryRun", true);
            two.put("project", "PAY");
            com.fasterxml.jackson.databind.node.ArrayNode twoRows = two.putArray("rows");
            ObjectNode first = twoRows.addObject();
            first.putArray("fields").add(FIELD);
            first.put("required", true);
            first.putArray("issueTypes").add("Task");
            ObjectNode secondRow = twoRows.addObject();
            secondRow.putArray("fields").add(FIELD2);
            secondRow.put("required", false);
            secondRow.putArray("issueTypes").add("Task");
            JsonNode twoPreview = call(server, "set_field_required", two);
            assertEquals(twoPreview.toString(), "WOULD_CHANGE_REQUIREMENTS", twoPreview.path("status").asText());
            JsonNode twoFields = twoPreview.path("targets").get(0).path("fields");
            assertEquals(2, twoFields.size());
            assertEquals("WOULD_MAKE_REQUIRED", twoFields.get(0).path("status").asText());
            assertEquals("ALREADY_OPTIONAL", twoFields.get(1).path("status").asText());
            assertFalse(required.get());
            JsonNode twoApplied = operation(server, start(server, "set_field_required", twoPreview.path("planId").asText()));
            submitted++;
            assertEquals(twoApplied.toString(), "REQUIREMENTS_CHANGED", twoApplied.path("result").path("status").asText());
            assertTrue(required.get());
        } finally {
            try {
                if (runner != null) runner.destroy();
                assertTrue("Background workers did not finish before closing AO/HSQL",
                        finished.tryAcquire(submitted, 20, TimeUnit.SECONDS));
            } finally {
                FieldJournalTestSupport.close();
            }
        }
    }

    private static ObjectNode oneRowArgs() { return rowsArgs(false, true); }

    /** Rows form for Task and FIELD; with {@code twice} a second row repeats the field with the other flag. */
    private static ObjectNode rowsArgs(boolean twice, boolean firstRequired) {
        ObjectNode args = JSON.createObjectNode();
        args.put("dryRun", true);
        args.put("project", "PAY");
        com.fasterxml.jackson.databind.node.ArrayNode rows = args.putArray("rows");
        for (int i = 0; i < (twice ? 2 : 1); i++) {
            ObjectNode row = rows.addObject();
            row.putArray("fields").add(FIELD);
            row.put("required", i == 0 ? firstRequired : !firstRequired);
            row.putArray("issueTypes").add("Task");
        }
        return args;
    }

    private static ObjectNode legacyArgs() {
        ObjectNode args = JSON.createObjectNode();
        args.put("dryRun", true);
        args.put("project", "PAY");
        args.putArray("fields").add(FIELD);
        args.putArray("issueTypes").add("Task");
        return args;
    }

    private static String start(McpServer server, String tool, String planId) throws Exception {
        ObjectNode apply = JSON.createObjectNode();
        apply.put("dryRun", false);
        apply.put("planId", planId);
        ObjectNode start = JSON.createObjectNode();
        start.put("toolName", tool);
        start.put("arguments", apply);
        return call(server, "start_operation", start).path("operationId").asText();
    }

    private static boolean absent(JsonNode node) { return node.isMissingNode() || node.isNull(); }

    private static JsonNode operation(McpServer server, String operationId) throws Exception {
        ObjectNode args = JSON.createObjectNode();
        args.put("operationId", operationId);
        args.put("waitSeconds", 20);
        return call(server, "get_operation", args);
    }

    /** The unparsed JSON-RPC response: refusals of malformed arguments are MCP errors, not tool payloads. */
    private static JsonNode callRaw(McpServer server, String tool, JsonNode args) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.put("arguments", args);
        return JSON.readTree(server.handle(request.toString()));
    }

    /** Domain refusals may come back as an MCP tool error; the payload is the same JSON either way. */
    private static JsonNode call(McpServer server, String tool, JsonNode args) throws Exception {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.put("arguments", args);
        JsonNode response = JSON.readTree(server.handle(request.toString()));
        assertFalse(response.toString(), response.has("error"));
        return JSON.readTree(response.path("result").path("content").get(0).path("text").asText());
    }
}
