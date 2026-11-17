package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.workflow.JiraWorkflow;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ConditionsDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.junit.Test;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.configure.*;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static ru.rasulov.workflow.impl.edit.WorkflowConfigurationNativeTest.*;

/** Observable composite-planner contracts; all descriptors are parsed and round-tripped by Jira. */
public class WorkflowConfigurationPlannerTest {
    private static TransitionRule group(int id) {
        return new TransitionRule(null,id,null,null,new Conditions("AND",List.of(new Condition("group",new Parameters(null,"admins",null,null)))));
    }
    private static TransitionRule screen(int id,String target) {
        return new TransitionRule(null,id,new ScreenChoice("SET",target,null,null),null,null);
    }
    private static void failedWithoutWrites(WorkflowConfigurationFixture f,ConfigureWorkflowRequest request,String code) {
        ConfigureWorkflowResponse result=f.preview(request);
        assertEquals(result.getMessage(),code,result.getErrorCode());assertNull(result.getPlanId());assertTrue(f.nativeCalls.isEmpty());
    }
    private static ActionDescriptor action(WorkflowDescriptor descriptor,int id){return DescriptorFacts.locate(descriptor).get(id).action();}
    private static WorkflowConfigurationPlan plan(WorkflowConfigurationFixture f,ConfigureWorkflowRequest request){return f.planner.plan(request,f.journalFixture.currentActor.get());}

    @Test public void createPreviewIncludesUnconfiguredLocalGlobalAndInitialActionsAndSupportsUnrestrictedLocalKeys() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            String transitionKey="переход / "+"длинный".repeat(12),screenKey="экран / "+"ключ".repeat(30);
            WorkflowDefinition graph=new WorkflowDefinition("Preview","description",f.createGraph("Preview").getStatuses(),"Open",List.of(
                    new TransitionDefinition(transitionKey,"Start","Open",false,"In Progress",null,null),
                    new TransitionDefinition("back","Back",null,true,"Open",null,null)));
            ConfigureWorkflowRequest request=new ConfigureWorkflowRequest(true,graph,null,List.of(new NewScreen(screenKey,"Form",null,List.of())),List.of(
                    new TransitionRule(transitionKey,null,new ScreenChoice("SET",null,null,screenKey),null,null)),null);
            ConfigureWorkflowResponse preview=f.preview(request);assertEquals(preview.getMessage(),"WOULD_CREATE",preview.getStatus());assertNotNull(preview.getPlanId());
            assertEquals(3,preview.getTransitions().size());
            assertTrue(preview.getTransitions().stream().anyMatch(t->"GLOBAL".equals(t.get("kind")) && "back".equals(t.get("transitionKey"))));
            assertTrue(preview.getTransitions().stream().anyMatch(t->"INITIAL".equals(t.get("kind"))));
            assertTrue(preview.getTransitions().stream().anyMatch(t->transitionKey.equals(t.get("transitionKey")) && screenKey.equals(t.get("screenKey"))));
            assertEquals("CREATED",f.apply(preview).getStatus());assertEquals(1,f.screenStore.size());
        }
    }
    @Test public void existingNameReturnsCanonicalExistsAndDescriptionWarningButRulesNeverSilentlyConfigureIt() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Canonical",false);
            WorkflowDefinition base=f.createGraph(" canonical ");
            WorkflowDefinition graph=new WorkflowDefinition(base.getName(),"different",base.getStatuses(),base.getInitialStatus(),base.getTransitions());
            ConfigureWorkflowResponse exists=f.preview(new ConfigureWorkflowRequest(true,graph,null,null,null,null));
            assertEquals(exists.getMessage(),"EXISTS",exists.getStatus());assertEquals("Canonical",exists.getWorkflow());assertNull(exists.getPlanId());
            assertTrue(exists.getWarnings().stream().anyMatch(w->w.contains("description") && w.contains("unchanged")));
            failedWithoutWrites(f,new ConfigureWorkflowRequest(true,graph,null,null,List.of(new TransitionRule("start",null,null,List.of(),null)),null),"NAME_CONFLICT");
        }
    }
    @Test public void omittedAndEmptyRuleArraysAreUnchangedWithoutRepairingUnrelatedBrokenCommentScreens() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.putWorkflow("Existing",ValidatorFixtures.commentWorkflow(),false);
            String original=DescriptorFacts.definition(f.main.get("Existing"));
            for(List<TransitionRule> rules:Arrays.asList(null,List.<TransitionRule>of(),List.of(new TransitionRule(null,13,new ScreenChoice("KEEP",null,null,null),List.of(),new Conditions("AND",List.of()))))) {
                ConfigureWorkflowResponse result=f.preview(f.existing("Existing","ACTIVE",rules));assertEquals(result.getMessage(),"UNCHANGED",result.getStatus());assertNull(result.getPlanId());
            }
            assertEquals(original,DescriptorFacts.definition(f.main.get("Existing")));assertTrue(f.nativeCalls.isEmpty());
        }
    }
    @Test public void versionPolicyRejectsActiveWithDraftAndMissingExplicitDraft() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",true);
            failedWithoutWrites(f,f.existing("Existing","DRAFT",List.of(fields(11,"description"))),"VERSION_CONFLICT");
            f.drafts.put("Existing",WorkflowConfigurationFixture.copy(f.main.get("Existing")));
            failedWithoutWrites(f,f.existing("Existing","ACTIVE",List.of(fields(11,"description"))),"DRAFT_EXISTS");
        }
    }
    @Test public void requiredFieldsUnionAndConditionalCoverageNeverRewriteExistingValidators() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.putWorkflow("Existing",ValidatorFixtures.workflow(),false);
            ConfigureWorkflowResponse covered=f.preview(f.existing("Existing","ACTIVE",List.of(fields(23,"description","assignee"))));
            assertEquals("UNCHANGED",covered.getStatus());assertNull(covered.getPlanId());
            String before=DescriptorFacts.xml(ValidatorFixtures.validators(action(f.main.get("Existing"),22)).get(0));
            ConfigureWorkflowResponse result=f.apply(f.preview(f.existing("Existing","ACTIVE",List.of(fields(22,"assignee","description"),fields(24,"assignee")))));
            assertEquals(result.getMessage(),"UPDATED_INACTIVE",result.getStatus());
            assertEquals(before,DescriptorFacts.xml(ValidatorFixtures.validators(action(f.main.get("Existing"),22)).get(0)));
            assertEquals(3,ValidatorFixtures.validators(action(f.main.get("Existing"),22)).size());
            assertEquals(List.of("description"),RequiredFieldsDescriptor.scan(action(f.main.get("Existing"),22)).get(2).fieldIds());
            assertEquals(2,ValidatorFixtures.validators(action(f.main.get("Existing"),24)).size());
            assertFalse(RequiredFieldsDescriptor.scan(action(f.main.get("Existing"),24)).get(1).conditional());
        }
    }
    @Test public void requestedUnrecognizedTemplatesRefuseTheWholePlan() {
        for(boolean comment:List.of(false,true))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.putWorkflow("Existing",comment?ValidatorFixtures.commentWorkflow():ValidatorFixtures.workflow(),false);
            TransitionRule rule=comment?new TransitionRule(null,19,null,List.of(new Validator("comment_required",null)),null):fields(25,"description");
            failedWithoutWrites(f,f.existing("Existing","ACTIVE",List.of(rule)),"UNSUPPORTED_DESCRIPTOR");
        }
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.putWorkflow("Existing",ConditionFixtures.workflow(),false);
            failedWithoutWrites(f,f.existing("Existing","ACTIVE",List.of(group(18))),"UNSUPPORTED_DESCRIPTOR");
        }
    }
    @Test public void conditionsRejectOrAndUnknownRootsButAppendAfterPreservedNestedBranches() {
        for(String root:List.of("OR","XOR"))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.putWorkflow("Existing",ConditionFixtures.workflow(),false);
            f.mutateWorkflowSource("Existing",descriptor->action(descriptor,15).getRestriction().getConditionsDescriptor().setType(root));
            failedWithoutWrites(f,f.existing("Existing","ACTIVE",List.of(group(15))),"UNSUPPORTED_DESCRIPTOR");
        }
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            WorkflowDescriptor descriptor=ConditionFixtures.workflow();ActionDescriptor target=action(descriptor,14);
            ConditionsDescriptor nested=(ConditionsDescriptor)target.getRestriction().getConditionsDescriptor().getConditions().get(1);
            // A matching condition inside OR must not count as an unconditional direct child.
            ((com.opensymphony.workflow.loader.ConditionDescriptor)nested.getConditions().get(0)).getArgs().put("group","admins");
            String preserved=DescriptorFacts.xml(nested);f.putWorkflow("Existing",descriptor,false);
            ConfigureWorkflowResponse saved=f.apply(f.preview(f.existing("Existing","ACTIVE",List.of(group(14)))));assertEquals(saved.getMessage(),"UPDATED_INACTIVE",saved.getStatus());
            ConditionsDescriptor root=action(f.main.get("Existing"),14).getRestriction().getConditionsDescriptor();
            assertEquals(3,root.getConditions().size());assertEquals(preserved,DescriptorFacts.xml((ConditionsDescriptor)root.getConditions().get(1)));
            assertEquals(ConditionDescriptors.Outcome.UNCHANGED,ConditionDescriptors.check(action(f.main.get("Existing"),14),ConditionDescriptors.Candidate.group("ADMINS")).outcome());
        }
    }
    @Test public void initialAcceptsRulesCommonOnlyAcceptsScreensAndDuplicateIdsRefuseBoth() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.putWorkflow("Existing",ConditionFixtures.workflow(),false);f.screen(77,"Form",List.of());
            ConfigureWorkflowResponse initial=f.apply(f.preview(f.existing("Existing","ACTIVE",List.of(group(1)))));assertEquals(initial.getMessage(),"UPDATED_INACTIVE",initial.getStatus());
            failedWithoutWritesAfterPreviousApply(f,f.existing("Existing","ACTIVE",List.of(screen(1,"77"))),"UNSUPPORTED_DESCRIPTOR");
            failedWithoutWritesAfterPreviousApply(f,f.existing("Existing","ACTIVE",List.of(group(41))),"UNSUPPORTED_DESCRIPTOR");
            ConfigureWorkflowResponse common=f.preview(f.existing("Existing","ACTIVE",List.of(screen(41,"77"))));assertNotNull(common.getMessage(),common.getPlanId());
            assertEquals(List.of(2),common.getTransitions().get(0).get("usingSteps"));assertTrue(common.getDiff().stream().anyMatch(d->d.contains("used by steps [2]")));
            assertEquals("UPDATED_INACTIVE",f.apply(common).getStatus());
        }
        for(boolean screen:List.of(false,true))try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.putWorkflow("Existing",ConditionFixtures.workflow(),false);
            f.mutateWorkflowSource("Existing",descriptor->action(descriptor,13).setId(31));f.screen(77,"Form",List.of());
            failedWithoutWrites(f,f.existing("Existing","ACTIVE",List.of(screen?screen(31,"77"):group(31))),"UNSUPPORTED_DESCRIPTOR");
        }
    }
    private static void failedWithoutWritesAfterPreviousApply(WorkflowConfigurationFixture f,ConfigureWorkflowRequest request,String code) {
        int calls=f.nativeCalls.size();ConfigureWorkflowResponse result=f.preview(request);assertEquals(code,result.getErrorCode());assertNull(result.getPlanId());assertEquals(calls,f.nativeCalls.size());
    }
    @Test public void screenChecksAreLimitedToChangedTransitionsAndReportExistingRestrictions() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.putWorkflow("Existing",ValidatorFixtures.commentWorkflow(),false);f.screen(77,"Empty",List.of());
            ConfigureWorkflowResponse preview=f.preview(f.existing("Existing","ACTIVE",List.of(screen(21,"77"))));assertNotNull(preview.getMessage(),preview.getPlanId());
            assertTrue(preview.getWarnings().stream().anyMatch(w->w.contains("assignee") && w.contains("absent")));
            assertEquals(2,((List<?>)preview.getTransitions().get(0).get("validators")).size());
            assertEquals("UPDATED_INACTIVE",f.apply(preview).getStatus());assertTrue(CommentRequiredDescriptor.screenUsable(action(f.main.get("Existing"),21),f.screens));
            assertFalse("Unrelated missing-screen validator is not repaired",CommentRequiredDescriptor.hasScreenView(action(f.main.get("Existing"),13)));
        }
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            f.putWorkflow("Existing",ValidatorFixtures.commentWorkflow(),false);
            failedWithoutWrites(f,f.existing("Existing","ACTIVE",List.of(group(13))),"TRANSITION_SCREEN_MISSING");
        }
    }
    @Test public void screenValidationRejectsAmbiguousNamesUnusedAndDuplicateKeysAndDuplicateFields() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",false);f.screen(71,"Same",List.of());f.screen(72,"Same",List.of());
            failedWithoutWrites(f,f.existing("Existing","ACTIVE",List.of(new TransitionRule(null,11,new ScreenChoice("SET",null,"Same",null),null,null))),"AMBIGUOUS_SCREEN");
            ConfigureWorkflowRequest base=combined(f,"Invalid");
            for(List<NewScreen> screens:List.of(List.of(base.newScreens().get(0),new NewScreen("unused","Unused",null,List.of())),List.of(base.newScreens().get(0),base.newScreens().get(0)),List.of(new NewScreen("form","Form",null,List.of("description","description")))))
                failedWithoutWrites(f,new ConfigureWorkflowRequest(true,base.create(),null,screens,base.transitionRules(),null),"VALIDATION_ERROR");
        }
    }
    @Test public void screenNameOccupancyAndUnrelatedCatalogChangesDoNotInvalidateAPlan() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowRequest request=combined(f,"Independent");WorkflowConfigurationPlan before=plan(f,request);ConfigureWorkflowResponse preview=f.preview(request);
            f.screen(99,"Workflow form",List.of("description"));f.status("999","Unrelated","done");f.orderable("unused","Unused");
            when(f.groups.getUsersInGroupCount(f.groups.getGroup("admins"))).thenReturn(8);
            WorkflowConfigurationPlan after=plan(f,request);assertEquals(before.fingerprint,after.fingerprint);
            assertTrue(after.response.getWarnings().stream().anyMatch(w->w.contains("separate new screen")));
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals(result.getMessage(),"CREATED",result.getStatus());assertEquals(2,f.screenStore.size());
        }
    }
    @Test public void createTemplateIsFingerprintDependencyButItsDraftIsNot() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowRequest request=combined(f,"Template");WorkflowConfigurationPlan original=plan(f,request);
            f.drafts.put(JiraWorkflow.DEFAULT_WORKFLOW_NAME,WorkflowConfigurationFixture.copy(f.main.get(JiraWorkflow.DEFAULT_WORKFLOW_NAME)));
            assertEquals(original.fingerprint,plan(f,request).fingerprint);
            ConfigureWorkflowResponse preview=f.preview(request);f.main.get(JiraWorkflow.DEFAULT_WORKFLOW_NAME).getMetaAttributes().put("external","changed");
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals("PREVIEW_STALE",result.getErrorCode());assertTrue(f.nativeCalls.isEmpty());
        }
    }
    @Test public void draftPreviewNeverMutatesTheNativeDescriptorReturnedByTheManager() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            existingGraph(f,"Existing",true);WorkflowDescriptor nativeDraft=WorkflowConfigurationFixture.copy(f.main.get("Existing"));f.drafts.put("Existing",nativeDraft);
            JiraWorkflow main=mock(JiraWorkflow.class),draft=mock(JiraWorkflow.class);
            when(main.getName()).thenReturn("Existing");when(main.getDescriptor()).thenReturn(f.main.get("Existing"));
            when(draft.getName()).thenReturn("Existing");when(draft.getDescriptor()).thenReturn(nativeDraft);
            String revision=f.revision("Existing"),before=DescriptorFacts.definition(nativeDraft);
            when(f.inspector.snapshot("Existing")).thenReturn(new WorkflowInspector.Snapshot("Existing",main,draft,true,false,revision));
            ConfigureWorkflowResponse result=f.preview(f.existing("Existing","DRAFT",List.of(fields(11,"description"))));
            assertEquals(result.getMessage(),"WOULD_CHANGE",result.getStatus());assertNotNull(result.getPlanId());
            assertEquals(before,DescriptorFacts.definition(nativeDraft));assertTrue(f.nativeCalls.isEmpty());
        }
    }
    @Test @SuppressWarnings("unchecked") public void rulesPreserveConditionalResultsJoinsAndSplitsWithoutTreatingThemAsStructuralEdits() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            WorkflowDescriptor descriptor=ConditionFixtures.workflow();
            com.opensymphony.workflow.loader.DescriptorFactory factory=com.opensymphony.workflow.loader.DescriptorFactory.getFactory();
            com.opensymphony.workflow.loader.ConditionalResultDescriptor conditional=factory.createConditionalResultDescriptor();
            conditional.setStep(3);conditional.setOldStatus("Not Done");conditional.setStatus("Done");conditional.setParent(action(descriptor,10));
            ConditionsDescriptor conditions=factory.createConditionsDescriptor();conditions.setType("AND");conditions.setParent(conditional);
            com.opensymphony.workflow.loader.ConditionDescriptor permission=factory.createConditionDescriptor();permission.setType("class");permission.setParent(conditions);
            permission.getArgs().put("class.name","com.atlassian.jira.workflow.condition.PermissionCondition");permission.getArgs().put("permission","RESOLVE_ISSUES");
            conditions.getConditions().add(permission);conditional.getConditions().add(conditions);action(descriptor,10).getConditionalResults().add(conditional);
            com.opensymphony.workflow.loader.SplitDescriptor split=factory.createSplitDescriptor();split.setId(5);split.setParent(descriptor);
            com.opensymphony.workflow.loader.ResultDescriptor branch=factory.createResultDescriptor();branch.setStep(1);branch.setOldStatus("Not Done");branch.setStatus("Done");branch.setParent(split);split.getResults().add(branch);descriptor.getSplits().add(split);
            com.opensymphony.workflow.loader.ResultDescriptor intoSplit=factory.createResultDescriptor();intoSplit.setSplit(5);intoSplit.setOldStatus("Not Done");intoSplit.setParent(action(descriptor,13));action(descriptor,13).setUnconditionalResult(intoSplit);
            com.opensymphony.workflow.loader.JoinDescriptor join=factory.createJoinDescriptor();join.setId(6);join.setParent(descriptor);
            ConditionsDescriptor joinConditions=factory.createConditionsDescriptor();joinConditions.setType("AND");joinConditions.setParent(join);
            com.opensymphony.workflow.loader.ConditionDescriptor joinPermission=factory.createConditionDescriptor();joinPermission.setType("class");joinPermission.setParent(joinConditions);
            joinPermission.getArgs().put("class.name","com.atlassian.jira.workflow.condition.PermissionCondition");joinPermission.getArgs().put("permission","RESOLVE_ISSUES");
            joinConditions.getConditions().add(joinPermission);join.getConditions().add(joinConditions);
            com.opensymphony.workflow.loader.ResultDescriptor joined=factory.createResultDescriptor();joined.setStep(1);joined.setOldStatus("Not Done");joined.setStatus("Done");joined.setParent(join);join.setResult(joined);descriptor.getJoins().add(join);
            com.opensymphony.workflow.loader.ResultDescriptor intoJoin=factory.createResultDescriptor();intoJoin.setJoin(6);intoJoin.setOldStatus("Not Done");intoJoin.setParent(action(descriptor,12));action(descriptor,12).setUnconditionalResult(intoJoin);
            f.putWorkflow("Existing",descriptor,false);
            String conditionalBefore=DescriptorFacts.xml(DescriptorFacts.conditionalResults(action(f.main.get("Existing"),10)).get(0));
            String joinBefore=DescriptorFacts.xml((com.opensymphony.workflow.loader.JoinDescriptor)f.main.get("Existing").getJoins().get(0));
            String splitBefore=DescriptorFacts.xml((com.opensymphony.workflow.loader.SplitDescriptor)f.main.get("Existing").getSplits().get(0));
            ConfigureWorkflowResponse preview=f.preview(f.existing("Existing","ACTIVE",List.of(group(10),group(12),group(13))));assertNotNull(preview.getMessage(),preview.getPlanId());
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals(result.getMessage(),"UPDATED_INACTIVE",result.getStatus());
            WorkflowDescriptor saved=f.main.get("Existing");
            assertEquals(conditionalBefore,DescriptorFacts.xml(DescriptorFacts.conditionalResults(action(saved,10)).get(0)));
            assertEquals(joinBefore,DescriptorFacts.xml((com.opensymphony.workflow.loader.JoinDescriptor)saved.getJoins().get(0)));
            assertEquals(splitBefore,DescriptorFacts.xml((com.opensymphony.workflow.loader.SplitDescriptor)saved.getSplits().get(0)));
            assertEquals(6,action(saved,12).getUnconditionalResult().getJoin());assertEquals(5,action(saved,13).getUnconditionalResult().getSplit());
            for(int id:List.of(10,12,13))assertEquals(ConditionDescriptors.Outcome.UNCHANGED,ConditionDescriptors.check(action(saved,id),ConditionDescriptors.Candidate.group("admins")).outcome());
        }
    }
    @Test public void equivalentRequestedRulesNormalizeOnceAndRemainUnchangedAfterNativeSave() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            ConfigureWorkflowRequest base=combined(f,"Idempotent");TransitionRule original=base.transitionRules().get(0);
            List<Validator> validators=new ArrayList<>(original.addValidators());validators.addAll(original.addValidators());
            List<Condition> conditions=new ArrayList<>(original.addConditions().rules());conditions.addAll(original.addConditions().rules());
            TransitionRule repeated=new TransitionRule("start",null,original.screen(),validators,new Conditions("AND",conditions));
            ConfigureWorkflowResponse saved=f.apply(f.preview(new ConfigureWorkflowRequest(true,base.create(),null,base.newScreens(),List.of(repeated),null)));
            assertEquals(saved.getMessage(),"CREATED",saved.getStatus());ActionDescriptor action=action(f.main.get("Idempotent"),11);
            assertEquals(2,RequiredFieldsDescriptor.scan(action).size());assertEquals(2,action.getRestriction().getConditionsDescriptor().getConditions().size());
            String before=DescriptorFacts.definition(f.main.get("Idempotent"));int calls=f.nativeCalls.size();
            ConfigureWorkflowResponse unchanged=f.preview(f.existing("Idempotent","ACTIVE",List.of(new TransitionRule(null,11,null,validators,new Conditions("AND",conditions)))));
            assertEquals(unchanged.getMessage(),"UNCHANGED",unchanged.getStatus());assertNull(unchanged.getPlanId());assertEquals(calls,f.nativeCalls.size());assertEquals(before,DescriptorFacts.definition(f.main.get("Idempotent")));
            TransitionRule inverted=new TransitionRule(null,11,null,null,new Conditions("AND",List.of(new Condition("project_role",new Parameters(null,null,List.of("10000"),true)))));
            String oldCondition=DescriptorFacts.xml((ConditionsDescriptor)action.getRestriction().getConditionsDescriptor());
            ConfigureWorkflowResponse changed=f.apply(f.preview(f.existing("Idempotent","ACTIVE",List.of(inverted))));assertEquals(changed.getMessage(),"UPDATED_INACTIVE",changed.getStatus());
            ConditionsDescriptor root=action(f.main.get("Idempotent"),11).getRestriction().getConditionsDescriptor();assertEquals(3,root.getConditions().size());
            root.getConditions().remove(2);assertEquals(oldCondition,DescriptorFacts.xml(root));
        }
    }
    @Test public void ownDuplicateScreenDoesNotInvalidateAnExistingScreenNameLookupOrModifyTheExistingScreen() {
        try(WorkflowConfigurationFixture f=new WorkflowConfigurationFixture()) {
            WorkflowDefinition graph=new WorkflowDefinition("Existing","",f.createGraph("Existing").getStatuses(),"Open",List.of(
                    new TransitionDefinition("start","Start","Open",false,"In Progress",null,null),
                    new TransitionDefinition("back","Back",null,true,"Open",null,null)));
            WorkflowConfigurationPlan source=plan(f,new ConfigureWorkflowRequest(true,graph,null,null,null,null));assertNotNull(source.response.getMessage(),source.fingerprint);f.putWorkflow("Existing",source.descriptor,false);
            f.screen(77,"Form",List.of("description"));Object before=WorkflowConfigurationPlanner.screenFacts(f.screenStore.get(77L));
            ConfigureWorkflowRequest existing=f.existing("Existing","ACTIVE",List.of(
                    new TransitionRule(null,11,new ScreenChoice("SET",null,"Form",null),null,null),
                    new TransitionRule(null,21,new ScreenChoice("SET",null,null,"new"),null,null)));
            ConfigureWorkflowResponse preview=f.preview(new ConfigureWorkflowRequest(true,null,existing.existing(),List.of(new NewScreen("new","Form",null,List.of("assignee"))),existing.transitionRules(),null));
            assertNotNull(preview.getMessage(),preview.getPlanId());assertTrue(preview.getWarnings().stream().anyMatch(w->w.contains("separate new screen")));
            ConfigureWorkflowResponse result=f.apply(preview);assertEquals(result.getMessage(),"UPDATED_INACTIVE",result.getStatus());assertEquals(2,f.screenStore.size());
            assertEquals(before,WorkflowConfigurationPlanner.screenFacts(f.screenStore.get(77L)));assertEquals("77",DescriptorFacts.meta(action(f.main.get("Existing"),11)).get(DescriptorFacts.SCREEN_META));
            assertFalse("77".equals(DescriptorFacts.meta(action(f.main.get("Existing"),21)).get(DescriptorFacts.SCREEN_META)));
        }
    }
}
