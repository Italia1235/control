package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.FieldManager;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.ScreenLayoutLimits;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.screen.ScreenLayout;
import ru.rasulov.common.impl.screen.ScreenLocks;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.screens.api.ScreenFieldRemover;
import ru.rasulov.screens.api.dto.layout.*;
import ru.rasulov.screens.api.dto.layout.RemoveFieldFromScreensResponse.*;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** Field × screen removal under the same cluster lock as add. Preview never writes or locks. */
@Named
@ExportAsService(ScreenFieldRemover.class)
public class ScreenFieldRemoverImpl implements ScreenFieldRemover {
    private static final String TOOL = "remove_field_from_screens";
    private static final Logger LOG = LoggerFactory.getLogger(ScreenFieldRemoverImpl.class);
    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final CustomFieldManager customFields;
    private final FieldManager fields;
    private final FieldScreenManager screens;
    private final ScreenLayoutImpactReader impact;
    private final RequiredOnCreate required;
    private final ScreenLayoutWriter writer;

    @Inject
    public ScreenFieldRemoverImpl(AdministratorGuard guard, NamedLock lock, PreviewPlanExecutor executor,
            ActionPlanCodec codec, ChangeJournal journal, @ComponentImport CustomFieldManager customFields,
            @ComponentImport FieldManager fields, @ComponentImport FieldScreenManager screens,
            ScreenLayoutImpactReader impact, RequiredOnCreate required, ScreenLayoutWriter writer) {
        this.guard = guard; this.lock = lock; this.executor = executor; this.codec = codec; this.journal = journal;
        this.customFields = customFields; this.fields = fields; this.screens = screens;
        this.impact = impact; this.required = required; this.writer = writer;
    }

    @Override public RemoveFieldFromScreensResponse remove(RemoveFieldFromScreensRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (!request.isDryRun()) return journal.execute(TOOL, user.getKey(),
                () -> lock.run(ScreenLocks.EDIT_SCREENS, "screens", () -> apply(request.getPlanId(), user)));
        Prepared prepared = prepare(request);
        if (!prepared.writable) return prepared.response;
        return executor.issue(TOOL, user.getKey(), request, fingerprint(prepared), prepared.response::withPlan);
    }

    private RemoveFieldFromScreensResponse apply(String id, ApplicationUser user) {
        return executor.apply(TOOL, user.getKey(), id, RemoveFieldFromScreensRequest.class,
                RemoveFieldFromScreensResponse::stale, this::prepare, p -> p.writable, this::fingerprint, this::write);
    }

    private String fingerprint(Prepared prepared) {
        Map<Long, String> layouts = new LinkedHashMap<>();
        for (Target target : prepared.targets) layouts.put(target.before.id(), target.before.fingerprint());
        return codec.fingerprint(Arrays.asList(prepared.response, layouts));
    }

    private Prepared prepare(RemoveFieldFromScreensRequest request) {
        try {
            validate(request);
            List<OrderableField> resolved = resolve(request.getFieldIds());
            List<FieldResult> results = new ArrayList<>();
            List<ScreenImpact> impacts = new ArrayList<>();
            List<Target> targets = new ArrayList<>();
            RequiredOnCreate.Session checks = required.session();
            boolean writes = false;
            screens.refresh();
            for (RemoveFieldFromScreensRequest.ScreenTarget target : request.getScreens()) {
                Long screenId = target.getScreenId();
                try {
                    FieldScreen screen = screens.getFieldScreen(screenId);
                    if (screen == null) {
                        for (OrderableField field : resolved) results.add(failure(screenId, field,
                                LayoutErrorCode.UNKNOWN_SCREEN, "No screen has this id. Use get_project_screens."));
                        continue;
                    }
                    ScreenLayout before = ScreenLayout.of(screen);
                    ScreenImpact usage = impact.read(screen);
                    List<FieldResult> rows = new ArrayList<>();
                    for (OrderableField field : resolved) {
                        ScreenLayout.Item item = before.item(field.getId());
                        if (item == null) {
                            rows.add(new FieldResult(screenId, field.getId(), field.getName(), LayoutStatus.NOT_PRESENT,
                                    null, null, null, null, null, null, 0, null, false, null, null));
                            continue;
                        }
                        ScreenLayout.Tab tab = before.containing(field.getId());
                        int index = tab.items().indexOf(item);
                        LayoutErrorCode code = null;
                        RequiredOnCreate.Result mandatory = new RequiredOnCreate.Result(0, List.of(), false);
                        if ("summary".equals(field.getId()) && usage.getOperations().contains("CREATE")) code = LayoutErrorCode.PROTECTED_FIELD;
                        else mandatory = checks.check(screen, field.getId());
                        List<LayoutWarning> warnings = new ArrayList<>();
                        if (mandatory.getPairs() > 0) warnings.add(new LayoutWarning("REQUIRED_ON_CREATE",
                                "Required by Field Configuration for " + mandatory.getPairs() + " project/issue-type pairs that create issues"
                                        + " through this screen. After removal, issue creation there fails until the field is made optional"
                                        + " (set_field_required with required:false for requiredProjects)."));
                        rows.add(new FieldResult(screenId, field.getId(), field.getName(),
                                code == null ? LayoutStatus.WOULD_REMOVE : LayoutStatus.FAILED, code,
                                tab.id(), tab.name(), item.position(), index == 0 ? null : tab.items().get(index - 1).fieldId(),
                                index + 1 == tab.items().size() ? null : tab.items().get(index + 1).fieldId(),
                                mandatory.getPairs(), mandatory.getProjects(), mandatory.isTruncated(), warnings,
                                code == null ? null : "summary cannot be removed from a CREATE screen."));
                    }
                    Set<String> removals = new LinkedHashSet<>();
                    for (FieldResult row : rows) if (row.getStatus() == LayoutStatus.WOULD_REMOVE) removals.add(row.getFieldId());
                    List<LayoutWarning> warnings = new ArrayList<>(usage.getWarnings());
                    if (!removals.isEmpty() && usage.getOperations().contains("VIEW")) warnings.add(new LayoutWarning("HIDES_VALUES",
                            "Stored issue values are not deleted and remain searchable, but will no longer appear on this VIEW screen."));
                    for (ScreenLayout.Tab tab : before.tabs()) if (!tab.items().isEmpty()
                            && tab.items().stream().allMatch(item -> removals.contains(item.fieldId()))) warnings.add(new LayoutWarning("EMPTY_TAB",
                                    "Tab '" + tab.name() + "' (" + tab.id() + ") will be empty after removal."));
                    impacts.add(new ScreenImpact(usage.getScreenId(), usage.getName(), usage.getOperations(), usage.getProjects(),
                            usage.getProjectNames(), usage.isDefaultScreen(), warnings));
                    targets.add(new Target(before, rows));
                    results.addAll(rows);
                    writes |= !removals.isEmpty();
                } catch (RuntimeException e) {
                    LOG.error("Cannot preview field removal from screen {}", screenId, e);
                    for (OrderableField field : resolved) results.add(failure(screenId, field, LayoutErrorCode.JIRA_ERROR,
                            "Jira could not read the complete screen/configuration state. Nothing on this screen was planned."));
                }
            }
            boolean failed = results.stream().anyMatch(row -> row.getStatus() == LayoutStatus.FAILED);
            LayoutStatus status = writes ? LayoutStatus.WOULD_REMOVE : failed ? LayoutStatus.FAILED : LayoutStatus.NOT_PRESENT;
            RemoveFieldFromScreensResponse response = new RemoveFieldFromScreensResponse(true, status, null, null,
                    results, impacts, null, null, null);
            return new Prepared(response, targets, writes);
        } catch (Failure e) {
            return new Prepared(RemoveFieldFromScreensResponse.failed(true, e.code, e.getMessage()), List.of(), false);
        } catch (RuntimeException e) {
            LOG.error("Cannot prepare screen field removal", e);
            return new Prepared(RemoveFieldFromScreensResponse.failed(true, LayoutErrorCode.JIRA_ERROR,
                    "Jira could not read the complete preview. Nothing was changed."), List.of(), false);
        }
    }

    private RemoveFieldFromScreensResponse write(Prepared prepared) {
        Map<Long, List<FieldResult>> written = new LinkedHashMap<>();
        List<ActualScreen> actual = new ArrayList<>();
        boolean stopped = false, failed = false, removed = false, unknown = false;
        for (Target target : prepared.targets) {
            List<String> removals = target.rows.stream().filter(row -> row.getStatus() == LayoutStatus.WOULD_REMOVE)
                    .map(FieldResult::getFieldId).toList();
            if (removals.isEmpty()) continue;
            if (!stopped) {
                try { journal.checkpoint(); }
                catch (RuntimeException e) { stopped = true; failed = true; }
            }
            if (stopped) {
                written.put(target.before.id(), target.rows.stream().map(row -> row.getStatus() == LayoutStatus.WOULD_REMOVE
                        ? row.outcome(LayoutStatus.NOT_ATTEMPTED, null, "Stopped at a journal checkpoint before this screen.") : row).toList());
                continue;
            }
            ScreenLayoutWriter.Result result = writer.remove(target.before, removals);
            List<FieldResult> rows = new ArrayList<>();
            for (FieldResult row : target.rows) {
                if (row.getStatus() != LayoutStatus.WOULD_REMOVE) { rows.add(row); continue; }
                LayoutStatus status;
                if (result.actual() != null && result.actual().item(row.getFieldId()) == null) status = LayoutStatus.REMOVED;
                else if (!result.attempted().contains(row.getFieldId())) status = LayoutStatus.NOT_ATTEMPTED;
                else if (result.actual() == null) status = LayoutStatus.UNKNOWN;
                else status = LayoutStatus.FAILED;
                removed |= status == LayoutStatus.REMOVED;
                rows.add(row.outcome(status, status == LayoutStatus.FAILED || status == LayoutStatus.UNKNOWN
                        ? LayoutErrorCode.JIRA_ERROR : null, result.mismatch() ? null : result.message()));
            }
            written.put(target.before.id(), rows);
            failed |= !result.succeeded();
            unknown |= result.actual() == null;
            actual.add(observation(target, result));
            try { journal.checkpoint(); }
            catch (RuntimeException e) { stopped = true; failed = true; }
        }
        List<FieldResult> results = new ArrayList<>();
        for (FieldResult original : prepared.response.getResults()) {
            List<FieldResult> rows = written.get(original.getScreenId());
            FieldResult result = original;
            if (rows != null) for (FieldResult row : rows) if (row.getFieldId().equals(original.getFieldId())) { result = row; break; }
            results.add(result);
            failed |= result.getStatus() == LayoutStatus.FAILED;
        }
        LayoutStatus status = unknown ? LayoutStatus.UNKNOWN : failed ? removed ? LayoutStatus.PARTIAL : LayoutStatus.FAILED : LayoutStatus.REMOVED;
        return new RemoveFieldFromScreensResponse(false, status, failed ? LayoutErrorCode.JIRA_ERROR : null,
                failed ? "Some screens could not be fully verified, or removals failed or were not attempted. Inspect per-field results and independently read actual state; no rollback was performed." : null,
                results, prepared.response.getScreens(), actual, null, null);
    }

    private static ActualScreen observation(Target target, ScreenLayoutWriter.Result result) {
        if (result.actual() == null) return new ActualScreen(target.before.id(), LayoutStatus.UNKNOWN, null, result.message());
        Set<Long> affected = new HashSet<>();
        for (FieldResult row : target.rows) if (row.getStatus() == LayoutStatus.WOULD_REMOVE) affected.add(row.getTabId());
        List<ActualTab> tabs = new ArrayList<>();
        for (ScreenLayout.Tab tab : result.actual().tabs()) if (affected.contains(tab.id())) {
            List<ActualField> fields = new ArrayList<>();
            for (ScreenLayout.Item item : tab.items()) fields.add(new ActualField(item.layoutItemId(), item.fieldId(), item.position(), item.shownWhenEmpty()));
            tabs.add(new ActualTab(tab.id(), tab.name(), tab.position(), fields));
        }
        return new ActualScreen(target.before.id(), result.succeeded() ? LayoutStatus.REMOVED : LayoutStatus.FAILED, tabs, result.message());
    }

    private List<OrderableField> resolve(List<String> requested) {
        List<OrderableField> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String input : requested) {
            String id = input == null ? "" : input.trim();
            if (id.isEmpty()) throw new Failure(LayoutErrorCode.VALIDATION_ERROR, "Each fieldIds element must be a nonblank field ID, not a name.");
            if (id.chars().allMatch(Character::isDigit)) id = "customfield_" + id;
            OrderableField field = id.startsWith("customfield_") ? customFields.getCustomFieldObject(id)
                    : fields.isOrderableField(id) ? fields.getOrderableField(id) : null;
            if (field == null) throw new Failure(LayoutErrorCode.UNKNOWN_FIELD, "Unknown orderable field ID: " + input + ". Nothing was changed.");
            if (!seen.add(field.getId())) throw new Failure(LayoutErrorCode.VALIDATION_ERROR, "Repeated field: " + input + ". Nothing was changed.");
            result.add(field);
        }
        return result;
    }
    private static void requireMode(RemoveFieldFromScreensRequest request) {
        if (request == null || request.isDryRun() == null) throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        if (request.isDryRun() && request.getPlanId() != null) throw new InvalidRequestException("Preview takes fieldIds and screens, not planId.");
        if (!request.isDryRun() && (request.getFieldIds() != null || request.getScreens() != null
                || request.getPlanId() == null || request.getPlanId().trim().isEmpty()))
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
    }
    private static void validate(RemoveFieldFromScreensRequest request) {
        if (request.getFieldIds() == null || request.getFieldIds().isEmpty() || request.getFieldIds().size() > ScreenLayoutLimits.MAX_FIELDS_PER_CALL)
            throw new Failure(LayoutErrorCode.VALIDATION_ERROR, "fieldIds must contain 1.." + ScreenLayoutLimits.MAX_FIELDS_PER_CALL + " IDs.");
        if (request.getScreens() == null || request.getScreens().isEmpty() || request.getScreens().size() > ScreenLayoutLimits.MAX_SCREENS_PER_CALL)
            throw new Failure(LayoutErrorCode.VALIDATION_ERROR, "screens must contain 1.." + ScreenLayoutLimits.MAX_SCREENS_PER_CALL + " targets.");
        if (request.getFieldIds().size() * request.getScreens().size() > ScreenLayoutLimits.MAX_PAIRS_PER_CALL)
            throw new Failure(LayoutErrorCode.VALIDATION_ERROR, "At most " + ScreenLayoutLimits.MAX_PAIRS_PER_CALL + " field-screen pairs are allowed.");
        Set<Long> seen = new HashSet<>();
        for (RemoveFieldFromScreensRequest.ScreenTarget target : request.getScreens())
            if (target == null || target.getScreenId() == null || target.getScreenId() <= 0 || !seen.add(target.getScreenId()))
                throw new Failure(LayoutErrorCode.VALIDATION_ERROR, "Each screenId must be a distinct positive integer from get_project_screens.");
    }
    private static FieldResult failure(Long screenId, OrderableField field, LayoutErrorCode code, String message) {
        return new FieldResult(screenId, field.getId(), field.getName(), LayoutStatus.FAILED, code,
                null, null, null, null, null, 0, null, false, null, message);
    }
    private record Target(ScreenLayout before, List<FieldResult> rows) { }
    private record Prepared(RemoveFieldFromScreensResponse response, List<Target> targets, boolean writable) { }
    private static final class Failure extends RuntimeException {
        private final LayoutErrorCode code;
        private Failure(LayoutErrorCode code, String message) { super(message); this.code = code; }
    }
}
