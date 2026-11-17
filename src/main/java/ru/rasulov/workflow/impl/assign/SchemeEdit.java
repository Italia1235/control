package ru.rasulov.workflow.impl.assign;

import com.atlassian.jira.workflow.WorkflowScheme;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The change a scheme gets when it is created or updated: the assignment gives all issue types a workflow (it becomes
 * the default and the explicit mappings go) or the named ones an explicit mapping; a clone swaps workflows for their
 * copies wherever the scheme names them, and touches nothing else.
 */
final class SchemeEdit {

    private final String workflow;
    private final boolean allIssueTypes;
    private final Set<String> issueTypeIds;
    private final Map<String, String> replacements;
    private final String defaultWorkflow;

    private SchemeEdit(String workflow, boolean allIssueTypes, Set<String> issueTypeIds,
                       Map<String, String> replacements, String defaultWorkflow) {
        this.workflow = workflow;
        this.allIssueTypes = allIssueTypes;
        this.issueTypeIds = Collections.unmodifiableSet(new TreeSet<String>(issueTypeIds));
        this.replacements = Collections.unmodifiableMap(new LinkedHashMap<String, String>(replacements));
        this.defaultWorkflow = defaultWorkflow;
    }

    static SchemeEdit allIssueTypes(String workflow) {
        return new SchemeEdit(workflow, true, Collections.<String>emptySet(), Collections.<String, String>emptyMap(),
                null);
    }

    static SchemeEdit issueTypes(String workflow, Set<String> issueTypeIds) {
        return new SchemeEdit(workflow, false, issueTypeIds, Collections.<String, String>emptyMap(), null);
    }

    /**
     * @param replacements    name of a workflow to the name of its copy
     * @param defaultWorkflow the workflow the scheme really uses for types it does not map (the system default scheme
     *                        has none configured and uses the built-in one), so that it can be replaced as well
     */
    static SchemeEdit replacing(Map<String, String> replacements, String defaultWorkflow) {
        return new SchemeEdit(null, false, Collections.<String>emptySet(), replacements, defaultWorkflow);
    }

    void apply(WorkflowScheme.Builder<?> builder) {
        if (!replacements.isEmpty() || defaultWorkflow != null) {
            replace(builder);
        } else if (allIssueTypes) {
            builder.clearMappings();
            builder.setDefaultWorkflow(workflow);
        } else {
            for (String issueTypeId : issueTypeIds) {
                builder.setMapping(issueTypeId, workflow);
            }
        }
    }

    private void replace(WorkflowScheme.Builder<?> builder) {
        String currentDefault = builder.getDefaultWorkflow() != null ? builder.getDefaultWorkflow() : defaultWorkflow;
        if (currentDefault != null && replacements.containsKey(currentDefault)) {
            builder.setDefaultWorkflow(replacements.get(currentDefault));
        }
        for (Map.Entry<String, String> mapping : new LinkedHashMap<String, String>(builder.getMappings()).entrySet()) {
            if (replacements.containsKey(mapping.getValue())) {
                builder.setMapping(mapping.getKey(), replacements.get(mapping.getValue()));
            }
        }
    }
}
