package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.config.ReindexMessageManager;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItem;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItemService;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.ChangeLinks;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.customfields.api.FieldContextCreator;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextRequest;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextResponse;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextResponse.ErrorCode;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextResponse.ExistingContext;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextResponse.NewContext;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextResponse.Status;
import ru.rasulov.customfields.api.dto.context.FieldContextsResponse;
import ru.rasulov.customfields.impl.CustomFieldOptionsWriter;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.customfields.impl.context.ContextScopeRules.Scope;
import ru.rasulov.customfields.impl.validation.FieldFailureException;
import ru.rasulov.customfields.impl.validation.OptionsValidator;
import ru.rasulov.customfields.impl.validation.TypeKeys;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Creates ONE new context of an existing custom field and nothing else. The final set of contexts, the new one included,
 * is validated by the same {@link ContextScopeRules} as {@code update_field_contexts}. The write itself is Jira's
 * native context creation under the custom-field lock; it is observed afterwards, never retried or compensated.
 */
@Named
@ExportAsService(FieldContextCreator.class)
public class FieldContextCreatorImpl implements FieldContextCreator {
    private static final String OPERATION = "create_field_context";
    // The same cluster lock as the creator and update_field_contexts: one lock domain for field/context mutations.
    private static final String LOCK_NAME = "ru.rasulov.control.create-custom-fields";
    private static final Logger LOG = LoggerFactory.getLogger(FieldContextCreatorImpl.class);
    /** The id of the not yet existing context in the final-set validation; real contexts have positive ids. */
    private static final long NEW_CONTEXT = 0L;
    private static final Scope EMPTY = new Scope(false, Collections.<Project>emptyList(), false,
            Collections.<com.atlassian.jira.issue.issuetype.IssueType>emptyList());

    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final FieldSelector selector;
    private final ManagedConfigurationItemService managed;
    private final ProjectResolver projects;
    private final IssueTypeResolver issueTypes;
    private final CustomFieldManager fields;
    private final FieldManager fieldManager;
    private final ContextWriter writer;
    private final ReindexMessageManager reindex;
    private final FieldContextSnapshots snapshots;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final ChangeLinks links;
    private final OptionsValidator optionsValidator;
    private final CustomFieldOptionsWriter optionsWriter;
    private final AssetsFieldConfiguration assets;

    @Inject
    public FieldContextCreatorImpl(AdministratorGuard guard, NamedLock lock, FieldSelector selector,
            @ComponentImport ManagedConfigurationItemService managed, ProjectResolver projects,
            IssueTypeResolver issueTypes, @ComponentImport CustomFieldManager fields,
            @ComponentImport FieldManager fieldManager, ContextWriter writer,
            @ComponentImport ReindexMessageManager reindex, FieldContextSnapshots snapshots, PreviewPlanExecutor executor,
            ActionPlanCodec codec, ChangeJournal journal, ChangeLinks links, OptionsValidator optionsValidator,
            CustomFieldOptionsWriter optionsWriter, AssetsFieldConfiguration assets) {
        this.guard = guard; this.lock = lock; this.selector = selector; this.managed = managed;
        this.projects = projects; this.issueTypes = issueTypes; this.fields = fields; this.fieldManager = fieldManager;
        this.writer = writer; this.reindex = reindex; this.snapshots = snapshots; this.executor = executor;
        this.codec = codec; this.journal = journal; this.links = links;
        this.optionsValidator = optionsValidator; this.optionsWriter = optionsWriter; this.assets = assets;
    }

    @Override
    public CreateFieldContextResponse create(CreateFieldContextRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (!request.isDryRun()) return journal.execute(OPERATION, user.getKey(),
                () -> lock.run(LOCK_NAME, "custom fields", () -> apply(request.getPlanId(), user)));
        Prepared prepared = prepare(request, user);
        if (prepared.preview.getStatus() != Status.WOULD_CREATE) return prepared.preview;
        LOG.info("Dry run: would create context '{}' of field {} (user {})", prepared.name, prepared.field.getId(), user.getUsername());
        return executor.issue(OPERATION, user.getKey(), request, prepared.fingerprint, prepared.preview::withPlan);
    }

    private CreateFieldContextResponse apply(String planId, ApplicationUser user) {
        return executor.apply(OPERATION, user.getKey(), planId, CreateFieldContextRequest.class,
                CreateFieldContextResponse::stale, request -> prepare(request, user), current -> true,
                current -> current.fingerprint, current -> write(current, user));
    }

    private Prepared prepare(CreateFieldContextRequest request, ApplicationUser user) {
        try {
            validateRequest(request);
            String name = request.getName().trim();
            String description = request.getDescription() == null ? "" : request.getDescription().trim();
            CustomField field = selector.resolve(request.getFieldId());
            ManagedConfigurationItem item = managed.getManagedCustomField(field);
            if (item != null && !managed.doesUserHavePermission(user, item)) throw new Failure(ErrorCode.FIELD_LOCKED,
                    "Jira does not permit this administrator to edit the field's contexts.", null);
            CustomFieldType<?, ?> type = field.getCustomFieldType();
            String typeKey = type == null ? null : type.getKey();
            if (type == null) requireSupportedType(field, null, typeKey, false); // names the unavailable type, never "wrong type"
            if (request.getAssets() != null) {
                try {
                    assets.validate(typeKey, request.getAssets());
                } catch (FieldFailureException e) {
                    throw new Failure(ErrorCode.VALIDATION_ERROR, e.getMessage(), null);
                }
            }
            requireSupportedType(field, type, typeKey, request.getAssets() != null);
            List<OptionDefinition> options = options(type, typeKey, request.getOptions());
            String persisted = snapshots.persistedField(field);

            List<Existing> existing = new ArrayList<Existing>();
            Map<Long, Scope> finalScopes = new LinkedHashMap<Long, Scope>();
            Map<Long, String> names = new LinkedHashMap<Long, String>();
            for (FieldConfigScheme scheme : sorted(field.getConfigurationSchemes())) {
                Existing context = new Existing(field, scheme);
                existing.add(context);
                finalScopes.put(context.id, context.scope); names.put(context.id, context.name);
                if (normalize(context.name).equals(normalize(name))) throw new Failure(ErrorCode.CONTEXT_NAME_EXISTS,
                        "Context " + context.id + " of " + field.getId() + " is already named '" + context.name
                                + "'. Choose another name; the existing context is not renamed or extended.",
                        conflict(context.id, context.name, null));
            }
            try {
                ContextScopeRules.validate(finalScopes, names, Collections.<Long>emptySet());
            } catch (ContextScopeRules.Failure e) {
                throw new Failure(ErrorCode.VALIDATION_ERROR, "The existing contexts of " + field.getId()
                        + " already conflict, so a new context cannot be checked against them: " + e.getMessage()
                        + " Repair them with update_field_contexts first.", null);
            }

            Scope scope = ScopeInput.resolve(request.isAllProjects(), request.getProjects(), request.isAllIssueTypes(),
                    request.getIssueTypes(), projects.session(), issueTypes.session());
            finalScopes.put(NEW_CONTEXT, scope); names.put(NEW_CONTEXT, name);
            try {
                ContextScopeRules.validate(finalScopes, names, Collections.singleton(NEW_CONTEXT));
            } catch (ContextScopeRules.Failure e) {
                throw new Failure(ErrorCode.valueOf(e.getCode()), e.getMessage(),
                        conflict(e.getContextId(), e.getContextName(), projectOf(scope, e.getProjectId())));
            }

            Map<String, Object> delta = ContextScopeRules.delta(EMPTY, scope);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> warnings = new ArrayList<Map<String, Object>>(
                    (List<Map<String, Object>>) delta.get("warnings"));
            Map<String, Object> effective = effect(scope, name, existing, delta, warnings);
            Map<String, Object> settings = settings(typeKey, options, request.getAssets());

            List<ExistingContext> unchanged = new ArrayList<ExistingContext>();
            List<Object> states = new ArrayList<Object>();
            for (Existing context : existing) { unchanged.add(context.view()); states.add(context.state); }
            FieldContextsResponse.FieldView fieldView = new FieldContextsResponse.FieldView(field.getId(), field.getName(),
                    typeKey, type.getName(), FieldContextTypes.typeClass(typeKey), item != null && item.isManaged(), false);
            NewContext newContext = new NewContext(null, name, description, scope.toView(), null, Collections.<Long>emptyList());
            CreateFieldContextResponse response = new CreateFieldContextResponse(true, Status.WOULD_CREATE, fieldView,
                    newContext, unchanged, effective, settings, warnings, true, null, null, null, null, null, null, null, null);
            String fingerprint = codec.fingerprint(Arrays.asList(codec.fingerprint(response), persisted, states));
            return new Prepared(field, name, description, scope, options, request.getAssets(), existing, response, fingerprint);
        } catch (Failure e) {
            return new Prepared(CreateFieldContextResponse.failed(true, e.code, e.getMessage(), null, e.conflict));
        } catch (ScopeInput.Invalid e) {
            return new Prepared(CreateFieldContextResponse.failed(true, ErrorCode.VALIDATION_ERROR, e.getMessage(), null, null));
        } catch (LookupFailure e) {
            ErrorCode code = e.getKind() == LookupFailure.Kind.UNKNOWN_PROJECT ? ErrorCode.UNKNOWN_PROJECT
                    : e.getKind() == LookupFailure.Kind.UNKNOWN_ISSUE_TYPE ? ErrorCode.UNKNOWN_ISSUE_TYPE : ErrorCode.VALIDATION_ERROR;
            return new Prepared(CreateFieldContextResponse.failed(true, code, e.getMessage(), e.getSuggestions(), null));
        } catch (FieldSelector.Failure e) {
            return new Prepared(CreateFieldContextResponse.failed(true, ErrorCode.UNKNOWN_FIELD, e.getMessage(), null, null));
        } catch (RuntimeException e) {
            LOG.error("Cannot preview a custom field context", e);
            return new Prepared(CreateFieldContextResponse.failed(true, ErrorCode.JIRA_ERROR,
                    "Jira could not read the complete persisted field/configuration state; nothing was changed. Details are in the Jira log.",
                    null, null));
        }
    }

    /**
     * Jira supplies the new FieldConfig and its native initial config items; existing settings are never copied.
     * Only types usable without explicit additional setup, or built-in select-like types with their own explicit
     * option list, are accepted.
     */
    private static void requireSupportedType(CustomField field, CustomFieldType<?, ?> type, String key, boolean assetsRequested) {
        if (type == null) throw new Failure(ErrorCode.UNSUPPORTED_FIELD_TYPE, "The type of " + field.getId()
                + " is not available (its plugin may be disabled), so a context for it cannot be created.", null);
        if (type instanceof MultipleSettableCustomFieldType) {
            if (TypeKeys.contextTakesOptions(key)) return;
            throw new Failure(ErrorCode.UNSUPPORTED_FIELD_TYPE, "Field type '" + key + "' keeps its own options per "
                    + "context but is not a supported built-in select-like type; nothing was changed.", null);
        }
        // An Assets object context is usable only with its own object schema, which the caller supplies explicitly.
        if (assetsRequested && AssetsFieldConfiguration.TYPE_KEY.equals(key)) return;
        if (!TypeKeys.contextNeedsNoSettings(key)) throw new Failure(ErrorCode.UNSUPPORTED_FIELD_TYPE,
                "Field type '" + key + "' may need settings per context (plugin settings or other configuration) that this tool "
                        + "does not create or copy, and they cannot be verified; nothing was changed.", null);
    }

    /** The new context's own options: required and validated for select-like types, refused for every other type. */
    private List<OptionDefinition> options(CustomFieldType<?, ?> type, String key, List<OptionDefinition> requested) {
        if (!TypeKeys.contextTakesOptions(key)) {
            if (requested != null) throw new Failure(ErrorCode.VALIDATION_ERROR, "Field type '" + key
                    + "' has no options of its own. Remove 'options'.", null);
            return Collections.emptyList();
        }
        if (requested == null || requested.isEmpty()) throw new Failure(ErrorCode.VALIDATION_ERROR, "Field type '" + key
                + "' keeps its own options per context: supply 'options' for the new context (at least one). "
                + "Options of existing contexts are never copied.", null);
        try {
            return optionsValidator.validate(type, requested);
        } catch (FieldFailureException e) {
            throw new Failure(ErrorCode.VALIDATION_ERROR, e.getMessage(), null);
        }
    }

    private Map<String, Object> settings(String typeKey, List<OptionDefinition> requested, AssetsFieldSettings requestedAssets) {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        if (requested.isEmpty()) {
            options.put("created", "NONE"); options.put("copiedFromExistingContext", false);
            options.put("note", "No stored CustomFieldOption rows are created or copied. Built-in picker choices "
                    + "(projects, versions, users or groups) still come from Jira.");
        } else {
            options.put("created", "REQUESTED"); options.put("copiedFromExistingContext", false);
            options.put("count", countOptions(requested)); options.put("values", requested);
            options.put("note", "Exactly these options, in this order, are created enabled in the new context's own "
                    + "FieldConfig. Options of existing contexts are neither copied nor changed.");
        }
        Map<String, Object> defaults = new LinkedHashMap<String, Object>();
        defaults.put("value", "NONE"); defaults.put("copiedFromExistingContext", false);
        defaults.put("note", "No stored context default is created or copied; the type retains its native initial behavior.");
        Map<String, Object> plugin = new LinkedHashMap<String, Object>();
        plugin.put("typeClass", FieldContextTypes.typeClass(typeKey)); plugin.put("created", "JIRA_INITIAL_CONFIGURATION");
        plugin.put("copiedFromExistingContext", false);
        plugin.put("note", "Jira supplies the type's initial config items, including built-in picker/filter defaults. "
                + "No existing filter, version order or other context settings are copied.");
        Map<String, Object> settings = new LinkedHashMap<String, Object>();
        settings.put("fieldConfig", "Jira creates one new FieldConfig for the context; no existing FieldConfig is reused or changed.");
        settings.put("options", options); settings.put("defaultValue", defaults); settings.put("pluginSettings", plugin);
        if (requestedAssets != null) {
            Map<String, Object> stored = new LinkedHashMap<String, Object>();
            stored.put("objectSchemaId", requestedAssets.getObjectSchemaId());
            stored.put("filterScopeAql", requestedAssets.getFilterScopeAql());
            plugin.put("created", "ASSETS_SETTINGS"); plugin.put("assets", stored);
            plugin.put("note", "Jira supplies the type's initial config items, then the requested Assets object schema and "
                    + "filter scope are stored on the new FieldConfig only. No existing context's Assets settings are copied or changed.");
        }
        return settings;
    }

    private static int countOptions(List<OptionDefinition> options) {
        int count = 0;
        for (OptionDefinition option : options) count += 1 + option.getChildren().size();
        return count;
    }

    /**
     * Jira selects a scheme by project before looking up the issue type. A project scheme shadows the global scheme
     * for the whole project: issue types missing from the project scheme have no global fallback.
     */
    private static Map<String, Object> effect(Scope scope, String name, List<Existing> existing,
                                              Map<String, Object> delta, List<Map<String, Object>> warnings) {
        Existing global = null;
        for (Existing context : existing) if (context.scope.isAllProjects()) global = context;
        List<Object> switches = new ArrayList<Object>(), kept = new ArrayList<Object>();
        for (Existing context : existing) {
            Scope covered = context.scope.isAllProjects() ? context.scope
                    : new Scope(false, context.scope.getProjects(), true, null);
            Map<String, Object> overlap = ContextScopeRules.overlap(scope, covered);
            if (((Number) overlap.get("count")).longValue() == 0) continue;
            Map<String, Object> row = new LinkedHashMap<String, Object>();
            row.put("contextId", context.id); row.put("name", context.name); row.put("configIds", context.configIds);
            row.put("combinations", overlap);
            if (context.scope.isAllProjects()) {
                row.put("newContext", name);
                row.put("storedSettingRowsNotCopied", context.storedRows);
                switches.add(row);
            } else kept.add(row);
        }
        Object newly = global != null && !scope.isAllProjects()
                ? ContextScopeRules.delta(global.scope, scope).get("addedCombinations") : delta.get("addedCombinations");
        Map<String, Object> lost = (Map<String, Object>) (global != null && !scope.isAllProjects()
                ? ContextScopeRules.delta(new Scope(false, scope.getProjects(), global.scope.isAllIssueTypes(),
                        global.scope.getIssueTypes()), scope).get("removedCombinations")
                : delta.get("removedCombinations"));
        Map<String, Object> effective = new LinkedHashMap<String, Object>();
        effective.put("newContextCombinations", delta.get("addedCombinations"));
        effective.put("newlyApplicable", newly);
        effective.put("newlyApplicableExact", kept.isEmpty());
        effective.put("configSwitches", switches);
        effective.put("lostApplicability", lost);
        effective.put("keptByExistingContexts", kept);
        effective.put("existingBehaviorChanged", !switches.isEmpty() || ((Number) lost.get("count")).longValue() > 0);
        if (!switches.isEmpty()) {
            Map<String, Object> warning = new LinkedHashMap<String, Object>();
            warning.put("code", "OVERRIDES_GLOBAL_CONTEXT");
            warning.put("message", "Not only an addition: for the combinations in effectiveChange.configSwitches the field stops "
                    + "using the config of global context '" + global.name + "' (config " + global.configIds
                    + ") and uses the new context's own config instead. Stored options, defaults and settings are not "
                    + "copied from it (the global config stores " + global.storedRows + " generic setting row(s)). "
                    + "Existing issue values are not changed or deleted.");
            warnings.add(warning);
        }
        if (((Number) lost.get("count")).longValue() > 0) {
            Map<String, Object> warning = new LinkedHashMap<String, Object>();
            warning.put("code", "GLOBAL_APPLICABILITY_REMOVED");
            warning.put("message", "The project context shadows the global context for every issue type in its projects. "
                    + "Types outside the new scope have no global fallback: see effectiveChange.lostApplicability. "
                    + "Stored issue values are not deleted, but field applicability, visibility and search may change.");
            warnings.add(warning);
        }
        if (!kept.isEmpty()) {
            Map<String, Object> warning = new LinkedHashMap<String, Object>();
            warning.put("code", "EXISTING_PROJECT_CONTEXTS_KEEP_PRIORITY");
            warning.put("message", "The new global context does not apply to the combinations listed in "
                    + "effectiveChange.keptByExistingContexts: project contexts keep project-wide priority, even for "
                    + "types absent from their scope. newlyApplicable is an upper bound that still includes them.");
            warnings.add(warning);
        }
        return effective;
    }

    private CreateFieldContextResponse write(Prepared plan, ApplicationUser user) {
        Set<Long> before = new HashSet<Long>();
        for (Existing context : plan.existing) before.add(context.id);
        String error = null;
        // Native observations survive both journal poststate failures and subsequent verification failures.
        try {
            journal.checkpoint();
            FieldConfigScheme created = writer.createAdditional(plan.field, plan.name, plan.description, plan.scope,
                    () -> plan.nativeAttempted = true, id -> plan.createdId = id);
            journal.checkpoint();
            if (!plan.options.isEmpty()) {
                FieldConfig config = created == null ? null : created.getOneAndOnlyConfig();
                if (config == null) throw new IllegalStateException(
                        "Jira returned no FieldConfig for the new context, so its options were not created.");
                optionsWriter.write(config, plan.options);
            }
            if (plan.assets != null) {
                FieldConfig config = created == null ? null : created.getOneAndOnlyConfig();
                if (config == null) throw new IllegalStateException(
                        "Jira returned no FieldConfig for the new context, so its Assets settings were not stored.");
                assets.configure(config.getId(), Long.valueOf(plan.field.getId().substring("customfield_".length())), plan.assets);
            }
            journal.checkpoint();
        } catch (RuntimeException e) {
            LOG.error("Failed to create context '{}' of {}", plan.name, plan.field.getId(), e);
            error = "Context creation stopped. No retry or rollback was performed. " + e.getMessage();
        }
        Verification actual = verify(plan, before);
        boolean confirmed = error == null && actual.unreadable == null && actual.problems.isEmpty() && actual.created != null;
        boolean wrote = plan.nativeAttempted || actual.created != null || actual.existingChanged || actual.unreadable != null;
        if (!confirmed) {
            StringBuilder message = new StringBuilder(error == null ? "The new context could not be confirmed." : error);
            for (String problem : actual.problems) message.append(' ').append(problem);
            if (actual.unreadable != null) message.append(" Actual state could not be read: ").append(actual.unreadable);
            error = message.toString();
        }
        Boolean notificationDisplayed = null;
        if (wrote) {
            notificationDisplayed = false;
            try {
                reindex.pushMessage(user, "admin.notifications.task.custom.fields");
                notificationDisplayed = true;
            } catch (RuntimeException e) {
                LOG.error("Could not display the context-change reindex notification", e);
                error = (error == null ? "" : error + " ") + "Jira could not display its reindex notification; reindexing was not launched.";
            }
        }
        NewContext view = actual.created;
        ChangeLinks.Report report = links.report();
        if (actual.created != null) {
            report.reused(ChangeKind.CUSTOM_FIELD, plan.field.getId(), plan.field.getName(), plan.field.getId());
            report.created(ChangeKind.FIELD_CONTEXT, String.valueOf(actual.created.getContextId()), plan.name, plan.field.getId());
        }
        if (confirmed && !plan.options.isEmpty())
            report.createdOptions(String.valueOf(actual.configIds.get(0)), plan.field.getName() + " / " + plan.name,
                    countOptions(plan.options));
        Status status = confirmed ? Status.CREATED : wrote ? Status.PARTIAL : Status.FAILED;
        if (confirmed) LOG.info("Created context {} (config {}) '{}' of field {} (user {})", actual.created.getContextId(),
                actual.configIds, plan.name, plan.field.getId(), user.getUsername());
        return new CreateFieldContextResponse(false, status, plan.preview.getField(), view,
                actual.unreadable == null ? actual.others : Collections.<ExistingContext>emptyList(),
                plan.preview.getEffectiveChange(), plan.preview.getSettings(), plan.preview.getWarnings(), wrote,
                notificationDisplayed, error != null && !confirmed ? ErrorCode.JIRA_ERROR : null,
                error, null, null, null, null, null).withReport(report.build());
    }

    private Verification verify(Prepared plan, Set<Long> before) {
        try {
            fields.refreshConfigurationSchemes(Long.valueOf(plan.field.getId().substring("customfield_".length())));
            fieldManager.refresh();
            CustomField current = fields.getCustomFieldObject(plan.field.getId());
            if (current == null) return Verification.unreadable(plan, "The field disappeared.");
            Map<Long, FieldConfigScheme> now = new TreeMap<Long, FieldConfigScheme>();
            for (FieldConfigScheme scheme : current.getConfigurationSchemes()) now.put(scheme.getId(), scheme);
            List<FieldConfigScheme> added = new ArrayList<FieldConfigScheme>();
            for (Map.Entry<Long, FieldConfigScheme> entry : now.entrySet())
                if (!before.contains(entry.getKey())) added.add(entry.getValue());
            // Capture the discovered identity before any settings reads, which may themselves fail.
            if (plan.createdId == null && added.size() == 1) plan.createdId = added.get(0).getId();
            List<String> problems = new ArrayList<String>();
            boolean existingChanged = false;
            List<ExistingContext> others = new ArrayList<ExistingContext>();
            for (Existing context : plan.existing) {
                FieldConfigScheme scheme = now.get(context.id);
                if (scheme == null) {
                    problems.add("Existing context " + context.id + " (" + context.name + ") disappeared.");
                    existingChanged = true;
                } else if (!context.state.equals(new Existing(current, scheme).state)) {
                    problems.add("Existing context " + context.id + " (" + context.name + ") changed; its name, description, scope, "
                            + "config, options, default or readable settings differ from before the write.");
                    existingChanged = true;
                }
            }
            FieldConfigScheme created = null;
            if (added.size() > 1) problems.add("More than one new context appeared: " + added.size() + ".");
            if (plan.createdId != null && !before.contains(plan.createdId) && now.containsKey(plan.createdId))
                created = now.get(plan.createdId);
            else if (plan.createdId != null) problems.add("Jira returned context " + plan.createdId
                    + " but it is not a new context of the field.");
            else problems.add("No new context was observed; the native write may have left unobserved configuration data.");
            List<Long> configIds = new ArrayList<Long>();
            NewContext view = null;
            if (created != null) {
                Existing state = new Existing(current, created);
                configIds = state.configIds;
                if (!plan.name.equals(created.getName())) problems.add("The stored context name differs from the approved one.");
                if (!plan.description.equals(created.getDescription() == null ? "" : created.getDescription()))
                    problems.add("The stored context description differs from the approved one.");
                if (!plan.scope.equals(state.scope)) problems.add("The stored scope differs from the approved scope.");
                if (state.configIds.size() != 1) problems.add("The new context must have exactly one distinct FieldConfig; found "
                        + state.configIds.size() + ".");
                if (state.storedRows != 0 || state.configuredUserFilter)
                    problems.add("The new context holds stored defaults/settings or a configured user filter "
                            + "instead of the approved Jira initial configuration.");
                if (plan.assets != null) {
                    AssetsFieldSettings stored = state.configIds.size() == 1
                            ? assets.settings(assets.snapshot(state.configIds.get(0))) : null;
                    if (stored == null || !AssetsFieldConfiguration.sameSettings(plan.assets, stored))
                        problems.add("The stored Assets settings of the new context differ from the approved ones.");
                }
                if (!expectedOptions(plan.options).equals(state.optionTree))
                    problems.add("The stored options of the new context differ from the approved ones "
                            + "(values, order, nesting or enabled state).");
                view = new NewContext(created.getId(), created.getName(),
                        created.getDescription() == null ? "" : created.getDescription(), plan.scope.toView(),
                        state.scope.toView(), configIds);
            }
            for (FieldConfigScheme scheme : now.values())
                if (!before.contains(scheme.getId()) && scheme != created) others.add(new Existing(current, scheme).view());
            for (Existing context : plan.existing) if (now.containsKey(context.id)) others.add(new Existing(current, now.get(context.id)).view());
            if (view == null && plan.createdId != null) view = unknownContext(plan);
            return new Verification(view, configIds, problems, others, existingChanged, null);
        } catch (RuntimeException e) {
            return Verification.unreadable(plan, String.valueOf(e.getMessage()));
        }
    }

    private static NewContext unknownContext(Prepared plan) {
        return new NewContext(plan.createdId, plan.name, plan.description, plan.scope.toView(), null,
                Collections.<Long>emptyList());
    }

    private static void validateRequest(CreateFieldContextRequest request) {
        if (request.getFieldId() == null || !request.getFieldId().matches("customfield_[1-9][0-9]*"))
            throw new Failure(ErrorCode.VALIDATION_ERROR, "fieldId must be the exact customfield_N id from get_field_contexts.", null);
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty() || name.length() > Limits.MAX_NAME_LENGTH)
            throw new Failure(ErrorCode.VALIDATION_ERROR,
                    "name is required and must have 1 to " + Limits.MAX_NAME_LENGTH + " characters.", null);
        if (request.getDescription() != null && request.getDescription().trim().length() > Limits.MAX_CONTEXT_DESCRIPTION_LENGTH)
            throw new Failure(ErrorCode.VALIDATION_ERROR,
                    "description exceeds the maximum of " + Limits.MAX_CONTEXT_DESCRIPTION_LENGTH + " characters.", null);
        ScopeInput.checkAxis(request.isAllProjects(), request.getProjects(), Limits.MAX_PROJECTS_PER_CONTEXT, "projects");
        ScopeInput.checkAxis(request.isAllIssueTypes(), request.getIssueTypes(), Limits.MAX_ISSUE_TYPES_PER_CONTEXT, "issueTypes");
    }

    private static void requireMode(CreateFieldContextRequest request) {
        if (request == null || request.isDryRun() == null) throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        if (request.isDryRun()) {
            if (request.getPlanId() != null) throw new InvalidRequestException("Preview does not accept planId.");
        } else if (request.getPlanId() == null || request.getPlanId().trim().isEmpty()
                || request.getFieldId() != null || request.getName() != null || request.getDescription() != null
                || request.isAllProjects() != null || request.getProjects() != null
                || request.isAllIssueTypes() != null || request.getIssueTypes() != null || request.getOptions() != null
                || request.getAssets() != null)
            throw new InvalidRequestException("Apply accepts only dryRun=false and the confirmed planId.");
    }

    private static List<FieldConfigScheme> sorted(Collection<FieldConfigScheme> schemes) {
        List<FieldConfigScheme> sorted = new ArrayList<FieldConfigScheme>(schemes);
        sorted.sort(Comparator.comparing(FieldConfigScheme::getId));
        return sorted;
    }

    /** The approved options in the shape of {@link Existing#optionTree}: [value, disabled, [[value, disabled]...]]. */
    private static List<Object> expectedOptions(List<OptionDefinition> options) {
        List<Object> tree = new ArrayList<Object>();
        for (OptionDefinition option : options) {
            List<Object> children = new ArrayList<Object>();
            for (OptionDefinition child : option.getChildren()) children.add(Arrays.<Object>asList(child.getValue(), false));
            tree.add(Arrays.<Object>asList(option.getValue(), false, children));
        }
        return tree;
    }

    /**
     * Stored option rows [id, parent, sequence, value, disabled] as an ordered tree; a row whose parent is not a
     * top-level option of the same config is kept as a separate orphan entry so that it never compares equal.
     */
    private static List<Object> optionTree(List<?> rows) {
        Map<Long, List<List<?>>> byParent = new HashMap<Long, List<List<?>>>();
        for (Object item : rows) {
            List<?> row = (List<?>) item;
            byParent.computeIfAbsent((Long) row.get(1), key -> new ArrayList<List<?>>()).add(row);
        }
        Comparator<List<?>> bySequence = Comparator.comparing(row -> (Long) row.get(2),
                Comparator.nullsFirst(Comparator.<Long>naturalOrder()));
        List<Object> tree = new ArrayList<Object>();
        List<List<?>> roots = byParent.remove(null);
        if (roots != null) {
            roots.sort(bySequence);
            for (List<?> root : roots) {
                List<Object> children = new ArrayList<Object>();
                List<List<?>> nested = byParent.remove((Long) root.get(0));
                if (nested != null) {
                    nested.sort(bySequence);
                    for (List<?> child : nested) children.add(Arrays.<Object>asList(child.get(3), disabled(child.get(4))));
                }
                tree.add(Arrays.<Object>asList(root.get(3), disabled(root.get(4)), children));
            }
        }
        for (List<List<?>> orphans : byParent.values()) tree.add(Arrays.<Object>asList("ORPHAN", orphans.size()));
        return tree;
    }

    private static boolean disabled(Object stored) {
        return stored != null && ("Y".equalsIgnoreCase(stored.toString()) || "true".equalsIgnoreCase(stored.toString()));
    }

    private static Map<String, Object> conflict(Long contextId, String contextName, Map<String, Object> project) {
        Map<String, Object> conflict = new LinkedHashMap<String, Object>();
        conflict.put("contextId", contextId); conflict.put("contextName", contextName);
        if (project != null) conflict.put("project", project);
        return conflict;
    }

    private static Map<String, Object> projectOf(Scope scope, Long projectId) {
        if (projectId == null) return null;
        for (Project project : scope.getProjects()) if (projectId.equals(project.getId())) {
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("id", project.getId()); view.put("key", project.getKey()); view.put("name", project.getName());
            return view;
        }
        return null;
    }

    /** A context as Jira has it now; {@code state} is what must stay equal for an untouched context. */
    private final class Existing {
        final Long id;
        final String name, description;
        final Scope scope;
        final List<Long> configIds = new ArrayList<Long>();
        final Map<String, Object> state = new LinkedHashMap<String, Object>();
        int storedRows;
        boolean configuredUserFilter;
        final List<Object> optionTree = new ArrayList<Object>();

        Existing(CustomField field, FieldConfigScheme scheme) {
            id = scheme.getId(); name = scheme.getName();
            description = scheme.getDescription() == null ? "" : scheme.getDescription();
            scope = ContextScopeRules.from(scheme);
            Map<Long, Object> configs = new TreeMap<Long, Object>();
            for (FieldConfig config : scheme.getConfigs().values()) {
                if (configs.containsKey(config.getId())) continue;
                Map<String, Object> snapshot = snapshots.config(field, config.getId());
                configs.put(config.getId(), snapshot); configIds.add(config.getId());
                storedRows += ((List<?>) snapshot.get("genericConfiguration")).size();
                optionTree.addAll(optionTree((List<?>) snapshot.get("options")));
                List<?> filters = (List<?>) snapshot.get("userPickerFilters");
                if (filters != null) {
                    configuredUserFilter |= filters.size() > 1;
                    for (Object item : filters) {
                        Map<?, ?> filter = (Map<?, ?>) item;
                        Map<?, ?> row = (Map<?, ?>) filter.get("filter");
                        configuredUserFilter |= Boolean.TRUE.equals(row.get("enabled"))
                                || !((List<?>) filter.get("groups")).isEmpty() || !((List<?>) filter.get("roles")).isEmpty();
                    }
                }
            }
            Collections.sort(configIds);
            state.put("id", id); state.put("name", name); state.put("description", description);
            state.put("scope", scope.toView()); state.put("configs", configs);
        }

        ExistingContext view() { return new ExistingContext(id, name, description, scope.toView(), configIds); }
    }

    private static final class Verification {
        final NewContext created;
        final List<Long> configIds;
        final List<String> problems;
        final List<ExistingContext> others;
        final boolean existingChanged;
        final String unreadable;

        Verification(NewContext created, List<Long> configIds, List<String> problems, List<ExistingContext> others,
                     boolean existingChanged, String unreadable) {
            this.created = created; this.configIds = configIds; this.problems = problems; this.others = others;
            this.existingChanged = existingChanged; this.unreadable = unreadable;
        }

        static Verification unreadable(Prepared plan, String reason) {
            return new Verification(plan.createdId == null ? null : unknownContext(plan),
                    Collections.<Long>emptyList(), Collections.<String>emptyList(),
                    Collections.<ExistingContext>emptyList(), false, reason);
        }
    }

    private static final class Failure extends RuntimeException {
        final ErrorCode code;
        final Map<String, Object> conflict;
        Failure(ErrorCode code, String message, Map<String, Object> conflict) {
            super(message); this.code = code; this.conflict = conflict;
        }
    }

    private static final class Prepared {
        final CustomField field;
        final String name, description;
        final Scope scope;
        final List<OptionDefinition> options;
        final AssetsFieldSettings assets;
        final List<Existing> existing;
        final CreateFieldContextResponse preview;
        final String fingerprint;
        boolean nativeAttempted;
        Long createdId;
        Prepared(CustomField field, String name, String description, Scope scope, List<OptionDefinition> options,
                 AssetsFieldSettings assets, List<Existing> existing, CreateFieldContextResponse preview, String fingerprint) {
            this.field = field; this.name = name; this.description = description; this.scope = scope;
            this.options = options; this.assets = assets; this.existing = existing; this.preview = preview;
            this.fingerprint = fingerprint;
        }
        Prepared(CreateFieldContextResponse failed) { this(null, null, null, null, null, null, null, failed, null); }
    }
}
