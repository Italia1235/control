package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;
import ru.rasulov.customfields.impl.validation.FieldFailureException;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Checks the {@code contexts} of one field and resolves projects and issue types to Jira objects. Writes nothing.
 * <p>
 * Nothing is assumed: a missing context, or a context whose project axis or issue type axis is not stated, is
 * {@code CONTEXT_REQUIRED} and tells the caller to ask the user. "All" has to be written ({@code allProjects: true},
 * {@code allIssueTypes: true}).
 */
@Named
public class ContextValidator {

    private static final String ASK_THE_USER = " Ask the user; do not choose for them.";

    private final ProjectResolver projectResolver;
    private final IssueTypeResolver issueTypeResolver;

    @Inject
    public ContextValidator(ProjectResolver projectResolver, IssueTypeResolver issueTypeResolver) {
        this.projectResolver = projectResolver;
        this.issueTypeResolver = issueTypeResolver;
    }

    /**
     * @return one resolved context per definition, in request order, with unique names; the options of each are the
     * context's own, as requested and unchecked
     * @throws FieldFailureException a context is missing, incomplete, unknown, archived, ambiguous or overlapping
     */
    public List<ResolvedContext> validate(List<ContextDefinition> definitions) {
        if (definitions.isEmpty()) {
            throw new FieldFailureException(CustomFieldErrorCode.CONTEXT_REQUIRED,
                    "'contexts' is required: say for which projects and for which issue types the field is. "
                            + "The user may want all of them (allProjects: true, allIssueTypes: true) or specific "
                            + "ones." + ASK_THE_USER);
        }
        if (definitions.size() > Limits.MAX_CONTEXTS_PER_FIELD) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "The field has " + definitions.size() + " contexts; the maximum is "
                            + Limits.MAX_CONTEXTS_PER_FIELD + ". Ask the user to reduce them or split the field.");
        }
        ProjectResolver.Session projectLookup = projectResolver.session();
        IssueTypeResolver.Session issueTypeLookup = issueTypeResolver.session();
        List<ResolvedContext> resolved = new ArrayList<ResolvedContext>(definitions.size());
        for (int i = 0; i < definitions.size(); i++) {
            resolved.add(resolve(definitions.get(i), i + 1, projectLookup, issueTypeLookup));
        }
        List<ResolvedContext> unique = withUniqueNames(resolved);
        checkFinalScope(unique);
        return withGlobalWarnings(unique);
    }

    private ResolvedContext resolve(ContextDefinition definition, int number, ProjectResolver.Session projectLookup,
                                    IssueTypeResolver.Session issueTypeLookup) {
        String where = "Context #" + number;
        if (definition == null) {
            throw new FieldFailureException(CustomFieldErrorCode.CONTEXT_REQUIRED,
                    where + " is empty: say for which projects and issue types it is." + ASK_THE_USER);
        }
        checkAxis(where, "projects", "allProjects", definition.isAllProjects(), definition.getProjects().size(),
                Limits.MAX_PROJECTS_PER_CONTEXT);
        checkAxis(where, "issue types", "allIssueTypes", definition.isAllIssueTypes(),
                definition.getIssueTypes().size(), Limits.MAX_ISSUE_TYPES_PER_CONTEXT);

        List<Project> projects = resolveProjects(where, definition, projectLookup);
        List<IssueType> issueTypes = resolveIssueTypes(where, definition, issueTypeLookup);
        String name = explicitName(where, definition.getName());
        return new ResolvedContext(name == null ? defaultName(definition.isAllProjects(), projects) : name,
                definition.isAllProjects(), projects, definition.isAllIssueTypes(), issueTypes,
                definition.hasOptions() ? definition.getOptions() : Collections.emptyList(),
                warnings(where, projects, issueTypes));
    }

    /** Exactly one of "all" and a non-empty list, and the list within its limit. */
    private static void checkAxis(String where, String axis, String allFlag, boolean all, int listed, int max) {
        if (all && listed > 0) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    where + ": give either " + allFlag + "=true or a list of " + axis + ", not both.");
        }
        if (!all && listed == 0) {
            throw new FieldFailureException(CustomFieldErrorCode.CONTEXT_REQUIRED,
                    where + " does not say which " + axis + " the field is for: set " + allFlag
                            + "=true or list them." + ASK_THE_USER);
        }
        if (listed > max) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    where + " lists " + listed + " " + axis + "; the maximum is " + max
                            + ". Ask the user to split the field or use " + allFlag + "=true.");
        }
    }

    private List<Project> resolveProjects(String where, ContextDefinition definition,
                                          ProjectResolver.Session lookup) {
        List<Project> projects = new ArrayList<Project>(definition.getProjects().size());
        Set<Long> seen = new HashSet<Long>();
        for (String text : definition.getProjects()) {
            Project project = project(text, lookup);
            if (!seen.add(project.getId())) {
                throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                        where + " lists project " + project.getKey() + " more than once (a key and a name of the "
                                + "same project count as one). Keep one.");
            }
            projects.add(project);
        }
        return Collections.unmodifiableList(projects);
    }

    private List<IssueType> resolveIssueTypes(String where, ContextDefinition definition,
                                              IssueTypeResolver.Session lookup) {
        List<IssueType> issueTypes = new ArrayList<IssueType>(definition.getIssueTypes().size());
        Set<String> seen = new HashSet<String>();
        for (String text : definition.getIssueTypes()) {
            IssueType issueType = issueType(text, lookup);
            if (!seen.add(issueType.getId())) {
                throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                        where + " lists issue type '" + issueType.getName() + "' more than once (a name and an id of "
                                + "the same type count as one). Keep one.");
            }
            issueTypes.add(issueType);
        }
        return Collections.unmodifiableList(issueTypes);
    }

    private Project project(String text, ProjectResolver.Session lookup) {
        try {
            return lookup.resolve(text);
        } catch (LookupFailure failure) {
            throw asFieldFailure(failure);
        }
    }

    private IssueType issueType(String text, IssueTypeResolver.Session lookup) {
        try {
            return lookup.resolve(text);
        } catch (LookupFailure failure) {
            throw asFieldFailure(failure);
        }
    }

    /** What a lookup refused becomes the error code of the field's result; message and suggestions stay as they are. */
    private static FieldFailureException asFieldFailure(LookupFailure failure) {
        CustomFieldErrorCode code;
        switch (failure.getKind()) {
            case UNKNOWN_PROJECT:
                code = CustomFieldErrorCode.UNKNOWN_PROJECT;
                break;
            case UNKNOWN_ISSUE_TYPE:
                code = CustomFieldErrorCode.UNKNOWN_ISSUE_TYPE;
                break;
            default:
                code = CustomFieldErrorCode.VALIDATION_ERROR;
        }
        return new FieldFailureException(code, failure.getMessage(), failure.getSuggestions());
    }

    private static String explicitName(String where, String raw) {
        String name = raw == null ? "" : raw.trim();
        if (name.length() > Limits.MAX_NAME_LENGTH) {
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    where + " has a name of " + name.length() + " characters; the maximum is "
                            + Limits.MAX_NAME_LENGTH + ".");
        }
        return name.isEmpty() ? null : name;
    }

    /** Keys of the first five projects, as the user reads them in Jira; "Global" for all projects. */
    static String defaultName(boolean allProjects, List<Project> projects) {
        if (allProjects) {
            return "Global";
        }
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < projects.size() && i < 5; i++) {
            if (i > 0) {
                name.append(", ");
            }
            name.append(projects.get(i).getKey());
        }
        return name.toString();
    }

    /**
     * A named issue type that none of the context's projects offers is allowed (the field simply will not show
     * there), but the user should hear about it.
     */
    private static List<String> warnings(String where, List<Project> projects, List<IssueType> issueTypes) {
        if (projects.isEmpty() || issueTypes.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> warnings = new ArrayList<String>();
        for (IssueType issueType : issueTypes) {
            if (!offeredByAny(projects, issueType)) {
                warnings.add(where + ": issue type '" + issueType.getName() + "' is not used by "
                        + (projects.size() == 1 ? "project " + projects.get(0).getKey() : "any of its projects")
                        + ", so the field will not appear for it there.");
            }
        }
        return warnings;
    }

    private static boolean offeredByAny(List<Project> projects, IssueType issueType) {
        for (Project project : projects) {
            for (IssueType offered : project.getIssueTypes()) {
                if (issueType.getId().equals(offered.getId())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The same final-set rules as {@code update_field_contexts} and {@code create_field_context}
     * ({@link ContextScopeRules#validate}): one global context at most, and a project (compared by id, so a key and a
     * name are the same project) in only one project-scoped context, whatever the issue types of the contexts are.
     */
    private static void checkFinalScope(List<ResolvedContext> contexts) {
        Map<Long, ContextScopeRules.Scope> scopes = new LinkedHashMap<Long, ContextScopeRules.Scope>();
        Map<Long, String> names = new HashMap<Long, String>();
        Set<Long> all = new HashSet<Long>();
        for (int i = 0; i < contexts.size(); i++) {
            ResolvedContext context = contexts.get(i);
            long number = i + 1;
            scopes.put(number, new ContextScopeRules.Scope(context.isAllProjects(), context.getProjects(),
                    context.isAllIssueTypes(), context.getIssueTypes()));
            names.put(number, context.getName());
            all.add(number);
        }
        try {
            ContextScopeRules.validate(scopes, names, all);
        } catch (ContextScopeRules.Failure failure) {
            long first = failure.getContextId();
            long second = laterConflict(scopes, first, failure);
            if (failure.getProjectId() == null) {
                throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                        "Contexts #" + first + " (" + names.get(first) + ") and #" + second + " (" + names.get(second)
                                + ") are both global (allProjects=true). A field has only one global context, even "
                                + "with different issue types. Use one global context with all the needed issue types, "
                                + "or separate fields." + ASK_THE_USER);
            }
            String project = projectKey(contexts, failure.getProjectId());
            throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                    "Project " + project + " is in contexts #" + first + " (" + names.get(first) + ") and #" + second
                            + " (" + names.get(second) + "). A project may belong to only one project-scoped context "
                            + "of a field, even when the issue types differ. Use ONE context for " + project
                            + " with all the needed issue types and one shared options list, or SEPARATE fields."
                            + ASK_THE_USER);
        }
    }

    /** The first context after {@code owner} that repeats what made {@code failure}: its project, or the global slot. */
    private static long laterConflict(Map<Long, ContextScopeRules.Scope> scopes, long owner,
                                      ContextScopeRules.Failure failure) {
        for (Map.Entry<Long, ContextScopeRules.Scope> entry : scopes.entrySet()) {
            if (entry.getKey() <= owner) continue;
            ContextScopeRules.Scope scope = entry.getValue();
            if (failure.getProjectId() == null ? scope.isAllProjects() : coversProject(scope, failure.getProjectId())) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("No second context found for the conflict with context " + owner);
    }

    private static boolean coversProject(ContextScopeRules.Scope scope, Long projectId) {
        for (Project project : scope.getProjects()) if (project.getId().equals(projectId)) return true;
        return false;
    }

    private static String projectKey(List<ResolvedContext> contexts, Long projectId) {
        for (ResolvedContext context : contexts) {
            for (Project project : context.getProjects()) if (project.getId().equals(projectId)) return project.getKey();
        }
        return String.valueOf(projectId);
    }

    /**
     * One global context may coexist with project-scoped ones: the same policy as {@code create_field_context} and
     * {@code update_field_contexts}, where a project's own context is chosen before the global one. That is not a
     * pure addition, so each project-scoped context gets a warning for the user instead of a refusal.
     */
    private static List<ResolvedContext> withGlobalWarnings(List<ResolvedContext> contexts) {
        ResolvedContext global = null;
        for (ResolvedContext context : contexts) if (context.isAllProjects()) global = context;
        if (global == null) return contexts;
        List<ResolvedContext> result = new ArrayList<ResolvedContext>(contexts.size());
        for (ResolvedContext context : contexts) {
            if (context.isAllProjects()) {
                result.add(context);
                continue;
            }
            List<String> warnings = new ArrayList<String>(context.getWarnings());
            warnings.add("Context '" + context.getName() + "' and the global context '" + global.getName()
                    + "' both exist: Jira picks the project's own context before the global one, so in "
                    + defaultName(false, context.getProjects()) + " the global context does not apply, including "
                    + "to issue types this context does not list. Tell the user.");
            result.add(context.withWarnings(warnings));
        }
        return result;
    }

    /** Names come from the user or from the project keys, so two contexts can end up with the same one. */
    private static List<ResolvedContext> withUniqueNames(List<ResolvedContext> contexts) {
        Set<String> taken = new HashSet<String>();
        List<ResolvedContext> unique = new ArrayList<ResolvedContext>(contexts.size());
        for (ResolvedContext context : contexts) {
            String name = context.getName();
            int suffix = 2;
            while (!taken.add(normalize(name))) {
                name = context.getName() + " (" + suffix++ + ")";
            }
            unique.add(name.equals(context.getName()) ? context : context.withName(name));
        }
        return unique;
    }
}
