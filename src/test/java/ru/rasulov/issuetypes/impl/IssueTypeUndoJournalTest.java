package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.avatar.Avatar;
import com.atlassian.jira.avatar.AvatarManager;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesRequest;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;
import ru.rasulov.common.api.dto.issuetype.ItemStatus;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real AO/HSQL persistence; native Jira managers are isolated domain doubles. */
public class IssueTypeUndoJournalTest {
    @Test public void successfulCreationSurvivesCoordinatorRestartAndRestoresDefaultMembership() throws Exception {
        Domain domain=new Domain();
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedIssueTypeUndoHandler(domain.support))) {
            String operation=domain.create(fixture);
            assertNotNull(domain.created.get());
            ChangeJournal reopened=fixture.reopen();
            Map<String,Object> preview=reopened.rollback("admin",operation,null,null,true,null);
            assertEquals("READY",preview.get("status"));
            Map<String,Object> result=reopened.rollback("admin",null,null,null,false,(String)preview.get("planId"));
            assertEquals("ROLLED_BACK",result.get("status"));
            assertNull(domain.created.get());
            assertEquals(Collections.singletonList(domain.original),domain.members);
        }
    }
    @Test public void unrelatedDefaultSchemeEditBlocksTheWholeRollbackWithoutDeletingType() throws Exception {
        Domain domain=new Domain();
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedIssueTypeUndoHandler(domain.support))) {
            String operation=domain.create(fixture);
            IssueType foreign=type("99","Foreign");domain.members.add(foreign);
            Map<String,Object> preview=fixture.journal.rollback("admin",operation,null,null,true,null);
            assertEquals("BLOCKED",preview.get("status"));
            Map<String,Object> result=fixture.journal.rollback("admin",null,null,null,false,(String)preview.get("planId"));
            assertEquals("BLOCKED",result.get("status"));
            assertNotNull(domain.created.get());assertTrue(domain.members.contains(foreign));
            verify(domain.types,never()).removeIssueType(anyString(),any());
        }
    }
    @Test public void newIssueUsingCreatedTypeBlocksDeletion() throws Exception {
        Domain domain=new Domain();
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedIssueTypeUndoHandler(domain.support))) {
            String operation=domain.create(fixture);
            when(domain.types.hasAssociatedIssues(domain.created.get())).thenReturn(true);
            org.ofbiz.core.entity.GenericValue issue=mock(org.ofbiz.core.entity.GenericValue.class);when(issue.getLong("id")).thenReturn(99L);
            when(domain.delegator.findListIteratorByCondition(eq("Issue"),any(),any(),any(),any(),any()))
                    .thenAnswer(call -> ru.rasulov.common.impl.undo.Cursors.over(Collections.singletonList(issue)));
            assertEquals("BLOCKED",fixture.journal.rollback("admin",operation,null,null,true,null).get("status"));
            assertEquals("2",domain.created.get().getId());assertTrue(domain.members.contains(domain.created.get()));
        }
    }
    @Test public void missingNativeTypeIdentityNeverDeletesAnotherTypeFoundByName() throws Exception {
        Domain domain=new Domain();domain.hideResult=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedIssueTypeUndoHandler(domain.support))) {
            domain.create(fixture);
            assertEquals(CreateIssueTypesResponse.Status.PARTIALLY_CHANGED,domain.lastResponse.getStatus());
            assertEquals(ItemStatus.UNKNOWN,domain.lastResponse.getResults().get(0).getStatus());
            assertEquals("the found id is shown but not claimed as created","2",domain.lastResponse.getResults().get(0).getIssueType().getId());
            assertTrue(domain.lastResponse.getReport().getCreated().isEmpty());
            assertNotNull(domain.created.get());assertTrue(domain.members.contains(domain.created.get()));
            verify(domain.types,never()).removeIssueType(anyString(),any());
        }
    }
    @Test public void batchUndoTouchesOnlyTheTypesThisBatchCreatedNotTheReusedOne() throws Exception {
        Domain domain=new Domain();
        IssueType consult=type("5","Консультация");domain.members.add(consult);
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedIssueTypeUndoHandler(domain.support))) {
            String operation=domain.create(fixture,def("a","Оплаты"),def("c","Консультация"),def("b","Снабжение"));
            assertEquals(Arrays.asList("1","5","2","3"),domain.ids());
            assertEquals("two steps: only created types have undo evidence",2,
                    ((List<?>)fixture.journal.getOperation(operation,"admin").get("steps")).size());
            Map<String,Object> preview=fixture.journal.rollback("admin",operation,null,null,true,null);
            assertEquals(preview.toString(),"READY",preview.get("status"));
            assertEquals(2,((List<?>)preview.get("steps")).size());
            Map<String,Object> result=fixture.journal.rollback("admin",null,null,null,false,(String)preview.get("planId"));
            assertEquals(result.toString(),"ROLLED_BACK",result.get("status"));
            assertEquals("only the created ones were removed",Arrays.asList("1","5"),domain.ids());
            verify(domain.types).removeIssueType(eq("2"),isNull());
            verify(domain.types).removeIssueType(eq("3"),isNull());
            verify(domain.types,never()).removeIssueType(eq("5"),any());
            verify(domain.types,never()).removeIssueType(eq("1"),any());
        }
    }
    @Test public void batchUndoIsBlockedByIssuesOfACreatedTypeAndNeverTouchesTheReusedOne() throws Exception {
        Domain domain=new Domain();
        IssueType consult=type("5","Консультация");domain.members.add(consult);
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedIssueTypeUndoHandler(domain.support))) {
            String operation=domain.create(fixture,def("a","Оплаты"),def("c","Консультация"),def("b","Снабжение"));
            IssueType second=domain.byId("3");
            when(domain.types.hasAssociatedIssues(second)).thenReturn(true);
            org.ofbiz.core.entity.GenericValue issue=mock(org.ofbiz.core.entity.GenericValue.class);when(issue.getLong("id")).thenReturn(99L);
            when(domain.delegator.findListIteratorByCondition(eq("Issue"),any(),any(),any(),any(),any()))
                    .thenAnswer(call -> ru.rasulov.common.impl.undo.Cursors.over(Collections.singletonList(issue)));
            Map<String,Object> preview=fixture.journal.rollback("admin",operation,null,null,true,null);
            assertEquals(preview.toString(),"BLOCKED",preview.get("status"));
            fixture.journal.rollback("admin",null,null,null,false,(String)preview.get("planId"));
            verify(domain.types,never()).removeIssueType(eq("3"),any());
            verify(domain.types,never()).removeIssueType(eq("5"),any());
            assertEquals(Arrays.asList("1","5","2","3"),domain.ids());
        }
    }
    @Test public void typeOfUnknownOriginIsNeverRemovedByUndoOfItsBatch() throws Exception {
        Domain domain=new Domain();domain.failOn="Снабжение";domain.leaveBehind=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin",new CreatedIssueTypeUndoHandler(domain.support))) {
            String operation=domain.create(fixture,def("a","Оплаты"),def("b","Снабжение"),def("c","Склад"));
            assertEquals(CreateIssueTypesResponse.Status.PARTIALLY_CHANGED,domain.lastResponse.getStatus());
            assertEquals(Arrays.asList(ItemStatus.CREATED,ItemStatus.UNKNOWN,
                    ItemStatus.NOT_EXECUTED),statuses(domain.lastResponse));
            assertEquals("the observed type has an id","3",domain.lastResponse.getResults().get(1).getIssueType().getId());
            List<?> steps=(List<?>)fixture.journal.getOperation(operation,"admin").get("steps");
            assertEquals("APPLIED",((Map<?,?>)steps.get(0)).get("status"));
            assertEquals("the unknown write stays an unconfirmed intent","PREPARED",((Map<?,?>)steps.get(1)).get("status"));
            Map<String,Object> preview=fixture.journal.rollback("admin",operation,null,null,true,null);
            fixture.journal.rollback("admin",null,null,null,false,(String)preview.get("planId"));
            verify(domain.types,never()).removeIssueType(eq("3"),any());
            assertNotNull("the observed type survives",domain.byId("3"));
        }
    }
    private static List<ItemStatus> statuses(CreateIssueTypesResponse response) {
        List<ItemStatus> result=new ArrayList<ItemStatus>();
        for(CreateIssueTypesResponse.ItemResult item:response.getResults())result.add(item.getStatus());
        return result;
    }
    private static IssueTypeDefinition def(String key,String name) {
        return new IssueTypeDefinition(key,name,"",false);
    }
    private static IssueType type(String id,String name) {
        IssueType type=mock(IssueType.class);when(type.getId()).thenReturn(id);when(type.getName()).thenReturn(name);
        when(type.getDescription()).thenReturn("");return type;
    }
    private static final class Domain {
        final IssueTypeManager types=mock(IssueTypeManager.class);
        final IssueTypeSchemeManager schemes=mock(IssueTypeSchemeManager.class);
        final AvatarManager avatars=mock(AvatarManager.class);
        final IssueType original=type("1","Task");
        final AtomicReference<IssueType> created=new AtomicReference<IssueType>();
        final List<IssueType> members=new ArrayList<IssueType>(Collections.singletonList(original));
        final FieldConfigScheme scheme=mock(FieldConfigScheme.class);
        final FieldConfig config=mock(FieldConfig.class);
        final OfBizDelegator delegator=mock(OfBizDelegator.class);
        final WorkflowSchemeManager workflows=mock(WorkflowSchemeManager.class);
        boolean hideResult;
        String failOn;boolean leaveBehind;
        final java.util.concurrent.atomic.AtomicInteger nextId=new java.util.concurrent.atomic.AtomicInteger(2);
        final IssueTypeUndoSupport support=new IssueTypeUndoSupport(types,schemes,delegator,
                workflows,mock(IssueTypeScreenSchemeManager.class));
        Domain() {
            when(delegator.findListIteratorByCondition(anyString(),any(),any(),any(),any(),any()))
                    .thenAnswer(call -> ru.rasulov.common.impl.undo.Cursors.over(Collections.emptyList()));
            when(workflows.getAssignableSchemes()).thenReturn(Collections.emptyList());
            when(scheme.getId()).thenReturn(10L);when(scheme.getOneAndOnlyConfig()).thenReturn(config);
            when(schemes.getDefaultIssueTypeSchemeId()).thenReturn(10L);when(schemes.getDefaultIssueTypeScheme()).thenReturn(scheme);
            when(schemes.getIssueTypesForScheme(scheme)).thenAnswer(call -> new ArrayList<IssueType>(members));
            when(schemes.getDefaultValue(config)).thenReturn(original);when(schemes.isDefaultIssueTypeScheme(scheme)).thenReturn(true);
            when(schemes.getAllRelatedSchemes(anyString())).thenReturn(Collections.singletonList(scheme));
            when(types.getIssueTypes()).thenAnswer(call -> new ArrayList<IssueType>(members));
            when(types.getIssueType(anyString())).thenAnswer(call -> {
                for(IssueType type:members)if(type.getId().equals(call.getArgument(0)))return type;return null;
            });
            when(avatars.getDefaultAvatarId(Avatar.Type.ISSUETYPE)).thenReturn(17L);
            when(types.createIssueType(anyString(),anyString(),any(Long.class))).thenAnswer(call -> {
                String name=call.getArgument(0);
                IssueType type=type(String.valueOf(nextId.getAndIncrement()),name);
                if(name.equals(failOn)){if(leaveBehind)members.add(type);throw new IllegalStateException("Jira storage error");}
                created.set(type);members.add(type);return hideResult?null:type;
            });
            doAnswer(call -> { members.removeIf(type -> type.getId().equals(call.getArgument(0)));created.set(null);return null; })
                    .when(types).removeIssueType(anyString(),isNull());
            when(schemes.update(eq(scheme),anyCollection())).thenAnswer(call -> scheme);
        }
        CreateIssueTypesResponse lastResponse;
        List<String> ids(){List<String> ids=new ArrayList<String>();for(IssueType type:members)ids.add(type.getId());return ids;}
        IssueType byId(String id){for(IssueType type:members)if(type.getId().equals(id))return type;return null;}
        String create(UndoTestFixture fixture) {
            return create(fixture,new IssueTypeDefinition("release","Release","",false));
        }
        String create(UndoTestFixture fixture,IssueTypeDefinition... definitions) {
            IssueTypeCreatorImpl creator=new IssueTypeCreatorImpl(fixture.guard,fixture.locks,
                    new ru.rasulov.common.impl.PreviewPlanExecutor(fixture.plans,new ActionPlanCodec()),new ActionPlanCodec(),fixture.journal,new IssueTypeBatchPlanner(types,avatars),
                    new IssueTypeBatchWriter(types,fixture.journal,support),ru.rasulov.common.impl.TestLinks.links());
            CreateIssueTypesResponse preview=creator.create(new CreateIssueTypesRequest(true,Arrays.asList(definitions),null));
            String session=fixture.journal.createSession("admin");
            String set=fixture.journal.createChangeSet(session,"admin");
            String operation=fixture.journal.openOperation("create_issue_types","admin",session,set);
            lastResponse=fixture.journal.runOperation(operation,() -> creator.create(
                    new CreateIssueTypesRequest(false,null,preview.getPlanId())));
            if(!hideResult&&failOn==null)assertEquals(CreateIssueTypesResponse.Status.CREATED,lastResponse.getStatus());
            return operation;
        }
    }
}
