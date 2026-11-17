package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.WorkflowSchemeManager;
import ru.rasulov.common.impl.undo.UndoStep;
import org.ofbiz.core.entity.GenericValue;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.*;

/** The Jira reads shared by creation and its compensation, without journal dependencies. */
@Named
public class IssueTypeUndoSupport {
    final IssueTypeManager types;
    final IssueTypeSchemeManager schemes;
    private final OfBizDelegator delegator;
    private final WorkflowSchemeManager workflows;
    private final IssueTypeScreenSchemeManager screens;
    private final ObjectMapper mapper = new ObjectMapper();
    @Inject public IssueTypeUndoSupport(@ComponentImport IssueTypeManager types,
                                       @ComponentImport IssueTypeSchemeManager schemes,
                                       @ComponentImport OfBizDelegator delegator,
                                       @ComponentImport WorkflowSchemeManager workflows,
                                       @ComponentImport IssueTypeScreenSchemeManager screens) {
        this.types = types; this.schemes = schemes; this.delegator = delegator;
        this.workflows = workflows; this.screens = screens;
    }
    /** True if an issue of the type exists whose id is not in {@code allowed}; stops at the first one. */
    boolean hasIssueOutside(String typeId, Set<Long> allowed) {
        return ru.rasulov.common.impl.undo.NativeEntityScan.anyMatch(delegator, "Issue",
                new org.ofbiz.core.entity.EntityFieldMap(Collections.<String,Object>singletonMap("type", typeId),
                        org.ofbiz.core.entity.EntityOperator.AND),
                Collections.singletonList("id"), row -> !allowed.contains(row.getLong("id")));
    }
    String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (IOException e) { throw new IllegalStateException("Cannot serialize issue type journal", e); }
    }
    @SuppressWarnings("unchecked") Map<String,Object> read(String json) {
        try { return mapper.readValue(json, TreeMap.class); }
        catch (IOException e) { throw new IllegalStateException("Cannot read issue type journal", e); }
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> rows(String json) {
        return (List<Map<String,Object>>)read(json).get("issues");
    }
    Map<String,Object> type(IssueType type) {
        Map<String,Object> value = new TreeMap<String,Object>();
        value.put("id", type.getId()); value.put("name", type.getName());
        value.put("description", type.getDescription()); value.put("subtask", type.isSubTask());
        value.put("avatar", type.getAvatar() == null ? null : type.getAvatar().getId());
        return value;
    }
    List<String> members(FieldConfigScheme scheme) {
        List<String> ids = new ArrayList<String>();
        for (IssueType type : schemes.getIssueTypesForScheme(scheme)) ids.add(type.getId());
        Collections.sort(ids); return ids;
    }
    Map<String,Object> creationBefore(String name) {
        Map<String,Object> value = new TreeMap<String,Object>();
        value.put("name", name); value.put("type", null);
        value.put("defaultScheme", schemes.getDefaultIssueTypeSchemeId());
        value.put("members", members(schemes.getDefaultIssueTypeScheme()));
        IssueType type = schemes.getDefaultValue(schemes.getDefaultIssueTypeScheme().getOneAndOnlyConfig());
        value.put("defaultType", type == null ? null : type.getId()); return value;
    }
    Map<String,Object> creationAfter(Map<String,Object> before, IssueType created) {
        Map<String,Object> value = new TreeMap<String,Object>(before);
        value.put("type", type(created));
        value.put("members", members(schemes.getDefaultIssueTypeScheme()));
        IssueType type = schemes.getDefaultValue(schemes.getDefaultIssueTypeScheme().getOneAndOnlyConfig());
        value.put("defaultType", type == null ? null : type.getId()); return value;
    }
    @SuppressWarnings("unchecked") String foreignReferences(String id,List<UndoStep> selection) {
        for(AssignableWorkflowScheme scheme:workflows.getAssignableSchemes()) {
            com.atlassian.jira.workflow.DraftWorkflowScheme draft=workflows.getDraftForParent(scheme);
            if(draft!=null && draft.getMappings().containsKey(id))return "Created issue type has a foreign draft workflow scheme reference.";
            if(!scheme.getMappings().containsKey(id))continue;
            boolean removed=false;
            for(UndoStep selected:selection) {
                if("UNDONE".equals(selected.getState()) || "DISCARDED".equals(selected.getState()))continue;
                if(!Arrays.asList("workflow.scheme-created","workflow.scheme-mappings","project.created").contains(selected.getKind()))continue;
                Map<String,Object> after=read(selected.getAfterJson());
                if("workflow.scheme-created".equals(selected.getKind()) && number(after.get("id"))==scheme.getId())removed=true;
                if("workflow.scheme-mappings".equals(selected.getKind()) && number(after.get("id"))==scheme.getId()
                        && !((Map<String,Object>)read(selected.getBeforeJson()).get("mappings")).containsKey(id))removed=true;
                if("project.created".equals(selected.getKind()) && ((Map<String,Object>)after.get("artifacts")).containsKey("workflow-scheme:"+scheme.getId()))removed=true;
            }
            if(!removed || workflows.hasDraft(scheme))return "Created issue type has a foreign workflow scheme reference.";
        }
        for(IssueTypeScreenScheme scheme:screens.getIssueTypeScreenSchemes()) {
            if(!scheme.containsEntity(id))continue;
            boolean removed=false;
            for(UndoStep selected:selection) {
                if("UNDONE".equals(selected.getState()) || "DISCARDED".equals(selected.getState()))continue;
                if(!Arrays.asList("screen.change","project.created").contains(selected.getKind()))continue;
                Map<String,Object> after=read(selected.getAfterJson()),before=read(selected.getBeforeJson());
                if("project.created".equals(selected.getKind()) && ((Map<String,Object>)after.get("artifacts")).containsKey("top:"+scheme.getId()))removed=true;
                if("top".equals(after.get("type")) && number(after.get("id"))==scheme.getId() && !Boolean.TRUE.equals(before.get("exists")))removed=true;
                if("mapping".equals(after.get("type")) && number(after.get("parent"))==scheme.getId()
                        && id.equals(after.get("issueType")) && !Boolean.TRUE.equals(before.get("exists")))removed=true;
            }
            if(!removed)return "Created issue type has a foreign screen scheme reference.";
        }
        return null;
    }
    private static long number(Object value) { return value instanceof Number?((Number)value).longValue():-1L; }
}
