package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
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
import ru.rasulov.common.api.dto.screen.FieldPlace;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.*;
import ru.rasulov.common.impl.screen.ScreenLayout;
import ru.rasulov.common.impl.screen.ScreenLocks;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.screens.api.ScreenLayoutEditor;
import ru.rasulov.screens.api.dto.layout.*;
import ru.rasulov.screens.api.dto.layout.EditScreenLayoutResponse.MoveResult;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** One approved final layout: remove, add, then sequential moves, without intermediate plans or writes. */
@Named
@ExportAsService(ScreenLayoutEditor.class)
public class ScreenLayoutEditorImpl implements ScreenLayoutEditor {
    private static final String TOOL = "edit_screen_layout";
    private static final Logger LOG = LoggerFactory.getLogger(ScreenLayoutEditorImpl.class);
    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final FieldScreenManager screens;
    private final CustomFieldManager customFields;
    private final FieldManager fields;
    private final RequiredOnCreate required;
    private final ScreenLayoutImpactReader impact;
    private final ScreenLayoutPlanner planner;
    private final ScreenLayoutWriter writer;

    @Inject public ScreenLayoutEditorImpl(AdministratorGuard guard, NamedLock lock, PreviewPlanExecutor executor,
            ActionPlanCodec codec, ChangeJournal journal, @ComponentImport FieldScreenManager screens,
            @ComponentImport CustomFieldManager customFields, @ComponentImport FieldManager fields,
            RequiredOnCreate required, ScreenLayoutImpactReader impact,
            ScreenLayoutPlanner planner, ScreenLayoutWriter writer) {
        this.guard = guard; this.lock = lock; this.executor = executor; this.codec = codec; this.journal = journal;
        this.screens = screens; this.customFields = customFields; this.fields = fields;
        this.required = required; this.impact = impact; this.planner = planner; this.writer = writer;
    }

    @Override public EditScreenLayoutResponse edit(EditScreenLayoutRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request);
        if (!request.isDryRun()) return journal.execute(TOOL, user.getKey(),
                () -> lock.run(ScreenLocks.EDIT_SCREENS, "screens", () -> apply(request.getPlanId(), user)));
        Prepared prepared = prepare(request);
        if (!prepared.writable) return prepared.response;
        return executor.issue(TOOL, user.getKey(), request, fingerprint(prepared), prepared.response::withPlan);
    }

    private EditScreenLayoutResponse apply(String id, ApplicationUser user) {
        return executor.apply(TOOL, user.getKey(), id, EditScreenLayoutRequest.class,
                EditScreenLayoutResponse::stale, this::prepare, p -> p.writable, this::fingerprint, this::write);
    }

    private String fingerprint(Prepared p) {
        return codec.fingerprint(Arrays.asList(p.response, p.before.fingerprint(), p.contextState));
    }

    private Prepared prepare(EditScreenLayoutRequest request) {
        List<String> errors = validate(request);
        if (!errors.isEmpty()) return invalid(request, errors);
        try {
            screens.refresh();
            FieldScreen screen = screens.getFieldScreen(request.getScreenId());
            if (screen == null) return failed(request, LayoutErrorCode.UNKNOWN_SCREEN, "No screen has this id. Use get_project_screens.");
            ScreenLayout before = ScreenLayout.of(screen), current = before;
            ScreenImpact usage = impact.read(screen);
            List<MoveResult> additions = new ArrayList<>(), moves = new ArrayList<>();
            List<RemoveFieldFromScreensResponse.FieldResult> removals = new ArrayList<>();
            List<LayoutWarning> warnings = new ArrayList<>(usage.getWarnings());
            Map<String, Object> contextState = new TreeMap<>();
            LayoutErrorCode code = null;
            RequiredOnCreate.Session checks = null;
            for (String input : list(request.getRemove())) {
                String id = ScreenLayoutPlanner.normalize(input);
                List<LayoutWarning> rowWarnings = new ArrayList<>();
                LayoutErrorCode rowCode = resolve(id) == null ? LayoutErrorCode.UNKNOWN_FIELD : null;
                LayoutStatus status = before.item(id) == null ? LayoutStatus.NOT_PRESENT : LayoutStatus.WOULD_REMOVE;
                RequiredOnCreate.Result mandatory = new RequiredOnCreate.Result(0, List.of(), false);
                if (rowCode == null && status == LayoutStatus.WOULD_REMOVE) {
                    if ("summary".equals(id) && usage.getOperations().contains("CREATE")) rowCode = LayoutErrorCode.PROTECTED_FIELD;
                    else {
                        if (checks == null) checks = required.session();
                        mandatory = checks.check(screen, id);
                        if (mandatory.getPairs() > 0) rowWarnings.add(new LayoutWarning("REQUIRED_ON_CREATE",
                                "Required by Field Configuration for " + mandatory.getPairs() + " project/issue-type pairs. Issue creation through this screen fails until the field is made optional using set_field_required."));
                    }
                }
                if (rowCode != null) { code = first(code, rowCode); errors.add("remove: " + id + ": " + rowCode); status = LayoutStatus.FAILED; }
                else current = current.remove(id);
                ScreenLayout.Tab priorTab = before.containing(id);
                ScreenLayout.Item priorItem = before.item(id);
                int priorIndex = priorTab == null ? -1 : priorTab.items().indexOf(priorItem);
                OrderableField resolved = resolve(id);
                removals.add(new RemoveFieldFromScreensResponse.FieldResult(before.id(), id, resolved == null ? null : resolved.getName(),
                        status, rowCode, priorTab == null ? null : priorTab.id(), priorTab == null ? null : priorTab.name(),
                        priorItem == null ? null : priorItem.position(), priorIndex <= 0 ? null : priorTab.items().get(priorIndex - 1).fieldId(),
                        priorTab == null || priorIndex + 1 == priorTab.items().size() ? null : priorTab.items().get(priorIndex + 1).fieldId(),
                        mandatory.getPairs(), mandatory.getProjects(), mandatory.isTruncated(), rowWarnings,
                        rowCode == null ? null : errors.get(errors.size() - 1)));
                warnings.addAll(rowWarnings);
            }
            for (ScreenFieldAdd add : list(request.getAdd())) {
                String id = ScreenLayoutPlanner.normalize(add.getFieldId());
                OrderableField field = resolve(id);
                LayoutErrorCode rowCode = field == null ? LayoutErrorCode.UNKNOWN_FIELD : null;
                List<LayoutWarning> rowWarnings = new ArrayList<>();
                if (field instanceof CustomField) {
                    CustomField custom = (CustomField) field;
                    // A global context lists null for "all projects/issue types"; the isAll* flags already record that.
                    contextState.put(id, custom.getConfigurationSchemes().stream()
                            .map(c -> Arrays.asList(c.getId(), c.getName(), c.isAllProjects(), c.isAllIssueTypes(),
                                    c.getAssociatedProjectObjects().stream().filter(Objects::nonNull).map(p -> p.getId()).sorted().toList(),
                                    c.getAssociatedIssueTypes().stream().filter(Objects::nonNull).map(t -> t.getId()).sorted().toList())).toList());
                    rowWarnings.add(new LayoutWarning("FIELD_CONTEXT_UNCHANGED", "Adding '" + id + "' to this screen does not extend its project or issue-type contexts; it remains unavailable outside them."));
                }
                LayoutStatus status = LayoutStatus.WOULD_ADD;
                String message = null;
                if (rowCode == null && current.item(id) != null) status = LayoutStatus.ALREADY_PRESENT;
                else if (rowCode == null) {
                    ScreenLayout.Tab tab = addTab(current, add.getTab());
                    if (tab == null) { rowCode = LayoutErrorCode.UNKNOWN_TAB; message = "No existing target tab; omit tab to use the first tab."; }
                    else {
                        int index = insertion(tab, add);
                        if (index < 0) { rowCode = LayoutErrorCode.UNKNOWN_ANCHOR; message = "Anchor is not on the destination tab after removals and preceding additions."; }
                        else current = current.insert(tab.id(), id, index, false);
                    }
                }
                if (rowCode != null) { code = first(code, rowCode); message = "add: " + id + ": " + rowCode + (message == null ? "" : ": " + message); errors.add(message); status = LayoutStatus.FAILED; }
                additions.add(new MoveResult(additions.size(), id, status, rowCode, message, rowWarnings));
                warnings.addAll(rowWarnings);
            }
            ScreenLayout membership = current;
            for (ScreenFieldMove move : list(request.getMoves())) {
                ScreenLayoutPlanner.Plan step = planner.step(current, move);
                MoveResult row = step.moves().get(0);
                moves.add(new MoveResult(moves.size(), row.getFieldId(), row.getStatus(), row.getErrorCode(), row.getMessage(), row.getWarnings()));
                warnings.addAll(step.warnings());
                if (step.errorCode() != null) { code = first(code, step.errorCode()); errors.add("moves: " + row.getFieldId() + ": " + step.message()); }
                else current = step.after();
            }
            if (removals.stream().anyMatch(r -> r.getStatus() == LayoutStatus.WOULD_REMOVE)) {
                if (usage.getOperations().contains("VIEW")) warnings.add(new LayoutWarning("HIDES_VALUES", "Stored issue values are not deleted and remain searchable, but removed fields no longer appear on this VIEW screen."));
                for (ScreenLayout.Tab tab : before.tabs()) if (!tab.items().isEmpty() && current.tab(tab.id()).items().isEmpty())
                    warnings.add(new LayoutWarning("EMPTY_TAB", "Tab '" + tab.name() + "' (" + tab.id() + ") will be empty."));
            }
            boolean valid = errors.isEmpty(), writable = valid && !current.matches(before);
            if (!valid) {
                additions = rejected(additions); moves = rejected(moves);
                removals = removals.stream().map(r -> r.getStatus() == LayoutStatus.FAILED ? r
                        : r.outcome(LayoutStatus.NOT_ATTEMPTED, null, "The entire layout was rejected; nothing was changed.")).toList();
            }
            EditScreenLayoutResponse response = new EditScreenLayoutResponse(true, before.id(), before.name(),
                    !valid ? LayoutStatus.FAILED : writable ? LayoutStatus.WOULD_EDIT : LayoutStatus.ALREADY_IN_PLACE,
                    additions, moves, removals, valid ? planner.diff(before, current) : List.of(), null, usage, warnings,
                    errors, code, valid ? writable ? null : "The final layout is unchanged; no plan was issued." : String.join("; ", errors), null, null, null);
            return new Prepared(response, before, membership, valid ? current : null, contextState, writable);
        } catch (RuntimeException e) {
            LOG.error("Cannot prepare layout for screen {}", request.getScreenId(), e);
            return failed(request, LayoutErrorCode.JIRA_ERROR, "Jira could not read the complete preview. Nothing was changed.");
        }
    }

    private EditScreenLayoutResponse write(Prepared p) {
        try { journal.checkpoint(); }
        catch (RuntimeException e) { return outcome(p, LayoutStatus.FAILED, null, Set.of(), null, "Stopped at a journal checkpoint before the screen; no write was attempted."); }
        ScreenLayoutWriter.MoveWriteResult written = writer.edit(p.before, p.membership, p.after);
        LayoutStatus status = written.status();
        String message = written.message();
        try { journal.checkpoint(); }
        catch (RuntimeException e) {
            if (status == LayoutStatus.EDITED) status = LayoutStatus.PARTIAL;
            message = (message == null ? "" : message + " ") + "Stopped at the journal checkpoint after the screen; readback is the observed state.";
        }
        return outcome(p, status, written.actual(), written.attemptedFields(), written.restoredField(), message);
    }

    private EditScreenLayoutResponse outcome(Prepared p, LayoutStatus status, ScreenLayout actual,
            Collection<String> attempted, String restoredField, String message) {
        return new EditScreenLayoutResponse(false, p.before.id(), p.before.name(), status,
                observed(p.response.getAdd(), p.after, actual, attempted, restoredField, message, LayoutStatus.ADDED),
                observed(p.response.getMoves(), p.after, actual, attempted, restoredField, message, LayoutStatus.MOVED),
                observedRemovals(p.response.getRemove(), actual, attempted, message),
                p.response.getTabs(), actual == null ? null : planner.actual(actual, actual.tabs().stream().map(ScreenLayout.Tab::id).collect(java.util.stream.Collectors.toSet())),
                p.response.getImpact(), p.response.getWarnings(), List.of(), status == LayoutStatus.EDITED ? null : LayoutErrorCode.JIRA_ERROR, message, null, null,
                fieldChanges(p.before, actual));
    }

    private static EditScreenLayoutResponse.FieldChanges fieldChanges(ScreenLayout before, ScreenLayout actual) {
        if (actual == null) return new EditScreenLayoutResponse.FieldChanges(false, List.of());
        Set<String> prior = fieldIds(before), current = fieldIds(actual);
        List<EditScreenLayoutResponse.FieldChange> changes = new ArrayList<>();
        for (String id : prior) if (!current.contains(id)) changes.add(new EditScreenLayoutResponse.FieldChange(id, "deleted"));
        for (String id : current) if (!prior.contains(id)) changes.add(new EditScreenLayoutResponse.FieldChange(id, "added"));
        return new EditScreenLayoutResponse.FieldChanges(true, changes);
    }

    private static Set<String> fieldIds(ScreenLayout layout) {
        Set<String> ids = new LinkedHashSet<>();
        for (ScreenLayout.Tab tab : layout.tabs()) for (ScreenLayout.Item item : tab.items()) ids.add(item.fieldId());
        return ids;
    }

    private static List<RemoveFieldFromScreensResponse.FieldResult> observedRemovals(
            List<RemoveFieldFromScreensResponse.FieldResult> rows, ScreenLayout actual, Collection<String> attempted, String message) {
        return rows.stream().map(row -> {
            LayoutStatus status = row.getStatus() == LayoutStatus.NOT_PRESENT ? LayoutStatus.NOT_PRESENT
                    : actual != null && actual.item(row.getFieldId()) == null && !attempted.isEmpty() ? LayoutStatus.REMOVED
                    : !attempted.contains(row.getFieldId()) ? LayoutStatus.NOT_ATTEMPTED
                    : actual == null ? LayoutStatus.UNKNOWN : LayoutStatus.FAILED;
            return row.outcome(status, status == LayoutStatus.FAILED || status == LayoutStatus.UNKNOWN ? LayoutErrorCode.JIRA_ERROR : null,
                    status == LayoutStatus.REMOVED || status == LayoutStatus.NOT_PRESENT ? null : message);
        }).toList();
    }

    private static List<MoveResult> observed(List<MoveResult> rows, ScreenLayout expected, ScreenLayout actual,
            Collection<String> attempted, String restored, String message, LayoutStatus success) {
        List<MoveResult> result = new ArrayList<>();
        for (MoveResult row : rows) {
            LayoutStatus status;
            if (row.getStatus() == LayoutStatus.ALREADY_PRESENT || row.getStatus() == LayoutStatus.NOT_PRESENT || row.getStatus() == LayoutStatus.ALREADY_IN_PLACE) status = row.getStatus();
            else if (actual != null && (success == LayoutStatus.REMOVED ? actual.item(row.getFieldId()) == null : atFinalPlace(expected, actual, row.getFieldId())) && !attempted.isEmpty()) status = success;
            else if (!attempted.contains(row.getFieldId())) status = LayoutStatus.NOT_ATTEMPTED;
            else if (actual == null) status = LayoutStatus.UNKNOWN;
            else if (row.getFieldId().equals(restored)) status = LayoutStatus.RESTORED;
            else status = LayoutStatus.FAILED;
            result.add(new MoveResult(row.getIndex(), row.getFieldId(), status,
                    status == LayoutStatus.FAILED || status == LayoutStatus.UNKNOWN ? LayoutErrorCode.JIRA_ERROR : null,
                    status == success || status == row.getStatus() ? null : message, row.getWarnings()));
        }
        return result;
    }

    private static boolean atFinalPlace(ScreenLayout expected, ScreenLayout actual, String id) {
        ScreenLayout.Tab target = expected.containing(id), found = actual.containing(id);
        if (target == null || found == null || !Objects.equals(target.id(), found.id())) return false;
        ScreenLayout.Item intended = expected.item(id), persisted = actual.item(id);
        return target.items().indexOf(intended) == found.items().indexOf(persisted)
                && intended.shownWhenEmpty() == persisted.shownWhenEmpty() && persisted.layoutItemId() != null
                && (intended.layoutItemId() == null || intended.layoutItemId().equals(persisted.layoutItemId()));
    }
    private OrderableField resolve(String id) {
        return id.startsWith("customfield_") ? customFields.getCustomFieldObject(id)
                : fields.isOrderableField(id) ? fields.getOrderableField(id) : null;
    }
    private static ScreenLayout.Tab addTab(ScreenLayout layout, String wanted) {
        if (wanted == null || wanted.trim().isEmpty()) return layout.tabs().isEmpty() ? null : layout.tabs().get(0);
        return layout.tabs().stream().filter(t -> wanted.trim().equals(String.valueOf(t.id())) || wanted.trim().equalsIgnoreCase(t.name())).findFirst().orElse(null);
    }
    private static int insertion(ScreenLayout.Tab tab, ScreenFieldAdd add) {
        if (add.getPlace() == null || add.getPlace() == FieldPlace.LAST) return tab.items().size();
        if (add.getPlace() == FieldPlace.FIRST) return 0;
        String anchor = ScreenLayoutPlanner.normalize(add.getAnchor());
        for (int i = 0; i < tab.items().size(); i++) if (tab.items().get(i).fieldId().equals(anchor)) return i + (add.getPlace() == FieldPlace.AFTER ? 1 : 0);
        return -1;
    }
    private static List<String> validate(EditScreenLayoutRequest r) {
        List<String> errors = new ArrayList<>();
        if (r.getScreenId() == null || r.getScreenId() <= 0) errors.add("A positive screenId is required.");
        int count = list(r.getAdd()).size() + list(r.getMoves()).size() + list(r.getRemove()).size();
        if (count < 1 || count > ScreenLayoutLimits.MAX_FIELDS_PER_CALL) errors.add("Provide 1.." + ScreenLayoutLimits.MAX_FIELDS_PER_CALL + " total add, moves and remove actions.");
        Map<String, String> seen = new HashMap<>();
        Set<String> removed = new HashSet<>();
        for (String id : list(r.getRemove())) { checkId(id, "remove", seen, errors); removed.add(ScreenLayoutPlanner.normalize(id)); }
        for (ScreenFieldAdd add : list(r.getAdd())) {
            if (add == null) { errors.add("add cannot contain null."); continue; }
            checkId(add.getFieldId(), "add", seen, errors);
            FieldPlace place = add.getPlace();
            if (place == FieldPlace.UP || place == FieldPlace.DOWN) errors.add("add supports FIRST, LAST, BEFORE, AFTER only.");
            anchor(add.getFieldId(), add.getAnchor(), place, removed, "add", errors);
        }
        for (ScreenFieldMove move : list(r.getMoves())) {
            if (move == null) { errors.add("moves cannot contain null."); continue; }
            checkId(move.getFieldId(), "moves", seen, errors);
            FieldPlace place = move.getPlace();
            if (place == null) errors.add("Each move requires place.");
            boolean relative = place == FieldPlace.UP || place == FieldPlace.DOWN;
            if (relative && move.getTab() != null) errors.add("UP and DOWN cannot specify tab.");
            if (move.getSteps() != null && (!relative || move.getSteps() < 1 || move.getSteps() > 200)) errors.add("steps is 1..200 and only valid for UP/DOWN.");
            anchor(move.getFieldId(), move.getAnchor(), place, removed, "moves", errors);
        }
        return errors;
    }
    private static void checkId(String input, String action, Map<String, String> seen, List<String> errors) {
        String id = ScreenLayoutPlanner.normalize(input);
        if (id == null || id.isEmpty()) { errors.add(action + " requires nonblank field IDs, not names."); return; }
        String prior = seen.putIfAbsent(id, action);
        if (prior != null) errors.add("Field '" + id + "' occurs more than once: " + prior + " and " + action + ".");
    }
    private static void anchor(String field, String input, FieldPlace place, Set<String> removed, String action, List<String> errors) {
        String id = ScreenLayoutPlanner.normalize(input);
        boolean anchored = place == FieldPlace.BEFORE || place == FieldPlace.AFTER;
        if (anchored && (id == null || id.isEmpty())) errors.add(action + ": BEFORE/AFTER require a nonblank anchor.");
        if (!anchored && input != null) errors.add(action + ": anchor is only allowed with BEFORE/AFTER.");
        if (id != null && id.equals(ScreenLayoutPlanner.normalize(field))) errors.add(action + ": a field cannot be its own anchor.");
        if (id != null && removed.contains(id)) errors.add(action + ": anchor '" + id + "' is removed by this request.");
    }
    private static List<MoveResult> rejected(List<MoveResult> rows) {
        return rows.stream().map(r -> r.getStatus() == LayoutStatus.FAILED ? r : new MoveResult(r.getIndex(), r.getFieldId(), LayoutStatus.NOT_ATTEMPTED, null, "The entire layout was rejected; nothing was changed.", r.getWarnings())).toList();
    }
    private static LayoutErrorCode first(LayoutErrorCode a, LayoutErrorCode b) { return a == null ? b : a; }
    private static <T> List<T> list(List<T> values) { return values == null ? List.of() : values; }
    private static Prepared invalid(EditScreenLayoutRequest request, List<String> errors) {
        EditScreenLayoutResponse response = new EditScreenLayoutResponse(true, request.getScreenId(), null, LayoutStatus.FAILED,
                null, null, null, null, null, null, null, errors, LayoutErrorCode.VALIDATION_ERROR, String.join("; ", errors), null, null, null);
        return new Prepared(response, null, null, null, Map.of(), false);
    }
    private static Prepared failed(EditScreenLayoutRequest request, LayoutErrorCode code, String message) {
        return new Prepared(EditScreenLayoutResponse.failed(true, request.getScreenId(), code, message), null, null, null, Map.of(), false);
    }
    private static void requireMode(EditScreenLayoutRequest request) {
        if (request == null || request.isDryRun() == null) throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        if (request.isDryRun() && request.getPlanId() != null) throw new InvalidRequestException("Preview takes screenId and actions, not planId.");
        if (!request.isDryRun() && (request.getScreenId() != null || request.getAdd() != null || request.getMoves() != null || request.getRemove() != null || request.getPlanId() == null || request.getPlanId().trim().isEmpty()))
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
    }
    private record Prepared(EditScreenLayoutResponse response, ScreenLayout before, ScreenLayout membership, ScreenLayout after,
                            Map<String, Object> contextState, boolean writable) { }
}
