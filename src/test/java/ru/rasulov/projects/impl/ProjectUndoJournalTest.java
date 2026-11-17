package ru.rasulov.projects.impl;

import com.atlassian.jira.bc.project.ProjectCreationData;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.bc.user.search.UserSearchService;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.issue.fields.screen.issuetype.*;
import com.atlassian.jira.notification.NotificationSchemeManager;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.permission.PermissionSchemeManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.project.template.*;
import com.atlassian.jira.project.type.ProjectTypeKey;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.*;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.projects.api.dto.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Native mutable Jira model with real AO/HSQL journal persistence. */
public class ProjectUndoJournalTest {
    @Test public void nativeCreationAndDeletionSurviveCoordinatorRestartWithoutRemovingSharedSchemes() throws Exception {
        Domain domain=new Domain();
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            String operation=domain.create(fixture);
            assertEquals("ALPHA",domain.current.get().getKey());
            Map<String,Object> preview=fixture.reopen().rollback("admin",operation,null,null,true,null);
            assertEquals("READY",preview.get("status"));
            assertEquals("ROLLED_BACK",fixture.reopen().rollback("admin",null,null,null,false,(String)preview.get("planId")).get("status"));
            assertNull(domain.current.get());
            assertEquals(Collections.singletonList(domain.typeScheme),domain.types.getAllSchemes());
            verify(domain.types,never()).deleteScheme(any());
        }
    }
    @Test public void templateScreenSchemeDeletionRemovesItsChildAssociations() throws Exception {
        Domain domain=new Domain();domain.createTemplateScreens=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            String operation=domain.create(fixture);
            Map<String,Object> preview=fixture.reopen().rollback("admin",operation,null,null,true,null);
            assertEquals(preview.toString(),"READY",preview.get("status"));
            Map<String,Object> result=fixture.reopen().rollback("admin",null,null,null,false,(String)preview.get("planId"));
            assertEquals(result.toString(),"ROLLED_BACK",result.get("status"));
            assertNull(domain.current.get());
            assertFalse(domain.topLinks);
            assertFalse(domain.childItems);
            assertTrue(domain.topRemoved);
            assertTrue(domain.childRemoved);
        }
    }
    @Test public void newlyCreatedIssueBlocksWholeProjectDestruction() throws Exception {
        Domain domain=new Domain();
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            String operation=domain.create(fixture);domain.issueCount=1;
            Map<String,Object> preview=fixture.journal.rollback("admin",operation,null,null,true,null);
            assertEquals("BLOCKED",preview.get("status"));
            assertEquals("BLOCKED",fixture.journal.rollback("admin",null,null,null,false,(String)preview.get("planId")).get("status"));
            assertNotNull(domain.current.get());
            verify(domain.service,never()).deleteProject(any(),any());
        }
    }
    @Test public void editedProjectBlocksDeletionEvenWhenItStillHasTheCreatedKey() throws Exception {
        Domain domain=new Domain();
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            String operation=domain.create(fixture);domain.name="Changed by somebody else";
            assertEquals("BLOCKED",fixture.journal.rollback("admin",operation,null,null,true,null).get("status"));
            assertEquals("Changed by somebody else",domain.current.get().getName());
            verify(domain.service,never()).deleteProject(any(),any());
        }
    }
    @Test public void missingNativeReturnedIdentityNeverDeletesAProjectFoundByMatchingKey() throws Exception {
        Domain domain=new Domain();domain.hideResult=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            try {domain.create(fixture);fail("Missing created identity must fail");}catch(IllegalStateException expected) { }
            assertNotNull(domain.current.get());
            verify(domain.service,never()).deleteProject(any(),any());
        }
    }
    @Test public void templateOwnedTypeIsDeletedByConfirmedIdWhileForeignTypeIsPreserved() throws Exception {
        Domain domain=new Domain();domain.createTemplateType=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            String operation=domain.create(fixture);
            IssueType foreign=Domain.type("99","Foreign");domain.templateTypes.add(foreign);
            Map<String,Object> preview=fixture.journal.rollback("admin",operation,null,null,true,null);
            assertEquals("READY",preview.get("status"));
            assertEquals("ROLLED_BACK",fixture.journal.rollback("admin",null,null,null,false,(String)preview.get("planId")).get("status"));
            assertEquals(Collections.singletonList(foreign),domain.templateTypes);
        }
    }
    @Test public void softwareProjectUndoIsRefusedFromTheJournalSnapshotBeforeAnyDeletion() throws Exception {
        Domain domain=new Domain();domain.templateKey=ProjectTemplates.SCRUM;domain.projectType="software";
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            String operation=domain.create(fixture);
            Map<String,Object> preview=fixture.journal.rollback("admin",operation,null,null,true,null);
            assertEquals(preview.toString(),"BLOCKED",preview.get("status"));
            assertTrue(preview.toString(),preview.toString().contains("Software"));
            verify(domain.service,never()).validateDeleteProject(any(),anyString());
            verify(domain.service,never()).deleteProject(any(),any());
            assertNotNull("the project and its board stay",domain.current.get());
        }
    }
    @Test public void softwareIsDecidedByTheRecordedSnapshotNotByTheCurrentJiraProject() throws Exception {
        Domain domain=new Domain();
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            domain.create(fixture);
            assertEquals("business",domain.current.get().getProjectTypeKey().getKey());
            Map<String,Object> recorded=new HashMap<String,Object>();
            Map<String,Object> project=new HashMap<String,Object>();project.put("id",11L);project.put("type","software");
            recorded.put("project",project);recorded.put("artifacts",new HashMap<String,Object>());
            try{domain.support.undo(recorded,fixture.currentActor.get());fail("Software undo must be refused");}
            catch(IllegalStateException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("Software"));}
            verify(domain.service,never()).validateDeleteProject(any(),anyString());
            verify(domain.service,never()).deleteProject(any(),any());
            assertNotNull(domain.current.get());
        }
    }
    @Test public void foreignSchemeReferenceBlocksProjectAndTemplateTypeDeletionTogether() throws Exception {
        Domain domain=new Domain();domain.createTemplateType=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            String operation=domain.create(fixture);
            FieldConfigScheme foreign=mock(FieldConfigScheme.class);when(foreign.getId()).thenReturn(99L);
            when(domain.types.getAllRelatedSchemes("50")).thenReturn(Arrays.asList(domain.typeScheme,foreign));
            assertEquals("BLOCKED",fixture.journal.rollback("admin",operation,null,null,true,null).get("status"));
            assertNotNull(domain.current.get());assertEquals("50",domain.templateTypes.get(0).getId());
        }
    }
    @Test public void templateWorkflowAndItsNewStatusAreRemovedAfterTheirOwnedScheme() throws Exception {
        Domain domain=new Domain();domain.createTemplateWorkflow=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            String operation=domain.create(fixture);
            Map<String,Object> preview=fixture.reopen().rollback("admin",operation,null,null,true,null);
            assertEquals("READY",preview.get("status"));
            assertEquals("ROLLED_BACK",fixture.reopen().rollback("admin",null,null,null,false,(String)preview.get("planId")).get("status"));
            assertNull(domain.ownedWorkflowScheme);assertNull(domain.ownedWorkflow);assertNull(domain.ownedStatus);
            assertNull(domain.current.get());
        }
    }
    @Test public void newStatusForeignWorkflowReferencePreventsAnyTemplateDestruction() throws Exception {
        Domain domain=new Domain();domain.createTemplateWorkflow=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedProjectUndoHandler(domain.support))) {
            String operation=domain.create(fixture);domain.foreignStatusReference=true;
            assertEquals("BLOCKED",fixture.journal.rollback("admin",operation,null,null,true,null).get("status"));
            assertNotNull(domain.current.get());assertNotNull(domain.ownedStatus);assertNotNull(domain.ownedWorkflowScheme);
        }
    }
    private static final class Domain {
        final ProjectManager projects=mock(ProjectManager.class);
        final ProjectService service=mock(ProjectService.class);
        final IssueManager issues=mock(IssueManager.class);
        final IssueTypeSchemeManager types=mock(IssueTypeSchemeManager.class);
        final WorkflowSchemeManager workflows=mock(WorkflowSchemeManager.class);
        final IssueTypeScreenSchemeManager screens=mock(IssueTypeScreenSchemeManager.class);
        final FieldScreenSchemeManager fieldScreens=mock(FieldScreenSchemeManager.class);
        final ProjectTemplateManager templates=mock(ProjectTemplateManager.class);
        final FieldConfigScheme typeScheme=mock(FieldConfigScheme.class);
        final AssignableWorkflowScheme workflowScheme=mock(AssignableWorkflowScheme.class);
        final IssueTypeScreenScheme screenScheme=mock(IssueTypeScreenScheme.class);
        final AtomicReference<Project> current=new AtomicReference<Project>();
        final IssueTypeManager issueTypes=mock(IssueTypeManager.class);
        final List<IssueType> templateTypes=new ArrayList<IssueType>();
        final WorkflowManager workflowManager=mock(WorkflowManager.class);
        final StatusManager statuses=mock(StatusManager.class);
        final OfBizDelegator delegator=mock(OfBizDelegator.class);
        AssignableWorkflowScheme ownedWorkflowScheme;
        JiraWorkflow ownedWorkflow;
        com.atlassian.jira.issue.status.Status ownedStatus;
        final ProjectUndoSupport support=new ProjectUndoSupport(projects,service,issues,types,workflows,
                workflowManager,screens,fieldScreens,mock(FieldScreenManager.class),
                mock(PermissionSchemeManager.class),mock(NotificationSchemeManager.class),delegator,
                mock(ProjectRoleManager.class),new ActionPlanCodec(),issueTypes,statuses);
        String name="Project Alpha",templateKey=ProjectTemplates.PROJECT_MANAGEMENT,projectType="business";long issueCount;boolean hideResult,createTemplateType,createTemplateWorkflow,foreignStatusReference;
        boolean createTemplateScreens,topCreated,topRemoved,topLinks,childRemoved,childItems;
        Domain() throws Exception {
            when(typeScheme.getId()).thenReturn(1L);when(workflowScheme.getId()).thenReturn(2L);when(screenScheme.getId()).thenReturn(3L);
            when(types.getConfigScheme(any(Project.class))).thenReturn(typeScheme);
            when(types.getAllSchemes()).thenReturn(Collections.singletonList(typeScheme));
            when(types.getDefaultIssueTypeScheme()).thenReturn(typeScheme);
            when(issueTypes.getIssueTypes()).thenAnswer(call->new ArrayList<IssueType>(templateTypes));
            when(issueTypes.getIssueType(anyString())).thenAnswer(call->{
                for(IssueType type:templateTypes)if(type.getId().equals(call.getArgument(0)))return type;return null;
            });
            doAnswer(call->{templateTypes.removeIf(type->type.getId().equals(call.getArgument(0)));return null;})
                    .when(issueTypes).removeIssueType(anyString(),isNull());
            when(types.getIssueTypesForScheme(typeScheme)).thenAnswer(call->new ArrayList<IssueType>(templateTypes));
            when(types.getAllRelatedSchemes(anyString())).thenReturn(Collections.singletonList(typeScheme));
            when(types.isDefaultIssueTypeScheme(typeScheme)).thenReturn(true);
            when(workflows.getWorkflowSchemeObj(any(Project.class))).thenAnswer(call->ownedWorkflowScheme==null?workflowScheme:ownedWorkflowScheme);
            when(workflows.getWorkflowSchemeObj(anyLong())).thenAnswer(call->ownedWorkflowScheme!=null && ownedWorkflowScheme.getId().equals(call.getArgument(0))?ownedWorkflowScheme:null);
            when(workflows.getAssignableSchemes()).thenAnswer(call->ownedWorkflowScheme==null?Collections.singletonList(workflowScheme):Arrays.asList(workflowScheme,ownedWorkflowScheme));
            when(workflows.deleteWorkflowScheme(any())).thenAnswer(call->{ownedWorkflowScheme=null;return true;});
            when(workflowManager.getWorkflow(anyString())).thenAnswer(call->ownedWorkflow!=null && ownedWorkflow.getName().equals(call.getArgument(0))?ownedWorkflow:null);
            when(workflowManager.getWorkflows()).thenAnswer(call->ownedWorkflow==null?Collections.emptyList():Collections.singletonList(ownedWorkflow));
            when(workflowManager.getWorkflowsIncludingDrafts()).thenAnswer(call->{
                List<JiraWorkflow> values=new ArrayList<JiraWorkflow>();if(ownedWorkflow!=null)values.add(ownedWorkflow);
                if(foreignStatusReference) {
                    JiraWorkflow foreign=mock(JiraWorkflow.class);when(foreign.getName()).thenReturn("Foreign");
                    when(foreign.getLinkedStatusIds()).thenReturn(Collections.singleton("60"));values.add(foreign);
                }
                return values;
            });
            doAnswer(call->{ownedWorkflow=null;return null;}).when(workflowManager).deleteWorkflow(any());
            when(statuses.getStatuses()).thenAnswer(call->ownedStatus==null?Collections.emptyList():Collections.singletonList(ownedStatus));
            when(statuses.getStatus("60")).thenAnswer(call->ownedStatus);
            doAnswer(call->{ownedStatus=null;return null;}).when(statuses).removeStatus("60");
            GenericValue workflowRow=mock(GenericValue.class);when(workflowRow.getLong("id")).thenReturn(200L);
            when(delegator.findByCondition(eq("Workflow"),any(),any(),isNull())).thenReturn(Collections.singletonList(workflowRow));
            when(workflows.getSchemesForWorkflowIncludingDrafts(any())).thenReturn(Collections.emptyList());
            when(screens.getIssueTypeScreenScheme(any(Project.class))).thenReturn(screenScheme);
            when(screens.getIssueTypeScreenSchemes()).thenAnswer(call->
                    (createTemplateScreens && !topCreated) || topRemoved ? Collections.emptyList():Collections.singletonList(screenScheme));
            when(screens.getIssueTypeScreenScheme(3L)).thenAnswer(call->topCreated&&!topRemoved?screenScheme:null);
            doAnswer(call->{topLinks=false;return null;}).when(screens).removeIssueTypeSchemeEntities(screenScheme);
            doAnswer(call->{topRemoved=true;return null;}).when(screens).removeIssueTypeScreenScheme(screenScheme);
            when(projects.getProjectObjects()).thenAnswer(call->current.get()==null?Collections.emptyList():Collections.singletonList(current.get()));
            when(projects.getProjectObj(11L)).thenAnswer(call->current.get());
            when(issues.getIssueCountForProject(11L)).thenAnswer(call->issueCount);
            when(templates.getProjectTemplate(any(ProjectTemplateKey.class))).thenAnswer(call->{
                ProjectTemplate template=mock(ProjectTemplate.class);
                when(template.getKey()).thenReturn(new ProjectTemplateKey(templateKey));
                when(template.getProjectTypeKey()).thenReturn(new ProjectTypeKey(projectType));
                return Optional.of(template);
            });
            ProjectService.CreateProjectValidationResult create=mock(ProjectService.CreateProjectValidationResult.class);
            when(create.isValid()).thenReturn(true);
            when(service.validateCreateProject(any(ApplicationUser.class),any(ProjectCreationData.class))).thenReturn(create);
            when(service.createProject(create)).thenAnswer(call->{
                if(createTemplateType)templateTypes.add(type("50","Template type"));
                if(createTemplateScreens) {
                    topCreated=true;topLinks=true;childItems=true;
                    FieldScreenScheme child=mock(FieldScreenScheme.class);when(child.getId()).thenReturn(4L);
                    IssueTypeScreenSchemeEntity link=mock(IssueTypeScreenSchemeEntity.class);
                    when(link.getFieldScreenScheme()).thenReturn(child);
                    when(screenScheme.getEntities()).thenReturn(Collections.singletonList(link));
                    when(fieldScreens.getFieldScreenScheme(4L)).thenAnswer(inv->childRemoved?null:child);
                    when(screens.getIssueTypeScreenSchemes(child)).thenAnswer(inv->topLinks?Collections.singletonList(topRemoved?null:screenScheme):Collections.emptyList());
                    doAnswer(inv->{childRemoved=true;return null;}).when(fieldScreens).removeFieldScreenScheme(child);
                    doAnswer(inv->{childItems=false;return null;}).when(fieldScreens).removeFieldSchemeItems(child);
                }
                if(createTemplateWorkflow) {
                    ownedStatus=mock(com.atlassian.jira.issue.status.Status.class);
                    when(ownedStatus.getId()).thenReturn("60");when(ownedStatus.getName()).thenReturn("Template status");
                    ownedWorkflow=mock(JiraWorkflow.class);when(ownedWorkflow.getName()).thenReturn("Template workflow");
                    when(ownedWorkflow.getDescriptor()).thenReturn(mock(com.opensymphony.workflow.loader.WorkflowDescriptor.class));
                    when(ownedWorkflow.getLinkedStatusObjects()).thenReturn(Collections.singletonList(ownedStatus));
                    when(ownedWorkflow.getLinkedStatusIds()).thenReturn(Collections.singleton("60"));
                    ownedWorkflowScheme=mock(AssignableWorkflowScheme.class);when(ownedWorkflowScheme.getId()).thenReturn(12L);
                    when(ownedWorkflowScheme.getConfiguredDefaultWorkflow()).thenReturn("Template workflow");
                }
                Project project=mock(Project.class);when(project.getId()).thenReturn(11L);when(project.getKey()).thenReturn("ALPHA");
                when(project.getName()).thenAnswer(inv->name);when(project.getLeadUserKey()).thenReturn("admin");
                when(project.getProjectTypeKey()).thenReturn(new ProjectTypeKey(projectType));
                when(project.getGenericValue()).thenReturn(mock(GenericValue.class));current.set(project);return hideResult?null:project;
            });
            ProjectService.DeleteProjectValidationResult delete=mock(ProjectService.DeleteProjectValidationResult.class);
            when(delete.isValid()).thenReturn(true);when(service.validateDeleteProject(any(),eq("ALPHA"))).thenReturn(delete);
            doAnswer(call->{current.set(null);return null;}).when(service).deleteProject(any(),eq(delete));
        }
        static IssueType type(String id,String name) {
            IssueType type=mock(IssueType.class);when(type.getId()).thenReturn(id);when(type.getName()).thenReturn(name);return type;
        }
        String create(UndoTestFixture fixture) {
            ApplicationUser actor=fixture.currentActor.get();when(actor.getUsername()).thenReturn("admin");
            when(fixture.users.getUserByName("admin")).thenReturn(actor);
            ProjectCreatorImpl creator=new ProjectCreatorImpl(service,projects,templates,fixture.users,mock(UserSearchService.class),
                    fixture.guard,fixture.locks,fixture.plans,new ActionPlanCodec(),fixture.journal,support,
                    ru.rasulov.common.impl.TestLinks.links(), new ru.rasulov.issuetypes.impl.IssueTypeSetsImpl(
                    org.mockito.Mockito.mock(ru.rasulov.issuetypes.impl.IssueTypeSetPlanner.class),
                    org.mockito.Mockito.mock(ru.rasulov.issuetypes.impl.IssueTypeSetExecutor.class), fixture.locks));
            CreateProjectResponse preview=creator.create(ProjectRequests.preview(templateKey,name,"ALPHA","admin"));
            assertEquals(CreateProjectResponse.Status.WOULD_CREATE,preview.getStatus());
            String session=fixture.journal.createSession("admin"),set=fixture.journal.createChangeSet(session,"admin");
            String operation=fixture.journal.openOperation("create_project","admin",session,set);
            CreateProjectResponse result=fixture.journal.runOperation(operation,()->creator.create(ProjectRequests.apply(preview.getPlanId())));
            assertEquals(CreateProjectResponse.Status.CREATED,result.getStatus());return operation;
        }
    }
}
