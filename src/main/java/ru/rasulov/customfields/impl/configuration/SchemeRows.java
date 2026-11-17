package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.issuetype.IssueType;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.MappingView;
import ru.rasulov.customfields.api.dto.configuration.SchemeMappingInput;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolves requested scheme rows: issue type (or DEFAULT) to field configuration (or DEFAULT, or INHERIT). */
@Named
public class SchemeRows {
    public static final String INHERIT = "INHERIT";
    static final int MAX_ROWS = 50;

    private final IssueTypeResolver issueTypes;
    private final FieldConfigurationCatalog catalog;

    @Inject
    public SchemeRows(IssueTypeResolver issueTypes, FieldConfigurationCatalog catalog) {
        this.issueTypes = issueTypes;
        this.catalog = catalog;
    }

    /** Requested rows in request order; a null key is the DEFAULT row, a null layout is INHERIT. */
    public Resolved resolve(List<SchemeMappingInput> inputs, boolean allowInherit) {
        if (inputs.isEmpty()) throw invalid("'mappings' must contain at least one row.");
        if (inputs.size() > MAX_ROWS) throw invalid("'mappings' has " + inputs.size() + " rows; the maximum is " + MAX_ROWS + ".");
        Resolved resolved = new Resolved();
        for (int i = 0; i < inputs.size(); i++) {
            SchemeMappingInput input = inputs.get(i);
            if (input == null) throw invalid("mappings[" + i + "] is empty.");
            String typeText = trim(input.getIssueType());
            String target = trim(input.getFieldConfiguration());
            if (typeText.isEmpty()) throw invalid("mappings[" + i + "].issueType is required: an issue type or DEFAULT.");
            if (target.isEmpty()) throw invalid("mappings[" + i + "].fieldConfiguration is required.");
            String typeId = null;
            String typeName = FieldConfigurationCatalog.DEFAULT;
            if (!FieldConfigurationCatalog.DEFAULT.equals(typeText)) {
                IssueType type = issueType(typeText);
                typeId = type.getId();
                typeName = type.getName();
            }
            if (resolved.rows.containsKey(typeId)) {
                throw invalid("Issue type '" + typeName + "' is listed more than once in 'mappings'.");
            }
            FieldLayout layout = null;
            if (INHERIT.equals(target)) {
                if (!allowInherit) throw invalid("mappings[" + i + "].fieldConfiguration INHERIT is not allowed here.");
                if (typeId == null) throw invalid("The DEFAULT row cannot INHERIT: give it a field configuration.");
            } else {
                layout = catalog.configuration(target);
            }
            resolved.rows.put(typeId, layout);
            resolved.names.put(typeId, typeName);
        }
        return resolved;
    }

    private IssueType issueType(String text) {
        try {
            return issueTypes.resolve(text);
        } catch (LookupFailure e) {
            throw new FieldConfigurationCatalog.Failure(e.getKind() == LookupFailure.Kind.UNKNOWN_ISSUE_TYPE
                    ? ErrorCode.UNKNOWN_ISSUE_TYPE : ErrorCode.VALIDATION_ERROR, e.getMessage());
        }
    }

    private static FieldConfigurationCatalog.Failure invalid(String message) {
        return new FieldConfigurationCatalog.Failure(ErrorCode.VALIDATION_ERROR, message);
    }

    private static String trim(String text) { return text == null ? "" : text.trim(); }

    /** Request-local resolved rows. */
    public static final class Resolved {
        final Map<String, FieldLayout> rows = new LinkedHashMap<String, FieldLayout>();
        final Map<String, String> names = new LinkedHashMap<String, String>();

        /** Rows as views; INHERIT rows have no field configuration. */
        List<MappingView> views() {
            List<MappingView> views = new ArrayList<MappingView>();
            for (Map.Entry<String, FieldLayout> row : rows.entrySet()) {
                views.add(new MappingView(row.getKey(), names.get(row.getKey()), row.getValue() != null,
                        row.getValue() == null ? null : FieldConfigurationCatalog.ref(row.getValue())));
            }
            return views;
        }

        /** Target configuration ids, null for Jira's default configuration. */
        Long layoutId(String issueTypeId) {
            FieldLayout layout = rows.get(issueTypeId);
            return layout == null || layout.isDefault() ? null : layout.getId();
        }
    }
}
