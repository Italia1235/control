package ru.rasulov.workflow.impl.edit;

import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.JournalCancelledException;
import ru.rasulov.workflow.api.dto.StatusCategoryKey;
import ru.rasulov.workflow.api.dto.WorkflowStatusView;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.NewScreen;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowStatus;
import ru.rasulov.workflow.impl.StatusCatalog;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;
import java.util.function.Function;
import java.util.function.Supplier;
import static ru.rasulov.workflow.impl.edit.WorkflowConfigurationPlan.map;

/** Native-only execution. Every call has a durable intent, confirmation, and separate read-back. Never compensates. */
@Named
public final class WorkflowConfigurationWriter {
    private final WorkflowManager workflows;
    private final WorkflowInspector inspector;
    private final StatusService statusService;
    private final StatusCatalog statuses;
    private final FieldScreenFactory screenFactory;
    private final FieldScreenManager screens;
    private final OfBizDelegator delegator;
    private final ChangeJournal journal;
    @Inject
    public WorkflowConfigurationWriter(@ComponentImport WorkflowManager workflows, WorkflowInspector inspector,
            @ComponentImport StatusService statusService, StatusCatalog statuses,
            @ComponentImport FieldScreenFactory screenFactory, @ComponentImport FieldScreenManager screens,
            @ComponentImport OfBizDelegator delegator, ChangeJournal journal) {
        this.workflows=workflows;this.inspector=inspector;this.statusService=statusService;this.statuses=statuses;
        this.screenFactory=screenFactory;this.screens=screens;this.delegator=delegator;this.journal=journal;
    }
    ConfigureWorkflowResponse write(WorkflowConfigurationPlan p,ApplicationUser user) {
        journal.startWorkflowProgress(p.execution());
        Map<String,String> statusIds=new LinkedHashMap<>();
        try {
            for(WorkflowConfigurationPlan.StatusIntent intent:p.statusIntents) {
                WorkflowStatus status=intent.status;String key=intent.receiptKey;
                if(!status.isNew()) {statusIds.put(key,status.getExisting().getId());continue;}
                Status created=call(p,key,()-> {
                    ServiceOutcome<Status> outcome=statusService.createStatus(user,status.getName(),status.getDescription(),
                            "/images/icons/status_generic.gif",statuses.categoryOf(StatusCategoryKey.valueOf(status.getCategory())));
                    if(!outcome.isValid()) throw new NoWrite("Jira rejected status creation: "+StatusCatalog.reasons(outcome.getErrorCollection()));
                    return outcome.getReturnedValue();
                },s->s==null?null:s.getId());
                if(created==null) unavailable(p,key,"Status creation returned no ID.");
                String id=step(p,key).get("id").toString();
                Object expected=map("id",id,"name",status.getName(),"description",WorkflowConfigurationPlanner.normalizedDescription(status.getDescription()),"category",status.getCategory());
                verify(p,key,expected,()->WorkflowConfigurationPlanner.statusFacts(statusById(id)));
                p.readers.remove("status-create:"+key);
                p.expect(intent.dependency,p.graph==null?List.of(expected):expected);
                statusIds.put(key,id);
                DescriptorFacts.meta(DescriptorFacts.step(p.descriptor,intent.stepId)).put(DescriptorFacts.STATUS_META,id);
            }
            Map<String,Long> screenIds=new LinkedHashMap<>();
            int ordinal=-1;
            for(Map.Entry<String,NewScreen> entry:p.screens.entrySet()) {
                ordinal++;String key=entry.getKey();NewScreen spec=entry.getValue();String receipt=Integer.toString(ordinal);
                FieldScreen screen=screenFactory.createScreen();screen.setName(spec.name().trim());screen.setDescription(spec.description());
                call(p,"screen:"+receipt,()->{screens.createFieldScreen(screen);return screen;},s->s.getId()==null?null:s.getId().toString());
                if(screen.getId()==null) unavailable(p,"screen:"+receipt,"Screen creation returned no ID.");
                Long id=screen.getId();screenIds.put(key,id);
                List<Object> expectedTabs=new ArrayList<>();
                Object expected=screenState(id,spec,expectedTabs);
                verify(p,"screen:"+receipt,expected,()->WorkflowConfigurationPlanner.screenFacts(screens.getFieldScreen(id)));
                ownScreen(p,id,expected);ownScreenLookups(p,id,spec.name().trim());
                FieldScreenTab tab=call(p,"tab:"+receipt,()->screen.addTab("Field Tab"),t->t==null || t.getId()==null?null:t.getId().toString());
                if(tab==null || tab.getId()==null) unavailable(p,"tab:"+receipt,"Screen tab creation returned no ID.");
                List<String> expectedFields=new ArrayList<>();
                expectedTabs.add(map("id",tab.getId(),"name","Field Tab","fields",List.copyOf(expectedFields)));
                expected=screenState(id,spec,expectedTabs);
                verifyScreen(p,"tab:"+receipt,id,expected);p.expect("owned-screen:"+id,expected);
                for(int i=0;i<spec.fieldIds().size();i++) {
                    String field=spec.fieldIds().get(i);
                    call(p,"field:"+receipt+":"+i,()->{tab.addFieldScreenLayoutItem(field);return id;},Object::toString);
                    expectedFields.add(field);expectedTabs.set(0,map("id",tab.getId(),"name","Field Tab","fields",List.copyOf(expectedFields)));
                    expected=screenState(id,spec,expectedTabs);verifyScreen(p,"field:"+receipt+":"+i,id,expected);p.expect("owned-screen:"+id,expected);
                }
            }
            for(Map.Entry<Integer,String> link:p.screenKeys.entrySet()) DescriptorFacts.meta(DescriptorFacts.locate(p.descriptor).get(link.getKey()).action()).put(DescriptorFacts.SCREEN_META,screenIds.get(link.getValue()).toString());
            p.after=DescriptorFacts.definition(p.descriptor);
            JiraWorkflow target;
            if(p.graph!=null) {
                JiraWorkflow template=workflows.getWorkflow(JiraWorkflow.DEFAULT_WORKFLOW_NAME);
                if(template==null) throw new WorkflowConfigurationPlan.StateChanged("The built-in template workflow is unavailable before copy.");
                WorkflowDescriptor copied=copyDescriptor(template.getDescriptor());
                copied.getMetaAttributes().put(DescriptorFacts.DESCRIPTION_META,p.graph.getDescription());
                String templateDefinition=DescriptorFacts.definition(copied);
                target=call(p,"workflow-copy",()->workflows.copyWorkflow(user,p.name,p.graph.getDescription(),template),this::workflowId);
                if(target==null) unavailable(p,"workflow-copy","Workflow copy returned no object; no object is recovered by name.");
                verify(p,"workflow-copy",templateDefinition,()->definition(workflows.getWorkflow(p.name)));
                p.expect("workflow",map("name",target.getName(),"definition",templateDefinition));
            } else if(p.target.mode==EditTarget.Mode.DRAFT_CREATE) {
                String originalMain=DescriptorFacts.definition(p.target.snapshot.main().getDescriptor());
                target=call(p,"draft",()->workflows.createDraftWorkflow(user,p.name),this::workflowId);
                if(target==null) unavailable(p,"draft","Draft creation returned no object.");
                verify(p,"draft",p.before,()->definition(workflows.getDraftWorkflow(p.name)));
                if(!originalMain.equals(definition(workflows.getWorkflow(p.name)))) throw new WorkflowConfigurationPlan.StateChanged("Active main changed while this operation created a draft. The draft exists; rules were not saved.");
                WorkflowInspector.Snapshot now=inspector.snapshot(p.name);
                if(now==null || now.draft()==null || !p.before.equals(definition(now.draft()))) throw new WorkflowConfigurationPlan.StateChanged("The created draft no longer has the expected source definition.");
                p.expect("workflow",now.revision());
            } else target=p.target.base;
            p.check();
            call(p,"workflow-save",()->{replaceContent(target.getDescriptor(),p.descriptor);workflows.updateWorkflow(user,target);return target;},this::workflowId);
            verify(p,"workflow-save",p.after,()->definition(p.graph!=null || p.target.mode==EditTarget.Mode.INACTIVE_SAVE?workflows.getWorkflow(p.name):workflows.getDraftWorkflow(p.name)));
            journal.workflowProgress(p.execution(),true);
            ConfigureWorkflowResponse done=p.response.result(p.graph!=null?"CREATED":p.target.mode==EditTarget.Mode.INACTIVE_SAVE?"UPDATED_INACTIVE":"DRAFT_SAVED",null,
                    "Every native step was confirmed and read back as planned. No publication, assignment, retry or rollback was performed.",p.execution());
            List<WorkflowStatusView> views=done.getStatuses();
            if(!p.statusIntents.isEmpty()) {
                views=new ArrayList<>();for(WorkflowConfigurationPlan.StatusIntent intent:p.statusIntents) {
                    WorkflowStatus status=intent.status;
                    views.add(new WorkflowStatusView(status.getName(),statusIds.get(intent.receiptKey),status.getCategory(),status.isNew()));
                }
            }
            List<Map<String,Object>> transitions=new ArrayList<>();
            Map<Integer,DescriptorFacts.Located> saved=DescriptorFacts.locate(p.descriptor);
            for(Map<String,Object> preview:done.getTransitions()) {
                Map<String,Object> transition=new LinkedHashMap<>(preview);
                transition.put("screenId",DescriptorFacts.meta(saved.get(((Number)preview.get("actionId")).intValue()).action()).get(DescriptorFacts.SCREEN_META));
                transitions.add(transition);
            }
            List<Map<String,Object>> objects=new ArrayList<>();
            for(Map<String,Object> preview:done.getObjects()) {
                Map<String,Object> object=new LinkedHashMap<>(preview);
                if("SCREEN".equals(object.get("kind"))) object.put("screenId",screenIds.get(object.get("key")));
                if("STATUS".equals(object.get("kind")) && object.containsKey("receiptKey")) object.put("id",statusIds.get(object.get("receiptKey")));
                objects.add(object);
            }
            Map<String,String> statusDiffs=new HashMap<>();
            for(WorkflowConfigurationPlan.StatusIntent intent:p.statusIntents) if(intent.status.isNew() && p.graph==null) {
                statusDiffs.put(WorkflowDescriptorPatcher.addedStatusDiff(intent.stepId,intent.status.getName(),intent.token),
                        WorkflowDescriptorPatcher.addedStatusDiff(intent.stepId,intent.status.getName(),statusIds.get(intent.receiptKey)));
            }
            List<String> diff=new ArrayList<>();
            for(String row:done.getDiff()) diff.add(statusDiffs.getOrDefault(row,row));
            return new ConfigureWorkflowResponse(false,done.getStatus(),done.getWorkflow(),done.getVersion(),done.getTargetVersion(),done.getRevision(),null,done.getMessage(),diff,done.getWarnings(),transitions,objects,views,done.getConsumers(),done.getExecution(),null);
        } catch(JournalCancelledException e) {
            journal.workflowProgress(p.execution(),true);
            return p.response.failure(outcome(p),"CANCELLED",e.getMessage()+" No automatic retry or rollback; inspect the saved receipt before further changes.",p.execution());
        } catch(Stop | WorkflowConfigurationPlan.StateChanged e) {
            journal.workflowProgress(p.execution(),true);
            return p.response.failure(outcome(p),e instanceof WorkflowConfigurationPlan.StateChanged?"PREVIEW_STALE":"JIRA_ERROR",e.getMessage()+" No automatic retry or rollback; inspect the saved receipt before further changes.",p.execution());
        } catch(RuntimeException e) {
            journal.workflowProgress(p.execution(),true);
            return p.response.failure(outcome(p),"JIRA_ERROR",e.getMessage()+" No automatic retry or rollback.",p.execution());
        }
    }
    private Status statusById(String id) { return statuses.findById(id); }
    private <T> T call(WorkflowConfigurationPlan p,String key,Supplier<T> write,Function<T,String> id) {
        journal.checkpoint();p.check();Map<String,Object> step=step(p,key);
        step.put("writeStatus","UNKNOWN");journal.workflowProgress(p.execution(),false);
        T result;
        try { result=write.get(); }
        catch(RuntimeException e) {
            if(e instanceof NoWrite) step.put("writeStatus","FAILED");
            step.put("error",error(e.getMessage()));journal.workflowProgress(p.execution(),false);
            throw new Stop("Native step '"+key+"' "+step.get("writeStatus")+": "+e.getMessage());
        }
        step.put("writeStatus","APPLIED");
        // Confirmation is durable even when obtaining the returned ID or read-back subsequently fails.
        journal.workflowProgress(p.execution(),false);
        String known;
        try {known=id.apply(result);}
        catch(RuntimeException e){unavailable(p,key,"Native call returned, but ID unavailable: "+e.getMessage());return result;}
        if(known==null) unavailable(p,key,"Native call returned, but its ID is unavailable.");
        step.put("id",known);
        journal.workflowProgress(p.execution(),false);return result;
    }
    private void verify(WorkflowConfigurationPlan p,String key,Object expected,Supplier<Object> read) {
        Map<String,Object> step=step(p,key);
        Object actual;
        try{actual=read.get();}
        catch(RuntimeException e){unavailable(p,key,"Read-back failed: "+e.getMessage());return;}
        if(actual==null){unavailable(p,key,"Read-back is unavailable.");return;}
        boolean match=p.codec.fingerprint(expected).equals(p.codec.fingerprint(actual));
        step.put("verification",match?"MATCH":"MISMATCH");if(!match)step.put("error",mismatch(p,expected,actual));
        journal.workflowProgress(p.execution(),false);if(!match)throw new Stop("Native step '"+key+"' applied, but its read-back differs.");
    }
    private static final String MISMATCH_PREFIX="Native write read-back differs from the confirmed state.";
    private static final String ELLIPSIS="\u2026";
    /** Prefix, then every differing field name, then expected!=actual values squeezed into what is left of the receipt error budget. Names are never displaced by values. */
    private static String mismatch(WorkflowConfigurationPlan p,Object expected,Object actual) {
        if(!(expected instanceof Map) || !(actual instanceof Map)) return MISMATCH_PREFIX;
        Map<?,?> want=(Map<?,?>)expected,got=(Map<?,?>)actual;
        Set<Object> keys=new LinkedHashSet<>(want.keySet());keys.addAll(got.keySet());
        List<String> names=new ArrayList<>();List<String> values=new ArrayList<>();
        for(Object key:keys) {
            Object left=want.get(key),right=got.get(key);
            if(p.codec.fingerprint(left).equals(p.codec.fingerprint(right))) continue;
            names.add(String.valueOf(key));values.add(render(left));values.add(render(right));
        }
        if(names.isEmpty()) return MISMATCH_PREFIX;
        int limit=ru.rasulov.common.impl.undo.WorkflowReceiptCodec.ERROR_LENGTH;
        String head=MISMATCH_PREFIX+" Fields: "+String.join(",",names)+".";
        if(head.length()>limit) return head.substring(0,limit-1)+ELLIPSIS;
        String label=" expected!=actual: ";
        int available=limit-head.length()-label.length()-2*(names.size()-1)-2*names.size();
        if(available<values.size()) return head;
        int[] share=new int[values.size()];
        List<Integer> order=new ArrayList<>();for(int i=0;i<values.size();i++)order.add(i);
        order.sort(Comparator.comparingInt(i->values.get(i).length()));
        for(int k=0;k<order.size();k++) {int i=order.get(k);share[i]=Math.min(values.get(i).length(),available/(order.size()-k));available-=share[i];}
        StringBuilder text=new StringBuilder(head).append(label);
        for(int i=0;i<names.size();i++) {
            if(i>0) text.append("; ");
            text.append(fit(values.get(2*i),share[2*i])).append("!=").append(fit(values.get(2*i+1),share[2*i+1]));
        }
        return text.toString();
    }
    private static String render(Object value) {
        if(value==null) return "null";
        String text=value.toString();
        if(!(value instanceof CharSequence)) return text;
        StringBuilder quoted=new StringBuilder("\"");
        for(int i=0;i<text.length();i++) quoted.append(Character.isISOControl(text.charAt(i))?' ':text.charAt(i));
        return quoted.append('"').toString();
    }
    private static String fit(String value,int limit){return value.length()<=limit?value:value.substring(0,limit-1)+ELLIPSIS;}
    private void unavailable(WorkflowConfigurationPlan p,String key,String message){Map<String,Object> step=step(p,key);step.put("verification","UNAVAILABLE");step.put("error",error(message));journal.workflowProgress(p.execution(),false);throw new Stop(message);}
    private void verifyScreen(WorkflowConfigurationPlan p,String key,Long id,Object expected){verify(p,key,expected,()->WorkflowConfigurationPlanner.screenFacts(screens.getFieldScreen(id)));}
    private void ownScreen(WorkflowConfigurationPlan p,Long id,Object expected){p.readers.put("owned-screen:"+id,()->WorkflowConfigurationPlanner.screenFacts(screens.getFieldScreen(id)));p.expect("owned-screen:"+id,expected);}
    @SuppressWarnings("unchecked") private void ownScreenLookups(WorkflowConfigurationPlan p,Long id,String name) {
        for(String dependency:p.readers.keySet()) if(dependency.startsWith("screen-lookup:") && dependency.substring("screen-lookup:".length()).trim().equalsIgnoreCase(name)) {
            List<Object> expected=new ArrayList<>((List<Object>)p.dependencies.get(dependency));
            expected.add(map("id",id,"name",name));expected.sort(Comparator.comparing(Object::toString));p.expect(dependency,expected);
        }
    }
    private static Object screenState(Long id,NewScreen spec,List<Object> tabs){return map("id",id,"name",spec.name().trim(),"description",spec.description(),"tabs",List.copyOf(tabs));}
    private String workflowId(JiraWorkflow workflow){if(workflow==null)return null;Object id=WorkflowSnapshots.workflow(workflow,delegator).get("id");return id==null?null:id.toString();}
    private static Object definition(JiraWorkflow workflow){return workflow==null?null:DescriptorFacts.definition(workflow.getDescriptor());}
    private static WorkflowDescriptor copyDescriptor(WorkflowDescriptor descriptor) {
        try {return com.atlassian.jira.workflow.WorkflowUtil.convertXMLtoWorkflowDescriptor(com.atlassian.jira.workflow.WorkflowUtil.convertDescriptorToXML(descriptor));}
        catch(Exception e){throw new WorkflowConfigurationPlan.StateChanged("The workflow template can no longer be copied exactly.");}
    }
    private static Map<String,Object> step(WorkflowConfigurationPlan p,String key){for(Map<String,Object> step:p.steps)if(key.equals(step.get("key")))return step;throw new IllegalStateException("No reserved receipt step "+key);}
    private static String outcome(WorkflowConfigurationPlan p){boolean unknown=false;for(Map<String,Object> step:p.steps){if("APPLIED".equals(step.get("writeStatus")))return "PARTIAL";if("UNKNOWN".equals(step.get("writeStatus")))unknown=true;}return unknown?"UNKNOWN":"FAILED";}
    private static String error(String message){if(message==null)return "Native error";int limit=ru.rasulov.common.impl.undo.WorkflowReceiptCodec.ERROR_LENGTH;return message.length()<=limit?message:message.substring(0,limit);}
    @SuppressWarnings("unchecked") private static void replaceContent(WorkflowDescriptor target,WorkflowDescriptor content){
        if(target==content)return;
        target.getMetaAttributes().clear();target.getMetaAttributes().putAll(content.getMetaAttributes());
        target.getSteps().clear();for(Object step:new ArrayList<>(content.getSteps()))target.addStep((com.opensymphony.workflow.loader.StepDescriptor)step);
        target.getInitialActions().clear();for(Object action:new ArrayList<>(content.getInitialActions()))target.addInitialAction((com.opensymphony.workflow.loader.ActionDescriptor)action);
        target.getGlobalActions().clear();for(Object action:new ArrayList<>(content.getGlobalActions()))target.addGlobalAction((com.opensymphony.workflow.loader.ActionDescriptor)action);
        target.getCommonActions().clear();target.getCommonActions().putAll(content.getCommonActions());
        target.getRegisters().clear();target.getRegisters().addAll(content.getRegisters());
        target.getTriggerFunctions().clear();target.getTriggerFunctions().putAll(content.getTriggerFunctions());
        target.getSplits().clear();target.getSplits().addAll(content.getSplits());target.getJoins().clear();target.getJoins().addAll(content.getJoins());
    }
    private static final class NoWrite extends RuntimeException{NoWrite(String message){super(message);}}
    private static final class Stop extends RuntimeException{Stop(String message){super(message);}}
}
