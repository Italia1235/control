package ru.rasulov.workflow.impl.edit;

import com.atlassian.crowd.embedded.api.Group;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.issue.fields.Field;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenLayoutItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenTab;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.opensymphony.workflow.loader.ActionDescriptor;
import com.opensymphony.workflow.loader.ConditionsDescriptor;
import com.opensymphony.workflow.loader.ConditionDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.workflow.api.dto.WorkflowStatusView;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.*;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.api.dto.edit.ScriptRunnerStatus;
import ru.rasulov.workflow.api.dto.edit.ValidatorFieldRef;
import ru.rasulov.workflow.api.dto.edit.WorkflowChange;
import ru.rasulov.workflow.impl.*;
import ru.rasulov.workflow.impl.ResolvedWorkflow.WorkflowStatus;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;
import static ru.rasulov.workflow.impl.edit.WorkflowConfigurationPlan.map;
import static ru.rasulov.workflow.impl.edit.WorkflowConfigurationPlan.ConfigurationFailure;

/** Builds one detached final descriptor and fingerprints only the dependencies it actually used. */
@Named
public final class WorkflowConfigurationPlanner {
    private final WorkflowManager workflows;
    private final WorkflowInspector inspector;
    private final WorkflowRequestValidator graphValidator;
    private final WorkflowDescriptorBuilder builder;
    private final ExistingWorkflowMatcher matcher;
    private final StatusCatalog statuses;
    private final ConstantsManager constants;
    private final FieldManager fields;
    private final FieldScreenManager screens;
    private final TransitionScreenCatalog screenCatalog;
    private final GroupManager groups;
    private final ProjectRoleManager roles;
    private final ScriptRunnerGate gate;
    private final ValidatorFieldPlacement placement;
    private final ActionPlanCodec codec;
    private final StepOccupancy occupancy;
    @Inject
    public WorkflowConfigurationPlanner(@ComponentImport WorkflowManager workflows, WorkflowInspector inspector,
            WorkflowRequestValidator graphValidator, WorkflowDescriptorBuilder builder, ExistingWorkflowMatcher matcher,
            StatusCatalog statuses, @ComponentImport ConstantsManager constants, @ComponentImport FieldManager fields,
            @ComponentImport FieldScreenManager screens, TransitionScreenCatalog screenCatalog,
            @ComponentImport GroupManager groups, @ComponentImport ProjectRoleManager roles, ScriptRunnerGate gate,
            ValidatorFieldPlacement placement, ActionPlanCodec codec, StepOccupancy occupancy) {
        this.workflows=workflows;this.inspector=inspector;this.graphValidator=graphValidator;this.builder=builder;
        this.matcher=matcher;this.statuses=statuses;this.constants=constants;this.fields=fields;this.screens=screens;
        this.screenCatalog=screenCatalog;this.groups=groups;this.roles=roles;this.gate=gate;this.placement=placement;this.codec=codec;
        this.occupancy=occupancy;
    }
    WorkflowConfigurationPlan plan(ConfigureWorkflowRequest request, ApplicationUser user) {
        WorkflowConfigurationPlan p = new WorkflowConfigurationPlan(request,codec);
        try { build(p,user); }
        catch (ConfigurationFailure e) { p.response=ConfigureWorkflowResponse.failed(true,p.name,e.code,e.getMessage()); }
        catch (WorkflowFailureException e) { p.response=ConfigureWorkflowResponse.failed(true,p.name,e.getErrorCode().name(),e.getMessage()); }
        catch (EditFailure e) { p.response=ConfigureWorkflowResponse.failed(true,p.name,e.getCode().name(),e.getMessage()); }
        catch (Exception e) { p.response=ConfigureWorkflowResponse.failed(true,p.name,"JIRA_ERROR","Could not read or round-trip the complete plan: " + e.getMessage()); }
        return p;
    }
    private void build(WorkflowConfigurationPlan p, ApplicationUser user) throws Exception {
        ConfigureWorkflowRequest r=p.request;
        if ((r.create()==null)==(r.existing()==null)) fail("VALIDATION_ERROR","Give exactly one of create or existing.");
        List<TransitionRule> rules=list(r.transitionRules()); List<NewScreen> requestedScreens=list(r.newScreens());
        limit(rules,100,"transitionRules");limit(requestedScreens,10,"newScreens");
        List<String> warnings=new ArrayList<>(), diff=new ArrayList<>();
        List<Map<String,Object>> objects=new ArrayList<>(), transitions=new ArrayList<>();
        Map<String,Integer> keys=new LinkedHashMap<>();
        Set<Integer> originalActions=new HashSet<>(), finalChecks=new LinkedHashSet<>(), structuralScreens=new HashSet<>();
        Object consumers=null;
        if (r.create()!=null) {
            p.name=r.create().getName();p.graph=graphValidator.validate(r.create(),user);p.name=p.graph.getName();
            warnings.addAll(p.graph.getWarnings());
            for(int i=0;i<r.create().getTransitions().size();i++) {
                TransitionDefinition transition=r.create().getTransitions().get(i);
                String key=key(transition.getKey(),"transition key");
                if(keys.put(key,11+10*i)!=null) fail("VALIDATION_ERROR","Transition key '"+key+"' is repeated.");
            }
            JiraWorkflow existing=findWorkflow(p.name);
            if(existing!=null) {
                if(!rules.isEmpty() || !requestedScreens.isEmpty() || !matcher.differences(p.graph,existing).isEmpty())
                    fail("NAME_CONFLICT","Workflow '"+existing.getName()+"' already exists. Use existing explicitly to configure it, or choose another name.");
                p.name=existing.getName();
                if(!Objects.equals(p.graph.getDescription(),existing.getDescription()==null?"":existing.getDescription()))
                    warnings.add("The existing workflow description differs from the requested description and is left unchanged.");
                p.response=response(p,"EXISTS",null,List.of(),warnings,List.of(),List.of(),null); return;
            }
            p.depend("workflow",()->workflowNameFacts(p.name));
            if(workflows.getWorkflow(JiraWorkflow.DEFAULT_WORKFLOW_NAME)==null) fail("JIRA_ERROR","The built-in template workflow is unavailable.");
            p.depend("template",()->mainFacts(workflows.getWorkflow(JiraWorkflow.DEFAULT_WORKFLOW_NAME)));
            Map<WorkflowStatus,String> ids=new IdentityHashMap<>();
            for(int i=0;i<p.graph.getStatuses().size();i++) {
                WorkflowStatus status=p.graph.getStatuses().get(i);
                p.depend("status:"+i,()->statusFacts(statuses.find(status.getName())));
                ids.put(status,status.isNew()?"LOCAL_STATUS_"+i:status.getExisting().getId());
                if(status.isNew()) { p.step("status:"+i,"STATUS");objects.add(map("kind","STATUS","receiptKey","status:"+i,"name",status.getName(),"category",status.getCategory(),"description",status.getDescription(),"global",true)); }
            }
            for(ResolvedWorkflow.WorkflowTransition t:p.graph.getTransitions()) {
                if("SET".equals(t.getResolution().getAction())) {
                    String id=t.getResolution().getValue();p.depend("resolution:"+id,()->resolutionFacts(constants.getResolution(id)));
                }
                if(t.getScreen()!=null) dependScreen(p,t.getScreen().getId());
            }
            p.descriptor=builder.build(p.graph,ids);p.before="";
            for(int i=0;i<p.graph.getStatuses().size();i++) {
                WorkflowStatus status=p.graph.getStatuses().get(i);
                WorkflowConfigurationPlan.StatusIntent intent=new WorkflowConfigurationPlan.StatusIntent(status,ids.get(status),"status:"+i,"status:"+i,null);
                bindStatus(p,intent);p.statusIntents.add(intent);
            }
            objects.add(map("kind","WORKFLOW","name",p.name,"assigned",false));diff.add("CREATE_WORKFLOW: "+p.name);
        } else {
            Existing existing=r.existing();p.name=nonblank(existing.workflow(),"existing.workflow");
            if(!"ACTIVE".equals(existing.version()) && !"DRAFT".equals(existing.version())) fail("VALIDATION_ERROR","existing.version must be ACTIVE or DRAFT.");
            String revision=nonblank(existing.expectedRevision(),"existing.expectedRevision");
            p.target=EditTarget.resolve(p.name,existing.version(),revision,inspector,workflows);
            if(p.target.refused()) fail(p.target.refusalCode.name(),p.target.refusalMessage);
            p.name=p.target.snapshot.name();p.descriptor=WorkflowUtil.convertXMLtoWorkflowDescriptor(WorkflowUtil.convertDescriptorToXML(p.target.descriptor));p.before=p.target.before;
            p.depend("workflow",()->workflowRevision(p.name));
            p.depend("workflow-main",()->mainFacts(workflows.getWorkflow(p.name)));
            p.depend("consumers",()->inspector.consumers(workflows.getWorkflow(p.name)).fingerprint());
            consumers=inspector.consumers(p.target.snapshot.main()).view();
            if(p.target.mode==EditTarget.Mode.DRAFT_CREATE) objects.add(map("kind","DRAFT","workflow",p.name,"shared",true));
            warnings.add("Workflow changes are shared by every project using this workflow. Control locks do not protect against Jira UI or other plugins.");
            if(p.target.mode!=EditTarget.Mode.INACTIVE_SAVE) warnings.add("The draft is shared by all administrators. Publishing it later affects every project using this workflow; use clone_project_workflows first when only one project should change. No publication or project assignment is performed.");
            originalActions.addAll(DescriptorFacts.locate(p.descriptor).keySet());
            if(existing.changes()!=null) {
                Set<Integer> removed=new TreeSet<>();
                for(WorkflowChange change:existing.changes()) if(change!=null && "REMOVE_STATUS".equals(change.getOp())
                        && change.getStepId()!=null && p.target.protectedSteps!=null && p.target.protectedSteps.contains(change.getStepId())) removed.add(change.getStepId());
                Map<Integer,Long> occupied=removed.isEmpty()?Map.of():occupancy.occupied(p.name,removed);
                if(!removed.isEmpty()) {
                    p.readers.put("occupancy",()->occupancy.occupied(p.name,removed));
                    p.expect("occupancy",occupied);
                    for(Integer id:removed) warnings.add("Active-version step "+id+" currently holds "+occupied.getOrDefault(id,0L)+" issues and is removed only from the draft. Occupancy is checked again by publish_workflow_draft before publication.");
                }
                List<WorkflowChange> resolved=resolveAddedStatuses(p,existing.changes(),user,warnings);
                WorkflowDescriptorPatcher.Catalog catalog=statusCatalog(p);
                WorkflowDescriptorPatcher.Result patched=WorkflowDescriptorPatcher.apply(p.descriptor,resolved,p.target.protectedSteps,occupied,catalog);
                for(WorkflowConfigurationPlan.StatusIntent intent:p.statusIntents) {
                    bindStatus(p,intent);
                    WorkflowStatus status=intent.status;
                    Map<String,Object> object=map("kind","STATUS","action",status.isNew()?"CREATE":"REUSE","global",true,
                            "name",status.getName(),"category",status.getCategory(),"description",status.isNew()?status.getDescription():normalizedDescription(status.getExisting().getDescription()),
                            "stepId",intent.stepId);
                    if(intent.clientKey!=null)object.put("clientKey",intent.clientKey);
                    if(status.isNew())object.put("receiptKey",intent.receiptKey);else object.put("id",status.getExisting().getId());
                    objects.add(object);
                }
                diff.addAll(patched.diff());warnings.addAll(patched.warnings());
                for(Map.Entry<Integer,String> entry:patched.steps().entrySet()) objects.add(map("kind","STEP","stepId",entry.getKey(),"clientKey",entry.getValue()));
                for(Map.Entry<Integer,String> entry:patched.transitions().entrySet()) {
                    finalChecks.add(entry.getKey());
                    if(entry.getValue()!=null) keys.put(entry.getValue(),entry.getKey());
                }
                for(WorkflowChange change:existing.changes()) if(change.getActionId()!=null && change.getScreen()!=null
                        && ("SET".equals(change.getScreen().getAction()) || "CLEAR".equals(change.getScreen().getAction()))) structuralScreens.add(change.getActionId());
                for(Map.Entry<Integer,String> entry:patched.transitions().entrySet()) for(WorkflowChange change:existing.changes())
                    if("ADD_TRANSITION".equals(change.getOp()) && Objects.equals(entry.getValue(),change.getTransitionKey())
                            && change.getScreen()!=null && ("SET".equals(change.getScreen().getAction()) || "CLEAR".equals(change.getScreen().getAction()))) structuralScreens.add(entry.getKey());
                finalChecks.addAll(structuralScreens);
            }
        }
        for(NewScreen screen:requestedScreens) {
            if(screen==null)fail("VALIDATION_ERROR","newScreens cannot contain null.");
            String key=key(screen.key(),"newScreens.key");String name=nonblank(screen.name(),"newScreens.name");
            if(name.length()>255 || (screen.description()!=null && screen.description().length()>10000)) fail("VALIDATION_ERROR","Screen name or description is too long.");
            xmlText(name);xmlText(screen.description());
            if(p.screens.put(key,screen)!=null) fail("VALIDATION_ERROR","New screen key '"+key+"' is repeated.");
            fieldIds(p,screen.fieldIds(),50,true);
            // Duplicate names are allowed: occupancy is a warning, never a stale dependency.
            if(!namedScreens(name).isEmpty()) warnings.add("A screen named '"+name+"' already exists. A separate new screen will be created; an identical name does not mean reuse.");
            // Receipt steps are addressed by the ordinal of the screen in newScreens, never by the user's key: the encoded receipt must not grow with key length.
            int ordinal=p.screens.size()-1;
            p.step("screen:"+ordinal,"SCREEN");p.step("tab:"+ordinal,"SCREEN");
            for(int i=0;i<screen.fieldIds().size();i++) p.step("field:"+ordinal+":"+i,"SCREEN");
            objects.add(map("kind","SCREEN","receiptKey","screen:"+ordinal,"key",key,"name",name,"description",screen.description(),"tab","Field Tab","fieldIds",screen.fieldIds()));
        }
        Set<Integer> seen=new HashSet<>();Set<String> usedScreens=new HashSet<>();
        for(TransitionRule rule:rules) {
            if(rule==null) fail("VALIDATION_ERROR","transitionRules cannot contain null.");
            int actionId;
            if(r.create()!=null) {
                if(rule.actionId()!=null || rule.transitionKey()==null || !keys.containsKey(rule.transitionKey())) fail("UNKNOWN_TRANSITION","Create rules require an explicitly declared transitionKey, never actionId.");
                actionId=keys.get(rule.transitionKey());
            } else {
                if((rule.transitionKey()==null)==(rule.actionId()==null)) fail("VALIDATION_ERROR","Existing rules require exactly one of actionId or transitionKey.");
                if(rule.transitionKey()!=null) {
                    if(!keys.containsKey(rule.transitionKey())) fail("UNKNOWN_TRANSITION","Unknown local transitionKey '"+rule.transitionKey()+"'.");
                    actionId=keys.get(rule.transitionKey());
                } else {
                    actionId=rule.actionId();
                    if(!originalActions.contains(actionId)) fail("UNKNOWN_TRANSITION","actionId must address a transition in the read workflow version, not an allocated ID.");
                }
            }
            if(!seen.add(actionId)) fail("VALIDATION_ERROR","Only one transitionRules block is allowed per transition.");
            DescriptorFacts.Located located=DescriptorFacts.locate(p.descriptor).get(actionId);
            if(located==null) fail("UNKNOWN_TRANSITION","No transition has actionId "+actionId+".");
            ActionDescriptor action=located.action();
            List<Validator> validators=list(rule.addValidators());limit(validators,10,"addValidators");
            List<Condition> conditions=rule.addConditions()==null?List.of():list(rule.addConditions().rules());limit(conditions,10,"addConditions.rules");
            if(rule.addConditions()!=null && (!"AND".equals(rule.addConditions().operator()) || rule.addConditions().rules()==null)) fail("VALIDATION_ERROR","addConditions requires operator AND and rules array.");
            boolean adding=!validators.isEmpty() || !conditions.isEmpty();
            if(located.duplicated() || (adding && located.kind()==DescriptorFacts.Kind.COMMON)) fail("UNSUPPORTED_DESCRIPTOR","Transition "+actionId+" is duplicated or COMMON and cannot take rules exactly.");
            if(rule.screen()!=null && !"KEEP".equals(rule.screen().action())) {
                if(structuralScreens.contains(actionId)) fail("VALIDATION_ERROR","Both existing.changes and transitionRules explicitly set the screen of transition "+actionId+".");
                if(!"SET".equals(rule.screen().action()) && !"CLEAR".equals(rule.screen().action())) fail("VALIDATION_ERROR","screen.action must be SET, CLEAR or KEEP.");
                if(located.kind()==DescriptorFacts.Kind.INITIAL) fail("UNSUPPORTED_DESCRIPTOR","INITIAL has no transition screen.");
                finalChecks.add(actionId);
                ScreenChoice choice=rule.screen();
                if("SET".equals(choice.action()) && (choice.screenId()==null?0:1)+(choice.screenName()==null?0:1)+(choice.screenKey()==null?0:1)!=1) fail("VALIDATION_ERROR","SET requires one screen reference.");
                if("CLEAR".equals(choice.action()) && (choice.screenId()!=null || choice.screenName()!=null || choice.screenKey()!=null)) fail("VALIDATION_ERROR","CLEAR has no screen reference.");
                String id;
                if("CLEAR".equals(choice.action())) {
                    id=null;
                } else if(choice.screenKey()!=null) {
                    if(!p.screens.containsKey(choice.screenKey())) fail("UNKNOWN_SCREEN","Unknown local screen key '"+choice.screenKey()+"'.");
                    usedScreens.add(choice.screenKey());p.screenKeys.put(actionId,choice.screenKey());id="LOCAL_SCREEN_"+UUID.nameUUIDFromBytes(choice.screenKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                } else {
                    FieldScreen screen=choice.screenId()!=null?screenById(choice.screenId()):screenCatalog.resolve(choice.screenName(),"screenName");
                    id=screen.getId().toString();dependScreen(p,screen.getId());
                    if(choice.screenName()!=null) p.depend("screen-lookup:"+choice.screenName(),()->namedScreens(choice.screenName()));
                }
                WorkflowDescriptorPatcher.applyScreen(action,id,"transition "+actionId
                        +(located.kind()==DescriptorFacts.Kind.COMMON?"; used by steps "+located.sources():""),diff);
            } else if(rule.screen()!=null && (rule.screen().screenId()!=null || rule.screen().screenName()!=null || rule.screen().screenKey()!=null)) fail("VALIDATION_ERROR","KEEP has no screen reference.");
            for(Validator validator:validators) {
                if(validator==null) fail("VALIDATION_ERROR","Validator cannot be null.");
                Parameters params=validator.parameters();
                if("comment_required".equals(validator.template())) {
                    if(params!=null && (params.fieldIds()!=null || params.group()!=null || params.roles()!=null || params.invert()!=null)) fail("VALIDATION_ERROR","comment_required takes no parameters.");
                    scriptRunner(p,"Comment required");
                    if(!CommentRequiredDescriptor.present(action)) { CommentRequiredDescriptor.apply(action,functionId(actionId,"comment"));diff.add("ADD_VALIDATOR: "+actionId+" comment_required"); }
                } else if("required_fields".equals(validator.template())) {
                    if(params==null || params.group()!=null || params.roles()!=null || params.invert()!=null) fail("VALIDATION_ERROR","required_fields takes only parameters.fieldIds.");
                    List<String> ids=fieldIds(p,params.fieldIds(),50,false);scriptRunner(p,"Field(s) required");
                    RequiredFieldsDescriptor.Change change=RequiredFieldsDescriptor.plan(action,ids);
                    if(!change.added().isEmpty()) {RequiredFieldsDescriptor.apply(action,change,functionId(actionId,"fields:"+String.join(",",change.added())));diff.add("ADD_VALIDATOR: "+actionId+" required_fields "+change.added());}
                } else fail("VALIDATION_ERROR","Unknown validator template; arbitrary scripts and classes are not accepted.");
            }
            for(Condition condition:conditions) {
                ConditionDescriptors.Candidate candidate=condition(p,condition,warnings);
                refuseRequestedConditionShape(action,candidate);
                ConditionDescriptors.Check check=ConditionDescriptors.check(action,candidate);
                if(check.outcome()==ConditionDescriptors.Outcome.UNSUPPORTED) fail("UNSUPPORTED_DESCRIPTOR",check.reason());
                if(check.outcome()==ConditionDescriptors.Outcome.ADD) {
                    ConditionDescriptors.apply(action,candidate,functionId(actionId,candidate.toString()));
                    diff.add("ADD_CONDITION: "+actionId+" "+candidate);
                }
            }
            if(adding) finalChecks.add(actionId);
            transitions.add(transitionView(p,located));
        }
        for(Integer id:finalChecks) {
            DescriptorFacts.Located located=DescriptorFacts.locate(p.descriptor).get(id);
            if(located!=null) checkFinalScreen(p,located.action(),id,warnings);
        }
        if(p.graph==null) {
            Set<Integer> included=new HashSet<>();
            for(Map<String,Object> transition:transitions) included.add(((Number)transition.get("actionId")).intValue());
            for(Integer id:finalChecks) if(!included.contains(id) && DescriptorFacts.locate(p.descriptor).containsKey(id)) transitions.add(transitionView(p,DescriptorFacts.locate(p.descriptor).get(id)));
            for(Map<String,Object> transition:transitions) for(Map.Entry<String,Integer> entry:keys.entrySet())
                if(entry.getValue().equals(transition.get("actionId"))) transition.put("transitionKey",entry.getKey());
        }
        if(!usedScreens.equals(p.screens.keySet())) fail("VALIDATION_ERROR","Every newScreens entry must be referenced by screenKey.");
        if(p.graph!=null) {
            transitions.clear();
            for(DescriptorFacts.Located located:DescriptorFacts.locate(p.descriptor).values()) {
                Map<String,Object> view=transitionView(p,located);
                for(Map.Entry<String,Integer> entry:keys.entrySet()) if(entry.getValue()==located.id()) view.put("transitionKey",entry.getKey());
                transitions.add(view);
            }
            consumers=map("projects",List.of(),"schemes",List.of());
        }
        if(p.target!=null && r.existing().changes()!=null) {
            WorkflowDescriptor references=WorkflowUtil.convertXMLtoWorkflowDescriptor(WorkflowUtil.convertDescriptorToXML(p.descriptor));
            p.depend("structural-references",()->inspector.referenced(references));
        }
        p.after=DescriptorFacts.definition(p.descriptor);
        try {
            String again=DescriptorFacts.definition(WorkflowUtil.convertXMLtoWorkflowDescriptor(WorkflowUtil.convertDescriptorToXML(p.descriptor)));
            if(!p.after.equals(again)) fail("UNSUPPORTED_DESCRIPTOR","Jira round-trip changes the final descriptor; nothing can be saved exactly.");
        } catch(ConfigurationFailure e){throw e;} catch(Exception e){fail("UNSUPPORTED_DESCRIPTOR","Jira cannot round-trip the final descriptor: "+e.getMessage());}
        if(p.graph==null && p.before.equals(p.after)) { p.response=response(p,"UNCHANGED",consumers,List.of(),warnings,transitions,List.of(),null);return; }
        if(p.graph!=null) p.step("workflow-copy","WORKFLOW_COPY");
        else if(p.target.mode==EditTarget.Mode.DRAFT_CREATE) p.step("draft","DRAFT");
        p.step("workflow-save","WORKFLOW_SAVE");p.reserveReceiptSize();
        if(!p.screens.isEmpty()) warnings.add("After a partial failure, a newly created standalone screen can remain unassigned or incomplete. There is no automatic rollback or mutation retry.");
        if(transitions.stream().anyMatch(t->t.get("conditions")!=null)) warnings.add("All conditions of an AND root must be satisfied. Conditions narrow access for every project using this workflow, including future projects; group membership is only an observation and may change.");
        p.response=response(p,p.graph!=null?"WOULD_CREATE":"WOULD_CHANGE",consumers,diff,warnings,transitions,objects,null);
        p.fingerprint=codec.fingerprint(map("request",r,"dependencies",p.dependencies,"after",p.after,
                "diff",diff,"transitions",transitions,"objects",objects,"consumers",consumers));
    }
    private ConfigureWorkflowResponse response(WorkflowConfigurationPlan p,String status,Object consumers,List<String> diff,List<String> warnings,List<Map<String,Object>> transitions,List<Map<String,Object>> objects,Map<String,Object> execution) {
        List<WorkflowStatusView> views=new ArrayList<>();
        if(p.graph!=null) for(WorkflowStatus s:p.graph.getStatuses()) views.add(new WorkflowStatusView(s.getName(),s.isNew()?null:s.getExisting().getId(),s.getCategory(),s.isNew()));
        if(p.graph==null) for(WorkflowConfigurationPlan.StatusIntent intent:p.statusIntents) {
            WorkflowStatus s=intent.status;views.add(new WorkflowStatusView(s.getName(),s.isNew()?null:s.getExisting().getId(),s.getCategory(),s.isNew()));
        }
        String message="UNCHANGED".equals(status)?unchangedMessage(p):"One final descriptor; forward-only native writes with no automatic rollback or mutation retry. No publication or project assignment.";
        return new ConfigureWorkflowResponse(true,status,p.name,p.graph!=null?"ACTIVE":p.request.existing().version(),p.graph!=null || p.target.mode==EditTarget.Mode.INACTIVE_SAVE?"ACTIVE":"DRAFT",p.target==null?null:p.target.snapshot.revision(),null,
                message+(status.startsWith("WOULD_")?" After human confirmation, apply this one preview through start_operation.":""),diff,warnings,transitions,objects,views,consumers,execution,null);
    }
    private static String unchangedMessage(WorkflowConfigurationPlan p) {
        Set<String> templates=new LinkedHashSet<>();
        for(TransitionRule rule:list(p.request.transitionRules())) {
            for(Validator validator:list(rule.addValidators()))templates.add(validator.template());
            if(rule.addConditions()!=null)for(Condition condition:list(rule.addConditions().rules()))templates.add(condition.template());
        }
        return templates.isEmpty()?"Nothing changes; existing screens and rules are preserved."
                :"The requested rules are already present: "+String.join(", ",templates)+". Nothing is changed.";
    }
    private List<WorkflowChange> resolveAddedStatuses(WorkflowConfigurationPlan p,List<WorkflowChange> changes,ApplicationUser user,List<String> warnings) {
        List<WorkflowChange> resolved=new ArrayList<>();
        Set<String> names=new HashSet<>();
        for(WorkflowChange change:changes) {
            if(change==null)fail("VALIDATION_ERROR","changes cannot contain null.");
            if(!"ADD_STATUS".equals(change.getOp())) {
                if(change.getNewStatus()!=null)fail("VALIDATION_ERROR","newStatus is allowed only for ADD_STATUS.");
                resolved.add(change);continue;
            }
            if((change.getStatusId()==null)==(change.getNewStatus()==null))fail("VALIDATION_ERROR","ADD_STATUS requires exactly one of statusId or newStatus.");
            int ordinal=p.statusIntents.size();
            WorkflowStatus status;String dependency,lookup;
            if(change.getNewStatus()!=null) {
                status=graphValidator.resolveExistingStatus(change.getNewStatus(),user,warnings);
                lookup=change.getNewStatus().getName().trim();
                dependency="status-name:"+ordinal;
                p.depend(dependency,()->statusCandidates(lookup));
            } else {
                String id=nonblank(change.getStatusId(),"statusId");
                Status existing=constants.getStatus(id);
                if(existing==null)fail("UNKNOWN_STATUS","No status has ID '"+id+"'. Use list_statuses.");
                status=new WorkflowStatus(existing.getName(),existing,StatusCatalog.categoryOf(existing),null);
                lookup=null;dependency="status-id:"+existing.getId();
                p.depend(dependency,()->statusFacts(constants.getStatus(existing.getId())));
            }
            if(status.isNew() && !names.add(ru.rasulov.common.impl.Normalizer.normalize(status.getName())))
                fail("VALIDATION_ERROR","The same resolved status is added more than once.");
            String token=status.isNew()?"LOCAL_STATUS_"+ordinal:status.getExisting().getId();
            String receipt="status:"+ordinal;
            if(status.isNew()) {
                p.step(receipt,"STATUS");
                ru.rasulov.workflow.api.dto.StatusCategoryKey category=ru.rasulov.workflow.api.dto.StatusCategoryKey.valueOf(status.getCategory());
                p.depend("status-category:"+ordinal,()->statuses.categoryFacts(category));
                p.depend("status-create:"+receipt,()->statuses.creationProblems(user,status.getName(),status.getDescription(),category));
                warnings.add("A new global status '"+status.getName()+"' will be created and available outside this workflow. Partial failure can leave it behind; no automatic rollback.");
            } else {
                warnings.add("Global status '"+status.getName()+"' is reused without changing its name, category or description.");
            }
            p.statusIntents.add(new WorkflowConfigurationPlan.StatusIntent(status,token,dependency,receipt,change.getClientKey()));
            resolved.add(new WorkflowChange(change.getOp(),change.getActionId(),change.getStepId(),token,change.getClientKey(),
                    change.getName(),change.getDescription(),change.getSource(),change.getTargetStepId(),change.getTargetClientKey(),
                    change.getScreen(),change.getResolution(),change.getTransitionKey(),null));
        }
        return resolved;
    }
    private WorkflowDescriptorPatcher.Catalog statusCatalog(WorkflowConfigurationPlan p) {
        WorkflowDescriptorPatcher.Catalog base=inspector.catalog();
        return new WorkflowDescriptorPatcher.Catalog() {
            public WorkflowDescriptorPatcher.StatusInfo status(String id) {
                for(WorkflowConfigurationPlan.StatusIntent intent:p.statusIntents) if(intent.token.equals(id))
                    return new WorkflowDescriptorPatcher.StatusInfo(id,intent.status.getName(),intent.status.isNew()
                            ?ru.rasulov.workflow.api.dto.StatusCategoryKey.valueOf(intent.status.getCategory()).jiraKey()
                            :intent.status.getExisting().getStatusCategory()==null?null:intent.status.getExisting().getStatusCategory().getKey());
                return base.status(id);
            }
            public boolean screenExists(long id){return base.screenExists(id);}
            public WorkflowDescriptorPatcher.ResolutionInfo resolution(String id){return base.resolution(id);}
        };
    }
    private static void bindStatus(WorkflowConfigurationPlan p,WorkflowConfigurationPlan.StatusIntent intent) {
        for(com.opensymphony.workflow.loader.StepDescriptor step:DescriptorFacts.steps(p.descriptor))
            if(intent.token.equals(DescriptorFacts.statusId(step))) {intent.stepId=step.getId();return;}
        fail("VALIDATION_ERROR","Added status has no bound descriptor step.");
    }
    List<Object> statusCandidates(String name) {
        List<Object> facts=new ArrayList<>();
        for(Status status:statuses.findAll(name))facts.add(statusFacts(status));
        return facts;
    }
    private void checkFinalScreen(WorkflowConfigurationPlan p,ActionDescriptor action,int id,List<String> warnings) {
        String key=p.screenKeys.get(id), meta=DescriptorFacts.meta(action).get(DescriptorFacts.SCREEN_META);
        FieldScreen screen=null;
        if(key==null && meta!=null) {try{screen=screens.getFieldScreen(Long.valueOf(meta));}catch(NumberFormatException ignored){}}
        if(screen!=null) dependScreen(p,screen.getId());
        Set<String> onNew=key==null?null:new HashSet<>(p.screens.get(key).fieldIds());
        List<ValidatorFieldRef> required=new ArrayList<>();int unknown=0;
        for(RequiredFieldsDescriptor.Entry entry:RequiredFieldsDescriptor.scan(action)) {
            if(entry.commentRequired() && (!CommentRequiredDescriptor.hasScreenView(action) || (key==null && screen==null))) fail("TRANSITION_SCREEN_MISSING","Transition "+id+" '"+action.getName()+"' has comment_required but no usable final transition screen with view=\"fieldscreen\". Set or repair its screen in this configure_workflow request.");
            if(entry.requiredFields() && !entry.conditional()) for(String field:entry.fieldIds()) {
                Field f=fields.getField(field);if(f!=null) p.depend("field:"+field,()->fieldFacts(field));
                required.add(new ValidatorFieldRef(field,f==null?null:f.getName()));
            }
            if(!entry.requiredFields() && !entry.commentRequired()) unknown++;
        }
        final FieldScreen finalScreen=screen;
        for(ValidatorFieldRef field:required) if(onNew!=null?!onNew.contains(field.getId()):finalScreen==null || !finalScreen.containsField(field.getId()))
            warnings.add("Transition "+id+": required field '"+field.getId()+"' is absent from the final transition screen; a user may be unable to fill it in.");
        if(p.graph==null && !required.isEmpty()) {
            p.depend("placement:"+id,()->placement.facts(p.target.snapshot.main(),required));
            warnings.addAll(placement.warnings(p.target.snapshot.main(),meta,required,onNew));
        }
        if(unknown>0) warnings.add("Transition "+id+": "+unknown+" unrecognized validator(s) preserved and not analyzed.");
    }
    private Map<String,Object> transitionView(WorkflowConfigurationPlan p,DescriptorFacts.Located located) {
        ActionDescriptor action=located.action();List<Map<String,Object>> validators=new ArrayList<>();
        for(RequiredFieldsDescriptor.Entry entry:RequiredFieldsDescriptor.scan(action)) {
            List<ValidatorFieldRef> refs=new ArrayList<>();
            for(String id:entry.fieldIds()) {
                Field field=fields.getField(id);refs.add(new ValidatorFieldRef(id,field==null?null:field.getName()));
                p.depend("field:"+id,()->fieldFacts(id));
            }
            String errorText=entry.requiredFields()?(refs.size()==1?"This field is required":"The following fields are required: "+String.join(", ",refs.stream().map(f->f.getName()==null?f.getId():f.getName()).toList()))
                    :entry.commentRequired()?"Please provide a comment for this transition. If no screen is in use: You need to provide a comment for this transition but no screen is in use":null;
            validators.add(map("position",entry.position(),"kind",entry.requiredFields()?"required_fields":entry.commentRequired()?"comment_required":"OTHER","fieldIds",entry.fieldIds(),"fields",refs,"errorText",errorText,"conditional",entry.conditional()));
        }
        List<String> from=stepNames(p.descriptor,located.kind()==DescriptorFacts.Kind.GLOBAL?DescriptorFacts.steps(p.descriptor).stream().map(s->s.getId()).toList():located.sources());
        List<String> to=stepNames(p.descriptor,DescriptorFacts.targets(action));if(DescriptorFacts.targets(action).contains(-1))to.addAll(from);
        DescriptorFacts.ResolutionRule resolution=DescriptorFacts.rule(action);
        Resolution resolved=resolution.value()==null || constants==null?null:constants.getResolution(resolution.value());
        if(resolution.value()!=null && constants!=null) p.depend("resolution:"+resolution.value(),()->resolutionFacts(constants.getResolution(resolution.value())));
        String screenKey=p.screenKeys.get(located.id()),screenId=DescriptorFacts.meta(action).get(DescriptorFacts.SCREEN_META);
        FieldScreen screen=null;
        if(screenKey==null && screenId!=null)try{screen=screens.getFieldScreen(Long.valueOf(screenId));}catch(NumberFormatException ignored){}
        if(screen!=null)dependScreen(p,screen.getId());
        String screenName=screenKey!=null?p.screens.get(screenKey).name():screen==null?null:screen.getName();
        return map("actionId",located.id(),"name",action.getName(),"kind",located.kind().name(),"usingSteps",located.sources(),"from",from,"to",to,
                "resolution",map("action",resolution.action(),"value",resolution.value(),"name",resolved==null?null:resolved.getName(),"description",resolved==null?null:resolved.getDescription()),
                "screenId",screenId,"screenName",screenName,"screenKey",screenKey,"view",action.getView(),"validators",validators,
                "unrecognizedValidators",validators.stream().filter(v->"OTHER".equals(v.get("kind"))).count(),
                "conditions",ConditionDescriptors.read(action,id->{p.depend("role-id:"+id,()->{ProjectRole role=roles.getProjectRole(id);return role==null?null:roleFacts(role);});return roleName(id);}),
                "conditionSemantics","For an AND root all conditions must be satisfied; group: user is a member of the named group; project_role: member of any listed role in the issue project; invert: member of none");
    }
    private static List<String> stepNames(WorkflowDescriptor descriptor,List<Integer> ids) {
        List<String> names=new ArrayList<>();
        for(int id:ids) {com.opensymphony.workflow.loader.StepDescriptor step=DescriptorFacts.step(descriptor,id);if(step!=null)names.add(step.getName());}
        return names;
    }
    private ConditionDescriptors.Candidate condition(WorkflowConfigurationPlan p,Condition condition,List<String> warnings) {
        if(condition==null || condition.parameters()==null) {fail("VALIDATION_ERROR","Condition requires typed parameters.");}
        Parameters params=condition.parameters();
        if("group".equals(condition.template())) {
            if(params.fieldIds()!=null || params.roles()!=null || params.invert()!=null) fail("VALIDATION_ERROR","group accepts only parameters.group.");
            String requested=nonblank(params.group(),"parameters.group");Group group=groups.getGroup(requested);
            if(group==null) fail("UNKNOWN_GROUP","No Jira group is named '"+requested+"'.");
            p.depend("group:"+requested,()->groupFacts(requested));
            int count=groups.getUsersInGroupCount(group);
            warnings.add("Group '"+group.getName()+"': observed "+count+" users including inactive users; membership may change."+(count==0?" While the group remains empty nobody satisfies this condition.":""));
            return ConditionDescriptors.Candidate.group(group.getName());
        }
        if(!"project_role".equals(condition.template()) || params.fieldIds()!=null || params.group()!=null) fail("VALIDATION_ERROR","Unknown condition template or unsupported parameters.");
        if(params.roles()==null || params.roles().isEmpty() || params.roles().size()>20) fail("VALIDATION_ERROR","project_role requires 1..20 roles.");
        List<Long> ids=new ArrayList<>();
        for(String typed:params.roles()) {
            ProjectRole role=resolveRole(nonblank(typed,"role"));
            if(ids.contains(role.getId())) fail("VALIDATION_ERROR","A project role is listed more than once.");
            ids.add(role.getId());p.depend("role:"+typed,()->roleFacts(resolveRole(typed)));
        }
        scriptRunner(p,"Project role",true);return ConditionDescriptors.Candidate.roles(ids,Boolean.TRUE.equals(params.invert()));
    }
    private ProjectRole resolveRole(String typed) {
        if(typed.matches("[0-9]+")) {ProjectRole role=null;try{role=roles.getProjectRole(Long.valueOf(typed));}catch(NumberFormatException ignored){}if(role==null) fail("UNKNOWN_ROLE","No project role has ID "+typed);return role;}
        List<ProjectRole> found=new ArrayList<>();for(ProjectRole role:roles.getProjectRoles()) if(typed.equals(role.getName())) found.add(role);
        if(found.isEmpty()) fail("UNKNOWN_ROLE","No project role is named '"+typed+"'.");if(found.size()>1) fail("AMBIGUOUS_ROLE","Several roles have name '"+typed+"'; specify an ID.");return found.get(0);
    }
    private Object groupFacts(String name) {Group group=groups.getGroup(name);return group==null?null:map("name",group.getName(),"empty",groups.getUsersInGroupCount(group)==0);}
    private static Object roleFacts(ProjectRole role){return map("id",role.getId(),"name",role.getName(),"description",role.getDescription());}
    private String roleName(long id){ProjectRole role=roles.getProjectRole(id);return role==null?null:role.getName();}
    private void scriptRunner(WorkflowConfigurationPlan p,String template) {scriptRunner(p,template,false);}
    private void scriptRunner(WorkflowConfigurationPlan p,String template,boolean condition) {
        java.util.function.Supplier<ScriptRunnerStatus> source=condition?gate::conditionStatus:gate::status;
        ScriptRunnerStatus status=source.get();if(!status.isInstalled() || !status.isEnabled()) fail("SCRIPTRUNNER_UNAVAILABLE",template+": "+status.getReason());
        if(!status.isSupported()) fail("SCRIPTRUNNER_VERSION_UNSUPPORTED",template+": installed ScriptRunner version "+status.getVersion()+" is unsupported; this captured configuration is verified only for 9.46.0. "+status.getReason());p.depend(condition?"scriptRunnerCondition":"scriptRunner",()->{ScriptRunnerStatus current=source.get();return map("installed",current.isInstalled(),"enabled",current.isEnabled(),"version",current.getVersion(),"supported",current.isSupported());});
    }
    private List<String> fieldIds(WorkflowConfigurationPlan p,List<String> ids,int max,boolean allowEmpty) {
        if(ids==null || (!allowEmpty && ids.isEmpty()) || ids.size()>max) fail("VALIDATION_ERROR","fieldIds must contain "+(allowEmpty?"0":"1")+".."+max+" fields.");
        Set<String> found=new HashSet<>();for(String id:ids) {
            nonblank(id,"field ID");if(!id.equals(id.trim()) || !found.add(id)) fail("VALIDATION_ERROR","Field IDs must be unique and have no surrounding spaces.");
            if(fields.getOrderableField(id)==null) fail(fields.getField(id)==null?"UNKNOWN_FIELD":"VALIDATION_ERROR","Field '"+id+"' is not an existing orderable field.");
            p.depend("field:"+id,()->fieldFacts(id));
        }return ids;
    }
    private Object fieldFacts(String id){Field field=fields.getOrderableField(id);return field==null?null:map("id",field.getId(),"name",field.getName(),"type",field.getClass().getName());}
    private FieldScreen screenById(String id){try{FieldScreen screen=screens.getFieldScreen(Long.valueOf(id));if(screen!=null)return screen;}catch(NumberFormatException ignored){}fail("UNKNOWN_SCREEN","No Jira screen has ID '"+id+"'.");return null;}
    private void dependScreen(WorkflowConfigurationPlan p,Long id){p.depend("screen:"+id,()->screenFacts(screens.getFieldScreen(id)));}
    static Object screenFacts(FieldScreen screen) {
        if(screen==null)return null;List<Object> tabs=new ArrayList<>();for(FieldScreenTab tab:screen.getTabs()) {List<String> ids=new ArrayList<>();for(FieldScreenLayoutItem item:tab.getFieldScreenLayoutItems())ids.add(item.getFieldId());tabs.add(map("id",tab.getId(),"name",tab.getName(),"fields",ids));}
        return map("id",screen.getId(),"name",screen.getName(),"description",screen.getDescription(),"tabs",tabs);
    }
    private List<Object> namedScreens(String name){List<Object> found=new ArrayList<>();for(FieldScreen screen:screens.getFieldScreens())if(screen.getName().trim().equalsIgnoreCase(name.trim()))found.add(map("id",screen.getId(),"name",screen.getName()));found.sort(Comparator.comparing(Object::toString));return found;}
    JiraWorkflow findWorkflow(String name){for(JiraWorkflow workflow:workflows.getWorkflows())if(workflow.getName().trim().equalsIgnoreCase(name.trim()))return workflow;return null;}
    private Object workflowNameFacts(String name){JiraWorkflow workflow=findWorkflow(name);return workflow==null?null:map("name",workflow.getName(),"definition",DescriptorFacts.definition(workflow.getDescriptor()));}
    static Object mainFacts(JiraWorkflow workflow){return workflow==null?null:map("name",workflow.getName(),"description",workflow.getDescription(),"definition",DescriptorFacts.definition(workflow.getDescriptor()));}
    private Object workflowRevision(String name){WorkflowInspector.Snapshot snapshot=inspector.snapshot(name);return snapshot==null?null:snapshot.revision();}
    /** Jira reads a status created without a description back as null while the request is normalized to ""; both sides compare as trimmed text. */
    static String normalizedDescription(String description){return description==null?"":description.trim();}
    static Object statusFacts(Status status){return status==null?null:map("id",status.getId(),"name",status.getName(),"description",normalizedDescription(status.getDescription()),"category",StatusCatalog.categoryOf(status));}
    private static Object resolutionFacts(Resolution resolution){return resolution==null?null:map("id",resolution.getId(),"name",resolution.getName(),"description",resolution.getDescription());}
    private static void refuseRequestedConditionShape(ActionDescriptor action,ConditionDescriptors.Candidate candidate){
        if(action.getRestriction()!=null) refuseConditionTree(action.getRestriction().getConditionsDescriptor(),candidate);
    }
    private static void refuseConditionTree(ConditionsDescriptor tree,ConditionDescriptors.Candidate candidate){
        if(tree==null || tree.getConditions()==null)return;
        for(Object child:tree.getConditions()) {
            if(child instanceof ConditionsDescriptor nested) refuseConditionTree(nested,candidate);
            else if(child instanceof ConditionDescriptor condition) {
                Object script=condition.getArgs().get("canned-script"), clazz=condition.getArgs().get("class.name");
                if((candidate.isGroup() && ConditionDescriptors.GROUP_CLASS.equals(clazz)) || (!candidate.isGroup() && ConditionDescriptors.ROLE_CANNED.equals(script))) {
                    if(!ConditionDescriptors.recognized(condition,candidate.isGroup())) fail("UNSUPPORTED_DESCRIPTOR","A requested condition template exists in an unrecognized captured form.");
                }
            }
        }
    }
    private static String functionId(int id,String rule){return UUID.nameUUIDFromBytes(("configure_workflow:"+id+":"+rule).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
    private static String key(String key,String label){nonblank(key,label);return key;}
    private static String nonblank(String value,String label){if(value==null || value.isBlank())fail("VALIDATION_ERROR",label+" must be nonblank.");return value.trim();}
    private static <T> List<T> list(List<T> values){return values==null?List.of():values;}
    private static void limit(List<?> list,int max,String label){if(list.size()>max)fail("VALIDATION_ERROR",label+" exceeds "+max+".");}
    private static void xmlText(String text){if(text!=null)for(int i=0;i<text.length();){int c=text.codePointAt(i);if(!(c==9 || c==10 || c==13 || (c>=32 && c<=0xD7FF) || (c>=0xE000 && c<=0xFFFD) || (c>=0x10000 && c<=0x10FFFF)))fail("VALIDATION_ERROR","Text contains a character not representable in XML.");i+=Character.charCount(c);}}
    private static void fail(String code,String message){throw new ConfigurationFailure(code,message);}
}
