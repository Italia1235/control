package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.AssignmentState;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.CreatedObject;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse.SchemeMode;
import ru.rasulov.screens.impl.OperationScreenPlan.Copy;
import ru.rasulov.screens.impl.OperationScreenPlan.Row;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/**
 * Builds the batch's child schemes, then either updates the mappings of the project's private top scheme in place or
 * builds ONE new top scheme and switches the project once. Never writes source Screen Schemes or Screens, never rolls
 * back: whatever was created is reported, with the state of the association or of the mappings read back from Jira.
 */
@Named
public class OperationScreenWriter {
    private static final Logger log = LoggerFactory.getLogger(OperationScreenWriter.class);
    private final FieldScreenFactory factory;
    private final FieldScreenSchemeManager fields;
    private final IssueTypeScreenSchemeManager tops;

    @Inject
    public OperationScreenWriter(@ComponentImport FieldScreenFactory factory,
            @ComponentImport FieldScreenSchemeManager fields, @ComponentImport IssueTypeScreenSchemeManager tops) {
        this.factory = factory; this.fields = fields; this.tops = tops;
    }

    /**
     * What exists after {@link #write}. {@code error == null} and {@code state == ASSIGNED}: the batch completed.
     * In place, {@code state} after a failure is what Jira returned when the mappings were read back.
     */
    static final class Result {
        final List<CreatedObject> schemes = new ArrayList<CreatedObject>();
        final Map<Copy, FieldScreenScheme> made = new IdentityHashMap<Copy, FieldScreenScheme>();
        final List<String> details = new ArrayList<String>();
        CreatedObject top;
        AssignmentState state = AssignmentState.NOT_ASSIGNED;
        String error;
        String note;
        String step = "creating a Screen Scheme";
        boolean mappingsStarted;
        boolean complete() { return error == null && state == AssignmentState.ASSIGNED; }
    }

    Result write(OperationScreenPlan plan) {
        Result result = new Result();
        boolean inPlace = plan.mode == SchemeMode.UPDATE_PRIVATE;
        IssueTypeScreenScheme top = null;
        try {
            for (Copy wanted : plan.copies) {
                result.step = "creating Screen Scheme '" + wanted.name + "'";
                FieldScreenScheme copy = factory.createFieldScreenScheme();
                copy.setName(wanted.name); copy.setDescription(wanted.source.getDescription());
                fields.createFieldScreenScheme(copy);
                int at = result.schemes.size();
                result.schemes.add(new CreatedObject(copy.getId(), wanted.name, false));
                result.made.put(wanted, copy);
                result.step = "filling Screen Scheme '" + wanted.name + "' (id " + copy.getId() + ")";
                fill(copy, wanted);
                result.schemes.set(at, new CreatedObject(copy.getId(), wanted.name, true));
            }
            if (inPlace) {
                result.mappingsStarted = true;
                updateMappings(plan, result);
            } else {
                result.step = "creating Issue Type Screen Scheme '" + plan.response.getNewSchemeName() + "'";
                top = factory.createIssueTypeScreenScheme();
                top.setName(plan.response.getNewSchemeName()); top.setDescription(plan.source.getDescription());
                tops.createIssueTypeScreenScheme(top);
                result.top = new CreatedObject(top.getId(), top.getName(), false);
                result.step = "filling Issue Type Screen Scheme " + top.getId();
                for (Row row : plan.rows) {
                    FieldScreenScheme target = row.existing;
                    if (row.copy != null) target = result.made.get(row.copy);
                    addEntity(top, row.typeId, target);
                }
                result.top = new CreatedObject(top.getId(), top.getName(), true);
                result.step = "switching the project to Issue Type Screen Scheme " + top.getId();
                tops.addSchemeAssociation(plan.project, top);
            }
            result.state = AssignmentState.ASSIGNED;
        } catch (RuntimeException e) {
            log.error("Operation screen assignment failed while " + result.step, e);
            result.error = "Jira failed while " + result.step + ": " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            if (inPlace) {
                // Before the first mapping write nothing of the top scheme can have changed; after it, only Jira knows.
                if (result.mappingsStarted) observeMappings(plan, result);
            } else if (result.step.startsWith("switching")) result.state = observe(plan, top, result);
        }
        return result;
    }

    /** After a failed switch: read which scheme the project really uses. */
    private AssignmentState observe(OperationScreenPlan plan, IssueTypeScreenScheme top, Result result) {
        try {
            IssueTypeScreenScheme current = tops.getIssueTypeScreenScheme(plan.project);
            if (current != null && top != null && top.getId().equals(current.getId())) {
                result.note = "Jira reported an error but the project already uses the new Issue Type Screen Scheme.";
                result.error = null;
                return AssignmentState.ASSIGNED;
            }
            if (current != null && plan.source.getId().equals(current.getId())) return AssignmentState.NOT_ASSIGNED;
        } catch (RuntimeException ignored) { /* unknown below */ }
        return AssignmentState.UNKNOWN;
    }

    /** In place: one write per changing row of the project's own top scheme; the scheme itself is not recreated. */
    private void updateMappings(OperationScreenPlan plan, Result result) {
        for (Row row : plan.rows) {
            if (row.copy == null) continue;
            FieldScreenScheme target = result.made.get(row.copy);
            String where = row.typeId == null ? "the DEFAULT mapping" : "the mapping of issue type " + row.typeId;
            if (row.inSource) {
                result.step = "updating " + where + " of Issue Type Screen Scheme " + plan.source.getId();
                IssueTypeScreenSchemeEntity entity = null;
                for (IssueTypeScreenSchemeEntity candidate : plan.source.getEntities()) {
                    if (Objects.equals(candidate.getIssueTypeId(), row.typeId)) { entity = candidate; break; }
                }
                if (entity == null) throw new IllegalStateException(where + " is missing in the scheme");
                entity.setFieldScreenScheme(target);
                tops.updateIssueTypeScreenSchemeEntity(entity);
            } else {
                result.step = "adding " + where + " to Issue Type Screen Scheme " + plan.source.getId();
                addEntity(plan.source, row.typeId, target);
            }
        }
    }

    /**
     * After a failed in-place write the ID of the scheme proves nothing: read the rows back and compare them with the rows
     * before the write and with the planned rows. Unreadable or a different association: UNKNOWN.
     */
    private void observeMappings(OperationScreenPlan plan, Result result) {
        result.state = AssignmentState.UNKNOWN;
        try {
            IssueTypeScreenScheme now = tops.getIssueTypeScreenScheme(plan.project);
            if (now == null || !plan.source.getId().equals(now.getId())) {
                result.details.add("The project no longer uses Issue Type Screen Scheme " + plan.source.getId() + ".");
                return;
            }
            Map<String, Long> actual = new TreeMap<String, Long>();
            for (IssueTypeScreenSchemeEntity entity : now.getEntities()) {
                actual.put(entity.getIssueTypeId() == null ? "DEFAULT" : entity.getIssueTypeId(), entity.getFieldScreenScheme().getId());
            }
            Map<String, Long> planned = new TreeMap<String, Long>(plan.sourceRows);
            for (Row row : plan.rows) {
                if (row.copy != null) planned.put(row.key(), result.made.get(row.copy).getId());
            }
            if (actual.equals(planned)) {
                result.note = "Jira reported an error but every planned mapping is in place.";
                result.error = null;
                result.state = AssignmentState.ASSIGNED;
                return;
            }
            result.state = actual.equals(plan.sourceRows) ? AssignmentState.NOT_ASSIGNED : AssignmentState.PARTIAL;
            Set<String> keys = new TreeSet<String>(planned.keySet());
            keys.addAll(actual.keySet());
            for (String key : keys) {
                if (Objects.equals(actual.get(key), plan.sourceRows.get(key)) && Objects.equals(actual.get(key), planned.get(key))) continue;
                result.details.add("Mapping " + key + " (Screen Scheme id): before " + plan.sourceRows.get(key)
                        + ", planned " + planned.get(key) + ", read back " + actual.get(key) + ".");
            }
        } catch (RuntimeException e) {
            log.error("Could not read back the mappings after a failed in-place update", e);
            result.state = AssignmentState.UNKNOWN;
        }
    }

    private void fill(FieldScreenScheme copy, Copy wanted) {
        Set<String> patched = new HashSet<String>();
        for (FieldScreenSchemeItem sourceItem : wanted.source.getFieldScreenSchemeItems()) {
            FieldScreen target = sourceItem.getFieldScreen();
            for (Map.Entry<String, FieldScreen> entry : wanted.patch.entrySet()) {
                if (sourceItem.getIssueOperation() != null && OperationScreenPlan.operation(entry.getKey()).getId()
                        .equals(sourceItem.getIssueOperation().getId())) {
                    target = entry.getValue(); patched.add(entry.getKey()); break;
                }
            }
            addItem(copy, sourceItem.getIssueOperation(), target);
        }
        for (Map.Entry<String, FieldScreen> entry : wanted.patch.entrySet()) {
            if (!patched.contains(entry.getKey())) addItem(copy, OperationScreenPlan.operation(entry.getKey()), entry.getValue());
        }
    }

    private void addItem(FieldScreenScheme scheme, com.atlassian.jira.issue.operation.ScreenableIssueOperation operation,
                         FieldScreen screen) {
        FieldScreenSchemeItem item = factory.createFieldScreenSchemeItem();
        item.setFieldScreenScheme(scheme); item.setIssueOperation(operation); item.setFieldScreen(screen);
        fields.createFieldScreenSchemeItem(item);
    }
    private void addEntity(IssueTypeScreenScheme scheme, String typeId, FieldScreenScheme fieldScheme) {
        IssueTypeScreenSchemeEntity entity = factory.createIssueTypeScreenSchemeEntity();
        entity.setIssueTypeScreenScheme(scheme); entity.setIssueTypeId(typeId); entity.setFieldScreenScheme(fieldScheme);
        tops.createIssueTypeScreenSchemeEntity(entity);
    }
}
