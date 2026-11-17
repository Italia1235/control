package ru.rasulov.workflow.impl.edit;

import org.junit.Test;
import ru.rasulov.common.rest.RequestBodies;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static ru.rasulov.workflow.impl.edit.WorkflowConfigurationNativeTest.*;

/** Existing newStatus is one complete native plan, not a separate global-status workaround. */
public class WorkflowExistingNewStatusTest {
    static ConfigureWorkflowRequest request(WorkflowConfigurationFixture f,String changes) {
        return request(f,"ACTIVE",changes);
    }
    private static ConfigureWorkflowRequest request(WorkflowConfigurationFixture f,String version,String changes) {
        return RequestBodies.parse("{\"dryRun\":true,\"existing\":{\"workflow\":\"Support\",\"version\":\""+version+"\",\"expectedRevision\":\""+f.revision("Support")+"\",\"changes\":"+changes+"}}",ConfigureWorkflowRequest.class,"request");
    }
    static String add(String name,String key) {
        return "{\"op\":\"ADD_STATUS\",\"clientKey\":\""+key+"\",\"newStatus\":{\"name\":\""+name+"\",\"category\":\"IN_PROGRESS\",\"description\":\" native description \"}}";
    }
    static WorkflowConfigurationFixture world() {
        WorkflowConfigurationFixture f=new WorkflowConfigurationFixture();
        existingGraph(f,"Support",false);return f;
    }
    @Test public void omittedDescriptionCreatesThroughPersistedPreview() {
        omittedDescriptionApplies(false);
    }
    @Test public void omittedDescriptionReusesThroughPersistedPreview() {
        omittedDescriptionApplies(true);
    }
    private void omittedDescriptionApplies(boolean reuse) {
        try(WorkflowConfigurationFixture f=world()) {
            if(reuse) {
                var existing=f.status("88","Review","done");
                when(existing.getDescription()).thenReturn("kept description");
            }
            int before=f.statusStore.size();
            ConfigureWorkflowResponse preview=f.preview(request(f,
                    "[{\"op\":\"ADD_STATUS\",\"clientKey\":\"review\",\"newStatus\":{\"name\":\"Review\",\"category\":\"IN_PROGRESS\"}}]"));
            assertEquals(preview.getMessage(),"WOULD_CHANGE",preview.getStatus());
            assertNotNull(preview.getPlanId());assertTrue(f.nativeCalls.isEmpty());
            ConfigureWorkflowResponse done=f.apply(preview);
            assertEquals(done.getMessage(),"UPDATED_INACTIVE",done.getStatus());
            var status=done.getObjects().stream().filter(x->"STATUS".equals(x.get("kind"))).findFirst().orElseThrow();
            String id=status.get("id").toString();
            assertEquals(reuse?"REUSE":"CREATE",status.get("action"));
            assertEquals(reuse?"kept description":"",f.statusStore.get(id).getDescription());
            assertEquals(before+(reuse?0:1),f.statusStore.size());
            assertEquals(id,DescriptorFacts.statusId(DescriptorFacts.step(f.main.get("Support"),((Number)status.get("stepId")).intValue())));
            assertEquals(reuse?List.of("workflow-save"):List.of("status","workflow-save"),f.nativeCalls);
            if(reuse) assertEquals("88",id);
            else assertEquals("MATCH",step(done,"status:0").get("verification"));
        }
    }
    @Test public void legacyCreateStatusesKeepNullDescriptionInPersistedRequest() throws Exception {
        try(WorkflowConfigurationFixture f=world()) {
            var codec=new ru.rasulov.common.impl.ActionPlanCodec();
            var create=new ConfigureWorkflowRequest(true,f.createGraph("Legacy"),null,null,null,null);
            String persisted=codec.request(create);
            var status=new com.fasterxml.jackson.databind.ObjectMapper().readTree(persisted).path("create").path("statuses").get(0);
            assertTrue(status.has("description"));assertTrue(status.get("description").isNull());
            assertNull(codec.mode(persisted,false,ConfigureWorkflowRequest.class).create().getStatuses().get(0).getDescription());
            ConfigureWorkflowResponse done=f.apply(f.preview(create));
            assertEquals(done.getMessage(),"CREATED",done.getStatus());
        }
    }
    @Test public void existingNewStatusCreatesNativeStatusAndBindsItsReturnedId() {
        try(WorkflowConfigurationFixture f=world()) {
            ConfigureWorkflowResponse preview=f.preview(request(f,"["+add(" Review ","review")+"]"));
            assertEquals(preview.getMessage(),"WOULD_CHANGE",preview.getStatus());
            assertTrue(f.nativeCalls.isEmpty());assertNotNull(preview.getPlanId());
            ConfigureWorkflowResponse done=f.apply(preview);
            assertEquals(done.getMessage(),"UPDATED_INACTIVE",done.getStatus());
            Map<String,Object> status=done.getObjects().stream().filter(x->"STATUS".equals(x.get("kind"))).findFirst().orElseThrow();
            assertEquals("CREATE",status.get("action"));assertEquals(true,status.get("global"));
            String id=status.get("id").toString();assertEquals("Review",f.statusStore.get(id).getName());
            assertEquals("native description",f.statusStore.get(id).getDescription());
            assertEquals(id,DescriptorFacts.statusId(DescriptorFacts.step(f.main.get("Support"),((Number)status.get("stepId")).intValue())));
            assertEquals(List.of("status","workflow-save"),f.nativeCalls);
            assertEquals("MATCH",step(done,"status:0").get("verification"));
        }
    }
    @Test public void sparseReorderedStepsAndTwoNewStatusesUseExplicitStepIds() {
        try(WorkflowConfigurationFixture f=world()) {
            var descriptor=f.main.get("Support");
            var first=DescriptorFacts.steps(descriptor).get(0);
            int old=first.getId();first.setId(27);
            for(var located:DescriptorFacts.locate(descriptor).values())
                if(located.action().getUnconditionalResult().getStep()==old)located.action().getUnconditionalResult().setStep(27);
            Collections.reverse(descriptor.getSteps());
            ConfigureWorkflowResponse preview=f.preview(request(f,"["+add("Review","review")+","+add("Test","test")+"]"));
            assertNotNull(preview.getMessage(),preview.getPlanId());
            ConfigureWorkflowResponse done=f.apply(preview);
            assertEquals(done.getMessage(),"UPDATED_INACTIVE",done.getStatus());
            for(var status:done.getObjects()) if("STATUS".equals(status.get("kind"))) {
                assertTrue(((Number)status.get("stepId")).intValue()>27);
                String id=status.get("id").toString();
                assertEquals(id,DescriptorFacts.statusId(DescriptorFacts.step(f.main.get("Support"),((Number)status.get("stepId")).intValue())));
                assertEquals(status.get("name"),f.statusStore.get(id).getName());
            }
            assertFalse(DescriptorFacts.definition(f.main.get("Support")).contains("LOCAL_STATUS_"));
            assertEquals(List.of("status","status","workflow-save"),f.nativeCalls);
        }
    }
    @Test public void createGraphReportsCreatedAndReusedStatusIdsThroughPersistedApply() {
        try(WorkflowConfigurationFixture f=world()) {
            var request=RequestBodies.parse("{\"dryRun\":true,\"create\":{\"name\":\"Created\",\"statuses\":["
                    +"{\"name\":\"Open\"},{\"name\":\"Review LOCAL_STATUS_1\",\"category\":\"IN_PROGRESS\"}],"
                    +"\"initialStatus\":\"Open\",\"transitions\":[{\"key\":\"review\",\"name\":\"Review\","
                    +"\"from\":\"Open\",\"to\":\"Review LOCAL_STATUS_1\"}]}}",ConfigureWorkflowRequest.class,"request");
            var preview=f.preview(request);
            assertNotNull(preview.getMessage(),preview.getPlanId());
            var done=f.apply(preview);
            assertEquals(done.getMessage(),"CREATED",done.getStatus());
            assertEquals("1",done.getStatuses().get(0).getId());
            String id=step(done,"status:1").get("id").toString();
            assertEquals(id,done.getStatuses().get(1).getId());
            var object=done.getObjects().stream().filter(x->"STATUS".equals(x.get("kind"))).findFirst().orElseThrow();
            assertEquals(id,object.get("id"));
            var bound=DescriptorFacts.steps(f.main.get("Created")).stream()
                    .filter(s->"Review LOCAL_STATUS_1".equals(s.getName())).findFirst().orElseThrow();
            assertEquals(id,DescriptorFacts.statusId(bound));
            assertEquals(List.of("status","workflow-copy","workflow-save"),f.nativeCalls);
        }
    }
    @Test public void sameNamedExplicitStatusesKeepDistinctIdsThroughPersistedApply() {
        for(int mode=0;mode<3;mode++) try(WorkflowConfigurationFixture f=world()) {
            prepareSparseTarget(f,mode);
            f.status("88","Review","done");f.status("89","Review","indeterminate");
            var preview=f.preview(request(f,mode==2?"DRAFT":"ACTIVE","[{\"op\":\"ADD_STATUS\",\"statusId\":\"88\",\"clientKey\":\"first\"},"
                    +"{\"op\":\"ADD_STATUS\",\"statusId\":\"89\",\"clientKey\":\"second\"}]"));
            assertNotNull(preview.getMessage(),preview.getPlanId());
            var done=f.apply(preview);
            assertEquals(done.getMessage(),mode==0?"UPDATED_INACTIVE":"DRAFT_SAVED",done.getStatus());
            var descriptor=mode==0?f.main.get("Support"):f.drafts.get("Support");
            var objects=done.getObjects().stream().filter(x->"STATUS".equals(x.get("kind"))).collect(java.util.stream.Collectors.toList());
            assertEquals(2,objects.size());
            for(int i=0;i<2;i++) {
                String id=i==0?"88":"89";
                var object=objects.get(i);
                assertEquals(id,done.getStatuses().get(i).getId());
                assertEquals(id,object.get("id"));
                assertEquals("REUSE",object.get("action"));
                int stepId=((Number)object.get("stepId")).intValue();
                assertTrue(stepId>27);
                assertEquals(id,DescriptorFacts.statusId(DescriptorFacts.step(descriptor,stepId)));
            }
            assertEquals(mode==1?List.of("draft","workflow-save"):List.of("workflow-save"),f.nativeCalls);
        }
    }
    @Test public void successDiffUsesNativeIdsWithoutReplacingTokensInUserNames() {
        for(int mode=0;mode<3;mode++) try(WorkflowConfigurationFixture f=world()) {
            prepareSparseTarget(f,mode);
            f.status("88","Reuse LOCAL_STATUS_0","done");
            var preview=f.preview(request(f,mode==2?"DRAFT":"ACTIVE","["+add("New LOCAL_STATUS_0","new")+","+add("Reuse LOCAL_STATUS_0","reuse")+"]"));
            assertNotNull(preview.getMessage(),preview.getPlanId());
            var done=f.apply(preview);
            assertEquals(done.getMessage(),mode==0?"UPDATED_INACTIVE":"DRAFT_SAVED",done.getStatus());
            var descriptor=mode==0?f.main.get("Support"):f.drafts.get("Support");
            var objects=done.getObjects().stream().filter(x->"STATUS".equals(x.get("kind"))).collect(java.util.stream.Collectors.toList());
            assertEquals(2,objects.size());
            for(int i=0;i<2;i++) {
                var object=objects.get(i);
                String id=object.get("id").toString();
                int stepId=((Number)object.get("stepId")).intValue();
                assertTrue(stepId>27);
                assertEquals(i==0?"CREATE":"REUSE",object.get("action"));
                assertEquals(id,done.getStatuses().get(i).getId());
                assertEquals(id,DescriptorFacts.statusId(DescriptorFacts.step(descriptor,stepId)));
                assertEquals(object.get("name"),DescriptorFacts.step(descriptor,stepId).getName());
                assertTrue(done.getDiff().toString(),done.getDiff().contains("ADD_STATUS: new step "+stepId
                        +" linked to status '"+object.get("name")+"' ("+id+"), no transitions yet"));
                if(i==0) assertEquals(id,step(done,"status:0").get("id"));
                else assertEquals("88",id);
            }
            assertEquals(mode==1?List.of("status","draft","workflow-save"):List.of("status","workflow-save"),f.nativeCalls);
        }
    }
    private static void prepareSparseTarget(WorkflowConfigurationFixture f,int mode) {
        var descriptor=f.main.get("Support");
        var first=DescriptorFacts.steps(descriptor).get(0);
        int old=first.getId();first.setId(27);
        for(var located:DescriptorFacts.locate(descriptor).values())
            if(located.action().getUnconditionalResult().getStep()==old)located.action().getUnconditionalResult().setStep(27);
        Collections.reverse(descriptor.getSteps());
        if(mode!=0)f.active.add("Support");
        if(mode==2)f.drafts.put("Support",WorkflowConfigurationFixture.copy(descriptor));
    }
    @Test public void reuseUsesCanonicalPropertiesAndWarnsWithoutAGlobalWrite() {
        try(WorkflowConfigurationFixture f=world()) {
            var canonical=f.status("88","Review","done");when(canonical.getDescription()).thenReturn("kept description");
            ConfigureWorkflowResponse preview=f.preview(request(f,"["+add(" review ","review")+"]"));
            assertNotNull(preview.getMessage(),preview.getPlanId());
            var object=preview.getObjects().stream().filter(x->"STATUS".equals(x.get("kind"))).findFirst().orElseThrow();
            assertEquals("REUSE",object.get("action"));assertEquals("88",object.get("id"));assertEquals("DONE",object.get("category"));
            assertEquals("kept description",object.get("description"));
            assertTrue(preview.getWarnings().toString().contains("IN_PROGRESS"));assertTrue(preview.getWarnings().toString().contains("DONE"));
            ConfigureWorkflowResponse done=f.apply(preview);assertEquals(done.getMessage(),"UPDATED_INACTIVE",done.getStatus());
            assertEquals(List.of("workflow-save"),f.nativeCalls);
            assertEquals("kept description",canonical.getDescription());
        }
    }
    static void refused(WorkflowConfigurationFixture f,String changes,String code) {
        ConfigureWorkflowResponse result=f.preview(request(f,changes));
        assertEquals(result.getMessage(),code,result.getErrorCode());assertNull(result.getPlanId());assertTrue(f.nativeCalls.isEmpty());
    }
    @Test public void ambiguityListsAllCandidatesWithoutDependingOnCatalogOrder() {
        try(WorkflowConfigurationFixture f=world()) {
            f.status("90","Review","done");f.status("89","REVIEW","new");
            refused(f,"["+add("review","review")+"]","AMBIGUOUS_STATUS");
            var result=f.preview(request(f,"["+add("review","review")+"]"));
            for(String value:List.of("89","90","Review","REVIEW","DONE","TO_DO"))assertTrue(result.getMessage(),result.getMessage().contains(value));
        }
    }
    @Test public void missingOrInvalidCategoryIsRefusedEvenWhenTheNameExists() {
        for(boolean reuse:List.of(false,true))for(String category:List.of("",",\"category\":\"WRONG\"",",\"category\":\"\""))
            try(WorkflowConfigurationFixture f=world()) {
                if(reuse)f.status("88","Review","done");
                String change="[{\"op\":\"ADD_STATUS\",\"newStatus\":{\"name\":\"Review\""+category+"}}]";
                try { refused(f,change,"VALIDATION_ERROR"); }
                catch(ru.rasulov.common.api.exception.InvalidRequestException expected) {assertTrue(f.nativeCalls.isEmpty());}
            }
    }
    @Test public void strictShapeXorAndClosedNewStatusNeverProduceAPlan() {
        for(String change:List.of(
                "{\"op\":\"ADD_STATUS\"}", "{\"op\":\"ADD_STATUS\",\"statusId\":null}",
                "{\"op\":\"ADD_STATUS\",\"newStatus\":null}",
                "{\"op\":\"ADD_STATUS\",\"newStatus\":[]}",
                "{\"op\":\"ADD_STATUS\",\"statusId\":\"88\",\"newStatus\":{\"name\":\"Review\",\"category\":\"IN_PROGRESS\"}}",
                "{\"op\":\"ADD_STATUS\",\"newStatus\":{\"name\":123,\"category\":\"IN_PROGRESS\"}}",
                "{\"op\":\"ADD_STATUS\",\"newStatus\":{\"name\":\"Review\",\"category\":\"IN_PROGRESS\",\"description\":null}}",
                "{\"op\":\"ADD_STATUS\",\"newStatus\":{\"name\":\"Review\",\"category\":\"IN_PROGRESS\",\"extra\":true}}",
                "{\"op\":\"UPDATE_METADATA\",\"description\":\"x\",\"newStatus\":{\"name\":\"Review\",\"category\":\"IN_PROGRESS\"}}"))
            try(WorkflowConfigurationFixture f=world()) {
                try {var result=f.preview(request(f,"["+change+"]"));assertNull(result.getPlanId());assertEquals("VALIDATION_ERROR",result.getErrorCode());}
                catch(ru.rasulov.common.api.exception.InvalidRequestException expected) { /* strict parse is also a no-plan refusal */ }
                assertTrue(f.nativeCalls.isEmpty());
            }
    }
    @Test public void duplicateNamesCanonicalIdsAndAlreadyRepresentedStatusesAreRefused() {
        for(String changes:List.of(
                "["+add("Review","a")+","+add(" review ","b")+"]",
                "[{\"op\":\"ADD_STATUS\",\"statusId\":\"1\"}]",
                "[{\"op\":\"REMOVE_STATUS\",\"stepId\":1},"+add("Open","again")+"]",
                "["+add("Open","again")+",{\"op\":\"REPLACE_STATUS\",\"stepId\":1,\"statusId\":\"3\"}]"))
            try(WorkflowConfigurationFixture f=world()){refused(f,changes,"VALIDATION_ERROR");}
        for(String second:List.of("{\"op\":\"ADD_STATUS\",\"statusId\":\"88\"}", "{\"op\":\"REPLACE_STATUS\",\"stepId\":1,\"statusId\":\"88\"}"))
            try(WorkflowConfigurationFixture f=world()) {
                f.status("88","Review","indeterminate");
                refused(f,"["+add("Review","a")+","+second+"]","VALIDATION_ERROR");
            }
    }
    @Test public void usedStatusFactsAndCandidateSetsGoStaleButUnrelatedCatalogChangesDoNot() {
        for(String change:List.of("new-match","extra-match","name","category","description","deleted","draft","unrelated"))
            try(WorkflowConfigurationFixture f=world()) {
                boolean reuse=!List.of("new-match","unrelated").contains(change);
                var status=reuse?f.status("88","Review","indeterminate"):null;
                ConfigureWorkflowResponse preview=f.preview(request(f,"["+add("Review","review")+"]"));assertNotNull(preview.getMessage(),preview.getPlanId());
                switch(change) {
                    case "new-match":case "extra-match":f.status("89","REVIEW","done");break;
                    case "name":when(status.getName()).thenReturn("Renamed");break;
                    case "category":f.status("88","Review","done");break;
                    case "description":when(status.getDescription()).thenReturn("external");break;
                    case "deleted":f.statusStore.remove("88");break;
                    case "draft":f.drafts.put("Support",WorkflowConfigurationFixture.copy(f.main.get("Support")));break;
                    case "unrelated":f.status("89","Different","done");f.orderable("irrelevant","Irrelevant");break;
                }
                ConfigureWorkflowResponse done=f.apply(preview);
                if(change.equals("unrelated"))assertEquals(done.getMessage(),"UPDATED_INACTIVE",done.getStatus());
                else {assertEquals(done.getMessage(),"PREVIEW_STALE",done.getErrorCode());assertTrue(f.nativeCalls.isEmpty());}
            }
    }
    @Test public void wholePlanPreflightRejectsInvalidScreensLastRulesAndNativeValidation() {
        for(String failure:List.of("screen","rule","native"))try(WorkflowConfigurationFixture f=world()) {
            var base=request(f,"["+add("Review","review")+"]");
            var rules=failure.equals("screen")
                    ?List.of(new ConfigureWorkflowRequest.TransitionRule(null,11,new ConfigureWorkflowRequest.ScreenChoice("SET","9999",null,null),null,null))
                    :List.of(new ConfigureWorkflowRequest.TransitionRule(null,11,null,List.of(new ConfigureWorkflowRequest.Validator("invalid",null)),null));
            if(failure.equals("native")) {
                var invalid=mock(com.atlassian.jira.bc.ServiceResult.class);when(invalid.isValid()).thenReturn(false);
                when(invalid.getErrorCollection()).thenReturn(new com.atlassian.jira.util.SimpleErrorCollection());
                when(f.statusService.validateCreateStatus(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any())).thenReturn(invalid);
            }
            var result=f.preview(new ConfigureWorkflowRequest(true,null,base.existing(),null,failure.equals("native")?null:rules,null));
            assertNull(result.getPlanId());assertNotNull(result.getErrorCode());assertTrue(f.nativeCalls.isEmpty());
        }
    }
    @Test public void externalCandidateAfterOwnStatusWriteStopsWithKnownReceiptInsteadOfRefreshingCatalog() {
        try(WorkflowConfigurationFixture f=world()) {
            var preview=f.preview(request(f,"["+add("Review","review")+"]"));
            f.afterNative=key->{if(key.equals("status"))f.status("999","REVIEW","done");};
            var done=f.apply(preview);
            assertEquals(done.getMessage(),"PARTIAL",done.getStatus());assertEquals("PREVIEW_STALE",done.getErrorCode());
            assertNotNull(step(done,"status:0").get("id"));assertEquals("NOT_ATTEMPTED",step(done,"workflow-save").get("writeStatus"));
            assertEquals(List.of("status"),f.nativeCalls);
        }
    }
    @Test public void everyExistingNativeBoundaryIsForwardOnlyAndReturnsOnlyKnownStatusEffects() {
        for(String boundary:List.of("status","draft","workflow-save"))for(boolean after:List.of(false,true))
            try(WorkflowConfigurationFixture f=world()) {
                f.active.add("Support");var preview=f.preview(request(f,"["+add("Review","review")+"]"));assertNotNull(preview.getMessage(),preview.getPlanId());
                java.util.function.Consumer<String> fail=key->{if(key.equals(boundary))throw new IllegalStateException("lost "+boundary);};
                if(after)f.afterNative=fail;else f.beforeNative=fail;
                var done=f.apply(preview);
                assertEquals(done.getMessage(),boundary.equals("status")?"UNKNOWN":"PARTIAL",done.getStatus());
                assertTrue(done.getTransitions().isEmpty());assertTrue(done.getDiff().isEmpty());
                if(boundary.equals("status")){assertTrue(done.getStatuses().isEmpty());assertTrue(done.getObjects().isEmpty());assertFalse(step(done,"status:0").containsKey("id"));}
                else {
                    assertEquals("APPLIED",step(done,"status:0").get("writeStatus"));
                    assertEquals("MATCH",step(done,"status:0").get("verification"));
                    assertEquals(step(done,"status:0").get("id"),done.getObjects().get(0).get("id"));
                }
                int count=f.nativeCalls.size();f.apply(preview);assertEquals(count,f.nativeCalls.size());
            }
    }
    @Test public void statusReadBackMismatchAndUnavailableRemainPartialAndDurable() {
        for(boolean unavailable:List.of(false,true))try(WorkflowConfigurationFixture f=world()) {
            var preview=f.preview(request(f,"["+add("Review","review")+"]"));
            f.afterNative=key->{if(key.equals("status")) {
                var status=f.statusStore.values().stream().filter(x->"Review".equals(x.getName())).findFirst().orElseThrow();
                if(unavailable)when(status.getDescription()).thenThrow(new IllegalStateException("read unavailable"));
                else when(status.getDescription()).thenReturn("wrong");
            }};
            var done=f.apply(preview);
            assertEquals("PARTIAL",done.getStatus());assertEquals("APPLIED",step(done,"status:0").get("writeStatus"));
            assertEquals(unavailable?"UNAVAILABLE":"MISMATCH",step(done,"status:0").get("verification"));
            assertNotNull(step(done,"status:0").get("id"));assertEquals("NOT_ATTEMPTED",step(done,"workflow-save").get("writeStatus"));
            assertEquals(List.of("status"),f.nativeCalls);
            try {
                var operations=f.journalFixture.entityManager.find(ru.rasulov.common.impl.undo.JournalOperation.class);
                var recovered=f.journalFixture.reopen().getOperation(operations[0].getOperationKey(),"admin");
                assertTrue(recovered.toString(),recovered.toString().contains(step(done,"status:0").get("id").toString()));
                assertEquals(1,f.nativeCalls.size());
            } catch(Exception e){throw new AssertionError(e);}
        }
    }
    @Test public void populatedActiveFixtureSavesOnlyDraftAndNeverMigratesOrWritesIssues() throws Exception {
        try(WorkflowConfigurationFixture f=world()) {
            f.active.add("Support");
            Map<String,Object> state=new LinkedHashMap<>(Map.of("id",10001L,"status","1","workflowId",700L,"workflow","Support","step",1,"project","L013"));
            var issue=mock(com.atlassian.jira.issue.MutableIssue.class);
            when(issue.getId()).thenAnswer(c->state.get("id"));when(issue.getStatusId()).thenAnswer(c->state.get("status"));
            when(issue.getWorkflowId()).thenAnswer(c->state.get("workflowId"));
            doAnswer(c->{state.put("workflow",((com.atlassian.jira.workflow.JiraWorkflow)c.getArgument(1)).getName());state.put("status",((com.atlassian.jira.issue.status.Status)c.getArgument(2)).getId());throw new AssertionError("Forbidden issue migration");})
                    .when(f.manager).migrateIssueToWorkflow(org.mockito.ArgumentMatchers.any(com.atlassian.jira.issue.MutableIssue.class),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            doAnswer(c->{state.put("status",c.getArgument(0));throw new AssertionError("Forbidden issue status write");}).when(issue).setStatusId(org.mockito.ArgumentMatchers.anyString());
            doAnswer(c->{state.put("workflowId",c.getArgument(0));throw new AssertionError("Forbidden issue reassociation");}).when(issue).setWorkflowId(org.mockito.ArgumentMatchers.anyLong());
            Map<String,Object> before=Map.copyOf(state);
            String active=DescriptorFacts.definition(f.main.get("Support"));
            var done=f.apply(f.preview(request(f,"["+add("Review","review")+"]")));
            assertEquals(done.getMessage(),"DRAFT_SAVED",done.getStatus());
            assertEquals(List.of("status","draft","workflow-save"),f.nativeCalls);
            assertEquals(active,DescriptorFacts.definition(f.main.get("Support")));
            assertFalse(active.equals(DescriptorFacts.definition(f.drafts.get("Support"))));
            assertEquals(before,state);assertEquals(before.get("id"),issue.getId());assertEquals(before.get("status"),issue.getStatusId());assertEquals(before.get("workflowId"),issue.getWorkflowId());
            verify(f.manager,never()).migrateIssueToWorkflow(org.mockito.ArgumentMatchers.any(com.atlassian.jira.issue.MutableIssue.class),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any());
            for(var invocation:mockingDetails(f.manager).getInvocations())
                assertTrue("Unexpected workflow mutation "+invocation.getMethod().getName(),invocation.getMethod().getName().startsWith("get")||List.of("createDraftWorkflow","updateWorkflow").contains(invocation.getMethod().getName()));
            for(var invocation:mockingDetails(f.delegator).getInvocations())
                assertTrue("Forbidden issue/association database write "+invocation.getMethod().getName(),invocation.getMethod().getName().startsWith("find"));
        }
    }
    @Test @SuppressWarnings("unchecked") public void combinedChainPreservesCommentAndForeignRulesWithIndependentKeyNamespaces() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.orderable("priority","Priority");f.orderable("customfield_101","Frosty Dune QG5");
            f.screen(7,"Original",List.of("comment"));
            String graph="{\"dryRun\":true,\"create\":{\"name\":\"Support\",\"statuses\":[{\"name\":\"Open\"},{\"name\":\"Done\"}],\"initialStatus\":\"Open\",\"transitions\":[{\"key\":\"finish\",\"name\":\"Finish\",\"from\":\"Open\",\"to\":\"Done\",\"screen\":\"7\",\"resolution\":{\"action\":\"SET\",\"value\":\"1\"}},{\"key\":\"control\",\"name\":\"Control\",\"from\":\"Open\",\"to\":\"Open\"}]},\"transitionRules\":[{\"transitionKey\":\"finish\",\"addValidators\":[{\"template\":\"comment_required\"}]}]}";
            var baseline=f.planner.plan(RequestBodies.parse(graph,ConfigureWorkflowRequest.class,"request"),f.journalFixture.currentActor.get());
            assertNotNull(baseline.response.getMessage(),baseline.fingerprint);
            var finish=DescriptorFacts.locate(baseline.descriptor).get(11).action();
            var foreign=com.opensymphony.workflow.loader.DescriptorFactory.getFactory().createValidatorDescriptor();
            foreign.setType("class");foreign.getArgs().put("class.name","foreign.PreservedValidator");foreign.getArgs().put("configuration","untouched");
            finish.getValidators().add(foreign);
            String control=DescriptorFacts.xml(DescriptorFacts.locate(baseline.descriptor).get(21).action());
            String comment=DescriptorFacts.xml((com.opensymphony.workflow.loader.ValidatorDescriptor)finish.getValidators().get(0));
            f.putWorkflow("Support",baseline.descriptor,false);
            String changes="[{\"op\":\"UPDATE_TRANSITION\",\"actionId\":11,\"source\":{\"clientKey\":\"same\"}},"
                    +"{\"op\":\"ADD_TRANSITION\",\"name\":\"Enter review\",\"source\":{\"stepId\":1},\"targetClientKey\":\"same\",\"transitionKey\":\"same\"},"
                    +add("Делоем","same")+"]";
            var base=request(f,changes);
            var screen=new ConfigureWorkflowRequest.NewScreen("same","Review form",null,List.of("priority","customfield_101","comment"));
            var set=new ConfigureWorkflowRequest.ScreenChoice("SET",null,null,"same");
            var inbound=new ConfigureWorkflowRequest.TransitionRule("same",null,set,List.of(new ConfigureWorkflowRequest.Validator("required_fields",new ConfigureWorkflowRequest.Parameters(List.of("priority"),null,null,null))),null);
            var outbound=new ConfigureWorkflowRequest.TransitionRule(null,11,set,List.of(new ConfigureWorkflowRequest.Validator("required_fields",new ConfigureWorkflowRequest.Parameters(List.of("customfield_101"),null,null,null))),null);
            var preview=f.preview(new ConfigureWorkflowRequest(true,null,base.existing(),List.of(screen),List.of(inbound,outbound),null));
            assertNotNull(preview.getMessage(),preview.getPlanId());assertTrue(f.nativeCalls.isEmpty());
            var done=f.apply(preview);assertEquals(done.getMessage(),"UPDATED_INACTIVE",done.getStatus());
            var saved=f.main.get("Support");var status=done.getObjects().stream().filter(x->"STATUS".equals(x.get("kind"))).findFirst().orElseThrow();
            int addedStep=((Number)status.get("stepId")).intValue();
            assertEquals(List.of(addedStep),DescriptorFacts.locate(saved).get(11).sources());
            var incoming=done.getTransitions().stream().filter(x->"same".equals(x.get("transitionKey"))).findFirst().orElseThrow();
            int incomingId=((Number)incoming.get("actionId")).intValue();
            assertEquals(List.of(addedStep),DescriptorFacts.targets(DescriptorFacts.locate(saved).get(incomingId).action()));
            assertEquals(control,DescriptorFacts.xml(DescriptorFacts.locate(saved).get(21).action()));
            var savedFinish=DescriptorFacts.locate(saved).get(11).action();
            assertEquals(comment,DescriptorFacts.xml((com.opensymphony.workflow.loader.ValidatorDescriptor)savedFinish.getValidators().get(0)));
            assertEquals("foreign.PreservedValidator",((com.opensymphony.workflow.loader.ValidatorDescriptor)savedFinish.getValidators().get(1)).getArgs().get("class.name"));
            assertTrue(RequiredFieldsDescriptor.scan(savedFinish).stream().anyMatch(e->e.fieldIds().contains("customfield_101")));
            assertTrue(RequiredFieldsDescriptor.scan(DescriptorFacts.locate(saved).get(incomingId).action()).stream().anyMatch(e->e.fieldIds().contains("priority")));
            for(var located:DescriptorFacts.locate(saved).values())if(located.sources().contains(1))
                assertFalse("No direct Open to Done bypass",DescriptorFacts.targets(located.action()).contains(2));
            assertEquals("comment",f.screenStore.values().stream().filter(s->"Review form".equals(s.getName())).findFirst().orElseThrow().getTabs().get(0).getFieldScreenLayoutItems().get(2).getFieldId());
        }
    }
}
