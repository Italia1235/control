package ru.rasulov.workflow.impl.undo;

import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.jira.workflow.JiraWorkflow;
import com.atlassian.jira.workflow.AssignableWorkflowScheme;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ofbiz.core.entity.EntityFieldMap;
import org.ofbiz.core.entity.EntityOperator;
import org.ofbiz.core.entity.GenericValue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Stable, bounded snapshots: descriptors are fingerprinted, never duplicated in the journal. */
public final class WorkflowSnapshots {
    private static final ObjectMapper JSON = new ObjectMapper();
    private WorkflowSnapshots() { }

    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not encode workflow undo state", e); }
    }
    public static Map<String, Object> parse(String value) {
        try { return JSON.readValue(value, new TypeReference<Map<String, Object>>() { }); }
        catch (Exception e) { throw new IllegalStateException("Could not read workflow undo state", e); }
    }
    public static Map<String, Object> absent(String name) {
        Map<String, Object> value = new TreeMap<String, Object>();
        value.put("absent", true); value.put("name", name); return value;
    }
    public static Map<String, Object> workflow(JiraWorkflow workflow) {
        Map<String, Object> value = new TreeMap<String, Object>();
        value.put("name", workflow.getName()); value.put("description", workflow.getDescription());
        value.put("updatedDate", workflow.getUpdatedDate() == null ? null : workflow.getUpdatedDate().getTime());
        value.put("author", workflow.getUpdateAuthor() == null ? null : workflow.getUpdateAuthor().getKey());
        StringWriter descriptor = new StringWriter();
        workflow.getDescriptor().writeXML(new PrintWriter(descriptor), 0);
        value.put("descriptorHash", hash(descriptor.toString()));
        return value;
    }
    public static Map<String, Object> workflow(JiraWorkflow workflow, OfBizDelegator delegator) {
        Map<String, Object> value = workflow(workflow);
        List<GenericValue> rows = delegator.findByCondition("Workflow",
                new EntityFieldMap(Collections.<String, Object>singletonMap("name", workflow.getName()), EntityOperator.AND),
                Arrays.asList("id"), null);
        if (rows.size() != 1) throw new IllegalStateException("Workflow identity is ambiguous: " + workflow.getName());
        value.put("id", rows.get(0).getLong("id")); return value;
    }
    public static String workflowFingerprint(JiraWorkflow workflow, OfBizDelegator delegator) {
        Map<String, Object> value = workflow(workflow);
        List<GenericValue> rows = delegator.findByCondition("Workflow",
                new EntityFieldMap(Collections.<String, Object>singletonMap("name", workflow.getName()), EntityOperator.AND),
                Arrays.asList("id"), null);
        try {
            boolean system = workflow.isSystemWorkflow();
            value.put("system", system);
            if (rows.size() == 1) value.put("id", rows.get(0).getLong("id"));
            else if (!system || !rows.isEmpty())
                throw new IllegalStateException("Original workflow identity is ambiguous: " + workflow.getName());
        } catch (com.atlassian.jira.workflow.WorkflowException e) {
            throw new IllegalStateException("Could not identify original workflow " + workflow.getName(), e);
        }
        return hash(json(value));
    }
    public static Map<String, Object> status(Status status) {
        Map<String, Object> value = new TreeMap<String, Object>();
        value.put("id", status.getId()); value.put("name", status.getName());
        value.put("description", status.getDescription()); value.put("icon", status.getIconUrl());
        value.put("category", status.getStatusCategory() == null ? null : status.getStatusCategory().getKey());
        value.put("sequence", status.getSequence()); return value;
    }
    public static Map<String, Object> scheme(AssignableWorkflowScheme scheme) {
        Map<String, Object> value = new TreeMap<String, Object>();
        value.put("id", scheme.getId()); value.put("name", scheme.getName());
        value.put("description", scheme.getDescription()); value.put("defaultWorkflow", scheme.getConfiguredDefaultWorkflow());
        Map<String, String> mappings = new TreeMap<String, String>();
        for (Map.Entry<String, String> entry : scheme.getMappings().entrySet())
            if (entry.getKey() != null) mappings.put(entry.getKey(), entry.getValue());
        value.put("mappings", mappings); return value;
    }
    public static String hash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            return hex.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static long number(Object value) { return ((Number) value).longValue(); }
    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) { return (Map<String, Object>) value; }
}
