package ru.rasulov.customfields.impl.requirement;

import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse.SchemeView;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse.TargetStatus;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse.TargetView;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse.FieldStatus;
import ru.rasulov.customfields.api.dto.requirement.SetFieldRequiredResponse.FieldView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Request-local: preview validation and native writes share the resolved targets. */
final class RequirementPlan {
    final ProjectRef project;
    final List<OrderableField> fields;
    final Plan resolved;
    final SetFieldRequiredResponse preview;

    RequirementPlan(ProjectRef project, List<OrderableField> fields, Plan resolved, SetFieldRequiredResponse preview) {
        this.project = project;
        this.fields = fields;
        this.resolved = resolved;
        this.preview = preview;
    }

    /** A field and the required flag it must end up with. */
    static final class Wanted {
        final OrderableField field;
        final boolean required;

        Wanted(OrderableField field, boolean required) { this.field = field; this.required = required; }
    }

    static final class Plan {
        final Project project;
        final SchemeView scheme;
        final List<Target> targets;
        final List<LayoutChange> changes;
        final List<Split> splits;
        final List<OrderableField> fields;
        final List<Object> state;

        Plan(Project project, SchemeView scheme, List<Target> targets, List<LayoutChange> changes,
             List<Split> splits, List<OrderableField> fields, List<Object> state) {
            this.project = project; this.scheme = scheme; this.targets = targets;
            this.changes = changes; this.splits = splits; this.fields = fields; this.state = state;
        }

        boolean hasChanges() { return !changes.isEmpty() || !splits.isEmpty(); }

        /** Statuses are per issue type and field, in {@link TargetView}; the field list itself carries none. */
        List<FieldView> fieldViews() {
            List<FieldView> result = new ArrayList<FieldView>();
            for (OrderableField field : fields) result.add(FieldRequirementPlanner.view(field));
            return result;
        }

        String note() {
            if (splits.isEmpty()) return null;
            return "Some selected issue types cannot change the field configuration they use in place: it is shared "
                    + "with issue types that were not selected, or the selected types need different results. "
                    + "That configuration is not changed: the affected types get their own copy "
                    + "(newFieldConfigurationName) in scheme '" + scheme.getName() + "' and the required flags "
                    + "change only in the copies; unchangedIssueTypes keep the original configuration.";
        }

        List<TargetView> views(boolean dryRun) {
            List<TargetView> views = new ArrayList<TargetView>();
            for (Target target : targets) views.add(target.view(dryRun));
            return views;
        }
    }

    static final class Target {
        private final IssueType issueType;
        private final FieldLayout layout;
        final List<String> changedFields;
        private final List<OrderableField> fields;
        private final Map<String, Boolean> wanted;
        private final AffectedProjects otherProjects;
        private final Split split;

        Target(IssueType issueType, FieldLayout layout, List<String> changedFields, Map<String, Boolean> wanted,
               AffectedProjects otherProjects, Split split, List<OrderableField> fields) {
            this.issueType = issueType; this.layout = layout; this.changedFields = changedFields; this.wanted = wanted;
            this.otherProjects = otherProjects; this.split = split; this.fields = fields;
        }

        TargetView view(boolean dryRun) {
            Boolean uniform = null;
            boolean mixed = false;
            List<FieldStatus> statuses = new ArrayList<FieldStatus>();
            for (OrderableField field : fields) {
                boolean required = wanted.get(field.getId());
                if (uniform == null) uniform = required;
                else if (uniform != required) mixed = true;
                statuses.add(new FieldStatus(field.getId(),
                        TargetStatus.of(required, changedFields.contains(field.getId()), dryRun)));
            }
            TargetStatus status = mixed || uniform == null ? null
                    : TargetStatus.of(uniform, !changedFields.isEmpty(), dryRun);
            return new TargetView(new IssueTypeRef(issueType.getId(), issueType.getName()), layout.getId(),
                    layout.getName(), status, otherProjects.total, otherProjects.shown,
                    split == null ? null : split.createdId, split == null ? null : split.newName,
                    split == null ? Collections.<IssueTypeRef>emptyList() : split.unchanged, statuses);
        }
    }

    /** Request-local: a layout, the selected types that leave it with one common outcome, and the copy they move to. */
    static final class Split {
        final FieldLayout source;
        final List<IssueTypeRef> unchanged;
        final List<IssueType> issueTypes = new ArrayList<IssueType>();
        String newName;
        final List<Wanted> fields;
        Long createdId;

        Split(FieldLayout source, List<IssueTypeRef> unchanged, List<Wanted> fields) {
            this.source = source; this.unchanged = unchanged; this.fields = fields;
        }
    }

    static final class AffectedProjects {
        final int total;
        final List<ProjectRef> shown;

        AffectedProjects(int total, List<ProjectRef> shown) {
            this.total = total; this.shown = shown;
        }
    }

    static final class LayoutChange {
        final FieldLayout layout;
        final List<Wanted> fields;

        LayoutChange(FieldLayout layout, List<Wanted> fields) { this.layout = layout; this.fields = fields; }
    }
}
