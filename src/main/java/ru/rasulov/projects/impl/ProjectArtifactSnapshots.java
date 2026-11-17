package ru.rasulov.projects.impl;

import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.issue.fields.screen.issuetype.*;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.atlassian.jira.workflow.JiraWorkflow;
import org.ofbiz.core.entity.EntityFieldMap;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericValue;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Project-owned snapshots using the journal's structural workflow/screen data formats. */
final class ProjectArtifactSnapshots {
    private ProjectArtifactSnapshots() { }
    static Map<String,Object> value(Object... pairs) {
        Map<String,Object> value=new TreeMap<String,Object>();
        for(int i=0;i<pairs.length;i+=2)value.put((String)pairs[i],pairs[i+1]);
        return value;
    }
    static Map<String,Object> workflow(JiraWorkflow workflow,OfBizDelegator delegator) {
        StringWriter xml=new StringWriter();workflow.getDescriptor().writeXML(new PrintWriter(xml),0);
        List<GenericValue> rows=delegator.findByCondition("Workflow",
                new EntityFieldMap(Collections.<String,Object>singletonMap("name",workflow.getName()),EntityOperator.AND),
                Arrays.asList("id"),null);
        if(rows.size()!=1)throw new IllegalStateException("Workflow identity is ambiguous: "+workflow.getName());
        return value("id",rows.get(0).getLong("id"),"name",workflow.getName(),"description",workflow.getDescription(),
                "updatedDate",workflow.getUpdatedDate()==null?null:workflow.getUpdatedDate().getTime(),
                "author",workflow.getUpdateAuthor()==null?null:workflow.getUpdateAuthor().getKey(),"descriptorHash",hash(xml.toString()));
    }
    private static String hash(String text) {
        try {
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder(64);
            for(byte b:bytes)result.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
            return result.toString();
        } catch(Exception e) {throw new IllegalStateException("Cannot fingerprint template workflow",e);}
    }
    static Map<String,Object> status(Status status) {
        return value("id",status.getId(),"name",status.getName(),"description",status.getDescription(),
                "icon",status.getIconUrl(),"category",status.getStatusCategory()==null?null:status.getStatusCategory().getKey(),"sequence",status.getSequence());
    }
    static Map<String,Object> scheme(AssignableWorkflowScheme scheme) {
        Map<String,String> mappings=new TreeMap<String,String>();
        for(Map.Entry<String,String> entry:scheme.getMappings().entrySet())if(entry.getKey()!=null)mappings.put(entry.getKey(),entry.getValue());
        return value("id",scheme.getId(),"name",scheme.getName(),"description",scheme.getDescription(),
                "defaultWorkflow",scheme.getConfiguredDefaultWorkflow(),"mappings",mappings);
    }
    static Map<String,Object> screen(FieldScreen screen) {
        List<Map<String,Object>> tabs=new ArrayList<Map<String,Object>>();
        for(FieldScreenTab tab:screen.getTabs()) {
            List<Map<String,Object>> fields=new ArrayList<Map<String,Object>>();
            for(FieldScreenLayoutItem item:tab.getFieldScreenLayoutItems())fields.add(value("id",item.getId(),"field",item.getFieldId(),
                    "position",item.getPosition(),"shown",item.isShownWhenEmpty()));
            tabs.add(value("id",tab.getId(),"name",tab.getName(),"position",tab.getPosition(),"children",fields));
        }
        return value("type","screen","exists",true,"id",screen.getId(),"name",screen.getName(),"description",screen.getDescription(),"children",tabs);
    }
    static Map<String,Object> screenScheme(FieldScreenScheme scheme) {
        List<Map<String,Object>> children=new ArrayList<Map<String,Object>>();
        for(FieldScreenSchemeItem item:scheme.getFieldScreenSchemeItems())children.add(value("id",item.getId(),
                "operation",item.getIssueOperation()==null?null:item.getIssueOperation().getId(),"screen",item.getFieldScreenId()));
        children.sort(Comparator.comparingLong(child->((Number)child.get("id")).longValue()));
        return value("type","scheme","exists",true,"id",scheme.getId(),"name",scheme.getName(),"description",scheme.getDescription(),"children",children);
    }
    static Map<String,Object> top(IssueTypeScreenScheme scheme) {
        List<Map<String,Object>> children=new ArrayList<Map<String,Object>>();
        for(IssueTypeScreenSchemeEntity entity:scheme.getEntities())children.add(value("id",entity.getId(),
                "issueType",entity.getIssueTypeId(),"scheme",entity.getFieldScreenScheme().getId()));
        children.sort(Comparator.comparingLong(child->((Number)child.get("id")).longValue()));
        return value("type","top","exists",true,"id",scheme.getId(),"name",scheme.getName(),"description",scheme.getDescription(),"children",children);
    }
}
