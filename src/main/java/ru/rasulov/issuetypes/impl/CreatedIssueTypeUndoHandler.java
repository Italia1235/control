package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.user.ApplicationUser;
import ru.rasulov.common.impl.undo.UndoHandler;
import ru.rasulov.common.impl.undo.UndoStep;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

@Named
public class CreatedIssueTypeUndoHandler implements UndoHandler {
    private final IssueTypeUndoSupport support;
    @Inject public CreatedIssueTypeUndoHandler(IssueTypeUndoSupport support) { this.support = support; }
    public String kind() { return "issuetype.created"; }
    public List<String> lockNames(UndoStep step) {
        return Arrays.asList(IssueTypeCreatorImpl.LOCK_NAME,ProjectIssueTypeServiceImpl.LOCK_NAME,
                "ru.rasulov.control.create-project","ru.rasulov.control.assign-workflow",
                "ru.rasulov.control.create-workflow","ru.rasulov.control.screen-scheme-clone",
                "ru.rasulov.control.edit-screens");
    }
    public String conflict(UndoStep step) { return conflict(step,Collections.singletonList(step)); }
    @SuppressWarnings("unchecked") public String conflict(UndoStep step,List<UndoStep> selection) {
        Map<String,Object> before = support.read(step.getBeforeJson());
        Map<String,Object> after = support.read(step.getAfterJson());
        Map<String,Object> recorded = (Map<String,Object>) after.get("type");
        if (recorded == null) return "Issue type creation has no confirmed ID; its result is uncertain.";
        String id = (String) recorded.get("id");
        if (!Objects.equals(((Number) before.get("defaultScheme")).longValue(), support.schemes.getDefaultIssueTypeSchemeId()))
            return "Default issue type scheme changed.";
        IssueType current = support.types.getIssueType(id);
        if (current == null) {
            if(!support.members(support.schemes.getDefaultIssueTypeScheme()).equals(before.get("members")))
                return "Default issue type scheme was independently edited after deletion.";
            IssueType defaultType=support.schemes.getDefaultValue(support.schemes.getDefaultIssueTypeScheme().getOneAndOnlyConfig());
            String defaultId=defaultType==null?null:defaultType.getId();
            if(!Objects.equals(defaultId,before.get("defaultType")) && !(defaultId==null && id.equals(after.get("defaultType"))))
                return "Default issue type was independently changed after deletion.";
            return null;
        }
        if (!support.json(recorded).equals(support.json(support.type(current)))) return "Created issue type was edited.";
        if (support.types.hasAssociatedIssues(current)) {
            Set<Long> restoring = new HashSet<Long>();
            for (UndoStep selected : selection) if ("issuetype.issue-migration".equals(selected.getKind())
                    && !"UNDONE".equals(selected.getState()) && !"DISCARDED".equals(selected.getState()))
                for (Map<String,Object> row : support.rows(selected.getBeforeJson()))
                    if (!id.equals(row.get("type"))) restoring.add(((Number) row.get("id")).longValue());
            if (support.hasIssueOutside(id, restoring)) return "Created issue type is used by foreign issues.";
        }
        for (FieldConfigScheme scheme : support.schemes.getAllRelatedSchemes(id)) {
            if (!support.schemes.isDefaultIssueTypeScheme(scheme) && !removesReference(scheme.getId(),id,selection))
                return "Created issue type has a foreign scheme reference.";
        }
        String foreign=support.foreignReferences(id,selection);if(foreign!=null)return foreign;
        Map<String,Object> live = support.creationAfter(before, current);
        List<String> members = new ArrayList<String>((List<String>) live.get("members"));
        for (UndoStep selected : selection) {
            if("UNDONE".equals(selected.getState()) || "DISCARDED".equals(selected.getState()))continue;
            if (selected.getId() <= step.getId() || !"issuetype.created".equals(selected.getKind())) continue;
            Map<String,Object> value = support.read(selected.getAfterJson());
            Map<String,Object> own = (Map<String,Object>) value.get("type");
            if (own != null) members.remove(own.get("id"));
        }
        live.put("members",members);
        if (!support.json(after).equals(support.json(live))) return "Default issue type scheme was edited.";
        return null;
    }
    @SuppressWarnings("unchecked") private boolean removesReference(long schemeId,String typeId,List<UndoStep> selection) {
        for (UndoStep selected : selection) {
            if("UNDONE".equals(selected.getState()) || "DISCARDED".equals(selected.getState()))continue;
            if ("issuetype.scheme-created".equals(selected.getKind())) {
                Map<String,Object> after=support.read(selected.getAfterJson());
                if(after.get("id") instanceof Number && ((Number)after.get("id")).longValue()==schemeId)return true;
            }
            if ("issuetype.project-scheme".equals(selected.getKind())) {
                Map<String,Object> after=(Map<String,Object>)support.read(selected.getAfterJson()).get("scheme");
                Map<String,Object> before=(Map<String,Object>)support.read(selected.getBeforeJson()).get("scheme");
                if(((Number)after.get("id")).longValue()==schemeId && !((List<String>)before.get("members")).contains(typeId))return true;
            }
            if ("project.created".equals(selected.getKind())) {
                Map<String,Object> after=support.read(selected.getAfterJson());
                if(((Map<String,Object>)after.get("artifacts")).containsKey("type:"+schemeId))return true;
            }
        }
        return false;
    }
    @SuppressWarnings("unchecked") public void undo(UndoStep step, ApplicationUser actor) {
        String reason = conflict(step); if (reason != null) throw new IllegalStateException(reason);
        Map<String,Object> after = support.read(step.getAfterJson());
        String id = (String) ((Map<String,Object>) after.get("type")).get("id");
        Map<String,Object> before = support.read(step.getBeforeJson());
        // No issues are associated: Jira's native removal updates schemes/caches, never migrates foreign data.
        if(support.types.getIssueType(id)!=null)support.types.removeIssueType(id,null);
        FieldConfigScheme restored = support.schemes.update(support.schemes.getDefaultIssueTypeScheme(),
                (List<String>) before.get("members"));
        support.schemes.setDefaultValue(restored.getOneAndOnlyConfig(), (String) before.get("defaultType"));
        if (support.types.getIssueType(id) != null || !support.members(restored).equals(before.get("members")))
            throw new IllegalStateException("Jira did not restore the default scheme after issue type deletion.");
    }
}
