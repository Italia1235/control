package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.util.SimpleErrorCollection;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Test;
import ru.rasulov.common.impl.undo.JournalOperation;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.configure.*;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.*;
import ru.rasulov.workflow.api.dto.edit.ScriptRunnerStatus;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Native boundary and composite-plan behavior; Jira managers mutate a real descriptor store rather than echo input. */
public class WorkflowConfigurationNativeTest {
    static ConfigureWorkflowRequest combined(WorkflowConfigurationFixture f,String name) {
        return new ConfigureWorkflowRequest(true,f.createGraph(name),null,
                List.of(new NewScreen("form","Workflow form",null,List.of("description","assignee"))),
                List.of(new TransitionRule("start",null,new ScreenChoice("SET",null,null,"form"),
                        List.of(new Validator("comment_required",null),new Validator("required_fields",new Parameters(List.of("description"),null,null,null))),
                        new Conditions("AND",List.of(new Condition("group",new Parameters(null,"admins",null,null)),
                                new Condition("project_role",new Parameters(null,null,List.of("Developers"),false)))))),null);
    }
    static TransitionRule fields(int id,String... fields) { return new TransitionRule(null,id,null,List.of(new Validator("required_fields",new Parameters(Arrays.asList(fields),null,null,null))),null); }
    static void existingGraph(WorkflowConfigurationFixture f,String name,boolean active) {
        WorkflowConfigurationPlan plan=f.planner.plan(new ConfigureWorkflowRequest(true,f.createGraph(name),null,null,null,null),f.journalFixture.currentActor.get());
        assertEquals("WOULD_CREATE",plan.response.getStatus());f.putWorkflow(name,plan.descriptor,active);
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> steps(ConfigureWorkflowResponse result){return (List<Map<String,Object>>)result.getExecution().get("steps");}
    static Map<String,Object> step(ConfigureWorkflowResponse result,String key){return steps(result).stream().filter(s->key.equals(s.get("key"))).findFirst().orElseThrow();}
    private static void allUntouchedAfter(ConfigureWorkflowResponse result,String key) {
        boolean after=false;for(Map<String,Object> item:steps(result)) {if(after)assertEquals(item.toString(),"NOT_ATTEMPTED",item.get("writeStatus"));if(key.equals(item.get("key")))after=true;}
        assertTrue("Expected receipt key "+key,after);
    }
    @Test public void oneCreatePlanWritesBothRulesAndOneScreenAndReadsEveryNativeStepBack() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowResponse preview=f.preview(combined(f,"Configured"));
            assertEquals(preview.getMessage(),"WOULD_CREATE",preview.getStatus());assertNotNull(preview.getPlanId());assertTrue(f.nativeCalls.isEmpty());
            ConfigureWorkflowResponse saved=f.apply(preview);
            assertEquals(saved.getMessage(),"CREATED",saved.getStatus());assertEquals("SUCCESS",saved.getOutcome());assertEquals("ACTIVE",saved.getTargetVersion());
            ActionDescriptor action=DescriptorFacts.locate(f.main.get("Configured")).get(11).action();
            List<RequiredFieldsDescriptor.Entry> validators=RequiredFieldsDescriptor.scan(action);
            assertEquals(2,validators.size());assertTrue(validators.get(0).commentRequired());assertEquals(List.of("description"),validators.get(1).fieldIds());
            assertEquals("fieldscreen",action.getView());assertEquals(1,f.screenStore.size());
            assertNotNull(f.screens.getFieldScreen(Long.valueOf(DescriptorFacts.meta(action).get(DescriptorFacts.SCREEN_META))));
            assertEquals(DescriptorFacts.meta(action).get(DescriptorFacts.SCREEN_META),saved.getTransitions().stream().filter(t->Integer.valueOf(11).equals(t.get("actionId"))).findFirst().orElseThrow().get("screenId"));
            assertEquals("AND",action.getRestriction().getConditionsDescriptor().getType());assertEquals(2,action.getRestriction().getConditionsDescriptor().getConditions().size());
            assertEquals(List.of("screen","tab","field","field","workflow-copy","workflow-save"),f.nativeCalls);
            for(Map<String,Object> item:steps(saved)){assertEquals(item.toString(),"APPLIED",item.get("writeStatus"));assertEquals(item.toString(),"MATCH",item.get("verification"));}
        }
    }
    @Test public void nativeCopyAndSaveStampsDoNotBlockTheConfirmedGraphAndRules() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            assertFalse(f.main.get(JiraWorkflow.DEFAULT_WORKFLOW_NAME).getMetaAttributes().containsKey("jira.updated.date"));
            ConfigureWorkflowResponse result=f.apply(f.preview(combined(f,"NativeStamps")));
            assertEquals(result.getMessage(),"CREATED",result.getStatus());
            assertEquals("MATCH",step(result,"workflow-copy").get("verification"));
            assertEquals("MATCH",step(result,"workflow-save").get("verification"));
            WorkflowDescriptor saved=f.main.get("NativeStamps");
            assertEquals("admin",saved.getMetaAttributes().get("jira.update.author.key"));
            assertNotNull(saved.getMetaAttributes().get("jira.updated.date"));
            ActionDescriptor action=DescriptorFacts.locate(saved).get(11).action();
            assertEquals("Start",action.getName());assertEquals(2,RequiredFieldsDescriptor.scan(action).size());
            assertEquals("fieldscreen",action.getView());assertEquals(2,action.getRestriction().getConditionsDescriptor().getConditions().size());
            assertEquals(1,Collections.frequency(f.nativeCalls,"workflow-copy"));
            assertEquals(1,Collections.frequency(f.nativeCalls,"workflow-save"));
        }
    }
    @Test public void nonStampMetadataChangedDuringNativeCopyStillStopsBeforeGraphSave() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowResponse preview=f.preview(combined(f,"ChangedCopy"));
            f.afterNative=key->{if(key.equals("workflow-copy"))f.main.get("ChangedCopy").getMetaAttributes().put("external","changed");};
            ConfigureWorkflowResponse result=f.apply(preview);
            assertEquals("PARTIAL",result.getStatus());
            assertEquals("APPLIED",step(result,"workflow-copy").get("writeStatus"));
            assertEquals("MISMATCH",step(result,"workflow-copy").get("verification"));
            assertEquals("NOT_ATTEMPTED",step(result,"workflow-save").get("writeStatus"));
            assertFalse(f.nativeCalls.contains("workflow-save"));
        }
    }
    @Test public void sharedScreenKeyCreatesOneScreenForLocalAndGlobalTransition() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            WorkflowDefinition graph=new WorkflowDefinition("Shared","",f.createGraph("Shared").getStatuses(),"Open",List.of(
                    new TransitionDefinition("start","Start","Open",false,"In Progress",null,null),
                    new TransitionDefinition("again","Again",null,true,"Open",null,null)));
            ScreenChoice choice=new ScreenChoice("SET",null,null,"one");
            ConfigureWorkflowRequest request=new ConfigureWorkflowRequest(true,graph,null,List.of(new NewScreen("one","Shared form","",List.of("description"))),List.of(
                    new TransitionRule("start",null,choice,List.of(),null),new TransitionRule("again",null,choice,List.of(),null)),null);
            ConfigureWorkflowResponse result=f.apply(f.preview(request));assertEquals(result.getMessage(),"CREATED",result.getStatus());assertEquals(1,f.screenStore.size());
            Map<Integer,DescriptorFacts.Located> actions=DescriptorFacts.locate(f.main.get("Shared"));
            assertEquals(DescriptorFacts.meta(actions.get(11).action()).get(DescriptorFacts.SCREEN_META),DescriptorFacts.meta(actions.get(21).action()).get(DescriptorFacts.SCREEN_META));
            assertEquals(1,Collections.frequency(f.nativeCalls,"screen"));
        }
    }
    @Test public void inactiveAndBothDraftModesSaveTheSameFinalDescriptorAndReportActualTargetVersion() {
        for(String mode:List.of("inactive","new-draft","existing-draft"))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",!mode.equals("inactive"));
            if(mode.equals("existing-draft"))f.drafts.put("Existing",WorkflowConfigurationFixture.copy(f.main.get("Existing")));
            String original=DescriptorFacts.definition(f.main.get("Existing"));
            ConfigureWorkflowResponse preview=f.preview(f.existing("Existing",mode.equals("existing-draft")?"DRAFT":"ACTIVE",List.of(fields(11,"description"))));
            ConfigureWorkflowResponse result=f.apply(preview);
            assertEquals(result.getMessage(),mode.equals("inactive")?"UPDATED_INACTIVE":"DRAFT_SAVED",result.getStatus());assertEquals(mode.equals("inactive")?"ACTIVE":"DRAFT",result.getTargetVersion());
            WorkflowDescriptor saved=(mode.equals("inactive")?f.main:f.drafts).get("Existing");
            assertEquals(List.of("description"),RequiredFieldsDescriptor.scan(DescriptorFacts.locate(saved).get(11).action()).get(0).fieldIds());
            if(!mode.equals("inactive"))assertEquals(original,DescriptorFacts.definition(f.main.get("Existing")));
            assertEquals(mode.equals("new-draft")?1:0,Collections.frequency(f.nativeCalls,"draft"));
        }
    }
    @Test public void errorsInTheLastRuleCannotCreateEvenTheFirstStandaloneObject() {
        for(String bad:List.of("group","role","field","screen","plugin","version","comment"))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowRequest base=combined(f,"Invalid");TransitionRule good=base.transitionRules().get(0),last;
            switch(bad) {
                case "group" -> last=new TransitionRule("start",null,good.screen(),good.addValidators(),new Conditions("AND",List.of(new Condition("group",new Parameters(null,"missing",null,null)))));
                case "role" -> last=new TransitionRule("start",null,good.screen(),good.addValidators(),new Conditions("AND",List.of(new Condition("project_role",new Parameters(null,null,List.of("missing"),null)))));
                case "field" -> last=new TransitionRule("start",null,good.screen(),List.of(new Validator("required_fields",new Parameters(List.of("missing"),null,null,null))),good.addConditions());
                case "screen" -> last=new TransitionRule("start",null,new ScreenChoice("SET","999",null,null),good.addValidators(),good.addConditions());
                case "plugin" -> {ScriptRunnerStatus missing=new ScriptRunnerStatus(false,false,null,false,"missing");when(f.gate.status()).thenReturn(missing);when(f.gate.conditionStatus()).thenReturn(missing);last=good;}
                case "version" -> {ScriptRunnerStatus old=new ScriptRunnerStatus(true,true,"9.46.1",false,"unsupported");when(f.gate.status()).thenReturn(old);when(f.gate.conditionStatus()).thenReturn(old);last=good;}
                default -> last=new TransitionRule("start",null,null,List.of(new Validator("comment_required",null)),null);
            }
            ConfigureWorkflowRequest request=new ConfigureWorkflowRequest(true,base.create(),null,bad.equals("comment")?null:base.newScreens(),List.of(last),null);
            ConfigureWorkflowResponse result=f.preview(request);assertEquals(bad+": "+result.getMessage(),"FAILED",result.getStatus());assertNull(result.getPlanId());assertTrue(f.nativeCalls.isEmpty());assertTrue(f.screenStore.isEmpty());assertFalse(f.main.containsKey("Invalid"));
        }
    }
    @Test public void externalDependenciesChangingAfterPreviewAlwaysFailBeforeAnyNativeCall() {
        List<Consumer<WorkflowConfigurationFixture>> changes=List.of(
                f->f.orderable("description","Renamed description"),
                f->when(f.statusStore.get("1").getDescription()).thenReturn("changed"),
                f->when(f.groups.getUsersInGroupCount(f.groups.getGroup("admins"))).thenReturn(0),
                f->when(f.roles.getProjectRole(10000L).getName()).thenReturn("Renamed role"),
                f->{ScriptRunnerStatus changed=new ScriptRunnerStatus(true,true,"9.46.1",false,"changed");when(f.gate.status()).thenReturn(changed);when(f.gate.conditionStatus()).thenReturn(changed);},
                f->f.main.get(JiraWorkflow.DEFAULT_WORKFLOW_NAME).getMetaAttributes().put("external","changed template"),
                f->f.main.put("Changed",WorkflowConfigurationFixture.copy(f.main.get(JiraWorkflow.DEFAULT_WORKFLOW_NAME))));
        for(int i=0;i<changes.size();i++)try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowResponse preview=f.preview(combined(f,"Changed"));assertNotNull(preview.getMessage(),preview.getPlanId());changes.get(i).accept(f);
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals("dependency "+i+": "+result.getMessage(),"PREVIEW_STALE",result.getErrorCode());assertTrue(f.nativeCalls.isEmpty());
        }
    }
    @Test public void sourceRevisionIncludesDraftAppearanceChangeAndDisappearance() {
        for(String mutation:List.of("appears","changes","disappears"))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",true);
            if(!mutation.equals("appears"))f.drafts.put("Existing",WorkflowConfigurationFixture.copy(f.main.get("Existing")));
            ConfigureWorkflowResponse preview=f.preview(f.existing("Existing",mutation.equals("appears")?"ACTIVE":"DRAFT",List.of(fields(11,"description"))));assertNotNull(preview.getMessage(),preview.getPlanId());
            if(mutation.equals("disappears"))f.drafts.remove("Existing");
            else {f.drafts.put("Existing",WorkflowConfigurationFixture.copy(f.main.get("Existing")));f.drafts.get("Existing").getMetaAttributes().put("external","change");}
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals("PREVIEW_STALE",result.getErrorCode());assertTrue(f.nativeCalls.isEmpty());
        }
    }
    @Test public void repeatedPlanningHasStableFingerprintIncludingAllRuleFunctionIds() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowRequest request=combined(f,"Stable");WorkflowConfigurationPlan a=f.planner.plan(request,f.journalFixture.currentActor.get()),b=f.planner.plan(request,f.journalFixture.currentActor.get());
            assertNotNull(a.response.getMessage(),a.fingerprint);assertEquals(a.fingerprint,b.fingerprint);assertEquals(a.after,b.after);
        }
    }
    @Test public void eachNativeBoundaryStopsForwardOnlyAndRetainsEveryUntouchedStep() {
        for(String boundary:List.of("screen","tab","field","workflow-copy","workflow-save"))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowResponse preview=f.preview(combined(f,"Boundary"));
            f.beforeNative=key->{if(boundary.equals(key))throw new IllegalStateException("injected "+boundary);};
            ConfigureWorkflowResponse result=f.apply(preview);String receipt=boundary.equals("screen")?"screen:0":boundary.equals("tab")?"tab:0":boundary.equals("field")?"field:0:0":boundary;
            assertEquals(boundary+": "+result.getMessage(),boundary.equals("screen")?"UNKNOWN":"PARTIAL",result.getStatus());
            assertEquals("UNKNOWN",step(result,receipt).get("writeStatus"));assertEquals("NOT_CHECKED",step(result,receipt).get("verification"));allUntouchedAfter(result,receipt);assertRulesNotDeclaredSaved(result);
            assertEquals(boundary.equals("screen")?0:1,f.screenStore.size());assertTrue("No rollback",!f.screenStore.isEmpty() || boundary.equals("screen"));
            verify(f.screens,never()).removeFieldScreen(anyLong());
        }
    }
    @Test public void cancellationBetweenNativeStepsRetainsConfirmedWritesAndStopsForward() throws Exception {
        for (String boundary : List.of("screen", "tab", "field", "workflow-copy")) {
            try (WorkflowConfigurationFixture f = new WorkflowConfigurationFixture()) {
                ConfigureWorkflowResponse preview = f.preview(combined(f, "Cancelled"));
                f.afterNative = key -> {
                    if (boundary.equals(key)) {
                        try {
                            JournalOperation operation = f.journalFixture.entityManager.find(JournalOperation.class)[0];
                            f.journalFixture.journal.cancel(operation.getOperationKey(), "admin", false);
                        } catch (Exception e) { throw new IllegalStateException(e); }
                    }
                };

                ConfigureWorkflowResponse result = f.apply(preview);

                assertEquals(result.getMessage(), "CANCELLED", result.getErrorCode());
                assertEquals("PARTIAL", result.getStatus());
                String receipt = boundary.equals("screen") ? "screen:0" : boundary.equals("tab") ? "tab:0"
                        : boundary.equals("field") ? "field:0:0" : boundary;
                assertEquals("APPLIED", step(result, receipt).get("writeStatus"));
                assertEquals("MATCH", step(result, receipt).get("verification"));
                allUntouchedAfter(result, receipt);
                assertEquals(result.getExecution(), f.journalFixture.journal.getOperation(
                        f.journalFixture.entityManager.find(JournalOperation.class)[0].getOperationKey(), "admin").get("execution"));
                assertRulesNotDeclaredSaved(result);
                assertEquals(1, f.screenStore.size());
                verify(f.screens, never()).removeFieldScreen(anyLong());
                assertEquals(boundary.equals("workflow-copy"), f.main.containsKey("Cancelled"));
            }
        }
    }

    @Test public void exceptionAfterCopyKeepsUnknownWithoutRecoveringTheIdByName() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowResponse preview=f.preview(new ConfigureWorkflowRequest(true,f.createGraph("UnknownCopy"),null,null,null,null));
            f.afterNative=key->{if(key.equals("workflow-copy"))throw new IllegalStateException("lost native return");};
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals("UNKNOWN",result.getStatus());assertTrue(f.main.containsKey("UnknownCopy"));
            assertEquals("UNKNOWN",step(result,"workflow-copy").get("writeStatus"));assertFalse(step(result,"workflow-copy").containsKey("id"));assertEquals("NOT_ATTEMPTED",step(result,"workflow-save").get("writeStatus"));
        }
    }
    private static void assertRulesNotDeclaredSaved(ConfigureWorkflowResponse result) {
        assertTrue("Planned transitions must not look saved: "+result.getTransitions(),result.getTransitions().isEmpty());
        assertTrue("A planned diff must not look saved: "+result.getDiff(),result.getDiff().isEmpty());
        for(Map<String,Object> object:result.getObjects())assertTrue("Only receipt-backed objects remain: "+object,List.of("STATUS","SCREEN").contains(object.get("kind")));
        assertTrue(result.getMessage(),result.getMessage().contains("not confirmed as saved"));
    }
    @Test public void draftFailureAndSaveFailureDescribeTheDraftWithoutClaimingItsRulesWereSaved() {
        for(String boundary:List.of("draft","workflow-save"))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",true);String original=DescriptorFacts.definition(f.main.get("Existing"));
            ConfigureWorkflowResponse preview=f.preview(f.existing("Existing","ACTIVE",List.of(fields(11,"description"))));
            f.afterNative=key->{if(key.equals(boundary))throw new IllegalStateException("lost return");};
            assertFalse("The preview described rules to be saved",preview.getTransitions().isEmpty());assertFalse(preview.getDiff().isEmpty());
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals(boundary.equals("draft")?"UNKNOWN":"PARTIAL",result.getStatus());
            assertEquals("UNKNOWN",step(result,boundary).get("writeStatus"));assertEquals(original,DescriptorFacts.definition(f.main.get("Existing")));assertTrue(f.drafts.containsKey("Existing"));
            if(boundary.equals("draft"))assertEquals("NOT_ATTEMPTED",step(result,"workflow-save").get("writeStatus"));
            assertRulesNotDeclaredSaved(result);
        }
    }
    @Test public void provenStatusRefusalIsFailedButAnExceptionAfterStatusWriteIsUnknownAndNeverRetried() {
        for(boolean refusal:List.of(true,false))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            WorkflowDefinition graph=new WorkflowDefinition("NewStatus","",List.of(new StatusDefinition("Open",null,null),new StatusDefinition("Review","IN_PROGRESS","new status")),"Open",List.of(new TransitionDefinition("review","Review","Open",false,"Review",null,null)));
            ConfigureWorkflowResponse preview=f.preview(new ConfigureWorkflowRequest(true,graph,null,null,null,null));assertNotNull(preview.getMessage(),preview.getPlanId());
            if(refusal) {ServiceOutcome<Status> invalid=mock(ServiceOutcome.class);when(invalid.isValid()).thenReturn(false);when(invalid.getErrorCollection()).thenReturn(new SimpleErrorCollection());doReturn(invalid).when(f.statusService).createStatus(any(),anyString(),anyString(),anyString(),any());}
            else f.afterNative=key->{if(key.equals("status"))throw new IllegalStateException("lost status return");};
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals(refusal?"FAILED":"UNKNOWN",result.getStatus());assertEquals(refusal?"FAILED":"UNKNOWN",step(result,"status:1").get("writeStatus"));
            assertFalse(step(result,"status:1").containsKey("id"));allUntouchedAfter(result,"status:1");verify(f.statusService,times(1)).createStatus(any(),anyString(),anyString(),anyString(),any());
        }
    }
    @Test public void successfulSaveWithoutReadBackIsAppliedUnavailableAndPartialNotSuccess() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",false);ConfigureWorkflowResponse preview=f.preview(f.existing("Existing","ACTIVE",List.of(fields(11,"description"))));
            f.afterNative=key->{if(key.equals("workflow-save"))when(f.manager.getWorkflow("Existing")).thenThrow(new IllegalStateException("read unavailable"));};
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals("PARTIAL",result.getStatus());assertEquals("APPLIED",step(result,"workflow-save").get("writeStatus"));assertEquals("UNAVAILABLE",step(result,"workflow-save").get("verification"));
        }
    }
    @Test public void mismatchingReadBackNeverClaimsSuccessAndDoesNotCompensate() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",false);ConfigureWorkflowResponse preview=f.preview(f.existing("Existing","ACTIVE",List.of(fields(11,"description"))));
            f.afterNative=key->{if(key.equals("workflow-save"))f.main.get("Existing").getMetaAttributes().put("external","different");};
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals("PARTIAL",result.getStatus());assertEquals("APPLIED",step(result,"workflow-save").get("writeStatus"));assertEquals("MISMATCH",step(result,"workflow-save").get("verification"));assertEquals(1,Collections.frequency(f.nativeCalls,"workflow-save"));
        }
    }
    @Test public void nativeIntentAndConfirmationAreDurableBeforeCallAndBeforeReadBack() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",false);ConfigureWorkflowResponse preview=f.preview(f.existing("Existing","ACTIVE",List.of(fields(11,"description"))));
            AtomicBoolean insideNative=new AtomicBoolean(),confirmedBeforeRead=new AtomicBoolean();
            f.beforeNative=key->{if(key.equals("workflow-save")){assertEquals("UNKNOWN",persistedStep(f,"workflow-save").get("writeStatus"));insideNative.set(true);}};
            f.afterNative=key->{if(key.equals("workflow-save"))when(f.manager.getWorkflow("Existing")).thenAnswer(c->{if(insideNative.get()){assertEquals("APPLIED",persistedStep(f,"workflow-save").get("writeStatus"));confirmedBeforeRead.set(true);}return workflow("Existing",f.main.get("Existing"));});};
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals(result.getMessage(),"UPDATED_INACTIVE",result.getStatus());assertTrue(confirmedBeforeRead.get());
        }
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> persistedStep(WorkflowConfigurationFixture f,String key) {
        try {JournalOperation[] operations=f.journalFixture.entityManager.find(JournalOperation.class);assertEquals(1,operations.length);Map<String,Object> operation=f.journalFixture.journal.getOperation(operations[0].getOperationKey(),"admin");List<Map<String,Object>> items=(List<Map<String,Object>>)((Map<String,Object>)operation.get("execution")).get("steps");return items.stream().filter(s->key.equals(s.get("key"))).findFirst().orElseThrow();}catch(Exception e){throw new IllegalStateException(e);}
    }
    private static JiraWorkflow workflow(String name,WorkflowDescriptor descriptor){JiraWorkflow workflow=mock(JiraWorkflow.class);when(workflow.getName()).thenReturn(name);when(workflow.getDescriptor()).thenReturn(WorkflowConfigurationFixture.copy(descriptor));return workflow;}
    @Test public void allFourSharedLocksSerializeParallelAppliesAndSecondPlanBecomesStale() throws Exception {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",false);ConfigureWorkflowRequest request=f.existing("Existing","ACTIVE",List.of(fields(11,"description")));
            ConfigureWorkflowResponse first=f.preview(request),second=f.preview(request);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger saves=new AtomicInteger();
            f.beforeNative=key->{if(key.equals("workflow-save")){saves.incrementAndGet();entered.countDown();try{if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("not released");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}};
            ExecutorService clients=Executors.newFixedThreadPool(2);
            try {Future<ConfigureWorkflowResponse> one=clients.submit(()->f.apply(first));assertTrue(entered.await(10,TimeUnit.SECONDS));Future<ConfigureWorkflowResponse> two=clients.submit(()->f.apply(second));assertEquals(1,saves.get());release.countDown();assertEquals("UPDATED_INACTIVE",one.get(10,TimeUnit.SECONDS).getStatus());assertEquals("PREVIEW_STALE",two.get(10,TimeUnit.SECONDS).getErrorCode());assertEquals(1,saves.get());}
            finally{release.countDown();clients.shutdownNow();clients.awaitTermination(10,TimeUnit.SECONDS);}
            assertTrue(f.journalFixture.lockNames().containsAll(List.of("ru.rasulov.control.assign-workflow","ru.rasulov.control.create-custom-fields","ru.rasulov.control.create-standalone-screen","ru.rasulov.control.create-workflow")));
        }
    }
    @Test public void individualBoundsAndReceiptCapacityAreValidatedWithoutWrites() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            List<String> fifty=new ArrayList<>();for(int i=0;i<50;i++){String id="customfield_"+i;f.orderable(id,"Field "+i);fifty.add(id);}
            ConfigureWorkflowRequest request=new ConfigureWorkflowRequest(true,f.createGraph("Fifty"),null,List.of(new NewScreen("form","Fifty",null,fifty)),List.of(new TransitionRule("start",null,new ScreenChoice("SET",null,null,"form"),List.of(new Validator("required_fields",new Parameters(fifty,null,null,null))),null)),null);
            ConfigureWorkflowResponse boundary=f.preview(request);assertEquals(boundary.getMessage(),"WOULD_CREATE",boundary.getStatus());assertNotNull(boundary.getPlanId());
            List<String> over=new ArrayList<>(fifty);f.orderable("customfield_51","51");over.add("customfield_51");
            ConfigureWorkflowRequest excess=new ConfigureWorkflowRequest(true,request.create(),null,List.of(new NewScreen("form","Too many",null,over)),request.transitionRules(),null);
            assertEquals("VALIDATION_ERROR",f.preview(excess).getErrorCode());assertTrue(f.nativeCalls.isEmpty());
            WorkflowConfigurationPlan plan=f.planner.plan(request,f.journalFixture.currentActor.get());assertNotNull(plan.fingerprint);assertTrue(WorkflowSnapshotsBytes(plan.execution())<32*1024);
            plan.steps.clear();String escaped="\u0001".repeat(32*1024);plan.step(escaped,"SCREEN");try{plan.reserveReceiptSize();fail("receipt overflow");}catch(WorkflowConfigurationPlan.ConfigurationFailure expected){assertEquals("VALIDATION_ERROR",expected.code);}
        }
    }
    @Test public void maximumScreenLayoutExecutesAll522NativeBoundariesAndStoresLosslesslyBelow32KiB() throws Exception {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            List<String> fields=new ArrayList<>();for(int i=0;i<50;i++){String id="customfield_"+i;f.orderable(id,"Field "+i);fields.add(id);}
            List<TransitionDefinition> transitions=new ArrayList<>();List<NewScreen> screens=new ArrayList<>();List<TransitionRule> rules=new ArrayList<>();
            for(int i=0;i<10;i++){
                String key="s"+i;transitions.add(new TransitionDefinition(key,"Start "+i,"Open",false,"In Progress",null,null));
                screens.add(new NewScreen(key,"Form "+i,null,fields));rules.add(new TransitionRule(key,null,new ScreenChoice("SET",null,null,key),null,null));
            }
            WorkflowDefinition graph=new WorkflowDefinition("Maximum","all boundaries",f.createGraph("Maximum").getStatuses(),"Open",transitions);
            ConfigureWorkflowResponse preview=f.preview(new ConfigureWorkflowRequest(true,graph,null,screens,rules,null));
            assertEquals(preview.getMessage(),"WOULD_CREATE",preview.getStatus());assertNotNull(preview.getPlanId());assertTrue(f.nativeCalls.isEmpty());
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals(result.getMessage(),"CREATED",result.getStatus());
            assertEquals(522,f.nativeCalls.size());assertEquals(522,steps(result).size());assertEquals(10,f.screenStore.size());
            for(Map<String,Object> item:steps(result)){assertEquals("APPLIED",item.get("writeStatus"));assertEquals("MATCH",item.get("verification"));assertNotNull(item.get("id"));}
            assertTrue("The expanded receipt is not subject to the storage cap",WorkflowSnapshotsBytes(result.getExecution())>32*1024);
            String encoded=ru.rasulov.common.impl.undo.WorkflowReceiptCodec.write(result.getExecution());
            assertTrue(ru.rasulov.common.impl.undo.WorkflowReceiptCodec.bytes(encoded)<=32*1024);
            assertEquals(result.getExecution(),ru.rasulov.common.impl.undo.WorkflowReceiptCodec.read(encoded));
            JournalOperation operation=f.journalFixture.entityManager.find(JournalOperation.class)[0];
            assertEquals(result.getExecution(),f.journalFixture.journal.getOperation(operation.getOperationKey(),"admin").get("execution"));
        }
    }
    @Test public void longCyrillicKeysAtEveryMaximumNeverGrowTheEncodedReceiptAndStayReadableThroughObjects() throws Exception {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            List<String> fields=new ArrayList<>();for(int i=0;i<50;i++){String id="customfield_"+i;f.orderable(id,"Field "+i);fields.add(id);}
            List<StatusDefinition> statuses=new ArrayList<>();for(int i=0;i<30;i++)statuses.add(new StatusDefinition("Статус "+i,"TO_DO",null));
            List<TransitionDefinition> transitions=new ArrayList<>();List<NewScreen> screens=new ArrayList<>();List<TransitionRule> rules=new ArrayList<>();
            for(int i=0;i<100;i++){
                String key=("переход-"+i+"-").concat("ж".repeat(100));
                transitions.add(new TransitionDefinition(key,"Переход "+i,"Статус "+(i%30),false,"Статус "+((i%30+1+i/30)%30),null,null));
                if(i<10){
                    String screenKey=("экран-ревью-"+i+"-").concat("я".repeat(100));
                    screens.add(new NewScreen(screenKey,"Форма "+i,null,fields));rules.add(new TransitionRule(key,null,new ScreenChoice("SET",null,null,screenKey),null,null));
                }
            }
            WorkflowDefinition graph=new WorkflowDefinition("Максимум","all maximums",statuses,"Статус 0",transitions);
            ConfigureWorkflowResponse preview=f.preview(new ConfigureWorkflowRequest(true,graph,null,screens,rules,null));
            assertEquals(preview.getMessage(),"WOULD_CREATE",preview.getStatus());assertNotNull(preview.getPlanId());assertTrue(f.nativeCalls.isEmpty());
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals(result.getMessage(),"CREATED",result.getStatus());
            assertEquals(30+520+2,steps(result).size());assertEquals(steps(result).size(),f.nativeCalls.size());assertEquals(10,f.screenStore.size());
            for(Map<String,Object> item:steps(result)){
                assertTrue(item.toString(),item.get("key").toString().matches("status:\\d+|screen:\\d+|tab:\\d+|field:\\d+:\\d+|workflow-copy|workflow-save"));
                assertEquals("APPLIED",item.get("writeStatus"));assertEquals("MATCH",item.get("verification"));
            }
            String encoded=ru.rasulov.common.impl.undo.WorkflowReceiptCodec.write(result.getExecution());
            assertTrue(ru.rasulov.common.impl.undo.WorkflowReceiptCodec.bytes(encoded)<=32*1024);
            assertEquals(result.getExecution(),ru.rasulov.common.impl.undo.WorkflowReceiptCodec.read(encoded));
            JournalOperation operation=f.journalFixture.entityManager.find(JournalOperation.class)[0];
            assertEquals(result.getExecution(),f.journalFixture.journal.getOperation(operation.getOperationKey(),"admin").get("execution"));
            // The ordinal in a step key is recoverable: the n-th requested screen is the object whose receiptKey is screen:n.
            int found=0;
            for(Map<String,Object> object:result.getObjects()) if("SCREEN".equals(object.get("kind"))) {
                assertEquals("screen:"+found,object.get("receiptKey"));assertEquals(screens.get(found).key(),object.get("key"));found++;
            }
            assertEquals(10,found);
        }
    }
    @Test public void returnedNativeWriteWithMissingOrThrowingIdIsDurablyAppliedUnavailableAndNeverContinues() {
        for(boolean throwing:List.of(false,true))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowResponse preview=f.preview(new ConfigureWorkflowRequest(true,f.createGraph("NoId"),null,null,null,null));
            f.afterNative=key->{if(key.equals("workflow-copy")) {
                if(throwing)when(f.delegator.findByCondition(eq("Workflow"),any(),any(),any())).thenThrow(new IllegalStateException("x".repeat(300)));
                else {org.ofbiz.core.entity.GenericValue row=mock(org.ofbiz.core.entity.GenericValue.class);when(row.getLong("id")).thenReturn(null);when(f.delegator.findByCondition(eq("Workflow"),any(),any(),any())).thenReturn(List.of(row));}
            }};
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals(result.getMessage(),"PARTIAL",result.getStatus());
            Map<String,Object> copied=step(result,"workflow-copy");assertEquals("APPLIED",copied.get("writeStatus"));assertEquals("UNAVAILABLE",copied.get("verification"));assertFalse(copied.containsKey("id"));
            assertTrue(copied.get("error").toString().length()<=ru.rasulov.common.impl.undo.WorkflowReceiptCodec.ERROR_LENGTH);
            assertEquals(copied,persistedStep(f,"workflow-copy"));assertEquals("NOT_ATTEMPTED",step(result,"workflow-save").get("writeStatus"));
            assertTrue(f.main.containsKey("NoId"));assertEquals(List.of("workflow-copy"),f.nativeCalls);
        }
    }
    @Test public void copiedWorkflowReadBackIncludesTheConfirmedDescriptionAndStopsOnItsMismatch() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowResponse preview=f.preview(new ConfigureWorkflowRequest(true,f.createGraph("CopyDescription"),null,null,null,null));
            f.afterNative=key->{if(key.equals("workflow-copy"))f.main.get("CopyDescription").getMetaAttributes().put("jira.description","not the confirmed description");};
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals("PARTIAL",result.getStatus());assertEquals("APPLIED",step(result,"workflow-copy").get("writeStatus"));
            assertEquals("MISMATCH",step(result,"workflow-copy").get("verification"));assertEquals("NOT_ATTEMPTED",step(result,"workflow-save").get("writeStatus"));
        }
    }
    @Test public void mainChangesDuringOwnDraftCreationAreNeverAcceptedAsAnExpectedDependency() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",true);ConfigureWorkflowResponse preview=f.preview(f.existing("Existing","ACTIVE",List.of(fields(11,"description"))));
            f.afterNative=key->{if(key.equals("draft"))f.main.get("Existing").getMetaAttributes().put("external","changed active main");};
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals("PARTIAL",result.getStatus());assertEquals("PREVIEW_STALE",result.getErrorCode());
            assertEquals("APPLIED",step(result,"draft").get("writeStatus"));assertEquals("MATCH",step(result,"draft").get("verification"));
            assertEquals("NOT_ATTEMPTED",step(result,"workflow-save").get("writeStatus"));assertTrue(f.drafts.containsKey("Existing"));
            assertTrue(RequiredFieldsDescriptor.scan(DescriptorFacts.locate(f.drafts.get("Existing")).get(11).action()).isEmpty());
        }
    }
    @Test public void everyNativeObjectKindStopsWithAppliedUnavailableWhenItsReadBackCannotBeRead() {
        for(String boundary:List.of("status","screen","tab","field","workflow-copy","draft","workflow-save"))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowRequest request;
            if(boundary.equals("draft") || boundary.equals("workflow-save")) {
                existingGraph(f,"Readback",boundary.equals("draft"));request=f.existing("Readback","ACTIVE",List.of(fields(11,"description")));
            } else if(boundary.equals("status")) {
                WorkflowDefinition graph=new WorkflowDefinition("Readback","",List.of(new StatusDefinition("Open",null,null),new StatusDefinition("Review","IN_PROGRESS","review")),"Open",List.of(new TransitionDefinition("review","Review","Open",false,"Review",null,null)));
                request=new ConfigureWorkflowRequest(true,graph,null,null,null,null);
            } else request=combined(f,"Readback");
            ConfigureWorkflowResponse preview=f.preview(request);assertNotNull(preview.getMessage(),preview.getPlanId());
            f.afterNative=key->{if(boundary.equals(key)) {
                if(boundary.equals("status"))when(f.statusManager.getStatus(anyString())).thenThrow(new IllegalStateException("status read-back unavailable"));
                else if(List.of("screen","tab","field").contains(boundary))when(f.screens.getFieldScreen(anyLong())).thenThrow(new IllegalStateException("screen read-back unavailable"));
                else if(boundary.equals("draft"))when(f.manager.getDraftWorkflow("Readback")).thenThrow(new IllegalStateException("draft read-back unavailable"));
                else when(f.manager.getWorkflow("Readback")).thenThrow(new IllegalStateException("workflow read-back unavailable"));
            }};
            ConfigureWorkflowResponse result=f.apply(preview);String receipt=switch(boundary){case "status"->"status:1";case "screen"->"screen:0";case "tab"->"tab:0";case "field"->"field:0:0";default->boundary;};
            assertEquals(boundary+": "+result.getMessage(),"PARTIAL",result.getStatus());assertEquals("APPLIED",step(result,receipt).get("writeStatus"));assertNotNull(step(result,receipt).get("id"));
            assertEquals("UNAVAILABLE",step(result,receipt).get("verification"));allUntouchedAfter(result,receipt);assertEquals(1,Collections.frequency(f.nativeCalls,boundary));
        }
    }
    private static int WorkflowSnapshotsBytes(Object value){return ru.rasulov.workflow.impl.undo.WorkflowSnapshots.json(value).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;}
}
