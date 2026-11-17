package ru.rasulov.workflow.impl.edit;

import com.atlassian.crowd.embedded.api.Group;
import com.atlassian.jira.bc.ServiceOutcome;
import com.atlassian.jira.bc.ServiceResult;
import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.config.ConstantsManager;
import com.atlassian.jira.config.StatusCategoryManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.status.category.StatusCategory;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.security.groups.GroupManager;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.WorkflowManager;
import com.atlassian.jira.workflow.WorkflowUtil;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.undo.UndoTestFixture;
import ru.rasulov.workflow.api.dto.StatusDefinition;
import ru.rasulov.workflow.api.dto.TransitionDefinition;
import ru.rasulov.workflow.api.dto.configure.*;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest.*;
import ru.rasulov.workflow.api.dto.edit.ScriptRunnerStatus;
import ru.rasulov.workflow.impl.*;
import ru.rasulov.workflow.impl.undo.WorkflowSnapshots;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real planner, native writer, preview cache and AO journal; only Jira's native stores are in-memory fakes. */
public final class WorkflowConfigurationFixture implements AutoCloseable {
    public final UndoTestFixture journalFixture=UndoTestFixture.create("admin");
    public final WorkflowManager manager=mock(WorkflowManager.class);
    public final WorkflowInspector inspector=mock(WorkflowInspector.class);
    public final FieldManager fields=mock(FieldManager.class);
    public final FieldScreenManager screens=mock(FieldScreenManager.class);
    public final FieldScreenFactory screenFactory=mock(FieldScreenFactory.class);
    public final ScriptRunnerGate gate=mock(ScriptRunnerGate.class);
    public final ValidatorFieldPlacement placement=mock(ValidatorFieldPlacement.class);
    public final StatusService statusService=mock(StatusService.class);
    public final ConstantsManager constants=mock(ConstantsManager.class);
    public final GroupManager groups=mock(GroupManager.class);
    public final ProjectRoleManager roles=mock(ProjectRoleManager.class);
    public final OfBizDelegator delegator=mock(OfBizDelegator.class);
    public final StatusManager statusManager=mock(StatusManager.class);
    public final StatusCategoryManager categoryManager=mock(StatusCategoryManager.class);
    public final StepOccupancy occupancy=mock(StepOccupancy.class);
    public final StatusCatalog catalog;
    public final WorkflowConfigurationPlanner planner;
    public final WorkflowConfigurationWriter writer;
    public final WorkflowConfigurerImpl configurer;
    public final Map<String,WorkflowDescriptor> main=new LinkedHashMap<>(),drafts=new LinkedHashMap<>();
    public final Set<String> active=new HashSet<>(),system=new HashSet<>();
    public final Map<Long,FieldScreen> screenStore=new LinkedHashMap<>();
    public final Map<String,Status> statusStore=new LinkedHashMap<>();
    public final List<String> nativeCalls=new ArrayList<>();
    public Consumer<String> beforeNative=key->{},afterNative=key->{};
    private final AtomicLong ids=new AtomicLong(100);
    private long saveTime=1700000000000L;
    private void stamp(WorkflowDescriptor descriptor) {
        descriptor.getMetaAttributes().put("jira.update.author.key","admin");
        descriptor.getMetaAttributes().put("jira.updated.date",Long.toString(++saveTime));
    }
    public WorkflowConfigurationFixture() {
        try {
            when(statusManager.getStatuses()).thenAnswer(c->new ArrayList<>(statusStore.values()));
            when(statusManager.getStatus(anyString())).thenAnswer(c->statusStore.get(c.getArgument(0)));
            when(constants.getStatus(anyString())).thenAnswer(c->statusStore.get(c.getArgument(0)));
            when(manager.getWorkflows()).thenAnswer(c->{List<JiraWorkflow> result=new ArrayList<>();for(String name:main.keySet())result.add(jw(name,copy(main.get(name)),false));return result;});
            when(manager.getWorkflow(anyString())).thenAnswer(c->{String name=c.getArgument(0);return main.containsKey(name)?jw(name,copy(main.get(name)),false):null;});
            when(manager.getWorkflowClone(anyString())).thenAnswer(c->{String name=c.getArgument(0);return main.containsKey(name)?jw(name,copy(main.get(name)),false):null;});
            when(manager.getDraftWorkflow(anyString())).thenAnswer(c->{String name=c.getArgument(0);return drafts.containsKey(name)?jw(name,copy(drafts.get(name)),true):null;});
            when(inspector.snapshot(anyString())).thenAnswer(c->{String name=c.getArgument(0);if(!main.containsKey(name))return null;return new WorkflowInspector.Snapshot(name,jw(name,copy(main.get(name)),false),drafts.containsKey(name)?jw(name,copy(drafts.get(name)),true):null,active.contains(name),system.contains(name),revision(name));});
            when(inspector.consumers(any())).thenReturn(new WorkflowInspector.Consumers(new TreeSet<>(),List.of(),new TreeMap<>()));
            WorkflowInspector referenceInspector=new WorkflowInspector(manager,null,constants,screens,delegator,journalFixture.guard);
            when(inspector.referenced(any(WorkflowDescriptor.class))).thenAnswer(c->referenceInspector.referenced(c.getArgument(0)));
            when(occupancy.occupied(anyString(),anySet())).thenReturn(Map.of());
            when(inspector.catalog()).thenReturn(new WorkflowDescriptorPatcher.Catalog() {
                public WorkflowDescriptorPatcher.StatusInfo status(String id) {
                    Status value=constants.getStatus(id);
                    return value==null?null:new WorkflowDescriptorPatcher.StatusInfo(id,value.getName(),value.getStatusCategory().getKey());
                }
                public boolean screenExists(long id) {return screens.getFieldScreen(id)!=null;}
                public WorkflowDescriptorPatcher.ResolutionInfo resolution(String id) {
                    Resolution value=constants.getResolution(id);
                    return value==null?null:new WorkflowDescriptorPatcher.ResolutionInfo(id,value.getName());
                }
            });
            when(placement.warnings(any(),any(),anyList(),any())).thenReturn(List.of());
            when(placement.facts(any(),anyList())).thenReturn(List.of());
            when(gate.status()).thenReturn(new ScriptRunnerStatus(true,true,"9.46.0",true,null));
            when(gate.conditionStatus()).thenReturn(new ScriptRunnerStatus(true,true,"9.46.0",true,null));
            when(manager.copyWorkflow(any(ApplicationUser.class),anyString(),any(),any(JiraWorkflow.class))).thenAnswer(c->{
                String name=c.getArgument(1);before("workflow-copy");WorkflowDescriptor descriptor=copy(((JiraWorkflow)c.getArgument(3)).getDescriptor());descriptor.getMetaAttributes().put("jira.description",c.getArgument(2));stamp(descriptor);main.put(name,descriptor);after("workflow-copy");return jw(name,copy(descriptor),false);
            });
            when(manager.createDraftWorkflow(any(ApplicationUser.class),anyString())).thenAnswer(c->{String name=c.getArgument(1);before("draft");drafts.put(name,copy(main.get(name)));after("draft");return jw(name,copy(drafts.get(name)),true);});
            doAnswer(c->{JiraWorkflow workflow=c.getArgument(1);before("workflow-save");WorkflowDescriptor descriptor=copy(workflow.getDescriptor());stamp(descriptor);(workflow.isDraftWorkflow()?drafts:main).put(workflow.getName(),descriptor);after("workflow-save");return null;}).when(manager).updateWorkflow(any(ApplicationUser.class),any(JiraWorkflow.class));
            when(screens.getFieldScreen(anyLong())).thenAnswer(c->screenStore.get(c.getArgument(0)));
            when(screens.getFieldScreens()).thenAnswer(c->new ArrayList<>(screenStore.values()));
            when(screenFactory.createScreen()).thenAnswer(c->newScreen(null,null,List.of()));
            doAnswer(c->{FieldScreen screen=c.getArgument(0);before("screen");screen.setId(ids.incrementAndGet());screenStore.put(screen.getId(),screen);after("screen");return null;}).when(screens).createFieldScreen(any(FieldScreen.class));
            ServiceResult valid=mock(ServiceResult.class);when(valid.isValid()).thenReturn(true);
            when(statusService.validateCreateStatus(any(),anyString(),anyString(),anyString(),any())).thenReturn(valid);
            when(statusService.createStatus(any(),anyString(),anyString(),anyString(),any())).thenAnswer(c->{before("status");String id=Long.toString(ids.incrementAndGet());StatusCategory category=c.getArgument(4);Status created=status(id,c.getArgument(1),category.getKey());when(created.getDescription()).thenReturn(c.getArgument(2));after("status");ServiceOutcome<Status> result=mock(ServiceOutcome.class);when(result.isValid()).thenReturn(true);when(result.getReturnedValue()).thenReturn(created);return result;});
            for(String category:List.of("new","indeterminate","done")){StatusCategory value=mock(StatusCategory.class);when(value.getKey()).thenReturn(category);when(categoryManager.getStatusCategoryByKey(category)).thenReturn(value);}
            GenericValue row=mock(GenericValue.class);when(row.getLong("id")).thenReturn(123L);
            when(delegator.findByCondition(eq("Workflow"),any(),any(),any())).thenReturn(List.of(row));
            status("1","Open","new");status("2","In Progress","indeterminate");status("3","Done","done");
            orderable("description","Description");orderable("assignee","Assignee");orderable("comment","Comment");
            Resolution resolution=mock(Resolution.class);when(resolution.getId()).thenReturn("1");when(resolution.getName()).thenReturn("Fixed");when(constants.getResolution("1")).thenReturn(resolution);
            Group group=mock(Group.class);when(group.getName()).thenReturn("admins");when(groups.getGroup("admins")).thenReturn(group);when(groups.getUsersInGroupCount(group)).thenReturn(1);
            ProjectRole role=mock(ProjectRole.class);when(role.getId()).thenReturn(10000L);when(role.getName()).thenReturn("Developers");when(roles.getProjectRole(10000L)).thenReturn(role);when(roles.getProjectRoles()).thenReturn(List.of(role));
            catalog=new StatusCatalog(statusManager,statusService,categoryManager);
            ActionPlanCodec codec=new ActionPlanCodec();
            planner=new WorkflowConfigurationPlanner(manager,inspector,new WorkflowRequestValidator(catalog,new TransitionScreenCatalog(screens),constants),new WorkflowDescriptorBuilder(),new ExistingWorkflowMatcher(),catalog,constants,fields,screens,new TransitionScreenCatalog(screens),groups,roles,gate,placement,codec,occupancy);
            writer=new WorkflowConfigurationWriter(manager,inspector,statusService,catalog,screenFactory,screens,delegator,journalFixture.journal);
            configurer=new WorkflowConfigurerImpl(journalFixture.guard,journalFixture.locks,planner,writer,new PreviewPlanExecutor(journalFixture.plans,codec),journalFixture.journal);
            WorkflowDescriptor template=WorkflowUtil.convertXMLtoWorkflowDescriptor("<?xml version=\"1.0\"?><!DOCTYPE workflow PUBLIC \"-//OpenSymphony Group//DTD OSWorkflow 2.8//EN\" \"http://www.opensymphony.com/osworkflow/workflow_2_8.dtd\"><workflow><initial-actions><action id=\"1\" name=\"Create\"><results><unconditional-result old-status=\"null\" status=\"open\" step=\"1\"/></results></action></initial-actions><steps><step id=\"1\" name=\"Open\"><meta name=\"jira.status.id\">1</meta></step></steps></workflow>");
            main.put(JiraWorkflow.DEFAULT_WORKFLOW_NAME,template);system.add(JiraWorkflow.DEFAULT_WORKFLOW_NAME);
        } catch(Exception e){journalFixture.close();throw new IllegalStateException(e);}
    }
    public ConfigureWorkflowResponse preview(ConfigureWorkflowRequest request){return configurer.configure(request);}
    public ConfigureWorkflowResponse apply(ConfigureWorkflowResponse preview){return configurer.configure(new ConfigureWorkflowRequest(false,null,null,null,null,preview.getPlanId()));}
    public ConfigureWorkflowRequest existing(String name,String version,List<TransitionRule> rules){return new ConfigureWorkflowRequest(true,null,new Existing(name,version,revision(name),null),null,rules,null);}
    public WorkflowDefinition createGraph(String name){return new WorkflowDefinition(name,"test workflow",List.of(new StatusDefinition("Open",null,null),new StatusDefinition("In Progress",null,null)),"Open",List.of(new TransitionDefinition("start","Start","Open",false,"In Progress",null,null)));}
    public void putWorkflow(String name,WorkflowDescriptor descriptor,boolean used){main.put(name,copy(descriptor));if(used)active.add(name);else active.remove(name);}
    /** Exposes a malformed native source only after the fixture has parsed a valid workflow. */
    public void mutateWorkflowSource(String name,Consumer<WorkflowDescriptor> mutation){
        mutation.accept(main.get(name));
        doAnswer(c->jw(name,main.get(name),false)).when(manager).getWorkflowClone(name);
        doAnswer(c->new WorkflowInspector.Snapshot(name,jw(name,main.get(name),false),null,active.contains(name),system.contains(name),revision(name))).when(inspector).snapshot(name);
    }
    public String revision(String name){return WorkflowSnapshots.hash(WorkflowSnapshots.json(Arrays.asList(name,active.contains(name),system.contains(name),main.containsKey(name)?DescriptorFacts.definition(main.get(name)):null,drafts.containsKey(name)?DescriptorFacts.definition(drafts.get(name)):null)));}
    public void orderable(String id,String name){OrderableField field=mock(OrderableField.class);when(field.getId()).thenReturn(id);when(field.getName()).thenReturn(name);when(fields.getField(id)).thenReturn(field);when(fields.getOrderableField(id)).thenReturn(field);}
    public Status status(String id,String name,String category){Status status=mock(Status.class);when(status.getId()).thenReturn(id);when(status.getName()).thenReturn(name);when(status.getDescription()).thenReturn("");StatusCategory value=mock(StatusCategory.class);when(value.getKey()).thenReturn(switch(category){case "TO_DO"->"new";case "IN_PROGRESS"->"indeterminate";case "DONE"->"done";default->category;});when(status.getStatusCategory()).thenReturn(value);statusStore.put(id,status);return status;}
    public FieldScreen screen(long id,String name,List<String> fields){FieldScreen screen=newScreen(id,name,fields);screenStore.put(id,screen);return screen;}
    private FieldScreen newScreen(Long initialId,String initialName,List<String> initialFields){
        FieldScreen screen=mock(FieldScreen.class);AtomicReference<Long> id=new AtomicReference<>(initialId);AtomicReference<String> name=new AtomicReference<>(initialName),description=new AtomicReference<>();List<FieldScreenTab> tabs=new ArrayList<>();
        when(screen.getId()).thenAnswer(c->id.get());doAnswer(c->{id.set(c.getArgument(0));return null;}).when(screen).setId(any());
        when(screen.getName()).thenAnswer(c->name.get());doAnswer(c->{name.set(c.getArgument(0));return null;}).when(screen).setName(any());
        when(screen.getDescription()).thenAnswer(c->description.get());doAnswer(c->{description.set(c.getArgument(0));return null;}).when(screen).setDescription(any());
        when(screen.getTabs()).thenAnswer(c->new ArrayList<>(tabs));
        when(screen.containsField(anyString())).thenAnswer(c->{for(FieldScreenTab tab:tabs)for(FieldScreenLayoutItem item:tab.getFieldScreenLayoutItems())if(item.getFieldId().equals(c.getArgument(0)))return true;return false;});
        when(screen.addTab(anyString())).thenAnswer(c->{before("tab");FieldScreenTab tab=tab(c.getArgument(0),List.of());tabs.add(tab);after("tab");return tab;});
        if(initialId!=null)tabs.add(tab("Field Tab",initialFields));return screen;
    }
    private FieldScreenTab tab(String name,List<String> initial){FieldScreenTab tab=mock(FieldScreenTab.class);when(tab.getId()).thenReturn(ids.incrementAndGet());when(tab.getName()).thenReturn(name);List<FieldScreenLayoutItem> fields=new ArrayList<>();for(String id:initial)fields.add(item(id));when(tab.getFieldScreenLayoutItems()).thenAnswer(c->new ArrayList<>(fields));doAnswer(c->{before("field");fields.add(item(c.getArgument(0)));after("field");return null;}).when(tab).addFieldScreenLayoutItem(anyString());return tab;}
    private FieldScreenLayoutItem item(String id){FieldScreenLayoutItem item=mock(FieldScreenLayoutItem.class);when(item.getFieldId()).thenReturn(id);return item;}
    private void before(String key){beforeNative.accept(key);nativeCalls.add(key);}
    private void after(String key){afterNative.accept(key);}
    private JiraWorkflow jw(String name,WorkflowDescriptor descriptor,boolean draft){JiraWorkflow workflow=mock(JiraWorkflow.class);when(workflow.getName()).thenReturn(name);when(workflow.getDescription()).thenReturn((String)descriptor.getMetaAttributes().get("jira.description"));when(workflow.getDescriptor()).thenReturn(descriptor);when(workflow.isDraftWorkflow()).thenReturn(draft);return workflow;}
    public static WorkflowDescriptor copy(WorkflowDescriptor descriptor){try{return WorkflowUtil.convertXMLtoWorkflowDescriptor(WorkflowUtil.convertDescriptorToXML(descriptor));}catch(Exception e){throw new IllegalStateException(e);}}
    @Override public void close(){journalFixture.close();}
}
