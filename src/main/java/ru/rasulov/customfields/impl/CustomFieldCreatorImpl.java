package ru.rasulov.customfields.impl;

import com.atlassian.jira.exception.RemoveException;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.GenericEntityException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.CustomFieldCreationService;
import ru.rasulov.common.api.CustomFieldCreationLimits;
import ru.rasulov.customfields.api.CustomFieldCreator;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.customfields.api.dto.CustomFieldTypeResponse;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.customfields.impl.filefield.FileFieldConfiguration;
import ru.rasulov.customfields.impl.grid.GridFieldConfiguration;
import ru.rasulov.common.api.dto.field.context.ContextSummary;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.customfields.impl.context.CreatedFieldReadback;
import ru.rasulov.customfields.impl.context.ContextSummarizer;
import ru.rasulov.customfields.impl.context.ContextWriter;
import ru.rasulov.customfields.impl.context.ResolvedContext;
import ru.rasulov.customfields.impl.validation.FieldFailureException;
import ru.rasulov.customfields.impl.validation.OptionsValidator;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.ChangeLinks;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.api.dto.field.CreationStatus;

import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.common.impl.undo.JournalCancelledException;
import ru.rasulov.common.impl.undo.JournalCapacityException;
import ru.rasulov.customfields.impl.undo.CustomFieldUndoHandler;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static ru.rasulov.common.impl.Normalizer.normalize;

/**
 * Validates and previews requested fields, then applies an approved plan under the native field lock.
 * Every field/context/option write has a durable intent and observed poststate. The coordinator compensates
 * proven writes on failure; uncertain identities and foreign edits remain blocked for safe recovery.
 */
@ExportAsService({CustomFieldCreator.class, CustomFieldCreationService.class})
@Named
public class CustomFieldCreatorImpl implements CustomFieldCreator {

    static final String LOCK_NAME = "ru.rasulov.control.create-custom-fields";

    private static final Logger log = LoggerFactory.getLogger(CustomFieldCreatorImpl.class);

    private final CustomFieldManager customFieldManager;
    private final AdministratorGuard administratorGuard;
    private final NamedLock namedLock;
    private final CustomFieldRequestValidator validator;
    private final ContextWriter contextWriter;
    private final ContextSummarizer contextSummarizer;
    private final CustomFieldOptionsWriter optionsWriter;
    private final CustomFieldTypeCatalog typeCatalog;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec planCodec;
    private final ChangeJournal journal;
    private final CustomFieldUndoHandler undo;
    private final AssetsFieldConfiguration assets;
    private final FileFieldConfiguration fileFields;
    private final GridFieldConfiguration grids;
    private final ChangeLinks links;
    private final CreatedFieldReadback readback;

    @Inject
    public CustomFieldCreatorImpl(@ComponentImport CustomFieldManager customFieldManager,
                                  AdministratorGuard administratorGuard,
                                  NamedLock namedLock,
                                  CustomFieldRequestValidator validator,
                                  ContextWriter contextWriter,
                                  ContextSummarizer contextSummarizer,
                                  CustomFieldOptionsWriter optionsWriter,
                                  CustomFieldTypeCatalog typeCatalog,
                                  PreviewPlanExecutor executor, ActionPlanCodec planCodec,
                                  ChangeJournal journal, CustomFieldUndoHandler undo,
                                  AssetsFieldConfiguration assets, FileFieldConfiguration fileFields,
                                  GridFieldConfiguration grids, ChangeLinks links, CreatedFieldReadback readback) {
        this.customFieldManager = customFieldManager;
        this.administratorGuard = administratorGuard;
        this.namedLock = namedLock;
        this.validator = validator;
        this.contextWriter = contextWriter;
        this.contextSummarizer = contextSummarizer;
        this.optionsWriter = optionsWriter;
        this.typeCatalog = typeCatalog;
        this.executor = executor;
        this.planCodec = planCodec;
        this.journal = journal;
        this.undo = undo;
        this.assets = assets;
        this.fileFields = fileFields;
        this.grids = grids;
        this.links = links;
        this.readback = readback;
    }

    @Override
    public CreateCustomFieldsResponse createAll(CreateCustomFieldsRequest request) {
        ApplicationUser user = administratorGuard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            checkCallLimits(request);
            CreationPlan current = plan(request, user);
            CreateCustomFieldsResponse response = current.preview;
            if (!hasWrites(response)) return response;
            return executor.issue("create_custom_fields", user.getKey(), request, fingerprint(current),
                    response::withPlan);
        }
        return journal.execute("create_custom_fields", user.getKey(),
                () -> namedLock.run(LOCK_NAME, "custom fields", () -> apply(request.getPlanId(), user)));
    }

    @Override
    public List<CustomFieldTypeResponse> listAvailableTypes() {
        administratorGuard.requireAdministrator();
        return typeCatalog.list();
    }

    private CreateCustomFieldsResponse apply(String planId, ApplicationUser user) {
        return executor.apply("create_custom_fields", user.getKey(), planId, CreateCustomFieldsRequest.class,
                CreateCustomFieldsResponse::stale, preview -> plan(preview, user), current -> true,
                this::fingerprint, current -> write(current, user));
    }

    private static void requireMode(CreateCustomFieldsRequest request) {
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun() && request.getPlanId() != null) {
            throw new InvalidRequestException("Preview takes fields, not planId.");
        }
        if (!request.isDryRun() && (!request.getFields().isEmpty() || request.getPlanId() == null
                || request.getPlanId().trim().isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }

    private static boolean hasWrites(CreateCustomFieldsResponse response) {
        for (FieldCreationResult result : response.getResults()) {
            if (result.getStatus() == ru.rasulov.common.api.dto.field.CreationStatus.WOULD_CREATE) return true;
        }
        return false;
    }

    private static void checkCallLimits(CreateCustomFieldsRequest request) {
        if (request == null || request.getFields().isEmpty()) {
            throw new InvalidRequestException("'fields' must contain at least one field definition.");
        }
        if (request.getFields().size() > CustomFieldCreationLimits.MAX_FIELDS_PER_CALL) {
            throw new InvalidRequestException("'fields' has " + request.getFields().size()
                    + " elements; the maximum is " + CustomFieldCreationLimits.MAX_FIELDS_PER_CALL + " per call. Split the request.");
        }
        int options = OptionsValidator.countOptions(request.getFields());
        if (options > Limits.MAX_OPTIONS_PER_CALL) {
            throw new InvalidRequestException("The call contains " + options + " options in total; the maximum is "
                    + Limits.MAX_OPTIONS_PER_CALL + " per call. Split the request.");
        }
    }

    private CreationPlan plan(CreateCustomFieldsRequest request, ApplicationUser user) {
        customFieldManager.refresh();
        ExistingFieldIndex existing = new ExistingFieldIndex(customFieldManager);
        Set<String> namesInCall = new HashSet<String>();
        List<FieldPlan> fields = new ArrayList<FieldPlan>(request.getFields().size());
        List<FieldCreationResult> results = new ArrayList<FieldCreationResult>(request.getFields().size());
        for (FieldDefinition definition : request.getFields()) {
            FieldPlan field = planField(definition, existing, namesInCall, user);
            fields.add(field);
            results.add(field.preview);
        }
        return new CreationPlan(fields, new CreateCustomFieldsResponse(true, results));
    }

    private FieldPlan planField(FieldDefinition definition, ExistingFieldIndex existing,
                                Set<String> namesInCall, ApplicationUser user) {
        String reportedName = definition == null ? null : definition.getName();
        try {
            ResolvedFieldDefinition resolved = validator.validate(definition);
            reportedName = resolved.getName();
            assets.validate(resolved.getType().getKey(), definition.getAssets());
            fileFields.validate(resolved.getType().getKey(), definition.getFileField());
            grids.validate(resolved.getType().getKey(), definition.getGrid());

            if (!namesInCall.add(normalize(resolved.getName()))) {
                throw new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR,
                        "Field name '" + resolved.getName() + "' is used more than once in this call. Keep one definition.");
            }

            List<CustomField> sameName = existing.find(resolved.getName());
            if (!sameName.isEmpty()) {
                FieldCreationResult result = handleExisting(resolved, sameName, user);
                List<String> ignored = new ArrayList<String>();
                if (definition.getAssets() != null) ignored.add("assets");
                if (definition.getFileField() != null) ignored.add("fileField");
                if (definition.getGrid() != null) ignored.add("grid");
                if (!ignored.isEmpty()) {
                    String message = result.getMessage() == null ? "" : result.getMessage() + " ";
                    result = FieldCreationResult.exists(result.getName(), result.getCustomFieldId(), result.getContexts(),
                            message + "Ignored from the request: " + String.join(", ", ignored)
                                    + ". Existing plugin settings were not changed.");
                }
                return new FieldPlan(null, null, null, result);
            }
            List<ContextSummary> contexts = contextSummarizer.summarize(resolved.getContexts());
            log.info("Dry run: would create custom field '{}' of type {} in {} context(s) (user {})",
                    resolved.getName(), resolved.getType().getKey(), contexts.size(), user.getUsername());
            return new FieldPlan(resolved, contexts, definition,
                    FieldCreationResult.wouldCreate(resolved.getName(), contexts,
                            creationMessage(resolved, definition)));
        } catch (FieldFailureException e) {
            return new FieldPlan(null, null, null,
                    FieldCreationResult.failed(reportedName, e.getErrorCode(), e.getMessage(), e.getSuggestions()));
        }
    }

    private CreateCustomFieldsResponse write(CreationPlan plan, ApplicationUser user) {
        List<FieldCreationResult> results = new ArrayList<FieldCreationResult>(plan.fields.size());
        ChangeLinks.Report report = links.report();
        for (FieldPlan field : plan.fields) {
            journal.checkpoint();
            if (field.resolved == null) {
                results.add(field.preview);
                if (field.preview.getStatus() == CreationStatus.EXISTS) {
                    report.reused(ChangeKind.CUSTOM_FIELD, field.preview.getCustomFieldId(),
                            field.preview.getName(), field.preview.getCustomFieldId());
                }
                continue;
            }
            try {
                results.add(create(field.resolved, field.contexts, user, field.definition, report));
            } catch (FieldFailureException e) {
                results.add(FieldCreationResult.failed(field.resolved.getName(), e.getErrorCode(), e.getMessage(),
                        e.getSuggestions()));
            }
        }
        return new CreateCustomFieldsResponse(false, results).withReport(report.build());
    }

    private String fingerprint(CreationPlan plan) {
        List<String> searcherKeys = new ArrayList<String>(plan.fields.size());
        for (FieldPlan field : plan.fields) searcherKeys.add(field.searcherKey);
        return planCodec.fingerprint(Arrays.asList(plan.preview, searcherKeys));
    }

    /** Request-local only: never store resolved Jira objects in ActionPlanStore. */
    private static final class CreationPlan {
        private final List<FieldPlan> fields;
        private final CreateCustomFieldsResponse preview;

        private CreationPlan(List<FieldPlan> fields, CreateCustomFieldsResponse preview) {
            this.fields = fields;
            this.preview = preview;
        }
    }

    private static final class FieldPlan {
        private final ResolvedFieldDefinition resolved;
        private final String searcherKey;
        private final List<ContextSummary> contexts;
        private final FieldDefinition definition;
        private final FieldCreationResult preview;

        private FieldPlan(ResolvedFieldDefinition resolved, List<ContextSummary> contexts,
                          FieldDefinition definition, FieldCreationResult preview) {
            this.resolved = resolved;
            this.searcherKey = resolved == null ? null : searcherKeyOf(resolved.getSearcher());
            this.contexts = contexts;
            this.definition = definition;
            this.preview = preview;
        }
    }

    private FieldCreationResult handleExisting(ResolvedFieldDefinition resolved, List<CustomField> sameName,
                                               ApplicationUser user) {
        String wantedType = resolved.getType().getKey();
        if (sameName.size() == 1 && wantedType.equals(typeKeyOf(sameName.get(0)))) {
            CustomField existing = sameName.get(0);
            log.info("Custom field '{}' already exists as {}, skipped (user {})",
                    resolved.getName(), existing.getId(), user.getUsername());
            List<ContextSummary> actual = contextSummarizer.summarize(existing);
            return FieldCreationResult.exists(resolved.getName(), existing.getId(), actual,
                    ignoredParts(resolved, existing, actual));
        }
        List<String> suggestions = new ArrayList<String>();
        for (CustomField existing : sameName) {
            suggestions.add(existing.getId() + " (" + typeKeyOf(existing) + ")");
        }
        throw new FieldFailureException(CustomFieldErrorCode.NAME_CONFLICT,
                "Field name '" + resolved.getName() + "' is already used by "
                        + (sameName.size() == 1 ? "a field of another type" : sameName.size() + " fields")
                        + " (see suggestions), not by a field of type '" + wantedType
                        + "'. Choose a different name or ask the user how to resolve the conflict.",
                suggestions);
    }

    /**
     * An existing field is never changed, so whatever the request said beyond name and type is dropped. Say so, or
     * the caller would report options, contexts or a searcher that were never applied.
     *
     * @return {@code null} when nothing was dropped
     */
    private String ignoredParts(ResolvedFieldDefinition resolved, CustomField existing,
                                List<ContextSummary> actualContexts) {
        List<String> ignored = new ArrayList<String>();
        if (resolved.hasOptions()) {
            ignored.add("options");
        }
        if (!contextSummarizer.sameScope(contextSummarizer.summarize(resolved.getContexts()), actualContexts)) {
            ignored.add("contexts");
        }
        if (resolved.isSearcherRequested()
                && !equalKeys(searcherKeyOf(resolved.getSearcher()), searcherKeyOf(existing.getCustomFieldSearcher()))) {
            ignored.add("searcherKey");
        }
        String existingDescription = existing.getDescription() == null ? "" : existing.getDescription();
        if (!resolved.getDescription().isEmpty() && !resolved.getDescription().equals(existingDescription)) {
            ignored.add("description");
        }
        if (ignored.isEmpty()) {
            return null;
        }
        return "Field '" + resolved.getName() + "' already exists as " + existing.getId() + "; nothing was changed. "
                + "Ignored from the request: " + String.join(", ", ignored) + ". Tell the user.";
    }

    private FieldCreationResult create(ResolvedFieldDefinition resolved, List<ContextSummary> contexts,
                                       ApplicationUser user, FieldDefinition definition, ChangeLinks.Report report) {
        AssetsFieldSettings settings = definition.getAssets();
        ResolvedContext first = resolved.getContexts().get(0);
        int writes = 1 + resolved.getContexts().size();
        if (settings != null) writes += resolved.getContexts().size();
        for (ResolvedContext context : resolved.getContexts())
            for (ru.rasulov.common.api.dto.field.OptionDefinition option : context.getOptions())
                writes += 1 + option.getChildren().size();
        journal.reserve(writes * 14336L);
        CustomField field;
        UndoStep step = journal.prepare("cf.field", "cf.field:" + resolved.getName(),
                undo.snapshot(resolved.getName(), null), null, 4096);
        try {
            field = customFieldManager.createCustomField(resolved.getName(), resolved.getDescription(),
                    resolved.getType(), resolved.getSearcher(), contextWriter.nodesOf(first),
                    contextWriter.issueTypesOf(first));
        } catch (JournalCancelledException | JournalCapacityException e) {
            throw e;
        } catch (GenericEntityException e) {
            throw new IllegalStateException("Jira failed to create custom field " + resolved.getName(), e);
        }
        report.created(ChangeKind.CUSTOM_FIELD, field.getId(), resolved.getName(), field.getId());
        journal.applied(step, undo.persistedSnapshot(resolved.getName(), field.getId()));
        journal.checkpoint();
        List<FieldConfig> configs = contextWriter.apply(field, resolved.getContexts());
        for (int i = 0; i < configs.size(); i++) {
            ResolvedContext resolvedContext = resolved.getContexts().get(i);
            Long contextConfigId = configs.get(i).getId();
            report.created(ChangeKind.FIELD_CONTEXT, String.valueOf(contextConfigId), resolvedContext.getName(),
                    field.getId());
            optionsWriter.write(configs.get(i), resolvedContext.getOptions());
            int optionCount = countOptions(resolvedContext);
            if (optionCount > 0) {
                report.createdOptions(String.valueOf(contextConfigId),
                        resolved.getName() + " / " + resolvedContext.getName(), optionCount);
            }
            if (settings != null) {
                Long configId = configs.get(i).getId();
                UndoStep assetsStep = journal.prepare("cf.assets", "cf.assets:" + configId,
                        assets.snapshot(configId), null, 4096);
                assets.configure(configId, Long.valueOf(field.getId().substring("customfield_".length())), settings);
                journal.applied(assetsStep, assets.snapshot(configId));
                journal.checkpoint();
            }
            if (definition.getFileField() != null)
                fileFields.configure(configs.get(i).getId(), definition.getFileField());
            if (definition.getGrid() != null)
                grids.configure(configs.get(i).getId(), definition.getGrid());
        }
        CreatedFieldReadback.Result actual = readback.read(field.getId(), resolved.getContexts(), configs);
        if (!actual.matches()) {
            List<String> ids = new ArrayList<String>();
            for (int i = 0; i < configs.size(); i++)
                ids.add("'" + resolved.getContexts().get(i).getName() + "' = config " + configs.get(i).getId());
            log.warn("Custom field '{}' was created as {} but Jira reports a different state than planned: {} (user {})",
                    resolved.getName(), field.getId(), actual.getDifferences(), user.getUsername());
            return FieldCreationResult.partial(resolved.getName(), field.getId(), actual.getActualContexts(),
                    "Field " + field.getId() + " WAS created, but what Jira reports differs from the plan: "
                            + String.join("; ", actual.getDifferences()) + ". 'contexts' is what Jira reports, not what "
                            + "was requested. Context configs written: " + ids + ". Nothing was undone, repaired or retried. "
                            + "Tell the user the real state; do not claim the requested contexts or options are saved.");
        }
        log.info("Created custom field '{}' as {} of type {} in {} context(s) (user {})",
                resolved.getName(), field.getId(), resolved.getType().getKey(), contexts.size(), user.getUsername());
        return FieldCreationResult.created(resolved.getName(), field.getId(), contexts, creationMessage(resolved, definition));
    }

    /** Options with their children, counted the way {@link #create} budgets its writes. */
    private static int countOptions(ResolvedContext context) {
        int count = 0;
        for (ru.rasulov.common.api.dto.field.OptionDefinition option : context.getOptions()) {
            count += 1 + option.getChildren().size();
        }
        return count;
    }


    private String creationMessage(ResolvedFieldDefinition resolved, FieldDefinition definition) {
        List<String> parts = new ArrayList<String>();
        AssetsFieldSettings settings = definition.getAssets();
        if (settings != null)
            parts.add("Assets settings for every new context: objectSchemaId="
                    + settings.getObjectSchemaId() + ", filterScopeAql="
                    + (settings.getFilterScopeAql() == null ? "" : settings.getFilterScopeAql()) + ".");
        if (definition.getFileField() != null) parts.add(fileFields.describe(definition.getFileField()));
        if (definition.getGrid() != null) parts.add(grids.describe(definition.getGrid()));
        String warnings = warningsOf(resolved);
        if (warnings != null) parts.add(warnings);
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /**
     * Things the caller should tell the user, in one text, or {@code null}: the warnings of the contexts, and a
     * select-like field (or a context of it) that would be created without options. Options cannot be added later by
     * this service, so an empty one is a decision the user has to confirm.
     */
    private static String warningsOf(ResolvedFieldDefinition resolved) {
        List<String> warnings = new ArrayList<String>();
        for (ResolvedContext context : resolved.getContexts()) {
            warnings.addAll(context.getWarnings());
        }
        if (resolved.getType() instanceof MultipleSettableCustomFieldType) {
            List<String> empty = new ArrayList<String>();
            for (ResolvedContext context : resolved.getContexts()) {
                if (context.getOptions().isEmpty()) {
                    empty.add("'" + context.getName() + "'");
                }
            }
            if (!empty.isEmpty()) {
                warnings.add("Field '" + resolved.getName() + "' has NO OPTIONS in context "
                        + String.join(", ", empty) + ". Ask the user whether they really want it without options: "
                        + "this tool cannot add options later, they would have to be added by hand in Jira.");
            }
        }
        return warnings.isEmpty() ? null : String.join(" ", warnings);
    }

    /** The type of a field can be {@code null} when the plugin providing it is disabled. */
    private static String typeKeyOf(CustomField field) {
        CustomFieldType<?, ?> type = field.getCustomFieldType();
        return type == null ? "unknown type" : type.getKey();
    }

    private static String searcherKeyOf(CustomFieldSearcher searcher) {
        return searcher == null ? null : searcher.getDescriptor().getCompleteKey();
    }

    private static boolean equalKeys(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
