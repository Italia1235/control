package ru.rasulov.workflow.impl.edit;

import org.junit.Test;
import java.util.*;
import ru.rasulov.workflow.api.dto.configure.*;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.*;
import ru.rasulov.workflow.api.dto.edit.*;
import ru.rasulov.workflow.api.dto.TransitionResolution;
import static org.junit.Assert.*;
import static ru.rasulov.workflow.impl.edit.EditFixtures.*;

/** Exercises detached patching followed by rules, native persistence and durable receipts. */
public class WorkflowConfigureCombinedTest {
    private WorkflowChange added(String key, ScreenPatch screen) {
        return new WorkflowChange("ADD_TRANSITION",null,null,null,null,"Return",null,from(2),1,null,screen,new TransitionResolution("CLEAR",null),key, null);
    }
    private ConfigureWorkflowRequest request(WorkflowConfigurationFixture w,List<WorkflowChange> changes,List<NewScreen> screens,List<TransitionRule> rules) {
        return new ConfigureWorkflowRequest(true,null,new Existing("Support","ACTIVE",w.revision("Support"),changes),screens,rules,null);
    }
    private WorkflowConfigurationFixture world() {
        WorkflowConfigurationFixture w=new WorkflowConfigurationFixture();
        w.status("3","In Progress","indeterminate");w.status("5","Resolved","done");w.status("6","Closed","done");
        w.screen(7,"Close",List.of("description"));w.screen(9,"Finish",List.of("description"));
        w.putWorkflow("Support",workflow(),false);return w;
    }
    private TransitionRule rule(String key,Integer id,ScreenChoice screen,List<Validator> validators) {
        return new TransitionRule(key,id,screen,validators,null);
    }
    @Test public void clearScreenThroughRulesAndChangesHasTheSameEffectAndTruthfulPreview() {
        for(String view:List.of("fieldscreen","other-view")) {
            String[] saved=new String[2];
            for(int path=0;path<2;path++) {
                try(WorkflowConfigurationFixture w=world()) {
                    var action=DescriptorFacts.locate(w.main.get("Support")).get(41).action();
                    DescriptorFacts.meta(action).put(DescriptorFacts.SCREEN_META,"7");
                    action.setView(view);
                    ConfigureWorkflowResponse preview=w.preview(request(w,
                            path==0?List.of(screen(41,"CLEAR",null)):null,null,
                            path==1?List.of(rule(null,41,new ScreenChoice("CLEAR",null,null,null),null)):null));
                    assertEquals(preview.getMessage(),"WOULD_CHANGE",preview.getStatus());
                    assertTrue(w.nativeCalls.isEmpty());
                    String diff=preview.getDiff().toString();
                    assertTrue(diff,diff.contains("7 -> none"));
                    assertEquals(diff,"fieldscreen".equals(view),diff.contains("view fieldscreen removed"));
                    assertFalse(diff,diff.contains("-> fieldscreen"));
                    ConfigureWorkflowResponse done=w.apply(preview);
                    assertEquals(done.getMessage(),"UPDATED_INACTIVE",done.getStatus());
                    var persisted=DescriptorFacts.locate(w.main.get("Support")).get(41).action();
                    assertNull(DescriptorFacts.meta(persisted).get(DescriptorFacts.SCREEN_META));
                    if("fieldscreen".equals(view)) assertTrue(persisted.getView()==null || persisted.getView().isEmpty());
                    else assertEquals(view,persisted.getView());
                    saved[path]=DescriptorFacts.xml(persisted);
                    assertEquals(List.of("workflow-save"),w.nativeCalls);
                }
            }
            assertEquals(saved[0],saved[1]);
        }
    }
    @Test public void screenSetAndLegacyRepairThroughBothPathsPreserveTheRestOfTheDescriptor() {
        for(String mode:List.of("no-screen","same-id","other-view","existing-comment")) {
            String[] saved=new String[2];
            for(int path=0;path<2;path++)try(WorkflowConfigurationFixture w=world()) {
                var action=DescriptorFacts.locate(w.main.get("Support")).get(11).action();
                if(!"no-screen".equals(mode)) DescriptorFacts.meta(action).put(DescriptorFacts.SCREEN_META,"7");
                action.setView("other-view".equals(mode)?"other-view":null);
                if("existing-comment".equals(mode)) CommentRequiredDescriptor.apply(action,"preserved-id");
                String before=DescriptorFacts.xml(action);
                String neighbor=DescriptorFacts.xml(DescriptorFacts.locate(w.main.get("Support")).get(13).action());
                var validators=List.of(new Validator("comment_required",null));
                ConfigureWorkflowRequest input=request(w,
                        path==0?List.of(screen(11,"SET",7L)):null,null,
                        List.of(rule(null,11,path==1?new ScreenChoice("SET","7",null,null):null,validators)));
                ConfigureWorkflowResponse preview=w.preview(input);
                assertEquals(preview.getMessage(),"WOULD_CHANGE",preview.getStatus());
                assertTrue(w.nativeCalls.isEmpty());
                assertEquals(before,DescriptorFacts.xml(DescriptorFacts.locate(w.main.get("Support")).get(11).action()));
                assertEquals("UPDATED_INACTIVE",w.apply(preview).getStatus());
                var persisted=DescriptorFacts.locate(w.main.get("Support")).get(11).action();
                assertEquals("7",DescriptorFacts.meta(persisted).get(DescriptorFacts.SCREEN_META));
                assertEquals("fieldscreen",persisted.getView());
                assertEquals(1,RequiredFieldsDescriptor.scan(persisted).stream().filter(e->e.commentRequired()).count());
                if("existing-comment".equals(mode)) assertTrue(DescriptorFacts.xml(persisted).contains("preserved-id"));
                assertEquals(neighbor,DescriptorFacts.xml(DescriptorFacts.locate(w.main.get("Support")).get(13).action()));
                assertEquals(List.of("workflow-save"),w.nativeCalls);
                ConfigureWorkflowResponse repeated=w.preview(request(w,
                        path==0?List.of(screen(11,"SET",7L)):null,null,
                        List.of(rule(null,11,path==1?new ScreenChoice("SET","7",null,null):null,validators))));
                assertEquals(repeated.getMessage(),"UNCHANGED",repeated.getStatus());
                assertNull(repeated.getPlanId());
                saved[path]=DescriptorFacts.xml(persisted);
            }
            assertEquals(saved[0],saved[1]);
        }
    }
    @Test public void newTransitionScreenAndRulesAreSavedInOnePlanWithActualMappings() {
        try(WorkflowConfigurationFixture w=world()) {
            String before=DescriptorFacts.xml(DescriptorFacts.locate(w.main.get("Support")).get(13).action());
            ConfigureWorkflowResponse preview=w.preview(request(w,List.of(added("return",null),rename(11,"Begin")),
                    List.of(new NewScreen("return","Return form",null,List.of("assignee"))),
                    List.of(new TransitionRule("return",null,new ScreenChoice("SET",null,null,"return"),
                            List.of(new Validator("required_fields",new Parameters(List.of("assignee"),null,null,null))),
                            new Conditions("AND",List.of(new Condition("group",new Parameters(null,"admins",null,null))))))));
            assertEquals(preview.getMessage(),"WOULD_CHANGE",preview.getStatus());assertNotNull(preview.getPlanId());assertTrue(w.nativeCalls.isEmpty());
            ConfigureWorkflowResponse done=w.apply(preview);assertEquals(done.getMessage(),"UPDATED_INACTIVE",done.getStatus());
            Map<String,Object> transition=done.getTransitions().stream().filter(t->"return".equals(t.get("transitionKey"))).findFirst().orElseThrow();
            int id=((Number)transition.get("actionId")).intValue();
            var saved=DescriptorFacts.locate(w.main.get("Support")).get(id).action();
            assertEquals("Return",saved.getName());assertTrue(RequiredFieldsDescriptor.scan(saved).stream().anyMatch(e->e.fieldIds().contains("assignee")));
            assertNotNull(ConditionDescriptors.read(saved,x->null));
            String screenId=DescriptorFacts.meta(saved).get(DescriptorFacts.SCREEN_META);assertNotNull(w.screenStore.get(Long.valueOf(screenId)));
            assertEquals(screenId,transition.get("screenId"));
            assertEquals(Long.valueOf(screenId),done.getObjects().stream().filter(o->"SCREEN".equals(o.get("kind"))).findFirst().orElseThrow().get("screenId"));
            assertEquals(before,DescriptorFacts.xml(DescriptorFacts.locate(w.main.get("Support")).get(13).action()));
            assertEquals(1,Collections.frequency(w.nativeCalls,"workflow-save"));
        }
    }
    @Test public void localReferencesRejectDuplicateUnknownNumericAndConflictingTargetsWithoutWrites() {
        try(WorkflowConfigurationFixture w=world()) {
            List<List<TransitionRule>> invalid=List.of(
                List.of(rule("absent",null,null,null)),List.of(rule("return",11,null,null)),
                List.of(rule(null,null,null,null)),List.of(rule(null,999,null,null)),
                List.of(rule("return",null,null,null),rule("return",null,null,null)),
                List.of(rule("return",null,new ScreenChoice("CLEAR",null,null,null),null)));
            for(List<TransitionRule> rules:invalid) {
                ConfigureWorkflowResponse p=w.preview(request(w,List.of(added("return",new ScreenPatch("CLEAR",null))),null,rules));
                assertEquals(p.getMessage(),"FAILED",p.getStatus());assertNull(p.getPlanId());assertTrue(w.nativeCalls.isEmpty());
            }
            ConfigureWorkflowResponse duplicate=w.preview(request(w,List.of(added("return",null),added("return",null)),null,null));
            assertEquals("FAILED",duplicate.getStatus());assertNull(duplicate.getPlanId());
        }
    }
    @Test public void finalValidationRepairsStructuralScreenAndRejectsNoOpRuleWithoutScreen() {
        try(WorkflowConfigurationFixture w=world()) {
            CommentRequiredDescriptor.apply(DescriptorFacts.locate(w.main.get("Support")).get(11).action(),"123");
            ConfigureWorkflowResponse missing=w.preview(request(w,null,null,List.of(rule(null,11,null,List.of(new Validator("comment_required",null))))));
            assertEquals("TRANSITION_SCREEN_MISSING",missing.getErrorCode());assertNull(missing.getPlanId());
            ConfigureWorkflowResponse repaired=w.preview(request(w,List.of(rename(11,"Begin")),
                List.of(new NewScreen("repair","Repair",null,List.of("comment"))),
                List.of(rule(null,11,new ScreenChoice("SET",null,null,"repair"),List.of(new Validator("comment_required",null))))));
            assertEquals(repaired.getMessage(),"WOULD_CHANGE",repaired.getStatus());
        }
    }
    @Test public void existingCommentValidatorsAreCheckedAfterBothScreenPathsEvenForNoOpSets() {
        for(String path:List.of("structural-clear","rule-clear","structural-noop","rule-noop","repair")) {
            try(WorkflowConfigurationFixture w=world()) {
                var action=DescriptorFacts.locate(w.main.get("Support")).get(11).action();
                CommentRequiredDescriptor.apply(action,"123");
                if(path.endsWith("noop")) {
                    DescriptorFacts.meta(action).put(DescriptorFacts.SCREEN_META,"7");
                    action.setView("other-view");
                } else if(path.endsWith("clear")) {
                    DescriptorFacts.meta(action).put(DescriptorFacts.SCREEN_META,"7");
                    action.setView(DescriptorFacts.SCREEN_VIEW);
                }
                List<WorkflowChange> changes=path.startsWith("structural")
                        ?List.of(screen(11,path.endsWith("clear")?"CLEAR":"SET",path.endsWith("clear")?null:7L)):List.of(rename(11,"Begin"));
                List<TransitionRule> rules=path.startsWith("rule")
                        ?List.of(rule(null,11,new ScreenChoice(path.endsWith("clear")?"CLEAR":"SET",path.endsWith("clear")?null:"7",null,null),null))
                        :path.equals("repair")?List.of(rule(null,11,new ScreenChoice("SET","7",null,null),null)):null;
                ConfigureWorkflowResponse result=w.preview(request(w,changes,null,rules));
                // Same screen metadata is not enough: SET must also repair the final view.
                if(path.endsWith("clear")) {
                    assertEquals(path+": "+result.getMessage(),"TRANSITION_SCREEN_MISSING",result.getErrorCode());
                    assertNull(result.getPlanId());
                } else {
                    assertEquals(path+": "+result.getMessage(),"WOULD_CHANGE",result.getStatus());
                    assertEquals("UPDATED_INACTIVE",w.apply(result).getStatus());
                    assertTrue(CommentRequiredDescriptor.screenUsable(DescriptorFacts.locate(w.main.get("Support")).get(11).action(),w.screens));
                }
            }
        }
    }
    @Test public void noOpScreenSetAndRepeatedRuleRequestsStillEnterFinalValidatorChecks() {
        for(boolean structural:List.of(false,true))try(WorkflowConfigurationFixture w=world()) {
            var action=DescriptorFacts.locate(w.main.get("Support")).get(11).action();
            CommentRequiredDescriptor.apply(action,"123");
            DescriptorFacts.meta(action).put(DescriptorFacts.SCREEN_META,"7");action.setView(DescriptorFacts.SCREEN_VIEW);
            // An explicit same-screen SET enters final validation even without descriptor changes.
            ConfigureWorkflowResponse okay=w.preview(request(w,structural?List.of(screen(11,"SET",7L)):null,null,
                    structural?null:List.of(rule(null,11,new ScreenChoice("SET","7",null,null),null))));
            assertEquals(okay.getMessage(),"UNCHANGED",okay.getStatus());
            action.setView("missing-view");
            ConfigureWorkflowResponse repeated=w.preview(request(w,null,null,
                    List.of(rule(null,11,null,List.of(new Validator("comment_required",null))))));
            assertEquals("TRANSITION_SCREEN_MISSING",repeated.getErrorCode());assertNull(repeated.getPlanId());
            assertTrue(w.nativeCalls.isEmpty());
        }
    }
    @Test public void unrelatedOldDefectsAndRenameOnlyStayOutsideFinalScreenValidation() {
        try(WorkflowConfigurationFixture w=world()) {
            CommentRequiredDescriptor.apply(DescriptorFacts.locate(w.main.get("Support")).get(11).action(),"123");
            for(List<WorkflowChange> changes:List.of(List.of(rename(13,"Quick")),List.of(rename(11,"Begin")),List.of(description("Revised")))) {
                ConfigureWorkflowResponse result=w.preview(request(w,changes,null,null));
                assertEquals(result.getMessage(),"WOULD_CHANGE",result.getStatus());assertNotNull(result.getPlanId());
            }
            ConfigureWorkflowResponse removed=w.preview(request(w,List.of(removeTransition(11)),null,null));
            assertEquals(removed.getMessage(),"WOULD_CHANGE",removed.getStatus());assertTrue(w.nativeCalls.isEmpty());
        }
    }
    @Test public void allExistingRequiredAndUnknownValidatorsArePreservedAndWarnedOnBothScreenPaths() {
        for(boolean structural:List.of(false,true))try(WorkflowConfigurationFixture w=world()) {
            var action=DescriptorFacts.locate(w.main.get("Support")).get(11).action();
            RequiredFieldsDescriptor.apply(action,RequiredFieldsDescriptor.plan(action,List.of("assignee")),"fields");
            ValidatorFixtures.validators(action).add(ValidatorFixtures.validator(ValidatorFixtures.JIRA_PERMISSION));
            String before=DescriptorFacts.xml(action);
            ConfigureWorkflowResponse result=w.preview(request(w,structural?List.of(screen(11,"SET",7L)):null,null,
                    structural?null:List.of(rule(null,11,new ScreenChoice("SET","7",null,null),null))));
            assertEquals(result.getMessage(),"WOULD_CHANGE",result.getStatus());
            assertTrue(result.getWarnings().toString(),result.getWarnings().stream().anyMatch(x->x.contains("assignee") && x.contains("absent")));
            assertTrue(result.getWarnings().toString(),result.getWarnings().stream().anyMatch(x->x.contains("unrecognized") && x.contains("not analyzed")));
            assertEquals(before,DescriptorFacts.xml(action));
            assertEquals("UPDATED_INACTIVE",w.apply(result).getStatus());
            assertEquals(2,RequiredFieldsDescriptor.scan(DescriptorFacts.locate(w.main.get("Support")).get(11).action()).size());
        }
    }
    @Test public void externalDependencyChangingAfterScreenCreationStopsBeforeAnyWorkflowSaveWithKnownReceiptId() {
        try(WorkflowConfigurationFixture w=world()) {
            String before=DescriptorFacts.definition(w.main.get("Support"));
            ConfigureWorkflowResponse preview=w.preview(request(w,List.of(added("return",null)),
                    List.of(new NewScreen("form","Return form",null,List.of("assignee"))),
                    List.of(rule("return",null,new ScreenChoice("SET",null,null,"form"),null))));
            assertNotNull(preview.getMessage(),preview.getPlanId());
            w.afterNative=key->{if("screen".equals(key))w.orderable("assignee","Externally renamed");};
            ConfigureWorkflowResponse result=w.apply(preview);
            assertEquals(result.getMessage(),"PARTIAL",result.getStatus());assertEquals("PREVIEW_STALE",result.getErrorCode());
            assertEquals("APPLIED",WorkflowConfigurationNativeTest.step(result,"screen:0").get("writeStatus"));
            assertNotNull(WorkflowConfigurationNativeTest.step(result,"screen:0").get("id"));
            assertEquals("NOT_ATTEMPTED",WorkflowConfigurationNativeTest.step(result,"tab:0").get("writeStatus"));
            assertEquals("NOT_ATTEMPTED",WorkflowConfigurationNativeTest.step(result,"workflow-save").get("writeStatus"));
            assertTrue(result.getTransitions().isEmpty());assertTrue(result.getDiff().isEmpty());
            assertEquals(before,DescriptorFacts.definition(w.main.get("Support")));
            assertEquals(List.of("screen"),w.nativeCalls);assertEquals(1,w.screenStore.size()-2);
        }
    }
    @Test public void stepTransitionAndScreenKeysUseIndependentNamespacesAndReturnPersistedIds() {
        try(WorkflowConfigurationFixture w=world()) {
            w.status("10001","Review","indeterminate");
            WorkflowChange transition=new WorkflowChange("ADD_TRANSITION",null,null,null,null,"Review",null,from(2),null,"same",null,new TransitionResolution("PRESERVE",null),"same", null);
            ConfigureWorkflowResponse preview=w.preview(request(w,List.of(addStatus("10001","same"),transition),
                    List.of(new NewScreen("same","Review form",null,List.of())),
                    List.of(rule("same",null,new ScreenChoice("SET",null,null,"same"),null))));
            assertEquals(preview.getMessage(),"WOULD_CHANGE",preview.getStatus());
            ConfigureWorkflowResponse result=w.apply(preview);
            assertEquals(result.getMessage(),"UPDATED_INACTIVE",result.getStatus());
            Map<String,Object> step=result.getObjects().stream().filter(x->"STEP".equals(x.get("kind"))).findFirst().orElseThrow();
            assertEquals("same",step.get("clientKey"));
            int stepId=((Number)step.get("stepId")).intValue();
            assertEquals("10001",DescriptorFacts.meta(DescriptorFacts.step(w.main.get("Support"),stepId)).get(DescriptorFacts.STATUS_META));
            Map<String,Object> saved=result.getTransitions().stream().filter(x->"same".equals(x.get("transitionKey"))).findFirst().orElseThrow();
            assertEquals(List.of(stepId),DescriptorFacts.targets(DescriptorFacts.locate(w.main.get("Support")).get(((Number)saved.get("actionId")).intValue()).action()));
            Map<String,Object> screen=result.getObjects().stream().filter(x->"SCREEN".equals(x.get("kind"))).findFirst().orElseThrow();
            assertEquals("same",screen.get("key"));assertEquals(screen.get("screenId").toString(),saved.get("screenId"));
        }
    }
}
