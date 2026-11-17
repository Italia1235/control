package ru.rasulov.projects.impl;

import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.IssueManager;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.issue.fields.screen.issuetype.*;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.permission.PermissionSchemeManager;
import com.atlassian.jira.notification.NotificationSchemeManager;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.scheme.Scheme;
import com.atlassian.jira.scheme.SchemeManager;
import com.atlassian.jira.security.roles.ProjectRoleManager;
import com.atlassian.jira.security.roles.ProjectRole;
import com.atlassian.jira.security.roles.RoleActor;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.workflow.*;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.impl.undo.UndoStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.ActionPlanCodec;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.*;

/** Template creation is opaque; only new artifacts reachable from its returned project are owned. */
@Named
public class ProjectUndoSupport {
    final ProjectManager projects;
    private final ProjectService service;
    private final IssueManager issues;
    private final IssueTypeSchemeManager types;
    private final WorkflowSchemeManager workflowSchemes;
    private final WorkflowManager workflows;
    private final IssueTypeScreenSchemeManager topScreens;
    private final FieldScreenSchemeManager screenSchemes;
    private final FieldScreenManager screens;
    private final PermissionSchemeManager permissions;
    private final NotificationSchemeManager notifications;
    private final ProjectRoleManager roles;
    private final OfBizDelegator delegator;
    private final ActionPlanCodec codec;
    private final IssueTypeManager issueTypes;
    private final StatusManager statuses;
    private final ObjectMapper mapper = new ObjectMapper();
    @Inject public ProjectUndoSupport(@ComponentImport ProjectManager projects, @ComponentImport ProjectService service,
            @ComponentImport IssueManager issues, @ComponentImport IssueTypeSchemeManager types,
            @ComponentImport WorkflowSchemeManager workflowSchemes, @ComponentImport WorkflowManager workflows,
            @ComponentImport IssueTypeScreenSchemeManager topScreens, @ComponentImport FieldScreenSchemeManager screenSchemes,
            @ComponentImport FieldScreenManager screens, @ComponentImport PermissionSchemeManager permissions,
            @ComponentImport NotificationSchemeManager notifications, @ComponentImport OfBizDelegator delegator,
            @ComponentImport ProjectRoleManager roles, ActionPlanCodec codec,
            @ComponentImport IssueTypeManager issueTypes, @ComponentImport StatusManager statuses) {
        this.projects=projects; this.service=service; this.issues=issues; this.types=types;
        this.workflowSchemes=workflowSchemes; this.workflows=workflows; this.topScreens=topScreens;
        this.screenSchemes=screenSchemes; this.screens=screens; this.permissions=permissions;
        this.notifications=notifications; this.delegator=delegator; this.codec=codec;
        this.roles=roles;
        this.issueTypes=issueTypes; this.statuses=statuses;
    }
    String json(Object value) { return codec.request(value); }
    @SuppressWarnings("unchecked") Map<String,Object> read(String value) {
        try { return mapper.readValue(value, TreeMap.class); }
        catch(IOException e) { throw new IllegalStateException("Cannot read project journal", e); }
    }
    Map<String,Object> before(String key) {
        Map<String,Object> value = new TreeMap<String,Object>(); value.put("key", key); value.put("project", null);
        Set<String> existing = new TreeSet<String>();
        for (IssueType x:issueTypes.getIssueTypes()) existing.add("issue-type:"+x.getId());
        for (Status x:statuses.getStatuses()) existing.add("status:"+x.getId());
        for (FieldConfigScheme x:types.getAllSchemes()) existing.add("type:"+x.getId());
        for (AssignableWorkflowScheme x:workflowSchemes.getAssignableSchemes()) existing.add("workflow-scheme:"+x.getId());
        for (JiraWorkflow x:workflows.getWorkflows()) existing.add("workflow:"+x.getName());
        for (IssueTypeScreenScheme x:topScreens.getIssueTypeScreenSchemes()) existing.add("top:"+x.getId());
        for (FieldScreenScheme x:screenSchemes.getFieldScreenSchemes()) existing.add("screen-scheme:"+x.getId());
        for (FieldScreen x:screens.getFieldScreens()) existing.add("screen:"+x.getId());
        for (Scheme x:permissions.getSchemeObjects()) existing.add("permission:"+x.getId());
        for (Scheme x:notifications.getSchemeObjects()) existing.add("notification:"+x.getId());
        IssueType defaultType=types.getDefaultValue(types.getDefaultIssueTypeScheme().getOneAndOnlyConfig());
        value.put("defaultIssueType",defaultType==null?null:defaultType.getId());
        value.put("existing", existing); value.put("artifacts", Collections.emptyMap()); return value;
    }
    Map<String,Object> intent(Map<String,Object> preparation) {
        Map<String,Object> value = new TreeMap<String,Object>(preparation);
        value.remove("existing"); return value;
    }
    @SuppressWarnings("unchecked") Map<String,Object> after(Map<String,Object> before, Project project) {
        Map<String,Object> value = new TreeMap<String,Object>(before); value.put("project", project(project));
        Set<String> existing = new HashSet<String>((Collection<String>)before.get("existing"));
        Map<String,Object> owned = new TreeMap<String,Object>();
        FieldConfigScheme type = types.getConfigScheme(project); add(owned,existing,"type:"+type.getId(),type(type));
        for(IssueType x:types.getIssueTypesForScheme(type))add(owned,existing,"issue-type:"+x.getId(),issueType(x));
        AssignableWorkflowScheme scheme=workflowSchemes.getWorkflowSchemeObj(project);
        add(owned,existing,"workflow-scheme:"+scheme.getId(),workflowScheme(scheme));
        Set<String> names = new HashSet<String>(scheme.getMappings().values()); names.add(scheme.getConfiguredDefaultWorkflow());
        for(String name:names) if(name!=null) {
            JiraWorkflow workflow=workflows.getWorkflow(name);
            add(owned,existing,"workflow:"+name,workflow(workflow));
            for(Status status:workflow.getLinkedStatusObjects())add(owned,existing,"status:"+status.getId(),ProjectArtifactSnapshots.status(status));
        }
        IssueTypeScreenScheme top=topScreens.getIssueTypeScreenScheme(project); add(owned,existing,"top:"+top.getId(),top(top));
        for(IssueTypeScreenSchemeEntity entity:top.getEntities()) {
            FieldScreenScheme fs=entity.getFieldScreenScheme(); add(owned,existing,"screen-scheme:"+fs.getId(),screenScheme(fs));
            for(FieldScreenSchemeItem item:fs.getFieldScreenSchemeItems()) {
                FieldScreen screen=item.getFieldScreen(); add(owned,existing,"screen:"+screen.getId(),screen(screen));
            }
        }
        Scheme permission=permissions.getSchemeFor(project); if(permission!=null) add(owned,existing,"permission:"+permission.getId(),scheme(permissions,permission));
        Scheme notification=notifications.getSchemeFor(project); if(notification!=null) add(owned,existing,"notification:"+notification.getId(),scheme(notifications,notification));
        IssueType defaultType=types.getDefaultValue(types.getDefaultIssueTypeScheme().getOneAndOnlyConfig());
        value.put("defaultIssueTypeAfter",defaultType==null?null:defaultType.getId());
        value.put("artifacts",owned); value.remove("existing"); return value;
    }
    private void add(Map<String,Object> values,Set<String> existing,String key,Object snapshot) { if(!existing.contains(key)) values.put(key,snapshot); }
    Map<String,Object> project(Project p) {
        Map<String,Object> value=new TreeMap<String,Object>();
        value.put("id",p.getId());value.put("key",p.getKey());value.put("name",p.getName());value.put("lead",p.getLeadUserKey());
        value.put("description",p.getDescription());value.put("url",p.getUrl());value.put("email",p.getEmail());
        value.put("assignee",p.getAssigneeType());value.put("type",p.getProjectTypeKey()==null?null:p.getProjectTypeKey().getKey());
        value.put("category",p.getProjectCategory()==null?null:p.getProjectCategory().getId());
        value.put("avatar",p.getAvatar()==null?null:p.getAvatar().getId());value.put("archived",p.isArchived());
        value.put("typeScheme",types.getConfigScheme(p).getId()); value.put("workflowScheme",workflowSchemes.getWorkflowSchemeObj(p).getId());
        value.put("screenScheme",topScreens.getIssueTypeScreenScheme(p).getId());
        value.put("permission",permissions.getSchemeIdFor(p));value.put("notification",notifications.getSchemeIdFor(p));
        value.put("row", new TreeMap<String,Object>(p.getGenericValue().getAllFields()));
        Map<String,Object> roleActors = new TreeMap<String,Object>();
        for (ProjectRole role : roles.getProjectRoles()) {
            List<String> actors = new ArrayList<String>();
            for (RoleActor actor : roles.getProjectRoleActors(role,p).getRoleActors())
                actors.add(actor.getId()+":"+actor.getType()+":"+actor.getParameter());
            Collections.sort(actors); if(!actors.isEmpty()) roleActors.put(role.getId().toString(),actors);
        }
        value.put("roles",roleActors);
        return value;
    }
    private Map<String,Object> base(Long id,String name,String description) {
        Map<String,Object> value=new TreeMap<String,Object>();value.put("id",id);value.put("name",name);value.put("description",description);return value;
    }
    private Object type(FieldConfigScheme x) {
        Map<String,Object> value=base(x.getId(),x.getName(),x.getDescription());List<String> ids=new ArrayList<String>();
        for(IssueType t:types.getIssueTypesForScheme(x))ids.add(t.getId());Collections.sort(ids);value.put("members",ids);
        IssueType defaultType=types.getDefaultValue(x.getOneAndOnlyConfig());value.put("default",defaultType==null?null:defaultType.getId());return value;
    }
    private Object workflowScheme(AssignableWorkflowScheme x) { return ProjectArtifactSnapshots.scheme(x); }
    private Object workflow(JiraWorkflow x) { return ProjectArtifactSnapshots.workflow(x,delegator); }
    private Object top(IssueTypeScreenScheme x) { return ProjectArtifactSnapshots.top(x); }
    private Object screenScheme(FieldScreenScheme x) { return ProjectArtifactSnapshots.screenScheme(x); }
    private Object screen(FieldScreen x) { return ProjectArtifactSnapshots.screen(x); }
    private Object issueType(IssueType x) {
        Map<String,Object> value=new TreeMap<String,Object>();value.put("id",x.getId());value.put("name",x.getName());
        value.put("description",x.getDescription());value.put("subtask",x.isSubTask());
        value.put("avatar",x.getAvatar()==null?null:x.getAvatar().getId());return value;
    }
    private Object scheme(SchemeManager manager,Scheme scheme) {
        try { List<Object> entities=new ArrayList<Object>();for(GenericValue e:manager.getEntities(manager.getScheme(scheme.getId())))entities.add(new TreeMap<String,Object>(e.getAllFields()));
            Collections.sort(entities,Comparator.comparing(this::json));return Arrays.asList(scheme.getId(),scheme.getName(),scheme.getDescription(),entities);
        } catch(Exception e) {throw new IllegalStateException("Cannot read project scheme",e);}
    }
    Object artifact(String key) {
        String[] pair=key.split(":",2); long id="workflow".equals(pair[0])?0:Long.parseLong(pair[1]);
        switch(pair[0]) {
            case "issue-type":IssueType issueType=issueTypes.getIssueType(pair[1]);return issueType==null?null:issueType(issueType);
            case "status":Status status=statuses.getStatus(pair[1]);return status==null?null:ProjectArtifactSnapshots.status(status);
            case "type":for(FieldConfigScheme x:types.getAllSchemes())if(x.getId()==id)return type(x);return null;
            case "workflow-scheme":AssignableWorkflowScheme ws=workflowSchemes.getWorkflowSchemeObj(id);return ws==null?null:workflowScheme(ws);
            case "workflow":JiraWorkflow wf=workflows.getWorkflow(pair[1]);return wf==null?null:workflow(wf);
            case "top":IssueTypeScreenScheme ts=topScreens.getIssueTypeScreenScheme(id);return ts==null?null:top(ts);
            case "screen-scheme":FieldScreenScheme fs=screenSchemes.getFieldScreenScheme(id);return fs==null?null:screenScheme(fs);
            case "screen":FieldScreen s=screens.getFieldScreen(id);return s==null?null:screen(s);
            case "permission":Scheme ps=permissions.getSchemeObject(id);return ps==null?null:scheme(permissions,ps);
            case "notification":Scheme ns=notifications.getSchemeObject(id);return ns==null?null:scheme(notifications,ns);
            default:throw new IllegalStateException("Unknown project template artifact "+key);
        }
    }
    String conflict(Map<String,Object> after) { return conflict(after, Long.MAX_VALUE, Collections.emptyList()); }
    @SuppressWarnings("unchecked") String conflict(Map<String,Object> after,long stepId,List<UndoStep> selection) {
        Map<String,Object> recorded=(Map<String,Object>)after.get("project");
        // The type comes from the journal's own after-snapshot, never from the project as Jira shows it now.
        if(recorded!=null && ProjectTemplates.SOFTWARE.equals(recorded.get("type")))
            return "Undo is refused for a Software project: the Software board and filter do not belong to the Control journal. Nothing was deleted.";
        if(recorded==null)return "Project creation has no confirmed ID; template effects are uncertain.";
        Project p=projects.getProjectObj(((Number)recorded.get("id")).longValue());
        Map<String,Object> artifacts=(Map<String,Object>)after.get("artifacts");
        Set<String> removable=new HashSet<String>(artifacts.keySet());
        Set<Long> removedProjects=new HashSet<Long>();
        for(UndoStep selected:selection) {
            if("UNDONE".equals(selected.getState()) || "DISCARDED".equals(selected.getState()))continue;
            if(selected.getId()<=stepId || !Arrays.asList("workflow.scheme-created","workflow.created","issuetype.scheme-created","screen.change","project.created").contains(selected.getKind()))continue;
            Map<String,Object> post=read(selected.getAfterJson());
            if("workflow.scheme-created".equals(selected.getKind()) && post.get("id")!=null)removable.add("workflow-scheme:"+post.get("id"));
            if("workflow.created".equals(selected.getKind()) && post.get("name")!=null)removable.add("workflow:"+post.get("name"));
            if("issuetype.scheme-created".equals(selected.getKind()) && post.get("id")!=null)removable.add("type:"+post.get("id"));
            if("screen.change".equals(selected.getKind()) && !Boolean.TRUE.equals(read(selected.getBeforeJson()).get("exists"))) {
                String kind=(String)post.get("type"); if("scheme".equals(kind))kind="screen-scheme";
                if(Arrays.asList("top","screen","screen-scheme").contains(kind))removable.add(kind+":"+post.get("id"));
            }
            if("project.created".equals(selected.getKind()) && post.get("project") instanceof Map)
                removedProjects.add(number(((Map<String,Object>)post.get("project")).get("id")));
        }
        if(artifacts.keySet().stream().anyMatch(key->key.startsWith("issue-type:"))) {
            IssueType currentDefault=types.getDefaultValue(types.getDefaultIssueTypeScheme().getOneAndOnlyConfig());
            Object projectedDefault=currentDefault==null?null:currentDefault.getId();
            for(UndoStep selected:selection) {
                if("UNDONE".equals(selected.getState()) || "DISCARDED".equals(selected.getState()))continue;
                if(selected.getId()<=stepId)continue;
                if("issuetype.created".equals(selected.getKind()))projectedDefault=read(selected.getBeforeJson()).get("defaultType");
                if("project.created".equals(selected.getKind()))projectedDefault=read(selected.getBeforeJson()).get("defaultIssueType");
            }
            Object expectedDefault=after.get("defaultIssueTypeAfter");
            boolean removedDefault=expectedDefault!=null && artifacts.containsKey("issue-type:"+expectedDefault)
                    && issueTypes.getIssueType((String)expectedDefault)==null && projectedDefault==null;
            if(!Objects.equals(expectedDefault,projectedDefault) && !Objects.equals(after.get("defaultIssueType"),projectedDefault)
                    && !removedDefault)return "Default issue type was independently changed.";
            if(after.get("defaultIssueType")!=null && issueTypes.getIssueType((String)after.get("defaultIssueType"))==null)
                return "Original default issue type no longer exists.";
        }
        if(p!=null) {
            Map<String,Object> live=project(p);
            for(UndoStep selected:selection) {
                if("UNDONE".equals(selected.getState()) || "DISCARDED".equals(selected.getState()))continue;
                if(selected.getId()<=stepId || !Arrays.asList("issuetype.project-scheme","workflow.project-binding","screen.change").contains(selected.getKind()))continue;
                Map<String,Object> prior=read(selected.getBeforeJson());
                if("issuetype.project-scheme".equals(selected.getKind()) && number(prior.get("project"))==p.getId())
                    live.put("typeScheme",((Map<String,Object>)prior.get("scheme")).get("id"));
                if("workflow.project-binding".equals(selected.getKind()) && number(prior.get("projectId"))==p.getId())
                    live.put("workflowScheme",((Map<String,Object>)prior.get("scheme")).get("id"));
                if("screen.change".equals(selected.getKind()) && "association".equals(prior.get("type")) && number(prior.get("id"))==p.getId())
                    live.put("screenScheme",prior.get("scheme"));
            }
            if(!json(recorded).equals(json(live)))return "Created project was edited or its schemes changed.";
            if(issues.getIssueCountForProject(p.getId())!=0 || !p.getProjectComponents().isEmpty() || !p.getVersions().isEmpty())
                return "Created project contains issues, components or versions.";
        }
        for(String key:artifacts.keySet()) {
            Object current=projectedArtifact(key,stepId,selection);if(current==null)continue;
            if(!json(current).equals(json(artifacts.get(key))))return "Template artifact was edited: "+key;
            String reason=references(key,p,removable,removedProjects);if(reason!=null)return reason;
        }
        return null;
    }
    private static long number(Object value) { return value instanceof Number?((Number)value).longValue():-1L; }
    @SuppressWarnings("unchecked") private Object projectedArtifact(String key,long stepId,List<UndoStep> selection) {
        Object current=artifact(key); if(current==null)return null;
        String[] pair=key.split(":",2);
        if("workflow-scheme".equals(pair[0])) {
            Map<String,Object> projected=new TreeMap<String,Object>((Map<String,Object>)current);
            for(UndoStep selected:selection) {
                if("UNDONE".equals(selected.getState()) || "DISCARDED".equals(selected.getState()))continue;
                if(selected.getId()<=stepId || !"workflow.scheme-mappings".equals(selected.getKind()))continue;
                Map<String,Object> post=read(selected.getAfterJson());post.remove("users");
                if(number(post.get("id"))!=Long.parseLong(pair[1]))continue;
                if(!json(projected).equals(json(post)))return Collections.singletonMap("conflict",true);
                projected=read(selected.getBeforeJson());projected.remove("users");
            }
            return projected;
        }
        for(UndoStep selected:selection) {
            if("UNDONE".equals(selected.getState()) || "DISCARDED".equals(selected.getState()))continue;
            if(selected.getId()<=stepId)continue;
            if("issuetype.project-scheme".equals(selected.getKind()) && "type".equals(pair[0])) {
                Map<String,Object> after=(Map<String,Object>)read(selected.getAfterJson()).get("scheme");
                if(number(after.get("id"))==Long.parseLong(pair[1])) {
                    Map<String,Object> prior=(Map<String,Object>)read(selected.getBeforeJson()).get("scheme");
                    Map<String,Object> normalized=base(((Number)prior.get("id")).longValue(),(String)prior.get("name"),(String)prior.get("description"));
                    normalized.put("members",prior.get("members"));normalized.put("default",prior.get("defaultType"));current=normalized;
                }
            }
            if(!"screen.change".equals(selected.getKind()))continue;
            Map<String,Object> prior=read(selected.getBeforeJson()), post=read(selected.getAfterJson());
            String target="top".equals(pair[0])?"top":"screen-scheme".equals(pair[0])?"scheme":"screen".equals(pair[0])?"screen":null;
            if(target!=null && target.equals(post.get("type")) && number(post.get("id"))==Long.parseLong(pair[1]))current=prior;
            if("top".equals(pair[0]) && "mapping".equals(post.get("type")) && number(post.get("parent"))==Long.parseLong(pair[1])) {
                Map<String,Object> copy=new TreeMap<String,Object>((Map<String,Object>)current);
                List<Map<String,Object>> children=new ArrayList<Map<String,Object>>((List<Map<String,Object>>)copy.get("children"));
                children.removeIf(child -> Objects.equals(child.get("issueType"),post.get("issueType")));
                if(Boolean.TRUE.equals(prior.get("exists"))) {
                    Map<String,Object> child=new TreeMap<String,Object>();child.put("id",prior.get("id"));
                    child.put("issueType",prior.get("issueType"));child.put("scheme",prior.get("scheme"));children.add(child);
                }
                children.sort(Comparator.comparingLong(child -> number(child.get("id"))));copy.put("children",children);current=copy;
            }
        }
        return current;
    }
    private String references(String key,Project project,Set<String> owned) {
        return references(key,project,owned,Collections.emptySet());
    }
    private boolean foreign(Long id,Project project,Set<Long> removed) {
        return (project==null || !id.equals(project.getId())) && !removed.contains(id);
    }
    private String references(String key,Project project,Set<String> owned,Set<Long> removed) {
        String[] pair=key.split(":",2);long id="workflow".equals(pair[0])?0:Long.parseLong(pair[1]);
        switch(pair[0]) {
            case "issue-type":
                if(issueTypes.hasAssociatedIssues(issueTypes.getIssueType(pair[1])))return "Template-created issue type is used by issues.";
                for(FieldConfigScheme scheme:types.getAllRelatedSchemes(pair[1]))
                    if(!types.isDefaultIssueTypeScheme(scheme)&&!owned.contains("type:"+scheme.getId()))return "Template-created issue type has a foreign scheme reference.";
                for(AssignableWorkflowScheme scheme:workflowSchemes.getAssignableSchemes()) {
                    if(scheme.getMappings().containsKey(pair[1])&&!owned.contains("workflow-scheme:"+scheme.getId()))return "Template-created issue type has a foreign workflow scheme reference.";
                    DraftWorkflowScheme draft=workflowSchemes.getDraftForParent(scheme);
                    if(draft!=null && draft.getMappings().containsKey(pair[1]))return "Template-created issue type has a foreign draft workflow scheme reference.";
                }
                for(IssueTypeScreenScheme scheme:topScreens.getIssueTypeScreenSchemes())
                    if(scheme.containsEntity(pair[1])&&!owned.contains("top:"+scheme.getId()))return "Template-created issue type has a foreign screen scheme reference.";
                break;
            case "status":
                if(!delegator.findByAnd("Issue",Collections.<String,Object>singletonMap("status",pair[1])).isEmpty())return "Template-created status is used by issues.";
                for(JiraWorkflow workflow:workflows.getWorkflowsIncludingDrafts())
                    if(workflow.getLinkedStatusIds().contains(pair[1])&&!owned.contains("workflow:"+workflow.getName()))return "Template-created status has a foreign workflow reference.";
                break;
            case "type":for(Project p:projects.getProjectObjects())if(foreign(p.getId(),project,removed)&&types.getConfigScheme(p).getId()==id)return "Template issue type scheme has a foreign project reference.";break;
            case "workflow-scheme":AssignableWorkflowScheme s=workflowSchemes.getWorkflowSchemeObj(id);
                if(workflowSchemes.hasDraft(s))return "Template workflow scheme has a draft.";
                for(Project p:workflowSchemes.getProjectsUsing(s))if(foreign(p.getId(),project,removed))return "Template workflow scheme has a foreign project reference.";break;
            case "workflow":JiraWorkflow w=workflows.getWorkflow(pair[1]);if(workflows.getDraftWorkflow(pair[1])!=null)return "Template workflow has a draft.";
                for(WorkflowScheme scheme:workflowSchemes.getSchemesForWorkflowIncludingDrafts(w))if(!owned.contains("workflow-scheme:"+scheme.getId()))return "Template workflow has a foreign scheme reference.";break;
            case "top":for(GenericValue p:topScreens.getProjects(topScreens.getIssueTypeScreenScheme(id)))if(foreign(p.getLong("id"),project,removed))return "Template screen scheme has a foreign project reference.";break;
            case "screen-scheme":for(Object top:topScreens.getIssueTypeScreenSchemes(screenSchemes.getFieldScreenScheme(id)))if(!owned.contains("top:"+((IssueTypeScreenScheme)top).getId()))return "Template screen scheme has a foreign reference.";break;
            case "screen":for(FieldScreenScheme scheme:screenSchemes.getFieldScreenSchemes(screens.getFieldScreen(id)))if(!owned.contains("screen-scheme:"+scheme.getId()))return "Template screen has a foreign scheme reference.";
                for(JiraWorkflow wf:workflows.getWorkflowsIncludingDrafts())
                    if(!owned.contains("workflow:"+wf.getName()) && !wf.getActionsForScreen(screens.getFieldScreen(id)).isEmpty())
                        return "Template screen has a foreign workflow reference.";break;
            case "permission":for(Project p:permissions.getProjects(permissions.getSchemeObject(id)))if(foreign(p.getId(),project,removed))return "Template permission scheme has a foreign reference.";break;
            case "notification":for(Project p:notifications.getProjects(notifications.getSchemeObject(id)))if(foreign(p.getId(),project,removed))return "Template notification scheme has a foreign reference.";break;
            default:break;
        }
        return null;
    }
    @SuppressWarnings("unchecked") void undo(Map<String,Object> after,ApplicationUser actor) {
        String reason=conflict(after);if(reason!=null)throw new IllegalStateException(reason);
        Map<String,Object> recorded=(Map<String,Object>)after.get("project");Project p=projects.getProjectObj(((Number)recorded.get("id")).longValue());
        if(p!=null) {
            ProjectService.DeleteProjectValidationResult validation=service.validateDeleteProject(actor,p.getKey());
            if(!validation.isValid())throw new IllegalStateException("Jira refused project deletion: "+validation.getErrorCollection());
            service.deleteProject(actor,validation);
            if(projects.getProjectObj(p.getId())!=null)throw new IllegalStateException("Jira did not delete the project.");
        }
        Map<String,Object> artifacts=(Map<String,Object>)after.get("artifacts");
        for(String kind:Arrays.asList("workflow-scheme","type","top","screen-scheme","workflow","screen","permission","notification","issue-type","status"))
            for(String key:artifacts.keySet())if(key.startsWith(kind+":")) {
                if(artifact(key)==null)continue;
                reason=references(key,null,artifacts.keySet());if(reason!=null)throw new IllegalStateException(reason);
                delete(key);
                if(artifact(key)!=null)throw new IllegalStateException("Jira did not remove template artifact "+key);
            }
        if(artifacts.keySet().stream().anyMatch(key->key.startsWith("issue-type:")))
            types.setDefaultValue(types.getDefaultIssueTypeScheme().getOneAndOnlyConfig(),(String)after.get("defaultIssueType"));
    }
    private void delete(String key) {
        String[] pair=key.split(":",2);long id="workflow".equals(pair[0])?0:Long.parseLong(pair[1]);
        try {switch(pair[0]) {
            case "issue-type":issueTypes.removeIssueType(pair[1],null);break;
            case "status":statuses.removeStatus(pair[1]);break;
            case "workflow-scheme":if(!workflowSchemes.deleteWorkflowScheme(workflowSchemes.getWorkflowSchemeObj(id)))throw new IllegalStateException("Jira refused workflow scheme deletion.");break;
            case "type":for(FieldConfigScheme x:types.getAllSchemes())if(x.getId()==id){types.deleteScheme(x);break;}break;
            case "top":
                IssueTypeScreenScheme top=topScreens.getIssueTypeScreenScheme(id);
                topScreens.removeIssueTypeSchemeEntities(top);
                topScreens.removeIssueTypeScreenScheme(top);break;
            case "screen-scheme":
                FieldScreenScheme child=screenSchemes.getFieldScreenScheme(id);
                screenSchemes.removeFieldSchemeItems(child);
                screenSchemes.removeFieldScreenScheme(child);break;
            case "workflow":workflows.deleteWorkflow(workflows.getWorkflow(pair[1]));break;
            case "screen":screens.removeFieldScreen(id);break;
            case "permission":permissions.deleteScheme(id);break;
            case "notification":notifications.deleteScheme(id);break;
            default:throw new IllegalStateException("Unknown artifact "+key);
        }}catch(Exception e){throw new IllegalStateException("Cannot remove project template artifact "+key,e);}
    }
}
