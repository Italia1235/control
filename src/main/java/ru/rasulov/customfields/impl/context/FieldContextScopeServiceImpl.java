package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.config.ReindexMessageManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItem;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItemService;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.context.GlobalIssueContext;
import com.atlassian.jira.issue.context.JiraContextNode;
import com.atlassian.jira.issue.context.ProjectContext;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.FieldConfigSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.customfields.api.FieldContextScopeService;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.customfields.api.dto.context.FieldContextsResponse;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsRequest;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsRequest.ContextUpdate;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsResponse;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsResponse.*;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.customfields.impl.grid.GridFieldConfiguration;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.customfields.impl.validation.FieldFailureException;
import ru.rasulov.customfields.impl.validation.TypeKeys;
import ru.rasulov.customfields.impl.context.ContextScopeRules.Scope;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** Changes only the native context associations. A failed write is observed, never retried or compensated. */
@Named
@ExportAsService(FieldContextScopeService.class)
public class FieldContextScopeServiceImpl implements FieldContextScopeService {
    private static final String OPERATION = "update_field_contexts";
    // The creator's existing lock is package-private; this is the same cluster lock, not a second lock domain.
    private static final String LOCK_NAME = "ru.rasulov.control.create-custom-fields";
    private static final Logger LOG = LoggerFactory.getLogger(FieldContextScopeServiceImpl.class);
    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final FieldSelector selector;
    private final ManagedConfigurationItemService managed;
    private final ProjectResolver projects;
    private final IssueTypeResolver issueTypes;
    private final CustomFieldManager fields;
    private final FieldManager fieldManager;
    private final FieldConfigSchemeManager schemes;
    private final ProjectManager projectManager;
    private final ReindexMessageManager reindex;
    private final FieldContextSnapshots snapshots;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final AssetsFieldConfiguration assets;
    private final OptionsManager optionsManager;
    private final OptionUsageCounter usage;

    @Inject
    public FieldContextScopeServiceImpl(AdministratorGuard guard, NamedLock lock, FieldSelector selector,
            @ComponentImport ManagedConfigurationItemService managed, ProjectResolver projects,
            IssueTypeResolver issueTypes, @ComponentImport CustomFieldManager fields,
            @ComponentImport FieldManager fieldManager, @ComponentImport FieldConfigSchemeManager schemes,
            @ComponentImport ProjectManager projectManager, @ComponentImport ReindexMessageManager reindex,
            FieldContextSnapshots snapshots, PreviewPlanExecutor executor, ActionPlanCodec codec, ChangeJournal journal,
            AssetsFieldConfiguration assets, @ComponentImport OptionsManager optionsManager, OptionUsageCounter usage) {
        this.guard = guard; this.lock = lock; this.selector = selector; this.managed = managed;
        this.projects = projects; this.issueTypes = issueTypes; this.fields = fields; this.fieldManager = fieldManager;
        this.schemes = schemes; this.projectManager = projectManager; this.reindex = reindex;
        this.snapshots = snapshots; this.executor = executor; this.codec = codec; this.journal = journal; this.assets = assets;
        this.optionsManager = optionsManager; this.usage = usage;
    }

    @Override
    public UpdateFieldContextsResponse update(UpdateFieldContextsRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (!request.isDryRun()) return journal.execute(OPERATION, user.getKey(),
                () -> lock.run(LOCK_NAME, "custom fields", () -> apply(request.getPlanId(), user)));
        Prepared prepared = prepare(request, user);
        if (prepared.preview.getStatus() != Status.WOULD_UPDATE) return prepared.preview;
        // Usage is a preview-only courtesy added AFTER the fingerprint was taken: it never makes a plan stale.
        UpdateFieldContextsResponse shown = prepared.hasOptions() ? previewResponse(prepared, countUsage(prepared, user)) : prepared.preview;
        return executor.issue(OPERATION, user.getKey(), request, prepared.fingerprint, shown::withPlan);
    }

    private UpdateFieldContextsResponse apply(String planId, ApplicationUser user) {
        return executor.apply(OPERATION, user.getKey(), planId, UpdateFieldContextsRequest.class,
                UpdateFieldContextsResponse::stale, request -> prepare(request, user), current -> true,
                current -> current.fingerprint, current -> write(current, user));
    }

    private Prepared prepare(UpdateFieldContextsRequest request, ApplicationUser user) {
        try {
            validateRequest(request);
            CustomField field = selector.resolve(request.getFieldId());
            ManagedConfigurationItem item = managed.getManagedCustomField(field);
            boolean locked = item != null && !managed.doesUserHavePermission(user, item);
            if (locked) throw failure(ErrorCode.FIELD_LOCKED, "Jira does not permit this administrator to edit the field's contexts.");
            if (field.getCustomFieldType() == null && hasAssets(request))
                throw failure(ErrorCode.VALIDATION_ERROR, "The type of " + field.getId() + " is not available (its plugin may be "
                        + "disabled), so its Assets settings cannot be validated or written; nothing was changed.");
            String persisted = snapshots.persistedField(field);
            Map<Long, FieldConfigScheme> existing = new TreeMap<Long, FieldConfigScheme>();
            Map<Long, Scope> finalScopes = new LinkedHashMap<Long, Scope>();
            Map<Long, String> names = new LinkedHashMap<Long, String>();
            for (FieldConfigScheme scheme : field.getConfigurationSchemes()) {
                existing.put(scheme.getId(), scheme); finalScopes.put(scheme.getId(), ContextScopeRules.from(scheme));
                names.put(scheme.getId(), scheme.getName());
            }
            List<Target> targets = new ArrayList<Target>();
            Map<Long, Object> configSnapshots = new TreeMap<Long, Object>();
            ProjectResolver.Session projectLookup = projects.session();
            IssueTypeResolver.Session typeLookup = issueTypes.session();
            for (ContextUpdate update : request.getContexts()) {
                FieldConfigScheme scheme = existing.get(update.getContextId());
                if (scheme == null) throw failure(ErrorCode.UNKNOWN_CONTEXT,
                        "Context " + update.getContextId() + " does not belong to " + field.getId() + ".");
                Map<Long, FieldConfig> configs = configs(scheme);
                if (configs.size() != 1) throw failure(ErrorCode.MULTIPLE_CONFIGS,
                        "Context " + scheme.getId() + " must have exactly one distinct FieldConfig; found " + configs.size() + ".");
                FieldConfig config = configs.values().iterator().next();
                Scope before = finalScopes.get(scheme.getId());
                boolean axesOmitted = update.isAllProjects() == null && update.getProjects() == null
                        && update.isAllIssueTypes() == null && update.getIssueTypes() == null;
                Scope after = axesOmitted ? before : ScopeInput.resolve(update.isAllProjects(), update.getProjects(),
                        update.isAllIssueTypes(), update.getIssueTypes(), projectLookup, typeLookup);
                Map<String, Object> snapshot = snapshots.config(field, config.getId());
                configSnapshots.put(config.getId(), snapshot);
                AssetsFieldSettings requested = update.getAssets(), current = null;
                boolean assetsChanged = false;
                if (requested != null) {
                    try {
                        assets.validate(field.getCustomFieldType().getKey(), requested);
                    } catch (FieldFailureException e) {
                        throw failure(ErrorCode.VALIDATION_ERROR, e.getMessage());
                    }
                    String stored = assets.snapshot(config.getId());
                    current = assets.initialized(stored) ? assets.settings(stored) : null;
                    assetsChanged = current == null || !AssetsFieldConfiguration.sameSettings(requested, current);
                }
                OptionsPlan optionsPlan = null;
                if (update.getOptions() != null) {
                    String typeKey = field.getCustomFieldType() == null ? null : field.getCustomFieldType().getKey();
                    if (typeKey == null || !TypeKeys.contextTakesOptions(typeKey))
                        throw failure(ErrorCode.OPTIONS_NOT_SUPPORTED, "Field " + field.getId() + " (" + typeKey + ") keeps no options of its own per "
                                + "context; only select, radio buttons, checkboxes, multi-select and cascading select options can be changed.");
                    Set<Long> defaultIds = OptionTree.defaultIds(field, config);
                    optionsPlan = OptionsChangeRules.plan(OptionTree.read(optionsManager.getOptions(config)), update.getOptions(),
                            defaultIds, TypeKeys.CASCADING_SELECT.equals(typeKey));
                }
                Map<String, Object> delta = new LinkedHashMap<String, Object>(ContextScopeRules.delta(before, after));
                if (unverifiable(field.getCustomFieldType().getKey())) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> warnings = new ArrayList<Map<String, Object>>(
                            (List<Map<String, Object>>) delta.get("warnings"));
                    Map<String, Object> warning = new LinkedHashMap<String, Object>();
                    warning.put("code", "PLUGIN_SETTINGS_UNVERIFIED");
                    warning.put("message", "The FieldConfig is retained, but this plugin has no safe settings reader; its settings cannot be verified.");
                    warnings.add(warning); delta.put("warnings", warnings);
                }
                targets.add(new Target(scheme, config, before, after, delta, snapshot, current, requested, assetsChanged, optionsPlan));
                finalScopes.put(scheme.getId(), after);
            }
            Set<Long> selected = new HashSet<Long>();
            for (Target target : targets) selected.add(target.scheme.getId());
            ContextScopeRules.validate(finalScopes, names, selected);
            int touched = 0;
            boolean hasOptions = false;
            for (Target target : targets) if (target.options != null) { hasOptions = true; touched += target.options.touched; }
            if (touched > Limits.MAX_OPTIONS_PER_CALL) throw failure(ErrorCode.OPTIONS_LIMIT, "One request may name at most "
                    + Limits.MAX_OPTIONS_PER_CALL + " options in add, rename, disable, enable and order together; this one names " + touched + ".");
            List<ContextRef> untouched = new ArrayList<ContextRef>();
            boolean changes = false, scopeChanges = false;
            for (Target target : targets) { changes |= target.changed(); scopeChanges |= target.scopeChanged(); }
            if (!changes && hasOptions) {
                List<String> lines = new ArrayList<String>();
                for (Target target : targets) if (target.options != null) lines.addAll(target.options.unchanged);
                throw failure(ErrorCode.OPTIONS_NO_CHANGE, "Nothing to change: every requested operation already holds. " + String.join("; ", lines));
            }
            for (FieldConfigScheme scheme : existing.values()) if (!selected.contains(scheme.getId()))
                untouched.add(new ContextRef(scheme.getId(), scheme.getName()));
            FieldContextsResponse.FieldView fieldView = new FieldContextsResponse.FieldView(field.getId(), field.getName(),
                    field.getCustomFieldType().getKey(), field.getCustomFieldType().getName(),
                    FieldContextTypes.typeClass(field.getCustomFieldType().getKey()), item != null && item.isManaged(), false);
            UpdateFieldContextsResponse response = previewResponse(fieldView, targets, untouched, changes, scopeChanges, null);
            String fingerprint = codec.fingerprint(Arrays.asList(codec.fingerprint(response), persisted, configSnapshots));
            return new Prepared(field, targets, response, fingerprint, fieldView, untouched, changes, scopeChanges);
        } catch (Failure e) {
            return new Prepared(UpdateFieldContextsResponse.failed(true, e.code, e.getMessage(), e.suggestions));
        } catch (OptionsChangeRules.Failure e) {
            return new Prepared(UpdateFieldContextsResponse.failed(true, e.code, e.getMessage(), null));
        } catch (ScopeInput.Invalid e) {
            return new Prepared(UpdateFieldContextsResponse.failed(true, ErrorCode.VALIDATION_ERROR, e.getMessage(), null));
        } catch (LookupFailure e) {
            ErrorCode code = e.getKind() == LookupFailure.Kind.UNKNOWN_PROJECT ? ErrorCode.UNKNOWN_PROJECT
                    : e.getKind() == LookupFailure.Kind.UNKNOWN_ISSUE_TYPE ? ErrorCode.UNKNOWN_ISSUE_TYPE : ErrorCode.VALIDATION_ERROR;
            return new Prepared(UpdateFieldContextsResponse.failed(true, code, e.getMessage(), e.getSuggestions()));
        } catch (FieldSelector.Failure e) {
            return new Prepared(UpdateFieldContextsResponse.failed(true, ErrorCode.UNKNOWN_FIELD, e.getMessage(), null));
        } catch (ContextScopeRules.Failure e) {
            return new Prepared(UpdateFieldContextsResponse.failed(true, ErrorCode.valueOf(e.getCode()), e.getMessage(), null));
        } catch (RuntimeException e) {
            LOG.error("Cannot preview custom field context scopes", e);
            return new Prepared(UpdateFieldContextsResponse.failed(true, ErrorCode.JIRA_ERROR,
                    "Jira could not read the complete persisted field/configuration state; nothing was changed. Details are in the Jira log.", null));
        }
    }

    /** The preview of a prepared plan; usage by context id is shown only when given (never part of the fingerprint). */
    private static UpdateFieldContextsResponse previewResponse(Prepared prepared, Map<Long, Map<Long, OptionUsage>> usage) {
        return previewResponse(prepared.fieldView, prepared.targets, prepared.untouched, prepared.changes, prepared.scopeChanges, usage);
    }
    private static UpdateFieldContextsResponse previewResponse(FieldContextsResponse.FieldView fieldView, List<Target> targets,
            List<ContextRef> untouched, boolean changes, boolean scopeChanges, Map<Long, Map<Long, OptionUsage>> usage) {
        List<ContextView> views = new ArrayList<ContextView>();
        for (Target target : targets)
            views.add(target.view(target.changed() ? Verdict.WOULD_UPDATE : Verdict.UNCHANGED, null, null,
                    usage == null ? null : usage.get(target.scheme.getId())));
        return new UpdateFieldContextsResponse(true, changes ? Status.WOULD_UPDATE : Status.UNCHANGED, fieldView, views, untouched,
                scopeChanges, null, null, null, null, null, null, null);
    }

    /** One best-effort search for every renamed or disabled option of all contexts; a failure only makes usage unknown. */
    private Map<Long, Map<Long, OptionUsage>> countUsage(Prepared prepared, ApplicationUser user) {
        Map<Long, Long> wanted = new LinkedHashMap<Long, Long>();
        for (Target target : prepared.targets) if (target.options != null) wanted.putAll(target.options.usageTargets());
        Map<Long, OptionUsage> counted = new LinkedHashMap<Long, OptionUsage>();
        if (!wanted.isEmpty()) {
            try {
                Map<Long, OptionUsage> found = usage.count(user, prepared.field, wanted);
                if (found != null) counted.putAll(found);
            } catch (RuntimeException e) {
                LOG.warn("Counting the use of options failed; the preview shows it as unknown", e);
                for (Long id : wanted.keySet()) counted.put(id, OptionUsage.unknown("The usage search failed: " + e.getMessage()));
            }
        }
        Map<Long, Map<Long, OptionUsage>> byContext = new LinkedHashMap<Long, Map<Long, OptionUsage>>();
        for (Target target : prepared.targets) if (target.options != null) {
            Map<Long, OptionUsage> own = new LinkedHashMap<Long, OptionUsage>();
            for (Long id : target.options.usageTargets().keySet()) if (counted.containsKey(id)) own.put(id, counted.get(id));
            byContext.put(target.scheme.getId(), own);
        }
        return byContext;
    }

    private UpdateFieldContextsResponse write(Prepared plan, ApplicationUser user) {
        List<ContextView> views = new ArrayList<ContextView>();
        Long failed = null;
        String error = null;
        boolean wrote = false;      // any native write may have happened
        boolean scopeWrote = false; // a scope may have moved: only then is reindexing relevant
        for (Target target : plan.targets) {
            if (failed != null || !target.changed()) {
                Observation actual = observe(plan.field, target);
                views.add(target.view(failed != null && target.changed() ? Verdict.NOT_ATTEMPTED : Verdict.UNCHANGED,
                        actual.scope, actual.error));
                continue;
            }
            boolean attempted = false;
            try {
                journal.checkpoint();
                if (target.scopeChanged()) {
                    Map<String, FieldConfig> mappings = new LinkedHashMap<String, FieldConfig>();
                    if (target.after.isAllIssueTypes()) mappings.put(null, target.config);
                    else for (IssueType type : target.after.getIssueTypes()) mappings.put(type.getId(), target.config);
                    FieldConfigScheme built = new FieldConfigScheme.Builder(target.scheme).setConfigs(mappings).toFieldConfigScheme();
                    List<JiraContextNode> contextNodes = nodes(target.after);
                    attempted = true;
                    schemes.updateFieldConfigScheme(built, contextNodes, plan.field);
                    journal.checkpoint();
                }
                if (target.assetsChanged) {
                    attempted = true;
                    assets.configure(target.config.getId(), Long.valueOf(plan.field.getId().substring("customfield_".length())),
                            target.assetsAfter);
                    journal.checkpoint();
                }
                if (target.options != null && target.options.changed()) {
                    attempted = true;
                    writeOptions(target);
                }
                Observation actual = observe(plan.field, target);
                if (actual.error != null) throw new IllegalStateException(actual.error);
                if (!target.after.equals(actual.scope)) throw new IllegalStateException("Persisted scope differs from the approved scope.");
                if (!unchangedConfig(target, actual.configSnapshot))
                    throw new IllegalStateException("The context's FieldConfig, full option tree, default or readable plugin settings changed.");
                if (target.options != null) {
                    Object rows = actual.configSnapshot.get("options");
                    String mismatch = rows instanceof List ? target.options.mismatch((List<?>) rows) : "the persisted options could not be read";
                    if (mismatch != null) throw new IllegalStateException("The persisted options differ from the approved ones: " + mismatch);
                }
                if (target.assetsChanged) {
                    Object stored = actual.configSnapshot.get("assets");
                    AssetsFieldSettings persisted = stored instanceof String && assets.initialized((String) stored)
                            ? assets.settings((String) stored) : null;
                    if (persisted == null || !AssetsFieldConfiguration.sameSettings(target.assetsAfter, persisted))
                        throw new IllegalStateException("Persisted Assets settings differ from the approved ones.");
                }
                wrote = true; scopeWrote |= target.scopeChanged();
                views.add(target.view(Verdict.UPDATED, actual.scope, null));
            } catch (RuntimeException e) {
                LOG.error("Failed to update context {} of {}", target.scheme.getId(), plan.field.getId(), e);
                failed = target.scheme.getId();
                error = "Context update stopped. No retry or rollback was performed. " + e.getMessage();
                // Native persistence can fail between axis writes. Always re-read the failed target too.
                Observation actual = observe(plan.field, target);
                // Unknown poststate remains potentially changed; a proven unchanged state is not a write.
                boolean moved = attempted && (actual.error != null || !target.before.equals(actual.scope)
                        || !target.configSnapshot.equals(actual.configSnapshot));
                wrote |= moved;
                scopeWrote |= moved && (target.scopeChanged() || actual.error != null || !target.before.equals(actual.scope));
                views.add(target.view(Verdict.FAILED, actual.scope,
                        actual.error == null ? error : error + " Actual state could not be read: " + actual.error));
            }
        }
        Boolean notificationDisplayed = null;
        if (scopeWrote) {
            notificationDisplayed = false;
            try {
                reindex.pushMessage(user, "admin.notifications.task.custom.fields");
                notificationDisplayed = true;
            }
            catch (RuntimeException e) {
                LOG.error("Could not display the context-change reindex notification", e);
                error = (error == null ? "" : error + " ") + "Jira could not display its reindex notification; reindexing was not launched.";
            }
        }
        return new UpdateFieldContextsResponse(false, failed != null ? (wrote ? Status.PARTIAL : Status.FAILED)
                : Status.UPDATED, plan.preview.getField(), views, plan.preview.getUnchangedContexts(), scopeWrote,
                notificationDisplayed, failed != null ? ErrorCode.JIRA_ERROR : null, error, null, failed, null, null);
    }

    /** Add, rename, enable, disable, order: each native write is journaled by a checkpoint; nothing is deleted. */
    private void writeOptions(Target target) {
        OptionsPlan plan = target.options;
        for (OptionsPlan.Add add : plan.adds) {
            optionsManager.createOption(target.config, add.parentId, add.sequence, add.value);
            journal.checkpoint();
        }
        for (Map.Entry<Long, String> rename : plan.renames.entrySet()) {
            optionsManager.setValue(existingOption(rename.getKey()), rename.getValue());
            journal.checkpoint();
        }
        for (Long id : plan.enables) { optionsManager.enableOption(existingOption(id)); journal.checkpoint(); }
        for (Long id : plan.disables) { optionsManager.disableOption(existingOption(id)); journal.checkpoint(); }
        if (!plan.sequences.isEmpty()) {
            List<Option> moved = new ArrayList<Option>();
            for (Map.Entry<Long, Long> sequence : plan.sequences.entrySet()) {
                Option option = existingOption(sequence.getKey());
                option.setSequence(sequence.getValue());
                moved.add(option);
            }
            optionsManager.updateOptions(moved);
            journal.checkpoint();
        }
    }
    private Option existingOption(Long id) {
        Option option = optionsManager.findByOptionId(id);
        if (option == null) throw new IllegalStateException("Option " + id + " no longer exists.");
        return option;
    }

    private Observation observe(CustomField field, Target target) {
        Scope scope = null;
        try {
            refresh(field);
            CustomField current = fields.getCustomFieldObject(field.getId());
            if (current == null) throw new IllegalStateException("The field disappeared.");
            FieldConfigScheme found = null;
            for (FieldConfigScheme scheme : current.getConfigurationSchemes())
                if (target.scheme.getId().equals(scheme.getId())) { found = scheme; break; }
            if (found == null) throw new IllegalStateException("The context disappeared.");
            scope = ContextScopeRules.from(found);
            Set<Long> ids = configs(found).keySet();
            if (!ids.equals(Collections.singleton(target.config.getId())))
                throw new IllegalStateException("The context's FieldConfig ids changed: " + ids);
            return new Observation(scope, snapshots.config(current, target.config.getId()), null);
        } catch (RuntimeException e) {
            return new Observation(scope, null, e.getMessage());
        }
    }

    /**
     * A changed Assets configuration legitimately differs in its schema and AQL only; every other property of the bean,
     * like the rest of the config, must be as planned. A configuration that did not exist before has no baseline to compare.
     * The option tree of a target with an options change is verified against the approved final state instead
     * ({@link OptionsPlan#mismatch}); everything else of its config must still be unchanged.
     */
    private static boolean unchangedConfig(Target target, Map<String, Object> actual) {
        if (!target.assetsChanged && target.options == null) return target.configSnapshot.equals(actual);
        Map<String, Object> planned = new LinkedHashMap<String, Object>(target.configSnapshot);
        Map<String, Object> observed = new LinkedHashMap<String, Object>(actual);
        if (target.options != null) { planned.remove("options"); observed.remove("options"); }
        if (target.assetsChanged) {
            if (target.assetsBefore == null) { planned.remove("assets"); observed.remove("assets"); }
            else { withoutAssetsSettings(planned); withoutAssetsSettings(observed); }
        }
        return planned.equals(observed);
    }
    private static void withoutAssetsSettings(Map<String, Object> snapshot) {
        Object assetsSnapshot = snapshot.get("assets");
        if (assetsSnapshot instanceof String) snapshot.put("assets", AssetsFieldConfiguration.withoutSettings((String) assetsSnapshot));
    }
    private static boolean hasAssets(UpdateFieldContextsRequest request) {
        for (ContextUpdate update : request.getContexts()) if (update.getAssets() != null) return true;
        return false;
    }

    private void refresh(CustomField field) {
        fields.refreshConfigurationSchemes(Long.valueOf(field.getId().substring("customfield_".length())));
        fieldManager.refresh();
    }
    private List<JiraContextNode> nodes(Scope scope) {
        if (scope.isAllProjects()) return Collections.singletonList(GlobalIssueContext.getInstance());
        List<JiraContextNode> nodes = new ArrayList<JiraContextNode>(scope.getProjects().size());
        for (Project project : scope.getProjects()) nodes.add(new ProjectContext(project.getId(), projectManager));
        return nodes;
    }
    private static void validateRequest(UpdateFieldContextsRequest request) {
        if (request.getFieldId() == null || !request.getFieldId().matches("customfield_[1-9][0-9]*"))
            throw failure(ErrorCode.VALIDATION_ERROR, "fieldId must be the exact customfield_N id from get_field_contexts.");
        if (request.getContexts() == null || request.getContexts().isEmpty()
                || request.getContexts().size() > Limits.MAX_CONTEXTS_PER_FIELD)
            throw failure(ErrorCode.VALIDATION_ERROR, "contexts must contain between 1 and " + Limits.MAX_CONTEXTS_PER_FIELD + " entries.");
        Set<Long> seen = new HashSet<Long>();
        for (ContextUpdate update : request.getContexts()) {
            if (update == null || update.getContextId() == null || update.getContextId() <= 0 || !seen.add(update.getContextId()))
                throw failure(ErrorCode.VALIDATION_ERROR, "Every contextId must be positive and listed exactly once.");
            // Entries that only carry Assets settings and/or an options change leave the scope untouched; every other entry names both axes.
            boolean axesOmitted = update.isAllProjects() == null && update.getProjects() == null
                    && update.isAllIssueTypes() == null && update.getIssueTypes() == null;
            if ((update.getAssets() != null || update.getOptions() != null) && axesOmitted) continue;
            ScopeInput.checkAxis(update.isAllProjects(), update.getProjects(), Limits.MAX_PROJECTS_PER_CONTEXT, "projects");
            ScopeInput.checkAxis(update.isAllIssueTypes(), update.getIssueTypes(), Limits.MAX_ISSUE_TYPES_PER_CONTEXT, "issueTypes");
        }
    }
    private static void requireMode(UpdateFieldContextsRequest request) {
        if (request == null || request.isDryRun() == null) throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        if (request.isDryRun()) {
            if (request.getPlanId() != null) throw new InvalidRequestException("Preview does not accept planId.");
        } else if (request.getPlanId() == null || request.getPlanId().trim().isEmpty()
                || request.getFieldId() != null || request.getContexts() != null)
            throw new InvalidRequestException("Apply accepts only dryRun=false and the confirmed planId.");
    }
    private static Map<Long, FieldConfig> configs(FieldConfigScheme scheme) {
        Map<Long, FieldConfig> configs = new TreeMap<Long, FieldConfig>();
        for (FieldConfig config : scheme.getConfigs().values()) configs.put(config.getId(), config);
        return configs;
    }
    private static boolean unverifiable(String key) {
        return FieldContextTypes.typeClass(key) == FieldContextsResponse.TypeClass.THIRD_PARTY || GridFieldConfiguration.TYPE_KEY.equals(key);
    }
    private static Failure failure(ErrorCode code, String message) { return new Failure(code, message); }
    private static final class Failure extends RuntimeException {
        final ErrorCode code;
        final List<String> suggestions = Collections.emptyList();
        Failure(ErrorCode code, String message) { super(message); this.code = code; }
    }
    private static final class Prepared {
        final CustomField field;
        final List<Target> targets;
        final UpdateFieldContextsResponse preview;
        final String fingerprint;
        final FieldContextsResponse.FieldView fieldView;
        final List<ContextRef> untouched;
        final boolean changes, scopeChanges;
        Prepared(CustomField field, List<Target> targets, UpdateFieldContextsResponse preview, String fingerprint,
                 FieldContextsResponse.FieldView fieldView, List<ContextRef> untouched, boolean changes, boolean scopeChanges) {
            this.field = field; this.targets = targets; this.preview = preview; this.fingerprint = fingerprint;
            this.fieldView = fieldView; this.untouched = untouched; this.changes = changes; this.scopeChanges = scopeChanges;
        }
        Prepared(UpdateFieldContextsResponse failed) { this(null, null, failed, null, null, null, false, false); }
        boolean hasOptions() {
            for (Target target : targets) if (target.options != null) return true;
            return false;
        }
    }
    private static final class Target {
        final FieldConfigScheme scheme;
        final FieldConfig config;
        final Scope before, after;
        final Map<String, Object> delta, configSnapshot;
        final AssetsFieldSettings assetsBefore, assetsAfter;
        final boolean assetsChanged;
        /** The planned options change of this context; null when the request carried no {@code options} for it. */
        final OptionsPlan options;
        Target(FieldConfigScheme scheme, FieldConfig config, Scope before, Scope after,
               Map<String, Object> delta, Map<String, Object> configSnapshot,
               AssetsFieldSettings assetsBefore, AssetsFieldSettings assetsAfter, boolean assetsChanged, OptionsPlan options) {
            this.scheme = scheme; this.config = config; this.before = before; this.after = after;
            this.delta = delta; this.configSnapshot = configSnapshot;
            this.assetsBefore = assetsBefore; this.assetsAfter = assetsAfter; this.assetsChanged = assetsChanged;
            this.options = options;
        }
        boolean scopeChanged() { return !before.equals(after); }
        boolean changed() { return scopeChanged() || assetsChanged || (options != null && options.changed()); }
        ContextView view(Verdict verdict, Scope actual, String message) { return view(verdict, actual, message, null); }
        /** {@code usage} (option id to use) is shown only in a preview that counted it. */
        ContextView view(Verdict verdict, Scope actual, String message, Map<Long, OptionUsage> usage) {
            return new ContextView(scheme.getId(), scheme.getName(), verdict, before.toView(), after.toView(),
                    actual == null ? null : actual.toView(), delta, Collections.singletonList(config.getId()), message,
                    assetsView(), options == null ? null : options.view(usage));
        }
        private Map<String, Object> assetsView() {
            if (assetsAfter == null) return null;
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("before", settingsMap(assetsBefore)); view.put("after", settingsMap(assetsAfter));
            return view;
        }
        private static Map<String, Object> settingsMap(AssetsFieldSettings settings) {
            if (settings == null) return null;
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("objectSchemaId", settings.getObjectSchemaId()); map.put("filterScopeAql", settings.getFilterScopeAql());
            return map;
        }
    }
    private static final class Observation {
        final Scope scope;
        final Map<String, Object> configSnapshot;
        final String error;
        Observation(Scope scope, Map<String, Object> configSnapshot, String error) {
            this.scope = scope; this.configSnapshot = configSnapshot; this.error = error;
        }
    }
}
