package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.context.GlobalIssueContext;
import com.atlassian.jira.issue.context.JiraContextNode;
import com.atlassian.jira.issue.context.ProjectContext;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.FieldConfigSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;

import ru.rasulov.customfields.impl.undo.FieldMutationJournal;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Turns resolved contexts into Jira contexts. {@code createCustomField} creates the first context together with the
 * field; this class describes it ({@link #nodesOf}, {@link #issueTypesOf}), renames it, and creates the others.
 */
@Named
public class ContextWriter {

    private final ProjectManager projectManager;
    private final FieldConfigSchemeManager schemeManager;
    private final ContextSchemeFactory schemeFactory;
    private final FieldMutationJournal mutations;

    @Inject
    public ContextWriter(@ComponentImport ProjectManager projectManager,
                         @ComponentImport FieldConfigSchemeManager schemeManager,
                         ContextSchemeFactory schemeFactory, FieldMutationJournal mutations) {
        this.projectManager = projectManager;
        this.schemeManager = schemeManager;
        this.schemeFactory = schemeFactory;
        this.mutations = mutations;
    }

    /** The Jira context nodes: the global one, or one per project. */
    public List<JiraContextNode> nodesOf(ResolvedContext context) {
        if (context.isAllProjects()) {
            return Collections.singletonList(GlobalIssueContext.getInstance());
        }
        List<JiraContextNode> nodes = new ArrayList<JiraContextNode>(context.getProjects().size());
        for (Project project : context.getProjects()) {
            nodes.add(new ProjectContext(project.getId(), projectManager));
        }
        return nodes;
    }

    /** The issue types for Jira: the listed ones, or Jira's marker for "all". */
    public List<IssueType> issueTypesOf(ResolvedContext context) {
        return context.isAllIssueTypes() ? FieldConfigSchemeManager.ALL_ISSUE_TYPES : context.getIssueTypes();
    }

    /** The Jira context nodes of a resolved scope: the global one, or one per project. */
    public List<JiraContextNode> nodesOf(ContextScopeRules.Scope scope) {
        if (scope.isAllProjects()) return Collections.singletonList(GlobalIssueContext.getInstance());
        List<JiraContextNode> nodes = new ArrayList<JiraContextNode>(scope.getProjects().size());
        for (Project project : scope.getProjects()) nodes.add(new ProjectContext(project.getId(), projectManager));
        return nodes;
    }

    public List<IssueType> issueTypesOf(ContextScopeRules.Scope scope) {
        return scope.isAllIssueTypes() ? FieldConfigSchemeManager.ALL_ISSUE_TYPES : scope.getIssueTypes();
    }

    /**
     * Creates one context with Jira's own initial configuration, without copying existing settings.
     * The native receipt is observed before journal poststate reads; a failure there must not hide its identity.
     * create_field_context retains journal steps but does not support automatic context deletion.
     */
    public FieldConfigScheme createAdditional(CustomField field, String name, String description,
                                              ContextScopeRules.Scope scope, Runnable beforeWrite,
                                              java.util.function.Consumer<Long> createdId) {
        return mutations.write(field, ru.rasulov.customfields.impl.undo.FieldUndoJson.map("type", "context", "name", name),
                () -> {
                    // Resolve arguments before declaring that the mutating native call was entered.
                    FieldConfigScheme requested = schemeFactory.create(field.getId(), name, description);
                    List<JiraContextNode> nodes = nodesOf(scope);
                    List<IssueType> types = issueTypesOf(scope);
                    beforeWrite.run();
                    FieldConfigScheme created = schemeManager.createFieldConfigScheme(requested, nodes, types, field);
                    if (created != null) createdId.accept(created.getId());
                    return created;
                });
    }

    /**
     * Finishes the contexts of a field that {@code createCustomField} has just created with the first one: names the
     * first context, creates the rest.
     *
     * @return the field configuration of every context, in the order of {@code contexts}; options go there
     * @throws RuntimeException Jira refused; the durable coordinator compensates proven writes
     */
    public List<FieldConfig> apply(CustomField field, List<ResolvedContext> contexts) {
        List<FieldConfigScheme> created = field.getConfigurationSchemes();
        if (created.isEmpty()) {
            throw new IllegalStateException("Field " + field.getId() + " has no context after it was created.");
        }
        List<FieldConfig> configs = new ArrayList<FieldConfig>(contexts.size());

        FieldConfigScheme first = mutations.write(field, ru.rasulov.customfields.impl.undo.FieldUndoJson.map(
                "type", "rename", "id", created.get(0).getId(), "oldName", created.get(0).getName(),
                "oldDescription", created.get(0).getDescription()),
                () -> schemeManager.updateFieldConfigScheme(
                        schemeFactory.renamed(created.get(0), contexts.get(0).getName(), "")));
        configs.add(first.getOneAndOnlyConfig());

        for (int i = 1; i < contexts.size(); i++) {
            ResolvedContext context = contexts.get(i);
            FieldConfigScheme scheme = mutations.write(field, ru.rasulov.customfields.impl.undo.FieldUndoJson.map(
                    "type", "context", "name", context.getName()), () -> schemeManager.createFieldConfigScheme(
                    schemeFactory.create(field.getId(), context.getName()), nodesOf(context), issueTypesOf(context),
                    field));
            configs.add(scheme.getOneAndOnlyConfig());
        }
        return configs;
    }
}
