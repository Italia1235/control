package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse.CopyView;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse.ErrorCode;

import ru.rasulov.common.impl.undo.ChangeJournal;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Named
public class FieldConfigurationCloneWriter {
    private final FieldLayoutManager manager;
    private final FieldLayoutCopyFactory factory;
    private final ChangeJournal journal;

    @Inject
    public FieldConfigurationCloneWriter(@ComponentImport FieldLayoutManager manager,
                                         FieldLayoutCopyFactory factory, ChangeJournal journal) {
        this.manager = manager;
        this.factory = factory;
        this.journal = journal;
    }

    Written write(FieldConfigurationClonePlan plan) {
        Map<FieldLayout, EditableFieldLayout> copies = new LinkedHashMap<FieldLayout, EditableFieldLayout>();
        for (Map.Entry<FieldLayout, String> entry : plan.layouts.entrySet()) {
            EditableFieldLayout copy = manager.storeAndReturnEditableFieldLayout(
                    factory.copyOf(entry.getKey(), entry.getValue()));
            manager.refresh();
            copy = manager.getEditableFieldLayout(copy.getId());
            copies.put(entry.getKey(), copy);
            journal.checkpoint();
        }
        FieldLayoutScheme createdScheme = manager.createFieldLayoutScheme(plan.newSchemeName, plan.sourceDescription);
        manager.refresh();
        createdScheme = manager.getMutableFieldLayoutScheme(createdScheme.getId());
        journal.checkpoint();
        for (FieldConfigurationClonePlan.Mapping mapping : plan.mappings) {
            manager.refresh();
            createdScheme = manager.getMutableFieldLayoutScheme(createdScheme.getId());
            EditableFieldLayout target = copies.get(mapping.layout);
            if (target == null || target.getId() == null) throw new IllegalStateException("A copied field configuration has no id.");
            FieldLayoutSchemeEntity existing = createdScheme.getEntity(mapping.issueTypeId);
            if (existing == null) manager.createFieldLayoutSchemeEntity(createdScheme, mapping.issueTypeId, target.getId());
            else {
                existing.setFieldLayoutId(target.getId());
                manager.updateFieldLayoutSchemeEntity(existing);
            }
            journal.checkpoint();
        }
        manager.addSchemeAssociation(plan.project, createdScheme.getId());
        manager.refresh();
        com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme actualAssignment =
                manager.getFieldConfigurationScheme(plan.project);
        if (actualAssignment == null || !createdScheme.getId().equals(actualAssignment.getId())) {
            throw new IllegalStateException("Jira did not persist the requested project field configuration assignment.");
        }
        journal.checkpoint();
        return new Written(createdScheme, copies);
    }

    static final class Written {
        private final FieldLayoutScheme scheme;
        private final Map<FieldLayout, EditableFieldLayout> layouts;

        Written(FieldLayoutScheme scheme, Map<FieldLayout, EditableFieldLayout> layouts) {
            this.scheme = scheme;
            this.layouts = new LinkedHashMap<FieldLayout, EditableFieldLayout>(layouts);
        }

        CopyView schemeView(CopyView planned) {
            return copied(planned, scheme.getId(), scheme.getName());
        }

        List<CopyView> layoutViews(List<CopyView> planned) {
            List<CopyView> result = new ArrayList<CopyView>();
            for (CopyView view : planned) {
                EditableFieldLayout copy = find(view.getSourceId());
                result.add(copy == null ? view : copied(view, copy.getId(), copy.getName()));
            }
            return result;
        }

        private EditableFieldLayout find(Long sourceId) {
            for (Map.Entry<FieldLayout, EditableFieldLayout> entry : layouts.entrySet()) {
                if (sourceId == null ? entry.getKey().getId() == null : sourceId.equals(entry.getKey().getId())) {
                    return entry.getValue();
                }
            }
            return null;
        }

        private static CopyView copied(CopyView source, Long id, String name) {
            return new CopyView(source.getSourceId(), source.getSourceName(), id, name,
                    source.getOtherProjectsTotal(), source.getOtherProjects());
        }
    }
}
