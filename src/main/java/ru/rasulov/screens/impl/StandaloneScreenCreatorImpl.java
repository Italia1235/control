package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.ChangeLinks;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.api.CustomFieldCreationService;
import ru.rasulov.common.api.CustomFieldCreationLimits;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;
import ru.rasulov.screens.api.StandaloneScreenCreator;
import ru.rasulov.screens.api.dto.CreateScreenFieldRequest;
import ru.rasulov.screens.api.dto.CreateScreenRequest;
import ru.rasulov.screens.api.dto.CreateScreenResponse;
import ru.rasulov.screens.api.dto.CreateScreenResponse.FieldResult;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Preview/apply orchestration for one screen that is deliberately not connected to a screen scheme or project. */
@ExportAsService(StandaloneScreenCreator.class)
@Named
public class StandaloneScreenCreatorImpl implements StandaloneScreenCreator {
    static final String OPERATION = "create_screen";
    static final String LOCK_NAME = "ru.rasulov.control.create-standalone-screen";

    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final FieldManager fieldManager;
    private final FieldScreenManager screenManager;
    private final CustomFieldCreationService fieldCreator;
    private final StandaloneScreenWriter writer;
    private final ActionPlanStore plans;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final ChangeLinks links;

    @Inject
    public StandaloneScreenCreatorImpl(AdministratorGuard guard, NamedLock lock,
                                       @ComponentImport FieldManager fieldManager,
                                       @ComponentImport FieldScreenManager screenManager,
                                       CustomFieldCreationService fieldCreator, StandaloneScreenWriter writer,
                                       ActionPlanStore plans, ActionPlanCodec codec, ChangeJournal journal,
                                       ChangeLinks links) {
        this.guard = guard; this.lock = lock; this.fieldManager = fieldManager;
        this.screenManager = screenManager;
        this.fieldCreator = fieldCreator; this.writer = writer; this.plans = plans; this.codec = codec;
        this.journal = journal;
        this.links = links;
    }

    @Override
    public CreateScreenResponse create(CreateScreenRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            checkLimits(request);
            Preview preview = preview(request, false);
            if (preview.response.getStatus() != CreateScreenResponse.Status.WOULD_CREATE) return preview.response;
            ActionPlanStore.Plan saved = plans.issue(OPERATION, user.getKey(), codec.request(request),
                    codec.fingerprint(preview.response));
            return preview.response.withPlan(saved.getId(), saved.getExpiresAt());
        }
        return journal.execute(OPERATION, user.getKey(),
                () -> lock.run("ru.rasulov.control.create-custom-fields", "custom fields",
                        () -> lock.run(LOCK_NAME, "standalone screens", () -> apply(request.getPlanId(), user))));
    }

    private CreateScreenResponse apply(String planId, ApplicationUser user) {
        ActionPlanStore.Plan saved = plans.find(OPERATION, user.getKey(), planId);
        if (saved == null) return CreateScreenResponse.stale();
        Preview current = null;
        try {
            CreateScreenRequest request = codec.mode(saved.getRequest(), true, CreateScreenRequest.class);
            current = preview(request, true);
            if (!saved.getFingerprint().equals(codec.fingerprint(current.response))) {
                discardFieldPlan(current);
                return CreateScreenResponse.stale();
            }
            writer.reserve(current.name, current.description, current.slots.size());
            CreateCustomFieldsResponse createdFields = applyFields(current);
            if (createdFields != null && "PREVIEW_STALE".equals(createdFields.getErrorCode())) {
                return CreateScreenResponse.stale();
            }
            List<FieldResult> fields = appliedResults(current, createdFields);
            if (hasFailure(fields)) {
                throw new InvalidRequestException(cancelMessage(fields));
            }
            List<String> ids = new ArrayList<String>(fields.size());
            for (FieldResult field : fields) ids.add(field.getFieldId());
            FieldScreen screen = writer.write(current.name, current.description, ids);
            ChangeLinks.Report report = links.report();
            if (createdFields != null) report.merge(createdFields.getReport());
            for (FieldResult field : fields) {
                if (field.getStatus() == FieldResult.Status.EXISTING && field.getFieldId() != null
                        && field.getFieldId().startsWith("customfield_")) {
                    report.reused(ChangeKind.CUSTOM_FIELD, field.getFieldId(), field.getName(), field.getFieldId());
                }
            }
            String screenId = String.valueOf(screen.getId());
            report.created(ChangeKind.SCREEN, screenId, current.name, screenId);
            if (!screen.getTabs().isEmpty()) {
                report.created(ChangeKind.SCREEN_TAB, String.valueOf(screen.getTabs().get(0).getId()),
                        StandaloneScreenWriter.TAB_NAME, screenId);
            }
            return CreateScreenResponse.success(false, screen.getId(), current.name, current.description,
                    StandaloneScreenWriter.TAB_NAME, fields).withReport(report.build());
        } finally {
            plans.remove(planId);
        }
    }

    private Preview preview(CreateScreenRequest request, boolean keepFieldPlan) {
        String name = trim(request.getName());
        String description = request.getDescription();
        List<FieldResult> results = new ArrayList<FieldResult>(request.getFields().size());
        List<Slot> slots = new ArrayList<Slot>(request.getFields().size());
        List<FieldDefinition> missing = new ArrayList<FieldDefinition>();
        Set<String> requested = new HashSet<String>();
        Set<String> ids = new HashSet<String>();

        if (name.isEmpty()) {
            return failedPreview(name, description, results, "Screen name must not be blank.");
        }
        if (screenManager.screenNameExists(name)) {
            return new Preview(CreateScreenResponse.failed(true, name, description, StandaloneScreenWriter.TAB_NAME,
                    results, CreateScreenResponse.ErrorCode.NAME_TAKEN,
                    "A screen named '" + name + "' already exists. Choose another name."), name, description,
                    slots, null);
        }

        List<OrderableField> available = new ArrayList<OrderableField>(fieldManager.getOrderableFields());
        available.sort(Comparator.comparing(OrderableField::getId));
        for (CreateScreenFieldRequest input : request.getFields()) {
            String text = input == null ? "" : trim(input.getName());
            if (text.isEmpty()) {
                results.add(failedField(input == null ? null : input.getName(), "VALIDATION_ERROR",
                        "Field name or id must not be blank.", null));
                slots.add(new Slot(null, -1));
                continue;
            }
            if (!requested.add(normalize(text))) {
                results.add(failedField(text, "VALIDATION_ERROR",
                        "Field '" + text + "' is repeated in this request.", null));
                slots.add(new Slot(null, -1));
                continue;
            }
            List<OrderableField> matches = matches(text, available);
            if (matches.size() > 1) {
                List<String> suggestions = new ArrayList<String>();
                for (OrderableField match : matches) suggestions.add(match.getId() + " (" + match.getName() + ")");
                results.add(failedField(text, "AMBIGUOUS_FIELD",
                        "Several orderable fields are named '" + text + "'. Choose one exact id.", suggestions));
                slots.add(new Slot(null, -1));
                continue;
            }
            if (matches.size() == 1) {
                OrderableField field = matches.get(0);
                if (!ids.add(field.getId())) {
                    results.add(failedField(text, "VALIDATION_ERROR",
                            "The same field was selected more than once. Keep one occurrence.", null));
                    slots.add(new Slot(null, -1));
                    continue;
                }
                String ignored = hasCreationDetails(input)
                        ? "The field already exists; type, description, searcher and options from this request are ignored."
                        : null;
                results.add(new FieldResult(text, field.getId(), field.getName(), FieldResult.Status.EXISTING,
                        typeKey(field), null, null, ignored, null));
                slots.add(new Slot(field.getId(), -1));
                continue;
            }
            if (looksLikeId(text)) {
                results.add(failedField(text, "UNKNOWN_FIELD", "No orderable field has the id '" + text + "'.", null));
                slots.add(new Slot(null, -1));
                continue;
            }
            if (input == null || trim(input.getTypeKey()).isEmpty()) {
                results.add(failedField(text, "MISSING_TYPE",
                        "No orderable field is named '" + text + "'. Localized labels are not translated. "
                                + "For an existing system field, use its Jira id (for example summary or description), "
                                + "not a new custom replacement. Only if a new custom field is intended: choose its type "
                                + "from list_custom_field_types and provide typeKey.", null));
                slots.add(new Slot(null, -1));
                continue;
            }
            int missingIndex = missing.size();
            ContextDefinition global = new ContextDefinition(null, true, null, true, null, null);
            missing.add(new FieldDefinition(text, input.getDescription(), input.getTypeKey(), input.getSearcherKey(), input.getOptions(), Collections.singletonList(global), null));
            results.add(null);
            slots.add(new Slot(null, missingIndex));
        }


        String fieldPlanId = null;
        if (!missing.isEmpty()) {
            CreateCustomFieldsResponse fields = fieldCreator.createAll(new CreateCustomFieldsRequest(true, missing));
            fieldPlanId = fields.getPlanId();
            for (int i = 0; i < slots.size(); i++) {
                Slot slot = slots.get(i);
                if (slot.missingIndex >= 0) results.set(i, fromCreation(request.getFields().get(i).getName(),
                        request.getFields().get(i).getTypeKey(), fields.getResults().get(slot.missingIndex)));
            }
            if (!keepFieldPlan && fieldPlanId != null) {
                plans.remove(fieldPlanId);
                fieldPlanId = null;
            }
        }
        CreateScreenResponse response = hasFailure(results)
                ? CreateScreenResponse.failed(true, name, description, StandaloneScreenWriter.TAB_NAME, results,
                    CreateScreenResponse.ErrorCode.FIELD_ERROR,
                    "Resolve the field errors before creating the screen.")
                : CreateScreenResponse.success(true, null, name, description, StandaloneScreenWriter.TAB_NAME, results);
        return new Preview(response, name, description, slots, fieldPlanId);
    }

    private CreateCustomFieldsResponse applyFields(Preview preview) {
        if (preview.fieldPlanId == null) return null;
        return fieldCreator.createAll(new CreateCustomFieldsRequest(false, null, preview.fieldPlanId));
    }

    private List<FieldResult> appliedResults(Preview preview, CreateCustomFieldsResponse created) {
        List<FieldResult> results = new ArrayList<FieldResult>(preview.slots.size());
        for (int i = 0; i < preview.slots.size(); i++) {
            Slot slot = preview.slots.get(i);
            FieldResult planned = preview.response.getFields().get(i);
            if (slot.missingIndex < 0) {
                results.add(planned);
            } else if (created == null && planned.getStatus() == FieldResult.Status.EXISTING
                    && planned.getFieldId() != null) {
                results.add(planned);
            } else if (created == null || slot.missingIndex >= created.getResults().size()) {
                results.add(failedField(planned.getRequestedName(), "JIRA_ERROR",
                        "Jira returned no result for this custom field.", null));
            } else {
                results.add(fromCreation(planned.getRequestedName(), planned.getTypeKey(),
                        created.getResults().get(slot.missingIndex)));
            }
        }
        return results;
    }


    private void discardFieldPlan(Preview preview) {
        if (preview != null && preview.fieldPlanId != null) plans.remove(preview.fieldPlanId);
    }

    private static List<OrderableField> matches(String text, List<OrderableField> available) {
        List<OrderableField> byName = new ArrayList<OrderableField>();
        for (OrderableField field : available) {
            if (field.getId().equalsIgnoreCase(text)) return Collections.singletonList(field);
            if (field.getName().equalsIgnoreCase(text)) byName.add(field);
        }
        return byName;
    }

    private static FieldResult fromCreation(String requestedName, String typeKey, FieldCreationResult result) {
        FieldResult.Status status;
        if (result.getStatus() == CreationStatus.WOULD_CREATE) status = FieldResult.Status.WOULD_CREATE;
        else if (result.getStatus() == CreationStatus.CREATED) status = FieldResult.Status.CREATED;
        else if (result.getStatus() == CreationStatus.EXISTS) status = FieldResult.Status.EXISTING;
        else if (result.getStatus() == CreationStatus.PARTIAL) status = FieldResult.Status.PARTIAL;
        else status = FieldResult.Status.FAILED;
        return new FieldResult(requestedName, result.getCustomFieldId(), result.getName(), status, typeKey,
                result.getContexts(), result.getErrorCode() == null ? null : result.getErrorCode().name(),
                result.getMessage(), result.getSuggestions());
    }

    private static FieldResult failedField(String name, String code, String message, List<String> suggestions) {
        return new FieldResult(name, null, name, FieldResult.Status.FAILED, null, null, code, message, suggestions);
    }

    private static boolean hasFailure(List<FieldResult> fields) {
        for (FieldResult field : fields) {
            if (field == null || field.getStatus() == FieldResult.Status.FAILED
                    || field.getStatus() == FieldResult.Status.PARTIAL) return true;
        }
        return false;
    }

    /**
     * The failure text names every custom field Jira already holds (id and status), so a diagnostic does not start from
     * "could not be created". Whether the journal rolled them back is in the operation record, never claimed here.
     */
    private static String cancelMessage(List<FieldResult> fields) {
        StringBuilder text = new StringBuilder(
                "At least one missing custom field could not be created; the operation was cancelled.");
        StringBuilder written = new StringBuilder();
        for (FieldResult field : fields) {
            if (field == null || field.getFieldId() == null) continue;
            if (field.getStatus() != FieldResult.Status.CREATED && field.getStatus() != FieldResult.Status.PARTIAL) continue;
            written.append(" ").append(field.getFieldId()).append(" '").append(field.getName()).append("' ")
                    .append(field.getStatus());
            if (field.getStatus() == FieldResult.Status.PARTIAL && field.getMessage() != null)
                written.append(" (").append(field.getMessage()).append(")");
            written.append(";");
        }
        if (written.length() > 0) {
            text.append(" The screen was not created. Custom fields written in this operation:").append(written)
                    .append(" Read the operation's rollback state for what was undone; do not assume it.");
        }
        return text.toString();
    }

    private static boolean hasCreationDetails(CreateScreenFieldRequest field) {
        return field != null && (!trim(field.getTypeKey()).isEmpty() || field.getDescription() != null
                || !trim(field.getSearcherKey()).isEmpty() || !field.getOptions().isEmpty());
    }

    private static String typeKey(OrderableField field) {
        return field instanceof CustomField ? ((CustomField) field).getCustomFieldType().getKey() : null;
    }

    private static boolean looksLikeId(String text) {
        return text.matches("(?i)customfield_\\d+") || text.matches("\\d+");
    }

    private static String trim(String text) { return text == null ? "" : text.trim(); }
    private static String normalize(String text) { return trim(text).toLowerCase(Locale.ROOT); }

    private static Preview failedPreview(String name, String description, List<FieldResult> fields, String message) {
        return new Preview(CreateScreenResponse.failed(true, name, description, StandaloneScreenWriter.TAB_NAME,
                fields, CreateScreenResponse.ErrorCode.VALIDATION_ERROR, message), name, description,
                Collections.<Slot>emptyList(), null);
    }

    private static void requireMode(CreateScreenRequest request) {
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun() && request.getPlanId() != null) {
            throw new InvalidRequestException("Preview takes screen details, not planId.");
        }
        if (!request.isDryRun() && (request.getName() != null || request.getDescription() != null
                || !request.getFields().isEmpty() || trim(request.getPlanId()).isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }

    private static void checkLimits(CreateScreenRequest request) {
        if (request.getFields().isEmpty()) {
            throw new InvalidRequestException("'fields' must contain at least one field.");
        }
        if (request.getFields().size() > CustomFieldCreationLimits.MAX_FIELDS_PER_CALL) {
            throw new InvalidRequestException("'fields' has " + request.getFields().size() + " elements; the maximum is "
                    + CustomFieldCreationLimits.MAX_FIELDS_PER_CALL + ".");
        }
    }

    private static final class Slot {
        final String existingId;
        final int missingIndex;
        Slot(String existingId, int missingIndex) { this.existingId = existingId; this.missingIndex = missingIndex; }
    }

    private static final class Preview {
        final CreateScreenResponse response;
        final String name;
        final String description;
        final List<Slot> slots;
        final String fieldPlanId;
        Preview(CreateScreenResponse response, String name, String description, List<Slot> slots, String fieldPlanId) {
            this.response = response; this.name = name; this.description = description;
            this.slots = slots; this.fieldPlanId = fieldPlanId;
        }
    }
}
