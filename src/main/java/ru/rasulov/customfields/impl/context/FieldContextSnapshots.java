package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.ofbiz.OfBizDelegator;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.customfields.impl.undo.CustomFieldUndoHandler;
import ru.rasulov.customfields.impl.validation.TypeKeys;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** Read-only persisted state. Never treats a failed snapshot as absence. */
@Named
public class FieldContextSnapshots {
    private final CustomFieldUndoHandler fields;
    private final OfBizDelegator db;
    private final AssetsFieldConfiguration assets;

    @Inject
    public FieldContextSnapshots(CustomFieldUndoHandler fields, @ComponentImport OfBizDelegator db,
                                 AssetsFieldConfiguration assets) {
        this.fields = fields; this.db = db; this.assets = assets;
    }

    public String persistedField(CustomField field) {
        return fields.persistedSnapshot(field.getName(), field.getId());
    }

    /** Config identity is retained by the writer; options include children, order, values and disabled state. */
    public Map<String, Object> config(CustomField field, Long configId) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        List<Object> options = new ArrayList<Object>();
        for (GenericValue row : db.findByAnd("CustomFieldOption",
                Collections.singletonMap("customfieldconfig", configId), Collections.singletonList("id ASC"))) {
            options.add(Arrays.asList(row.getLong("id"), row.getLong("parentoptionid"), row.getLong("sequence"),
                    row.getString("value"), row.getString("disabled")));
        }
        List<Object> generic = new ArrayList<Object>();
        // Every datatype for this config, not only DefaultValue: FileField settings also live here.
        for (GenericValue row : db.findByAnd("GenericConfiguration",
                Collections.singletonMap("datakey", configId.toString()), Collections.singletonList("id ASC"))) {
            generic.add(Arrays.asList(row.getLong("id"), row.getString("datatype"), row.getString("datakey"),
                    row.getString("xmlvalue")));
        }
        result.put("configId", configId); result.put("options", options); result.put("genericConfiguration", generic);
        String typeKey = field.getCustomFieldType().getKey();
        if ((TypeKeys.SYSTEM_PLUGIN + ":userpicker").equals(typeKey)
                || (TypeKeys.SYSTEM_PLUGIN + ":multiuserpicker").equals(typeKey)) {
            List<Object> filters = new ArrayList<Object>();
            for (GenericValue row : db.findByAnd("UserPickerFilter",
                    Collections.singletonMap("customfieldconfig", configId), Collections.singletonList("id ASC"))) {
                Map<String, Object> filter = new LinkedHashMap<String, Object>();
                filter.put("filter", new TreeMap<String, Object>(row.getAllFields()));
                filter.put("groups", filterRows("UserPickerFilterGroup", row.getLong("id")));
                filter.put("roles", filterRows("UserPickerFilterRole", row.getLong("id")));
                filters.add(filter);
            }
            result.put("userPickerFilters", filters);
        }
        if (AssetsFieldConfiguration.TYPE_KEY.equals(field.getCustomFieldType().getKey()))
            result.put("assets", assets.snapshot(configId));
        return result;
    }

    private List<Object> filterRows(String entity, Long filterId) {
        List<Object> rows = new ArrayList<Object>();
        for (GenericValue row : db.findByAnd(entity, Collections.singletonMap("userpickerfilter", filterId),
                Collections.singletonList("id ASC")))
            rows.add(new TreeMap<String, Object>(row.getAllFields()));
        return rows;
    }
}
