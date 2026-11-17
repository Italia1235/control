package ru.rasulov.workflow.impl;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.workflow.api.dto.*;
import ru.rasulov.workflow.api.dto.configure.*;
import ru.rasulov.workflow.impl.edit.WorkflowConfigurationFixture;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Creation contract through the unified preview/apply service, with native read-back. */
public class WorkflowCreatorImplTest {
    private WorkflowConfigurationFixture f;
    @Before public void setUp() { f=new WorkflowConfigurationFixture(); f.statusStore.remove("2"); }
    @After public void close() { f.close(); }
    static WorkflowDefinition keyed(WorkflowDefinition graph) {
        List<TransitionDefinition> transitions=new ArrayList<>(); int index=0;
        for(TransitionDefinition t:graph.getTransitions()) transitions.add(new TransitionDefinition("t"+(index++),t.getName(),t.getFrom(),t.isFromAny(),t.getTo(),t.getScreen(),t.getResolution()));
        return new WorkflowDefinition(graph.getName(),graph.getDescription(),graph.getStatuses(),graph.getInitialStatus(),transitions);
    }
    static ConfigureWorkflowRequest request(WorkflowDefinition graph) { return new ConfigureWorkflowRequest(true,keyed(graph),null,null,null,null); }
    private ConfigureWorkflowResponse preview() { return f.preview(request(WorkflowMocks.simple())); }
    private ConfigureWorkflowResponse create() { return f.apply(preview()); }
    @Test public void explicitModeAndPreviewTokenAreRequiredByTheService() {
        try { f.preview(new ConfigureWorkflowRequest(null,null,null,null,null,null)); fail("missing dryRun"); }
        catch(InvalidRequestException expected) { assertTrue(expected.getMessage().contains("dryRun")); }
        assertEquals("PREVIEW_STALE",f.configurer.configure(new ConfigureWorkflowRequest(false,null,null,null,null,"missing")).getErrorCode());
    }
    @Test public void dryRunWritesNothingAndDoesNotWaitForTheLock() {
        ru.rasulov.common.impl.NamedLock lock=mock(ru.rasulov.common.impl.NamedLock.class);
        ConfigureWorkflowResponse result=service(lock).configure(request(WorkflowMocks.simple()));
        assertEquals(result.getMessage(),"WOULD_CREATE",result.getStatus()); assertTrue(result.isDryRun());
        assertEquals("Bug flow",result.getWorkflow()); assertEquals(3,result.getStatuses().size());
        assertTrue(f.nativeCalls.isEmpty()); verifyNoInteractions(lock);
    }
    @Test public void dryRunSaysWhichStatusesAreNewAndHasNoIdForThem() {
        List<WorkflowStatusView> statuses=preview().getStatuses();
        assertEquals("Open",statuses.get(0).getName()); assertEquals("1",statuses.get(0).getId()); assertFalse(statuses.get(0).isNew());
        assertEquals("In Progress",statuses.get(1).getName()); assertNull(statuses.get(1).getId()); assertTrue(statuses.get(1).isNew()); assertEquals("IN_PROGRESS",statuses.get(1).getCategory());
    }
    @Test public void creatingMakesOnlyTheMissingStatusesThenTheWorkflowUnderTheLock() {
        ru.rasulov.common.impl.NamedLock lock=mock(ru.rasulov.common.impl.NamedLock.class);
        when(lock.run(anyString(),anyString(),any())).thenAnswer(c->((java.util.function.Supplier<?>)c.getArgument(2)).get());
        ru.rasulov.workflow.impl.edit.WorkflowConfigurerImpl service=service(lock);
        ConfigureWorkflowResponse preview=service.configure(request(WorkflowMocks.simple()));
        ConfigureWorkflowResponse result=service.configure(new ConfigureWorkflowRequest(false,null,null,null,null,preview.getPlanId()));
        assertEquals(result.getMessage(),"CREATED",result.getStatus());
        assertEquals(List.of("status","workflow-copy","workflow-save"),f.nativeCalls);
        assertEquals("1",result.getStatuses().get(0).getId()); assertNotNull(result.getStatuses().get(1).getId()); assertTrue(result.getStatuses().get(1).isNew()); assertEquals("3",result.getStatuses().get(2).getId());
        assertEquals("1",f.main.get("Bug flow").getStep(1).getMetaAttributes().get("jira.status.id"));
        verify(lock).run(eq("ru.rasulov.control.create-workflow"),anyString(),any());
    }
    @Test public void receiptListsCreatedNativeObjectsAndReusedStatusesAreNeverWritten() {
        ConfigureWorkflowResponse preview=preview(); assertNull(preview.getExecution());
        assertEquals(2,preview.getObjects().size()); assertEquals("STATUS",preview.getObjects().get(0).get("kind")); assertEquals("WORKFLOW",preview.getObjects().get(1).get("kind"));
        ConfigureWorkflowResponse result=f.apply(preview); List<Map<String,Object>> steps=steps(result);
        assertEquals(3,steps.size()); for(Map<String,Object> step:steps) { assertEquals("APPLIED",step.get("writeStatus")); assertEquals("MATCH",step.get("verification")); assertNotNull(step.get("id")); }
        assertEquals(1,Collections.frequency(f.nativeCalls,"status"));
    }
    @Test public void aWarningOfTheValidatorReachesTheCreatedResult() {
        WorkflowDefinition graph=WorkflowMocks.request("W",List.of(WorkflowMocks.status("Open",null),WorkflowMocks.status("Done",null)),"Open",List.of(WorkflowMocks.move("Wait","Open","Open")));
        ConfigureWorkflowResponse result=f.apply(f.preview(request(graph))); assertEquals(result.getMessage(),"CREATED",result.getStatus());
        assertTrue(result.getWarnings().toString(),result.getWarnings().toString().contains("No transition leads to 'Done'"));
    }
    @Test public void aRepeatOfAnExistingWorkflowIsExistsAndWritesNothing() {
        create(); f.nativeCalls.clear(); ConfigureWorkflowResponse result=preview(); assertEquals("EXISTS",result.getStatus()); assertNull(result.getPlanId()); assertTrue(f.nativeCalls.isEmpty());
    }
    @Test public void theNameOfAnExistingWorkflowIsComparedWithoutCase() {
        create(); WorkflowDescriptor descriptor=f.main.remove("Bug flow"); f.main.put("BUG FLOW",descriptor);
        ConfigureWorkflowResponse result=preview(); assertEquals("EXISTS",result.getStatus()); assertEquals("BUG FLOW",result.getWorkflow());
    }
    @Test public void aDifferentDescriptionOfAnExistingWorkflowIsReportedAsIgnored() {
        create(); WorkflowDefinition base=WorkflowMocks.simple(); ConfigureWorkflowResponse result=f.preview(request(new WorkflowDefinition(base.getName(),"new text",base.getStatuses(),base.getInitialStatus(),base.getTransitions())));
        assertEquals("EXISTS",result.getStatus()); assertTrue(result.getWarnings().toString(),result.getWarnings().toString().contains("description"));
        assertEquals("",f.manager.getWorkflow("Bug flow").getDescription());
    }
    @Test public void anExistingWorkflowThatDiffersIsANameConflictAndIsNotTouched() {
        create(); ((com.opensymphony.workflow.loader.ActionDescriptor)f.main.get("Bug flow").getStep(1).getActions().get(0)).setName("Changed"); String before=f.revision("Bug flow"); f.nativeCalls.clear();
        ConfigureWorkflowResponse result=preview(); assertEquals("FAILED",result.getStatus()); assertEquals("NAME_CONFLICT",result.getErrorCode()); assertNull(result.getPlanId()); assertEquals(before,f.revision("Bug flow")); assertTrue(f.nativeCalls.isEmpty());
    }
    @Test public void aRejectedRequestIsAFailedResultWithTheNameAsGivenAndNothingIsWritten() {
        WorkflowDefinition base=WorkflowMocks.simple(); ConfigureWorkflowResponse result=f.preview(request(new WorkflowDefinition(" Bug flow ",null,base.getStatuses(),"Backlog",base.getTransitions())));
        assertEquals("FAILED",result.getStatus()); assertEquals("UNKNOWN_STATUS",result.getErrorCode()); assertEquals(" Bug flow ",result.getWorkflow()); assertTrue(result.isDryRun()); assertTrue(f.nativeCalls.isEmpty());
    }
    @Test(expected=InvalidRequestException.class) public void anEmptyCallIsRejectedAsAWhole() { f.configurer.configure(null); }
    @Test public void nobodyWhoIsNotAnAdministratorGetsAnythingDone() {
        when(f.journalFixture.guard.requireAdministrator()).thenThrow(new AccessException("Jira administrator permission is required.",false));
        try { preview(); fail("expected AccessException"); } catch(AccessException expected) { assertTrue(f.nativeCalls.isEmpty()); }
    }
    @Test public void listingReturnsWhatTheCatalogHas() {
        assertEquals(2,f.catalog.list(null,null).getTotal()); assertEquals("Open",f.catalog.list("Open",5).getStatuses().get(0).getName());
    }
    @Test public void directPreviewApplyWritesApprovedGraphAndConsumesToken() {
        ConfigureWorkflowResponse preview=preview(); assertNotNull(preview.getPlanId()); assertTrue(f.nativeCalls.isEmpty());
        ConfigureWorkflowResponse result=f.apply(preview); assertEquals("CREATED",result.getStatus()); assertEquals("1",result.getStatuses().get(0).getId());
        assertEquals("1",f.main.get("Bug flow").getStep(1).getMetaAttributes().get("jira.status.id")); assertEquals("PREVIEW_STALE",f.apply(preview).getErrorCode()); assertEquals(1,Collections.frequency(f.nativeCalls,"workflow-copy"));
    }
    @Test public void directForeignTokenDoesNotWriteOrConsumeOwnersPlan() {
        ConfigureWorkflowResponse preview=preview(); com.atlassian.jira.user.ApplicationUser owner=f.journalFixture.currentActor.get();
        com.atlassian.jira.user.ApplicationUser other=ru.rasulov.common.impl.undo.UndoTestFixture.user("other-admin"); f.journalFixture.currentActor.set(other);
        assertEquals("PREVIEW_STALE",f.apply(preview).getErrorCode()); assertTrue(f.nativeCalls.isEmpty()); f.journalFixture.currentActor.set(owner); assertEquals("CREATED",f.apply(preview).getStatus());
    }
    @Test public void directChangedStatusRejectsAndConsumesPlanWithoutWrites() {
        ConfigureWorkflowResponse preview=preview(); Status original=f.statusStore.remove("1"); f.status("99","Open","new"); assertEquals("PREVIEW_STALE",f.apply(preview).getErrorCode()); f.statusStore.remove("99"); f.statusStore.put("1",original); assertEquals("PREVIEW_STALE",f.apply(preview).getErrorCode()); assertTrue(f.nativeCalls.isEmpty());
    }
    @Test public void directApplyNeverWritesStatusFromResolveAfterFingerprintCheck() {
        ConfigureWorkflowResponse preview=preview(); f.beforeNative=key->{if(key.equals("workflow-copy")) { f.statusStore.remove("1"); f.status("99","Open","new"); }};
        ConfigureWorkflowResponse result=f.apply(preview); assertFalse("CREATED".equals(result.getStatus()));
        for(WorkflowStatusView view:result.getStatuses()) assertFalse("Status 99 must never be reported","99".equals(view.getId()));
        assertEquals(steps(result).stream().filter(s->"status:1".equals(s.get("key"))).findFirst().orElseThrow().get("id"),result.getStatuses().get(0).getId());
        assertTrue("A changed dependency cannot silently substitute status 99",!f.main.containsKey("Bug flow") || !"99".equals(f.main.get("Bug flow").getStep(1).getMetaAttributes().get("jira.status.id")));
    }
    @Test public void directApplyResolvesThePlanInsideTheLockCallback() {
        ru.rasulov.common.impl.NamedLock lock=mock(ru.rasulov.common.impl.NamedLock.class);
        ru.rasulov.workflow.impl.edit.WorkflowConfigurerImpl service=service(lock);
        ConfigureWorkflowResponse preview=service.configure(request(WorkflowMocks.simple())); boolean[] locked={false};
        when(lock.run(anyString(),anyString(),any())).thenAnswer(c->{boolean old=locked[0]; locked[0]=true; try{return ((java.util.function.Supplier<?>)c.getArgument(2)).get();}finally{locked[0]=old;}});
        when(f.statusManager.getStatuses()).thenAnswer(c->{assertTrue("Apply resolves statuses under locks",locked[0]); return new ArrayList<>(f.statusStore.values());});
        assertEquals("CREATED",service.configure(new ConfigureWorkflowRequest(false,null,null,null,null,preview.getPlanId())).getStatus());
    }
    @Test public void changedResolutionCatalogInvalidatesPreviewWithoutWrites() {
        for(String change:List.of("deleted","name","description")) {
            com.atlassian.jira.issue.resolution.Resolution resolution=f.constants.getResolution("1"); when(resolution.getName()).thenReturn("Fixed"); when(resolution.getDescription()).thenReturn("Work completed"); ConfigureWorkflowResponse preview=preview();
            if(change.equals("deleted"))when(f.constants.getResolution("1")).thenReturn(null); if(change.equals("name"))when(resolution.getName()).thenReturn("Changed"); if(change.equals("description"))when(resolution.getDescription()).thenReturn("Changed");
            assertEquals(change,"PREVIEW_STALE",f.apply(preview).getErrorCode()); assertTrue(f.nativeCalls.isEmpty()); when(f.constants.getResolution("1")).thenReturn(resolution);
        }
    }
    @Test public void changedStatusCategoryInvalidatesPreviewWithoutWrites() {
        ConfigureWorkflowResponse preview=preview(); f.status("1","Open","done"); assertEquals("PREVIEW_STALE",f.apply(preview).getErrorCode()); assertTrue(f.nativeCalls.isEmpty());
    }
    @Test public void previewReportsEffectiveResolutionActionsAndMissingSetNeverWrites() {
        ConfigureWorkflowResponse preview=preview(); String shown=preview.getTransitions().toString()+preview.getDiff();
        assertTrue(shown,shown.contains("PRESERVE")); assertTrue(shown,shown.contains("SET")); assertTrue(shown,shown.contains("Fixed")); assertTrue(shown,shown.contains("CLEAR"));
        WorkflowDefinition base=WorkflowMocks.simple(); ConfigureWorkflowResponse missing=f.preview(request(new WorkflowDefinition(base.getName(),null,base.getStatuses(),"Open",List.of(new TransitionDefinition("Finish","Open",false,"Done")))));
        assertEquals("VALIDATION_ERROR",missing.getErrorCode()); assertTrue(f.nativeCalls.isEmpty());
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> steps(ConfigureWorkflowResponse result) { return (List<Map<String,Object>>)result.getExecution().get("steps"); }
    @Test public void directExpiredTokenDoesNotWrite() {
        Map<String,ActionPlanStore.Plan> stored=new HashMap<>();
        com.atlassian.cache.Cache<String,ActionPlanStore.Plan> cache=mock(com.atlassian.cache.Cache.class);
        when(cache.get(anyString())).thenAnswer(c->stored.get(c.getArgument(0)));
        doAnswer(c->{stored.put(c.getArgument(0),c.getArgument(1));return null;}).when(cache).put(anyString(),any(ActionPlanStore.Plan.class));
        doAnswer(c->{stored.remove(c.getArgument(0));return null;}).when(cache).remove(anyString());
        com.atlassian.cache.CacheManager manager=mock(com.atlassian.cache.CacheManager.class);
        when(manager.<String,ActionPlanStore.Plan>getCache(anyString(),isNull(),any(com.atlassian.cache.CacheSettings.class))).thenReturn(cache);
        ActionPlanStore plans=new ActionPlanStore(manager);
        ru.rasulov.workflow.impl.edit.WorkflowConfigurerImpl service=new ru.rasulov.workflow.impl.edit.WorkflowConfigurerImpl(
                f.journalFixture.guard,f.journalFixture.locks,f.planner,f.writer,
                new ru.rasulov.common.impl.PreviewPlanExecutor(plans,new ru.rasulov.common.impl.ActionPlanCodec()),f.journalFixture.journal);
        ConfigureWorkflowResponse preview=service.configure(request(WorkflowMocks.simple()));
        ActionPlanStore.Plan plan=stored.get(preview.getPlanId());
        stored.put(plan.getId(),new ActionPlanStore.Plan(plan.getId(),"configure_workflow","admin",plan.getRequest(),plan.getFingerprint(),0));
        assertEquals("PREVIEW_STALE",service.configure(new ConfigureWorkflowRequest(false,null,null,null,null,preview.getPlanId())).getErrorCode());
        assertTrue(f.nativeCalls.isEmpty());
    }
    private ru.rasulov.workflow.impl.edit.WorkflowConfigurerImpl service(ru.rasulov.common.impl.NamedLock lock) {
        return new ru.rasulov.workflow.impl.edit.WorkflowConfigurerImpl(f.journalFixture.guard,lock,f.planner,f.writer,
                new ru.rasulov.common.impl.PreviewPlanExecutor(f.journalFixture.plans,new ru.rasulov.common.impl.ActionPlanCodec()),f.journalFixture.journal);
    }
}
