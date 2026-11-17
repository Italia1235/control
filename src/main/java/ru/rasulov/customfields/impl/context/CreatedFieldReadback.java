package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.project.Project;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.dto.field.context.ContextSummary;
import ru.rasulov.customfields.impl.context.ContextScopeRules.Scope;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Re-reads a field Jira has just created and compares what it really stored (contexts, their projects, issue types and
 * options) with the plan. Read-only: never retries, repairs or deletes anything.
 */
@Named
public class CreatedFieldReadback {

    private final CustomFieldManager customFieldManager;
    private final OptionsManager optionsManager;
    private final ContextSummarizer summarizer;

    @Inject
    public CreatedFieldReadback(@ComponentImport CustomFieldManager customFieldManager,
                                @ComponentImport OptionsManager optionsManager, ContextSummarizer summarizer) {
        this.customFieldManager = customFieldManager;
        this.optionsManager = optionsManager;
        this.summarizer = summarizer;
    }

    /** What the re-read found: the contexts Jira reports, and every difference from the plan (empty = it matches). */
    public static final class Result {
        private final List<ContextSummary> actualContexts;
        private final List<String> differences;

        public Result(List<ContextSummary> actualContexts, List<String> differences) {
            this.actualContexts = actualContexts;
            this.differences = differences;
        }

        public List<ContextSummary> getActualContexts() { return actualContexts; }
        public List<String> getDifferences() { return differences; }
        public boolean matches() { return differences.isEmpty(); }
    }

    /**
     * @param planned the contexts in request order
     * @param written the field configuration Jira returned for each planned context, same order
     */
    public Result read(String fieldId, List<ResolvedContext> planned, List<FieldConfig> written) {
        try {
            return compare(fieldId, planned, written);
        } catch (RuntimeException e) {
            // The field exists; an unreadable Jira proves neither a match nor a mismatch.
            return new Result(Collections.<ContextSummary>emptyList(), Collections.singletonList(
                    "Jira could not be re-read, so the stored state is unverified: " + e));
        }
    }

    private Result compare(String fieldId, List<ResolvedContext> planned, List<FieldConfig> written) {
        List<String> differences = new ArrayList<String>();
        customFieldManager.refresh();
        CustomField field = customFieldManager.getCustomFieldObject(fieldId);
        if (field == null) {
            return new Result(Collections.<ContextSummary>emptyList(), Collections.singletonList(
                    "Jira does not return the field " + fieldId + " after it was created"));
        }
        List<FieldConfigScheme> schemes = field.getConfigurationSchemes();
        Set<Long> matched = new HashSet<Long>();
        for (int i = 0; i < planned.size(); i++) {
            ResolvedContext context = planned.get(i);
            FieldConfig config = written.get(i);
            FieldConfigScheme scheme = schemeOf(schemes, config.getId());
            if (scheme == null) {
                differences.add("context '" + context.getName() + "' (config " + config.getId() + ") is not among the "
                        + "contexts Jira reports");
                continue;
            }
            matched.add(scheme.getId());
            compareScope(context, scheme, differences);
            compareOptions(context, scheme, config, differences);
        }
        for (FieldConfigScheme scheme : schemes) {
            if (!matched.contains(scheme.getId())) {
                differences.add("Jira reports an unplanned context '" + scheme.getName() + "' (id " + scheme.getId() + ")");
            }
        }
        return new Result(summarizer.summarize(field), differences);
    }

    private static FieldConfigScheme schemeOf(List<FieldConfigScheme> schemes, Long configId) {
        for (FieldConfigScheme scheme : schemes) {
            if (scheme.getConfigs() == null) continue;
            for (FieldConfig config : scheme.getConfigs().values()) {
                if (config.getId().equals(configId)) return scheme;
            }
        }
        return null;
    }

    private static void compareScope(ResolvedContext context, FieldConfigScheme scheme, List<String> differences) {
        Scope wanted = new Scope(context.isAllProjects(), context.getProjects(), context.isAllIssueTypes(),
                context.getIssueTypes());
        Scope actual = ContextScopeRules.from(scheme);
        if (wanted.equals(actual)) return;
        differences.add("context '" + context.getName() + "' (id " + scheme.getId() + ") was planned for "
                + describe(wanted) + " but Jira reports " + describe(actual));
    }

    private static String describe(Scope scope) {
        StringBuilder text = new StringBuilder("projects ");
        if (scope.isAllProjects()) text.append("ALL");
        else {
            List<String> keys = new ArrayList<String>();
            for (Project project : scope.getProjects()) keys.add(project.getKey());
            text.append(keys);
        }
        text.append(", issue types ");
        if (scope.isAllIssueTypes()) text.append("ALL");
        else {
            List<String> names = new ArrayList<String>();
            for (IssueType type : scope.getIssueTypes()) names.add(type.getName() + " (" + type.getId() + ")");
            text.append(names);
        }
        return text.toString();
    }

    private void compareOptions(ResolvedContext context, FieldConfigScheme scheme, FieldConfig config,
                                List<String> differences) {
        List<String> wanted = flatten(context.getOptions());
        List<String> actual = new ArrayList<String>();
        Options options = optionsManager.getOptions(config);
        if (options != null && options.getRootOptions() != null) {
            for (Option root : options.getRootOptions()) {
                actual.add(root.getValue());
                if (root.getChildOptions() != null) {
                    for (Option child : root.getChildOptions()) actual.add(root.getValue() + " > " + child.getValue());
                }
            }
        }
        if (!wanted.equals(actual)) {
            differences.add("context '" + context.getName() + "' (id " + scheme.getId() + ") was planned with options "
                    + wanted + " but Jira reports " + actual);
        }
    }

    private static List<String> flatten(List<OptionDefinition> options) {
        List<String> flat = new ArrayList<String>();
        for (OptionDefinition option : options) {
            flat.add(option.getValue());
            for (OptionDefinition child : option.getChildren()) flat.add(option.getValue() + " > " + child.getValue());
        }
        return flat;
    }
}
