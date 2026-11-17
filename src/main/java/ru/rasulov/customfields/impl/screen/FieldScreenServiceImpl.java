package ru.rasulov.customfields.impl.screen;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.CustomFieldCreationLimits;
import ru.rasulov.customfields.api.FieldScreenService;
import ru.rasulov.customfields.api.Limits;
import ru.rasulov.customfields.api.dto.screen.AddToScreensRequest;
import ru.rasulov.customfields.api.dto.screen.AddToScreensResponse;
import ru.rasulov.customfields.api.dto.screen.ScreenErrorCode;
import ru.rasulov.customfields.api.dto.screen.ScreenPlan;
import ru.rasulov.customfields.api.dto.screen.ScreenResult;
import ru.rasulov.customfields.api.dto.screen.ScreenTarget;
import ru.rasulov.customfields.api.dto.screen.FieldScreensResult;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.screen.ScreenLocks;
import ru.rasulov.customfields.impl.context.ContextSummarizer;

import ru.rasulov.common.api.dto.screen.FieldPlace;
import ru.rasulov.common.impl.undo.ChangeJournal;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestration of the screen step: administrator check, find the field, then either read the plan or add the field
 * to the screens the caller chose, under a lock so that two calls cannot both find the field missing and both add it.
 */
@ExportAsService(FieldScreenService.class)
@Named
public class FieldScreenServiceImpl implements FieldScreenService {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(FieldScreenServiceImpl.class);

    private final CustomFieldManager customFieldManager;
    private final FieldManager fieldManager;
    private final AdministratorGuard administratorGuard;
    private final NamedLock namedLock;
    private final ScreenPlanner planner;
    private final ScreenWriter writer;
    private final ContextSummarizer contextSummarizer;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec planCodec;
    private final ChangeJournal journal;

    @Inject
    public FieldScreenServiceImpl(@ComponentImport CustomFieldManager customFieldManager,
                                  @ComponentImport FieldManager fieldManager,
                                  AdministratorGuard administratorGuard, NamedLock namedLock, ScreenPlanner planner,
                                  ScreenWriter writer, ContextSummarizer contextSummarizer,
                                  PreviewPlanExecutor executor, ActionPlanCodec planCodec, ChangeJournal journal) {
        this.customFieldManager = customFieldManager;
        this.fieldManager = fieldManager;
        this.administratorGuard = administratorGuard;
        this.namedLock = namedLock;
        this.planner = planner;
        this.writer = writer;
        this.contextSummarizer = contextSummarizer;
        this.executor = executor;
        this.planCodec = planCodec;
        this.journal = journal;
    }

    @Override
    public ScreenPlan planScreens(String fieldId) {
        administratorGuard.requireAdministrator();
        CustomField field = fieldOf(fieldId);
        return planner.plan(field, contextSummarizer.summarize(field));
    }

    @Override
    public ScreenPlan planScreens(String fieldId, String cursor,
                                  ru.rasulov.customfields.api.dto.screen.Recommendation recommendation) {
        administratorGuard.requireAdministrator();
        CustomField field = fieldOf(fieldId);
        return planner.plan(field, contextSummarizer.summarize(field), cursor, recommendation);
    }

    @Override
    public AddToScreensResponse addToScreens(AddToScreensRequest request) {
        ApplicationUser user = administratorGuard.requireAdministrator();
        requireMode(request);
        if (request.isDryRun()) {
            checkSize(request);
            AddToScreensResponse response = addAll(fieldsOf(request.getFieldIds()), request);
            if (!hasWrites(response)) return response;
            return executor.issue("add_field_to_screens", user.getKey(), request, fingerprint(request, response),
                    response::withPlan);
        }
        return journal.execute("add_field_to_screens", user.getKey(),
                () -> namedLock.run(ScreenLocks.EDIT_SCREENS, "screens", () -> apply(request.getPlanId(), user)));
    }

    private AddToScreensResponse apply(String planId, ApplicationUser user) {
        return executor.apply("add_field_to_screens", user.getKey(), planId, AddToScreensRequest.class,
                AddToScreensResponse::stale,
                preview -> new Preview(preview, addAll(fieldsOf(preview.getFieldIds()), preview)),
                current -> true, current -> fingerprint(current.request, current.response),
                current -> {
                    AddToScreensRequest write = new AddToScreensRequest(current.request.getFieldIds(), false,
                            current.request.getScreens());
                    return addAll(fieldsOf(write.getFieldIds()), write);
                });
    }

    private record Preview(AddToScreensRequest request, AddToScreensResponse response) { }

    private static void requireMode(AddToScreensRequest request) {
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        }
        if (request.isDryRun() && request.getPlanId() != null) {
            throw new InvalidRequestException("Preview takes fieldIds and screens, not planId.");
        }
        if (!request.isDryRun() && (!request.getFieldIds().isEmpty() || !request.getScreens().isEmpty()
                || request.getPlanId() == null || request.getPlanId().trim().isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }

    private static boolean hasWrites(AddToScreensResponse response) {
        for (FieldScreensResult field : response.getFields()) {
            for (ScreenResult result : field.getResults()) {
                if (result.getStatus() == ru.rasulov.customfields.api.dto.screen.ScreenStatus.WOULD_ADD) return true;
            }
        }
        return false;
    }

    private String fingerprint(AddToScreensRequest request, AddToScreensResponse response) {
        boolean positioned = request.getScreens().stream().anyMatch(target -> target != null
                && (target.getPlace() != null || target.getAnchor() != null));
        if (!positioned) return planCodec.fingerprint(response);
        List<Long> screenIds = new ArrayList<Long>(request.getScreens().size());
        for (ScreenTarget target : request.getScreens()) screenIds.add(target == null ? null : target.getScreenId());
        List<String> layouts = writer.fingerprints(screenIds);
        return planCodec.fingerprint(Arrays.asList(response, layouts));
    }

    private AddToScreensResponse addAll(List<OrderableField> fields, AddToScreensRequest request) {
        List<FieldScreensResult> byField = new ArrayList<FieldScreensResult>(fields.size());
        Map<Long, String> insertionCursors = null;
        // Set once a write ended without confirmation (or the journal stopped the call): every later pair is reported as
        // not attempted and nothing more is written, while the results already obtained are kept.
        String stopped = null;
        // Whether any write of this call may have reached Jira. Until then a journal stop (cancellation) still cancels the
        // operation; afterwards the finished results are kept and the rest is reported as not attempted.
        boolean mayHaveWritten = false;
        for (OrderableField field : fields) {
            Set<Long> seen = new HashSet<Long>();
            List<ScreenResult> results = new ArrayList<ScreenResult>(request.getScreens().size());
            for (ScreenTarget target : request.getScreens()) {
                if (stopped == null && !request.isDryRun()) {
                    try {
                        journal.checkpoint();
                    } catch (RuntimeException failure) {
                        if (!mayHaveWritten) throw failure;
                        LOG.warn("add_field_to_screens stopped by the journal after earlier writes", failure);
                        stopped = stopReason(failure);
                    }
                }
                if (stopped != null) {
                    results.add(ScreenResult.notAttempted(target == null ? null : target.getScreenId(), stopped));
                    continue;
                }
                if (target != null && target.getScreenId() != null && !seen.add(target.getScreenId())) {
                    results.add(ScreenResult.failed(target.getScreenId(), null, ScreenErrorCode.VALIDATION_ERROR,
                            "Screen " + target.getScreenId() + " is listed more than once. Keep one."));
                    continue;
                }
                boolean orderedInsertion = target != null
                        && (target.getPlace() == FieldPlace.AFTER || target.getPlace() == FieldPlace.FIRST);
                ScreenTarget writeTarget = target;
                if (!request.isDryRun() && orderedInsertion) {
                    String cursor = insertionCursors == null ? null : insertionCursors.get(target.getScreenId());
                    if (cursor != null) {
                        writeTarget = new ScreenTarget(target.getScreenId(), target.getTab(), FieldPlace.AFTER, cursor);
                    }
                }
                ScreenResult result = writer.add(field.getId(), writeTarget, request.isDryRun());
                results.add(result);
                if (!request.isDryRun() && orderedInsertion
                        && result.getStatus() == ru.rasulov.customfields.api.dto.screen.ScreenStatus.ADDED) {
                    if (insertionCursors == null) insertionCursors = new HashMap<Long, String>();
                    insertionCursors.put(target.getScreenId(), field.getId());
                }
                if (!request.isDryRun()) {
                    ru.rasulov.customfields.api.dto.screen.ScreenStatus state = result.getStatus();
                    if (state == ru.rasulov.customfields.api.dto.screen.ScreenStatus.ADDED
                            || state == ru.rasulov.customfields.api.dto.screen.ScreenStatus.UNKNOWN) mayHaveWritten = true;
                    if (state == ru.rasulov.customfields.api.dto.screen.ScreenStatus.UNKNOWN
                            || (state == ru.rasulov.customfields.api.dto.screen.ScreenStatus.FAILED
                            && result.getErrorCode() == ScreenErrorCode.JIRA_ERROR)) {
                        stopped = "Not started: an earlier screen of this call failed in Jira, so no further writes were made. "
                                + "Read the screens, then ask for a new preview.";
                    } else {
                        try {
                            journal.checkpoint();
                        } catch (RuntimeException failure) {
                            if (!mayHaveWritten) throw failure;
                            LOG.warn("add_field_to_screens stopped by the journal after earlier writes", failure);
                            stopped = stopReason(failure);
                        }
                    }
                }
            }
            byField.add(new FieldScreensResult(field.getId(), field.getName(), results));
        }
        return new AddToScreensResponse(request.isDryRun(), byField);
    }

    private static String stopReason(RuntimeException failure) {
        return "Not started: the operation was stopped before this screen ("
                + (failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage())
                + "). Writes already made are listed in the other results.";
    }

    private static void checkSize(AddToScreensRequest request) {
        int fields = request.getFieldIds().size();
        int screens = request.getScreens().size();
        if (fields == 0) {
            throw new InvalidRequestException("'fieldIds' must name at least one field, e.g. customfield_10123 or assignee.");
        }
        if (fields > CustomFieldCreationLimits.MAX_FIELDS_PER_CALL) {
            throw new InvalidRequestException("'fieldIds' has " + fields + " elements; the maximum is "
                    + CustomFieldCreationLimits.MAX_FIELDS_PER_CALL + " per call. Split the request.");
        }
        if (screens == 0) {
            throw new InvalidRequestException("'screens' must name at least one screen; take the ids from the plan.");
        }
        if (screens > Limits.MAX_SCREENS_PER_CALL) {
            throw new InvalidRequestException("'screens' has " + screens + " elements; the maximum is "
                    + Limits.MAX_SCREENS_PER_CALL + " per call. Split the request.");
        }
        if (fields * screens > Limits.MAX_SCREEN_ADDITIONS_PER_CALL) {
            throw new InvalidRequestException(fields + " fields on " + screens + " screens are " + fields * screens
                    + " additions; the maximum is " + Limits.MAX_SCREEN_ADDITIONS_PER_CALL
                    + " per call. Split the request.");
        }
    }

    /**
     * All fields of the request, in its order: custom fields and system fields that can be placed on a screen. A field
     * that does not exist or is listed twice refuses the whole call, before anything is written: a typo in one id must
     * not leave the other fields half done.
     */
    private List<OrderableField> fieldsOf(List<String> fieldIds) {
        List<OrderableField> fields = new ArrayList<OrderableField>(fieldIds.size());
        Set<String> found = new HashSet<String>();
        List<String> unknown = new ArrayList<String>();
        List<String> repeated = new ArrayList<String>();
        for (String fieldId : fieldIds) {
            String text = fieldId == null ? "" : fieldId.trim();
            if (text.isEmpty()) {
                throw new InvalidRequestException(
                        "'fieldIds' has an empty element; each is like customfield_10123 or assignee.");
            }
            OrderableField field = orderableFieldOf(idOf(text));
            if (field == null) {
                unknown.add("'" + text + "'");
            } else if (!found.add(field.getId())) {
                repeated.add("'" + text + "'");
            } else {
                fields.add(field);
            }
        }
        if (!unknown.isEmpty()) {
            throw new InvalidRequestException("No field that can be placed on a screen has the id "
                    + String.join(", ", unknown) + ". Use a custom field id like customfield_10123 or a system field id "
                    + "like assignee. Nothing was changed.");
        }
        if (!repeated.isEmpty()) {
            throw new InvalidRequestException("'fieldIds' lists " + String.join(", ", repeated)
                    + " more than once. Keep one of each. Nothing was changed.");
        }
        return fields;
    }

    /** A custom field by its id, otherwise a system field Jira can put on a screen; {@code null} if neither. */
    private OrderableField orderableFieldOf(String id) {
        if (id.startsWith("customfield_")) return customFieldManager.getCustomFieldObject(id);
        return fieldManager.isOrderableField(id) ? fieldManager.getOrderableField(id) : null;
    }

    private CustomField fieldOf(String fieldId) {
        String text = fieldId == null ? "" : fieldId.trim();
        if (text.isEmpty()) {
            throw new InvalidRequestException("'fieldId' is required, e.g. customfield_10123.");
        }
        CustomField field = customFieldManager.getCustomFieldObject(idOf(text));
        if (field == null) {
            throw new InvalidRequestException("No custom field has the id '" + text + "'.");
        }
        return field;
    }

    /** Accepts {@code customfield_10123} and the bare number {@code 10123}. */
    private static String idOf(String text) {
        return text.chars().allMatch(Character::isDigit) ? "customfield_" + text : text;
    }
}
