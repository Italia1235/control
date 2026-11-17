package ru.rasulov.customfields.impl.requirement;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.EditableDefaultFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.InMemoryActionPlans;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredRequest;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse;
import ru.rasulov.customfields.impl.JiraMocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Field lookup, issue-type scope, effective configuration selection, and preview/apply behavior. */
public class FieldRequirementServiceImplTest {
    @org.junit.After
    public void closeUndoDatabases() { ru.rasulov.customfields.impl.JiraMocks.closeJournals(); }
    private CustomFieldManager fields;
    private FieldManager systemFields;
    private FieldLayoutManager layouts;
    private ProjectResolver projects;
    private IssueTypeResolver issueTypes;
    private FieldRequirementServiceImpl service;
    private RequirementScreens screens;
    private ru.rasulov.customfields.impl.configuration.FieldLayoutCopyFactory copies;
    private Project project;
    private CustomField field;
    private IssueType task;
    private IssueType bug;

    @Before
    public void setUp() {
        fields = mock(CustomFieldManager.class);
        systemFields = mock(FieldManager.class);
        layouts = mock(FieldLayoutManager.class);
        projects = mock(ProjectResolver.class);
        issueTypes = mock(IssueTypeResolver.class);
        copies = mock(ru.rasulov.customfields.impl.configuration.FieldLayoutCopyFactory.class);

        JiraAuthenticationContext authentication = mock(JiraAuthenticationContext.class);
        GlobalPermissionManager permissions = mock(GlobalPermissionManager.class);
        ApplicationUser admin = mock(ApplicationUser.class);
        when(authentication.getLoggedInUser()).thenReturn(admin);
        when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
        when(admin.getKey()).thenReturn("admin-key");

        project = project(1L, "PAY", "Payments");
        task = issueType("10001", "Task");
        bug = issueType("10002", "Bug");
        when(project.getIssueTypes()).thenReturn(Arrays.asList(task, bug));
        when(projects.resolve("PAY")).thenReturn(project);

        field = field("customfield_10100", "Severity", "select", "Select List");
        when(fields.getCustomFieldObject("customfield_10100")).thenReturn(field);
        when(fields.getCustomFieldObjectsByNameIgnoreCase("Severity")).thenReturn(Collections.singletonList(field));
        when(field.isInScope(eq(1L), anyString())).thenReturn(true);
        when(layouts.getMutableFieldLayoutScheme(20L)).thenReturn(
                mock(com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme.class));

        screens = new RequirementScreens();
        service = new FieldRequirementServiceImpl(new AdministratorGuard(authentication, permissions),
                new NamedLock(JiraMocks.lockService(mock(ClusterLock.class))), projects, issueTypes,
                new ru.rasulov.customfields.impl.configuration.ConfigurableFieldResolver(
                        new ru.rasulov.customfields.impl.context.FieldSelector(fields), systemFields), layouts,
                screens.manager,
                new ru.rasulov.common.impl.PreviewPlanExecutor(InMemoryActionPlans.create(), new ActionPlanCodec()), new ActionPlanCodec(),
                ru.rasulov.customfields.impl.FieldJournalTestSupport.journal("admin-key"), copies);
    }

    @Test
    public void duplicateNamesReturnEveryTypedCandidateAndRequireAnId() {
        CustomField first = field("customfield_10101", "Priority", "textfield", "Text Field");
        CustomField second = field("customfield_10102", "Priority", "select", "Select List");
        when(fields.getCustomFieldObjectsByNameIgnoreCase("Priority")).thenReturn(Arrays.asList(second, first));

        SetFieldRequiredResponse response = service.setRequired(
                RequirementRequests.oneRow(true, "PAY", Arrays.asList("Severity", "Priority"), true, null));

        assertEquals(SetFieldRequiredResponse.Status.FAILED, response.getStatus());
        assertEquals(SetFieldRequiredResponse.ErrorCode.AMBIGUOUS_FIELD, response.getErrorCode());
        assertEquals(2, response.getCandidates().size());
        assertEquals("customfield_10101", response.getCandidates().get(0).getId());
        assertEquals("Priority", response.getCandidates().get(0).getName());
        assertEquals("textfield", response.getCandidates().get(0).getTypeKey());
        assertEquals("Text Field", response.getCandidates().get(0).getTypeName());
        assertNull(response.getPlanId());
        verify(layouts, never()).refresh();
        org.mockito.Mockito.verifyNoInteractions(copies);
    }

    @Test
    public void allIssueTypesSharingAConfigurationAreChangedOnceAndSharedProjectsAreReported() {
        FieldLayout layout = layout(10L, "Payments fields", false, false);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(layout);
        when(layouts.getFieldLayout(project, bug.getId())).thenReturn(layout);
        FieldConfigurationScheme scheme = mock(FieldConfigurationScheme.class);
        when(scheme.getId()).thenReturn(20L);
        when(scheme.getName()).thenReturn("Payments scheme");
        when(layouts.getFieldConfigurationScheme(project)).thenReturn(scheme);
        Collection<Project> sharedProjects = otherProjects(12);
        when(layouts.getProjectsUsing(layout)).thenReturn(sharedProjects);

        EditableFieldLayout editable = mock(EditableFieldLayout.class);
        FieldLayoutItem editableItem = mock(FieldLayoutItem.class);
        java.util.concurrent.atomic.AtomicBoolean required = new java.util.concurrent.atomic.AtomicBoolean();
        when(editable.getId()).thenReturn(10L);
        when(editable.getName()).thenReturn("Payments fields");
        when(editable.getFieldLayoutItems()).thenReturn(Collections.singletonList(editableItem));
        when(editableItem.getOrderableField()).thenReturn(field);
        when(editableItem.isRequired()).thenAnswer(call -> required.get());
        doAnswer(call -> { required.set(true); return null; }).when(editable).makeRequired(editableItem);
        when(editable.getFieldLayoutItem(field.getId())).thenReturn(editableItem);
        when(layouts.getEditableFieldLayout(10L)).thenReturn(editable);

        SetFieldRequiredResponse preview = service.setRequired(
                RequirementRequests.oneRow(true, "PAY", Collections.singletonList("10100"), true, null));

        assertEquals(SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, preview.getStatus());
        assertNotNull(preview.getPlanId());
        assertEquals("Payments scheme", preview.getFieldConfigurationScheme().getName());
        assertEquals(2, preview.getTargets().size());
        assertEquals(12, preview.getTargets().get(0).getOtherProjectsTotal());
        assertEquals(10, preview.getTargets().get(0).getOtherProjects().size());

        SetFieldRequiredResponse applied = service.setRequired(
                RequirementRequests.apply(preview.getPlanId()));

        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(true, editableItem.isRequired());
        assertEquals(true, layouts.getEditableFieldLayout(10L).getFieldLayoutItem(field.getId()).isRequired());
    }

    @Test
    public void oneNamedIssueTypeCanUseTheProjectsDefaultFieldConfiguration() {
        when(issueTypes.resolve("Task")).thenReturn(task);
        EditableDefaultFieldLayout layout = mock(EditableDefaultFieldLayout.class);
        FieldLayoutItem item = mock(FieldLayoutItem.class);
        java.util.concurrent.atomic.AtomicBoolean required = new java.util.concurrent.atomic.AtomicBoolean();
        when(layout.getFieldLayoutItems()).thenReturn(Collections.singletonList(item));
        when(item.getOrderableField()).thenReturn(field);
        when(item.isRequired()).thenAnswer(call -> required.get());
        doAnswer(call -> { required.set(true); return null; }).when(layout).makeRequired(item);
        when(layout.isDefault()).thenReturn(true);
        when(layout.getName()).thenReturn("Default Field Configuration");
        when(layout.getFieldLayoutItem(field.getId())).thenReturn(item);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(layout);
        when(layouts.getEditableDefaultFieldLayout()).thenReturn(layout);
        when(layouts.getProjectsUsing(layout)).thenReturn(Collections.singletonList(project));

        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(true, "PAY", Collections.singletonList("Severity"), null, Collections.singletonList("Task")));
        SetFieldRequiredResponse applied = service.setRequired(
                RequirementRequests.apply(preview.getPlanId()));

        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(1, applied.getTargets().size());
        assertEquals("Task", applied.getTargets().get(0).getIssueType().getName());
        assertEquals(true, applied.getFieldConfigurationScheme().isDefaultScheme());
        assertNull(applied.getTargets().get(0).getNewFieldConfigurationName());
        assertEquals(true, layouts.getEditableDefaultFieldLayout().getFieldLayoutItem(field.getId()).isRequired());
    }

    @Test
    public void applyChangesOnlyTheLayoutResolvedForTheCheckedPlan() {
        when(issueTypes.resolve("Task")).thenReturn(task);
        FieldLayout checked = layout(10L, "Checked fields", false, false);
        FieldLayout replacement = layout(11L, "Replacement fields", false, false);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(checked, checked, replacement);
        java.util.concurrent.atomic.AtomicBoolean checkedRequired = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean replacementRequired = new java.util.concurrent.atomic.AtomicBoolean();
        EditableFieldLayout checkedEditable = editableLayout(10L, "Checked fields", checkedRequired);
        EditableFieldLayout replacementEditable = editableLayout(11L, "Replacement fields", replacementRequired);
        when(layouts.getEditableFieldLayout(10L)).thenReturn(checkedEditable);
        when(layouts.getEditableFieldLayout(11L)).thenReturn(replacementEditable);

        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(true, "PAY", Collections.singletonList("Severity"), null, Collections.singletonList("Task")));
        SetFieldRequiredResponse applied = service.setRequired(
                RequirementRequests.apply(preview.getPlanId()));

        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals("checked layout must receive the native required change", true, checkedRequired.get());
        assertEquals("replacement layout must remain untouched", false, replacementRequired.get());
    }

    @Test
    public void previewHasNoWarningWhenUnselectedIssueTypesUseAnotherConfiguration() {
        when(issueTypes.resolve("Task")).thenReturn(task);
        FieldLayout taskLayout = layout(10L, "Task fields", false, false);
        FieldLayout bugLayout = layout(11L, "Bug fields", false, false);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(taskLayout);
        when(layouts.getFieldLayout(project, bug.getId())).thenReturn(bugLayout);

        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(true, "PAY", Collections.singletonList("Severity"), null, Collections.singletonList("Task")));

        assertNull(preview.getTargets().get(0).getNewFieldConfigurationName());
        assertEquals(0, preview.getTargets().get(0).getUnchangedIssueTypes().size());
        assertNull(preview.getMessage());
    }

    @Test
    public void selectedIssueTypeBecomesRequiredWithoutChangingTheConfigurationSharedWithUnselectedTypes() {
        when(issueTypes.resolve("Task")).thenReturn(task);
        FieldLayout shared = layout(10L, "Shared fields", false, false);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(shared);
        when(layouts.getFieldLayout(project, bug.getId())).thenReturn(shared);
        privateScheme();
        java.util.concurrent.atomic.AtomicBoolean sharedRequired = new java.util.concurrent.atomic.AtomicBoolean();
        EditableFieldLayout sharedEditable = editableLayout(10L, "Shared fields", sharedRequired);
        when(layouts.getEditableFieldLayout(10L)).thenReturn(sharedEditable);

        FieldLayout copyLayout = layout(50L, "PAY: Shared fields (Task)", false, false);
        EditableFieldLayout draft = mock(EditableFieldLayout.class);
        when(copies.copyOf(shared, "PAY: Shared fields (Task)")).thenReturn(draft);
        EditableFieldLayout stored = mock(EditableFieldLayout.class);
        when(stored.getId()).thenReturn(50L);
        when(layouts.storeAndReturnEditableFieldLayout(draft)).thenReturn(stored);
        java.util.concurrent.atomic.AtomicBoolean copyRequired = new java.util.concurrent.atomic.AtomicBoolean();
        EditableFieldLayout copyEditable = editableLayout(50L, "PAY: Shared fields (Task)", copyRequired);
        when(layouts.getEditableFieldLayout(50L)).thenReturn(copyEditable);
        com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme mutable =
                mock(com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme.class);
        when(layouts.getMutableFieldLayoutScheme(20L)).thenReturn(mutable);
        java.util.concurrent.atomic.AtomicBoolean mapped = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> { mapped.set(true); return null; })
                .when(layouts).createFieldLayoutSchemeEntity(mutable, "10001", 50L);
        when(layouts.getFieldLayout(project, task.getId())).thenAnswer(call -> mapped.get() ? copyLayout : shared);

        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(true, "PAY", Collections.singletonList("Severity"), null, Collections.singletonList("Task")));

        assertEquals(SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, preview.getStatus());
        SetFieldRequiredResponse.TargetView planned = preview.getTargets().get(0);
        assertEquals("Shared fields", planned.getFieldConfigurationName());
        assertEquals("PAY: Shared fields (Task)", planned.getNewFieldConfigurationName());
        assertNull(planned.getNewFieldConfigurationId());
        assertEquals("Bug", planned.getUnchangedIssueTypes().get(0).getName());
        assertEquals(0, planned.getOtherProjectsTotal());

        SetFieldRequiredResponse applied = service.setRequired(
                RequirementRequests.apply(preview.getPlanId()));

        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals("Bug shares this configuration and must stay optional", false, sharedRequired.get());
        assertEquals("the copy used by Task must become required", true, copyRequired.get());
        assertEquals(Long.valueOf(50L), applied.getTargets().get(0).getNewFieldConfigurationId());
        verify(layouts).createFieldLayoutSchemeEntity(mutable, task.getId(), 50L);
        verify(layouts, never()).createFieldLayoutSchemeEntity(mutable, bug.getId(), 50L);
    }

    @Test
    public void splitIsRefusedWhenTheProjectHasNoFieldConfigurationSchemeOfItsOwn() {
        when(issueTypes.resolve("Task")).thenReturn(task);
        FieldLayout shared = layout(10L, "Shared fields", false, false);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(shared);
        when(layouts.getFieldLayout(project, bug.getId())).thenReturn(shared);
        FieldConfigurationScheme scheme = privateScheme();
        Project operations = project(2L, "OPS", "Operations");
        when(layouts.getProjectsUsing(scheme)).thenReturn(Arrays.asList(project, operations));

        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(true, "PAY", Collections.singletonList("Severity"), null, Collections.singletonList("Task")));

        assertEquals(SetFieldRequiredResponse.Status.FAILED, preview.getStatus());
        assertEquals(SetFieldRequiredResponse.ErrorCode.SHARED_FIELD_CONFIGURATION_SCHEME, preview.getErrorCode());
        assertEquals(true, preview.getMessage().contains("Bug"));
        assertEquals(true, preview.getMessage().contains("clone_project_field_configurations"));
        assertNull(preview.getPlanId());
    }

    @Test
    public void optionalCopyLeavesTheConfigurationSharedWithUnselectedTypesRequired() {
        when(issueTypes.resolve("Task")).thenReturn(task);
        FieldLayout shared = layout(10L, "Shared fields", false, true);
        when(layouts.getFieldLayout(project, bug.getId())).thenReturn(shared);
        privateScheme();
        java.util.concurrent.atomic.AtomicBoolean sharedRequired = new java.util.concurrent.atomic.AtomicBoolean(true);
        EditableFieldLayout sharedEditable = editableLayout(10L, "Shared fields", sharedRequired);
        when(layouts.getEditableFieldLayout(10L)).thenReturn(sharedEditable);
        EditableFieldLayout draft = mock(EditableFieldLayout.class);
        when(copies.copyOf(shared, "PAY: Shared fields (Task)")).thenReturn(draft);
        EditableFieldLayout stored = mock(EditableFieldLayout.class);
        when(stored.getId()).thenReturn(50L);
        when(layouts.storeAndReturnEditableFieldLayout(draft)).thenReturn(stored);
        java.util.concurrent.atomic.AtomicBoolean copyRequired = new java.util.concurrent.atomic.AtomicBoolean(true);
        EditableFieldLayout copyEditable = editableLayout(50L, "PAY: Shared fields (Task)", copyRequired);
        when(layouts.getEditableFieldLayout(50L)).thenReturn(copyEditable);
        com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme mutable =
                mock(com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme.class);
        when(layouts.getMutableFieldLayoutScheme(20L)).thenReturn(mutable);
        java.util.concurrent.atomic.AtomicBoolean mapped = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(call -> { mapped.set(true); return null; })
                .when(layouts).createFieldLayoutSchemeEntity(mutable, "10001", 50L);
        FieldLayout copyLayout = layout(50L, "PAY: Shared fields (Task)", false, false);
        when(layouts.getFieldLayout(project, task.getId())).thenAnswer(call -> mapped.get() ? copyLayout : shared);

        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(true, "PAY", Collections.singletonList("Severity"), false, null, Collections.singletonList("Task")));

        assertEquals(SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, preview.getStatus());
        assertEquals(SetFieldRequiredResponse.TargetStatus.WOULD_MAKE_OPTIONAL, preview.getTargets().get(0).getStatus());
        assertEquals("PAY: Shared fields (Task)", preview.getTargets().get(0).getNewFieldConfigurationName());
        assertEquals(true, preview.getMessage().contains("required flags"));

        SetFieldRequiredResponse applied = service.setRequired(
                RequirementRequests.apply(preview.getPlanId()));

        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals("Bug keeps the shared configuration where the field stays required", true, sharedRequired.get());
        assertEquals("the copy used by Task must become optional", false, copyRequired.get());
    }

    @Test
    public void alreadyOptionalFieldNeedsNoApply() {
        FieldLayout layout = layout(10L, "Payments fields", false, false);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(layout);
        when(layouts.getFieldLayout(project, bug.getId())).thenReturn(layout);

        SetFieldRequiredResponse preview = service.setRequired(
                RequirementRequests.oneRow(true, "PAY", Collections.singletonList("Severity"), false, true, null));

        assertEquals(SetFieldRequiredResponse.Status.ALREADY_AS_REQUESTED, preview.getStatus());
        assertEquals(SetFieldRequiredResponse.TargetStatus.ALREADY_OPTIONAL, preview.getTargets().get(1).getStatus());
        assertNull(preview.getPlanId());
    }

    @Test
    public void systemFieldBecomesOptionalInEveryConfigurationOfTheProject() {
        OrderableField dueDate = systemField("duedate", "Due Date");
        FieldLayout layout = layout(dueDate, 10L, "Payments fields", false, true);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(layout);
        when(layouts.getFieldLayout(project, bug.getId())).thenReturn(layout);
        java.util.concurrent.atomic.AtomicBoolean required = new java.util.concurrent.atomic.AtomicBoolean(true);
        EditableFieldLayout editable = editableLayout(dueDate, 10L, "Payments fields", required);
        when(layouts.getEditableFieldLayout(10L)).thenReturn(editable);

        SetFieldRequiredResponse preview = service.setRequired(
                RequirementRequests.oneRow(true, "PAY", Collections.singletonList("duedate"), false, true, null));

        assertEquals(SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, preview.getStatus());
        assertEquals("duedate", preview.getFields().get(0).getId());
        assertEquals("System field", preview.getFields().get(0).getTypeName());

        SetFieldRequiredResponse applied = service.setRequired(
                RequirementRequests.apply(preview.getPlanId()));

        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(false, required.get());
    }

    @Test
    public void systemFieldBecomesRequired() {
        OrderableField priority = systemField("priority", "Priority");
        when(issueTypes.resolve("Task")).thenReturn(task);
        FieldLayout taskLayout = layout(priority, 10L, "Task fields", false, false);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(taskLayout);
        FieldLayout bugLayout = layout(priority, 11L, "Bug fields", false, false);
        when(layouts.getFieldLayout(project, bug.getId())).thenReturn(bugLayout);
        java.util.concurrent.atomic.AtomicBoolean required = new java.util.concurrent.atomic.AtomicBoolean();
        EditableFieldLayout editable = editableLayout(priority, 10L, "Task fields", required);
        when(layouts.getEditableFieldLayout(10L)).thenReturn(editable);

        SetFieldRequiredResponse preview = service.setRequired(RequirementRequests.oneRow(true, "PAY", Collections.singletonList("priority"), null, Collections.singletonList("Task")));
        SetFieldRequiredResponse applied = service.setRequired(
                RequirementRequests.apply(preview.getPlanId()));

        assertEquals(SetFieldRequiredResponse.Status.REQUIREMENTS_CHANGED, applied.getStatus());
        assertEquals(true, required.get());
    }

    @Test
    public void jiraMandatoryFieldCannotBecomeOptional() {
        OrderableField summary = systemField("summary", "Summary");
        when(systemFields.isMandatoryField(summary)).thenReturn(true);

        SetFieldRequiredResponse preview = service.setRequired(
                RequirementRequests.oneRow(true, "PAY", Collections.singletonList("summary"), false, true, null));

        assertEquals(SetFieldRequiredResponse.Status.FAILED, preview.getStatus());
        assertEquals(SetFieldRequiredResponse.ErrorCode.LOCKED_FIELD, preview.getErrorCode());
        assertNull(preview.getPlanId());
    }

    @Test
    public void systemAttachmentCannotBecomeRequiredAndTheWholeMixedBatchIsRefusedBeforePlanning() {
        systemField("attachment", "Attachment");
        SetFieldRequiredResponse response = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", Arrays.asList("Severity", "attachment", "NoSuchField"), true, null));

        assertEquals(SetFieldRequiredResponse.Status.FAILED, response.getStatus());
        assertNull(response.getPlanId());
        assertEquals(2, response.getFieldErrors().size());
        assertEquals("attachment", response.getFieldErrors().get(0).getSelector());
        assertEquals(SetFieldRequiredResponse.ErrorCode.FIELD_REQUIRED_UNSUPPORTED,
                response.getFieldErrors().get(0).getErrorCode());
        assertEquals(true, response.getFieldErrors().get(0).getMessage()
                .contains("Поле Attachment нельзя сделать обязательным через Field Configuration"));
        assertEquals(SetFieldRequiredResponse.ErrorCode.UNKNOWN_FIELD, response.getFieldErrors().get(1).getErrorCode());
        verify(layouts, never()).refresh();
        org.mockito.Mockito.verifyNoInteractions(copies);
    }

    @Test
    public void systemAssigneeCannotBecomeRequiredAndRefusesTheWholeBatch() {
        systemField("assignee", "Assignee");
        SetFieldRequiredResponse response = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", Arrays.asList("Severity", "assignee"), true, null));

        assertEquals(SetFieldRequiredResponse.Status.FAILED, response.getStatus());
        assertNull(response.getPlanId());
        assertEquals(1, response.getFieldErrors().size());
        assertEquals("assignee", response.getFieldErrors().get(0).getSelector());
        assertEquals(SetFieldRequiredResponse.ErrorCode.FIELD_REQUIRED_UNSUPPORTED,
                response.getFieldErrors().get(0).getErrorCode());
        assertEquals(true, response.getFieldErrors().get(0).getMessage()
                .contains("Поле Assignee нельзя сделать обязательным через Field Configuration"));
        verify(layouts, never()).refresh();
        org.mockito.Mockito.verifyNoInteractions(copies);
    }

    @Test
    public void systemAttachmentCanStillBecomeOptionalAndACustomFieldNamedAttachmentIsNotBlocked() {
        OrderableField attachment = systemField("attachment", "Attachment");
        FieldLayout taskLayout = layout(attachment, 10L, "Task fields", false, false);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(taskLayout);
        when(issueTypes.resolve("Task")).thenReturn(task);
        SetFieldRequiredResponse optional = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", Collections.singletonList("attachment"), false, null, Collections.singletonList("Task")));
        assertEquals(SetFieldRequiredResponse.Status.ALREADY_AS_REQUESTED, optional.getStatus());

        CustomField custom = field("customfield_10400", "Attachment", "textfield", "Text Field");
        when(fields.getCustomFieldObjectsByNameIgnoreCase("Attachment")).thenReturn(Collections.singletonList(custom));
        when(fields.getCustomFieldObject("customfield_10400")).thenReturn(custom);
        SetFieldRequiredResponse named = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", Collections.singletonList("customfield_10400"), true, null));
        if (named.getFieldErrors() != null) {
            for (SetFieldRequiredResponse.FieldError error : named.getFieldErrors()) {
                assertEquals(false, error.getErrorCode() == SetFieldRequiredResponse.ErrorCode.FIELD_REQUIRED_UNSUPPORTED);
            }
        }
        assertEquals(false, named.getErrorCode() == SetFieldRequiredResponse.ErrorCode.FIELD_REQUIRED_UNSUPPORTED);
    }

    @Test
    public void systemFieldIdThatIsAlsoACustomFieldNameIsAmbiguous() {
        systemField("labels", "Labels");
        CustomField custom = field("customfield_10300", "labels", "textfield", "Text Field");
        when(fields.getCustomFieldObjectsByNameIgnoreCase("labels")).thenReturn(Collections.singletonList(custom));

        SetFieldRequiredResponse preview = service.setRequired(
                RequirementRequests.oneRow(true, "PAY", Collections.singletonList("labels"), true, null));

        assertEquals(SetFieldRequiredResponse.ErrorCode.AMBIGUOUS_FIELD, preview.getErrorCode());
        assertEquals(2, preview.getCandidates().size());
        assertEquals("labels", preview.getCandidates().get(0).getId());
        assertEquals("customfield_10300", preview.getCandidates().get(1).getId());
    }

    @Test
    public void unknownFieldRejectsTheWholeBatchBeforePlanning() {
        SetFieldRequiredResponse response = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", Arrays.asList("Severity", "NoSuchField"), true, null));
        assertEquals(SetFieldRequiredResponse.Status.FAILED, response.getStatus());
        assertEquals(SetFieldRequiredResponse.ErrorCode.UNKNOWN_FIELD, response.getErrorCode());
        assertEquals(true, response.getMessage().contains("NoSuchField"));
        assertNull(response.getPlanId());
        verify(layouts, never()).refresh();
        org.mockito.Mockito.verifyNoInteractions(copies);
    }

    @Test
    public void lockedFieldRejectsTheWholeOptionalBatchBeforePlanning() {
        OrderableField summary = systemField("summary", "Summary");
        when(systemFields.isMandatoryField(summary)).thenReturn(true);
        SetFieldRequiredResponse response = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", Arrays.asList("Severity", "summary"), false, true, null));
        assertEquals(SetFieldRequiredResponse.ErrorCode.LOCKED_FIELD, response.getErrorCode());
        assertEquals(true, response.getMessage().contains("summary"));
        assertNull(response.getPlanId());
        verify(layouts, never()).refresh();
        org.mockito.Mockito.verifyNoInteractions(copies);
    }

    @Test
    public void everyUnknownAmbiguousAndLockedSelectorIsReportedForTheWholeBatch() {
        CustomField first = field("customfield_10101", "Priority", "textfield", "Text Field");
        CustomField second = field("customfield_10102", "Priority", "select", "Select List");
        when(fields.getCustomFieldObjectsByNameIgnoreCase("Priority")).thenReturn(Arrays.asList(second, first));
        OrderableField summary = systemField("summary", "Summary");
        when(systemFields.isMandatoryField(summary)).thenReturn(true);
        SetFieldRequiredResponse response = service.setRequired(RequirementRequests.oneRow(
                true, "PAY", Arrays.asList("Severity", "NoSuchField", "Priority", "summary"), false, true, null));

        assertEquals(SetFieldRequiredResponse.Status.FAILED, response.getStatus());
        assertNull(response.getPlanId());
        assertEquals(3, response.getFieldErrors().size());
        List<SetFieldRequiredResponse.FieldError> errors = response.getFieldErrors();
        assertEquals("NoSuchField", errors.get(0).getSelector());
        assertEquals(SetFieldRequiredResponse.ErrorCode.UNKNOWN_FIELD, errors.get(0).getErrorCode());
        assertEquals("Priority", errors.get(1).getSelector());
        assertEquals(SetFieldRequiredResponse.ErrorCode.AMBIGUOUS_FIELD, errors.get(1).getErrorCode());
        assertEquals(2, errors.get(1).getCandidates().size());
        assertEquals("customfield_10101", errors.get(1).getCandidates().get(0).getId());
        assertEquals("textfield", errors.get(1).getCandidates().get(0).getTypeKey());
        assertEquals("customfield_10102", errors.get(1).getCandidates().get(1).getId());
        assertEquals("select", errors.get(1).getCandidates().get(1).getTypeKey());
        assertEquals("summary", errors.get(2).getSelector());
        assertEquals(SetFieldRequiredResponse.ErrorCode.LOCKED_FIELD, errors.get(2).getErrorCode());
        for (SetFieldRequiredResponse.FieldError error : errors) {
            assertEquals(true, response.getMessage().contains(error.getSelector()));
        }
        assertEquals(2, response.getCandidates().size());
        verify(layouts, never()).refresh();
        org.mockito.Mockito.verifyNoInteractions(copies);
    }

    @Test
    public void selectorsResolvingToTheSameFieldAreRejectedBeforePlanning() {
        for (String alias : Arrays.asList("10100", "customfield_10100")) {
            SetFieldRequiredResponse response = service.setRequired(RequirementRequests.oneRow(
                    true, "PAY", Arrays.asList("Severity", alias), true, null));
            assertEquals(SetFieldRequiredResponse.ErrorCode.VALIDATION_ERROR, response.getErrorCode());
            assertEquals(true, response.getMessage().contains("customfield_10100"));
            assertNull(response.getPlanId());
        }
        verify(layouts, never()).refresh();
        org.mockito.Mockito.verifyNoInteractions(copies);
    }

    @Test
    public void oversizedFieldBatchIsRejectedBeforePlanning() {
        List<String> oversized = Collections.nCopies(
                ru.rasulov.customfields.api.Limits.MAX_FIELDS_PER_REQUIREMENT_CALL + 1, "Severity");
        SetFieldRequiredResponse response = service.setRequired(
                RequirementRequests.oneRow(true, "PAY", oversized, true, null));
        assertEquals(SetFieldRequiredResponse.ErrorCode.VALIDATION_ERROR, response.getErrorCode());
        assertEquals(true, response.getMessage().contains(String.valueOf(
                ru.rasulov.customfields.api.Limits.MAX_FIELDS_PER_REQUIREMENT_CALL)));
        assertNull(response.getPlanId());
        verify(layouts, never()).refresh();
        org.mockito.Mockito.verifyNoInteractions(copies);
    }

    @Test
    public void emptyAndBlankFieldBatchesAreRejectedBeforePlanning() {
        for (List<String> selectors : Arrays.asList(Collections.<String>emptyList(), Arrays.asList("Severity", " "))) {
            SetFieldRequiredResponse response = service.setRequired(
                    RequirementRequests.oneRow(true, "PAY", selectors, true, null));
            assertEquals(SetFieldRequiredResponse.ErrorCode.VALIDATION_ERROR, response.getErrorCode());
            assertNull(response.getPlanId());
        }
        verify(layouts, never()).refresh();
        org.mockito.Mockito.verifyNoInteractions(copies);
    }

    private FieldConfigurationScheme privateScheme() {
        FieldConfigurationScheme scheme = mock(FieldConfigurationScheme.class);
        when(scheme.getId()).thenReturn(20L);
        when(scheme.getName()).thenReturn("PAY: Field scheme");
        when(layouts.getFieldConfigurationScheme(project)).thenReturn(scheme);
        when(layouts.getProjectsUsing(scheme)).thenReturn(Collections.singletonList(project));
        return scheme;
    }

    private EditableFieldLayout editableLayout(Long id, String name,
                                               java.util.concurrent.atomic.AtomicBoolean required) {
        return editableLayout(field, id, name, required);
    }

    private static EditableFieldLayout editableLayout(OrderableField target, Long id, String name,
                                                      java.util.concurrent.atomic.AtomicBoolean required) {
        EditableFieldLayout editable = mock(EditableFieldLayout.class);
        FieldLayoutItem item = mock(FieldLayoutItem.class);
        when(editable.getId()).thenReturn(id);
        when(editable.getName()).thenReturn(name);
        when(editable.getFieldLayoutItems()).thenReturn(Collections.singletonList(item));
        when(editable.getFieldLayoutItem(target.getId())).thenReturn(item);
        when(item.getOrderableField()).thenReturn(target);
        when(item.isRequired()).thenAnswer(call -> required.get());
        doAnswer(call -> { required.set(true); return null; }).when(editable).makeRequired(item);
        doAnswer(call -> { required.set(false); return null; }).when(editable).makeOptional(item);
        return editable;
    }

    private static CustomField field(String id, String name, String typeKey, String typeName) {
        CustomField field = mock(CustomField.class);
        CustomFieldType<?, ?> type = mock(CustomFieldType.class);
        when(field.getId()).thenReturn(id);
        when(field.getName()).thenReturn(name);
        when(field.getCustomFieldType()).thenReturn(type);
        when(type.getKey()).thenReturn(typeKey);
        when(type.getName()).thenReturn(typeName);
        return field;
    }

    private FieldLayout layout(Long id, String name, boolean defaultLayout, boolean required) {
        return layout(field, id, name, defaultLayout, required);
    }

    private static FieldLayout layout(OrderableField target, Long id, String name, boolean defaultLayout,
                                      boolean required) {
        FieldLayout layout = mock(FieldLayout.class);
        FieldLayoutItem item = mock(FieldLayoutItem.class);
        when(layout.getId()).thenReturn(id);
        when(layout.getName()).thenReturn(name);
        when(layout.isDefault()).thenReturn(defaultLayout);
        when(layout.getFieldLayoutItem(target.getId())).thenReturn(item);
        when(item.isRequired()).thenReturn(required);
        return layout;
    }

    private OrderableField systemField(String id, String name) {
        OrderableField system = mock(OrderableField.class);
        when(system.getId()).thenReturn(id);
        when(system.getName()).thenReturn(name);
        when(systemFields.isOrderableField(id)).thenReturn(true);
        when(systemFields.getOrderableField(id)).thenReturn(system);
        return system;
    }

    private static IssueType issueType(String id, String name) {
        IssueType issueType = mock(IssueType.class);
        when(issueType.getId()).thenReturn(id);
        when(issueType.getName()).thenReturn(name);
        return issueType;
    }

    private static Project project(long id, String key, String name) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn(name);
        return project;
    }

    private static Collection<Project> otherProjects(int count) {
        List<Project> projects = new ArrayList<Project>();
        for (int i = 0; i < count; i++) projects.add(project(100 + i, "P" + i, "Project " + i));
        return projects;
    }

    private void sharedLayoutForBothTypes(boolean required) {
        FieldLayout layout = layout(10L, "Payments fields", false, required);
        when(layouts.getFieldLayout(project, task.getId())).thenReturn(layout);
        when(layouts.getFieldLayout(project, bug.getId())).thenReturn(layout);
        when(layouts.getFieldConfigurationScheme(project)).thenReturn(null);
        when(layouts.getProjectsUsing(layout)).thenReturn(Collections.singletonList(project));
    }

    private SetFieldRequiredResponse requireAllTypes(boolean required) {
        return service.setRequired(RequirementRequests.oneRow(true, "PAY", Collections.singletonList("Severity"),
                required, true, null));
    }

    private void assertRefusedBeforeAnyWrite(SetFieldRequiredResponse response) {
        assertEquals(SetFieldRequiredResponse.Status.FAILED, response.getStatus());
        assertNull(response.getPlanId());
        org.mockito.Mockito.verifyNoInteractions(copies);
        verify(layouts, never()).getEditableFieldLayout(org.mockito.ArgumentMatchers.anyLong());
        verify(layouts, never()).getEditableDefaultFieldLayout();
    }

    @Test
    public void fieldOnlyOnEditOrAnUnassignedScreenIsRefusedForEveryTypeWithScreenAndReason() {
        sharedLayoutForBothTypes(false);
        screens.defaultScreen(RequirementScreens.screen(7L, "No fields", false))
                .edit(RequirementScreens.screen(8L, "Edit only", false, field.getId()));

        SetFieldRequiredResponse response = requireAllTypes(true);

        assertRefusedBeforeAnyWrite(response);
        assertEquals(SetFieldRequiredResponse.ErrorCode.FIELD_NOT_ON_CREATE_SCREEN, response.getErrorCode());
        assertEquals(2, response.getFieldErrors().size());
        String first = response.getFieldErrors().get(0).getMessage();
        assertEquals(true, first.contains("Bug") && first.contains("'No fields' (7)"));
        assertEquals(true, response.getFieldErrors().get(1).getMessage().contains("Task"));
    }

    @Test
    public void hiddenFieldOnTheCreateScreenIsRefused() {
        sharedLayoutForBothTypes(false);
        when(layouts.getFieldLayout(project, task.getId()).getFieldLayoutItem(field.getId()).isHidden()).thenReturn(true);

        SetFieldRequiredResponse response = requireAllTypes(true);

        assertRefusedBeforeAnyWrite(response);
        assertEquals(SetFieldRequiredResponse.ErrorCode.FIELD_HIDDEN, response.getErrorCode());
        assertEquals(true, response.getFieldErrors().get(0).getMessage().contains("hidden"));
    }

    @Test
    public void fieldOutsideItsContextKeepsTheScopeDiagnostic() {
        sharedLayoutForBothTypes(false);
        when(field.isInScope(eq(1L), anyString())).thenReturn(false);

        SetFieldRequiredResponse response = requireAllTypes(true);

        assertRefusedBeforeAnyWrite(response);
        assertEquals(SetFieldRequiredResponse.ErrorCode.FIELD_NOT_APPLICABLE, response.getErrorCode());
    }

    @Test
    public void alreadyRequiredButNotFillableOnCreateIsNotReportedAsANoOp() {
        sharedLayoutForBothTypes(true);
        screens.defaultScreen(RequirementScreens.screen(7L, "No fields", false));

        SetFieldRequiredResponse response = requireAllTypes(true);

        assertRefusedBeforeAnyWrite(response);
        assertEquals(SetFieldRequiredResponse.ErrorCode.FIELD_NOT_ON_CREATE_SCREEN, response.getErrorCode());
    }

    @Test
    public void makingOptionalDoesNotNeedTheFieldOnCreate() {
        sharedLayoutForBothTypes(true);
        screens.defaultScreen(RequirementScreens.screen(7L, "No fields", false));

        SetFieldRequiredResponse response = requireAllTypes(false);

        assertEquals(SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, response.getStatus());
        assertNotNull(response.getPlanId());
    }

    @Test
    public void fieldOnTheCreateScreenThroughTheDefaultMappingOrItsOwnItemPasses() {
        sharedLayoutForBothTypes(false);
        screens.defaultScreen(RequirementScreens.screen(7L, "Default has it", false, field.getId()));
        assertEquals(SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, requireAllTypes(true).getStatus());

        screens.defaultScreen(RequirementScreens.screen(7L, "No fields", false))
                .create(RequirementScreens.screen(9L, "Own create", false, field.getId()));
        assertEquals(SetFieldRequiredResponse.Status.WOULD_CHANGE_REQUIREMENTS, requireAllTypes(true).getStatus());
    }

    @Test
    public void unreadableScreensNeverGiveAPlan() {
        sharedLayoutForBothTypes(false);
        when(screens.manager.getIssueTypeScreenScheme(project)).thenReturn(null);

        SetFieldRequiredResponse response = requireAllTypes(true);

        assertRefusedBeforeAnyWrite(response);
        assertEquals(SetFieldRequiredResponse.ErrorCode.JIRA_ERROR, response.getErrorCode());
    }

    @Test
    public void fieldRemovedFromCreateAfterPreviewStopsTheOldPlanBeforeAnyWrite() {
        sharedLayoutForBothTypes(false);
        FieldScreen create = RequirementScreens.screen(7L, "Create", false, field.getId());
        screens.defaultScreen(create);
        SetFieldRequiredResponse preview = requireAllTypes(true);
        assertNotNull(preview.getPlanId());

        when(create.containsField(field.getId())).thenReturn(false);
        SetFieldRequiredResponse applied = service.setRequired(
                RequirementRequests.apply(preview.getPlanId()));

        assertEquals(SetFieldRequiredResponse.ErrorCode.PREVIEW_STALE, applied.getErrorCode());
        verify(layouts, never()).getEditableFieldLayout(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    public void switchingTheCreateScreenAfterPreviewStopsTheOldPlan() {
        sharedLayoutForBothTypes(false);
        screens.defaultScreen(RequirementScreens.screen(7L, "Create", false, field.getId()));
        SetFieldRequiredResponse preview = requireAllTypes(true);

        screens.defaultScreen(RequirementScreens.screen(9L, "Other create", false, field.getId()));
        SetFieldRequiredResponse applied = service.setRequired(
                RequirementRequests.apply(preview.getPlanId()));

        assertEquals(SetFieldRequiredResponse.ErrorCode.PREVIEW_STALE, applied.getErrorCode());
    }
}
