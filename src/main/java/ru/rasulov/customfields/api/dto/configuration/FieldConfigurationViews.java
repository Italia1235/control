package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/** Views shared by the field configuration and field configuration scheme tools. */
public final class FieldConfigurationViews {
    private FieldConfigurationViews() { }

    public enum ErrorCode {
        VALIDATION_ERROR, UNKNOWN_PROJECT, UNKNOWN_FIELD, AMBIGUOUS_FIELD, UNKNOWN_ISSUE_TYPE,
        UNKNOWN_FIELD_CONFIGURATION, UNKNOWN_SCHEME, NAME_TAKEN, DEFAULT_SCHEME, PREVIEW_STALE, JIRA_ERROR
    }

    /** One field configuration; {@code id} is null for Jira's never-stored Default Field Configuration. */
    public static final class ConfigurationRef {
        private final Long id;
        private final String name;
        private final boolean defaultConfiguration;

        public ConfigurationRef(Long id, String name, boolean defaultConfiguration) {
            this.id = id; this.name = name; this.defaultConfiguration = defaultConfiguration;
        }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("defaultConfiguration") public boolean isDefaultConfiguration() { return defaultConfiguration; }
    }

    /** A field configuration scheme; {@code defaultScheme=true} is Jira's implicit scheme with no id. */
    public static final class SchemeRef {
        private final Long id;
        private final String name;
        private final String description;
        private final boolean defaultScheme;

        public SchemeRef(Long id, String name, String description, boolean defaultScheme) {
            this.id = id; this.name = name; this.description = description; this.defaultScheme = defaultScheme;
        }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("description") public String getDescription() { return description; }
        @JsonProperty("defaultScheme") public boolean isDefaultScheme() { return defaultScheme; }
    }

    /** A custom field (typeKey/typeName of its type) or a system field (typeName "System field"). */
    public static final class FieldRef {
        private final String id;
        private final String name;
        private final String typeKey;
        private final String typeName;

        public FieldRef(String id, String name, String typeKey, String typeName) {
            this.id = id; this.name = name; this.typeKey = typeKey; this.typeName = typeName;
        }
        @JsonProperty("id") public String getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("typeKey") public String getTypeKey() { return typeKey; }
        @JsonProperty("typeName") public String getTypeName() { return typeName; }
    }

    /** Projects other than the one asked about; at most ten are named, {@code total} counts all. */
    public static final class Usage {
        private final int total;
        private final List<ProjectRef> projects;

        public Usage(int total, List<ProjectRef> projects) {
            this.total = total; this.projects = Immutables.listOf(projects);
        }
        @JsonProperty("total") public int getTotal() { return total; }
        @JsonProperty("projects") public List<ProjectRef> getProjects() { return projects; }
    }

    /** One issue type's field configuration in a scheme; {@code explicit=false} means it falls to the DEFAULT row. */
    public static final class MappingView {
        private final String issueTypeId;
        private final String issueTypeName;
        private final boolean explicit;
        private final ConfigurationRef fieldConfiguration;

        public MappingView(String issueTypeId, String issueTypeName, boolean explicit,
                           ConfigurationRef fieldConfiguration) {
            this.issueTypeId = issueTypeId; this.issueTypeName = issueTypeName; this.explicit = explicit;
            this.fieldConfiguration = fieldConfiguration;
        }
        @JsonProperty("issueTypeId") public String getIssueTypeId() { return issueTypeId; }
        @JsonProperty("issueTypeName") public String getIssueTypeName() { return issueTypeName; }
        @JsonProperty("explicit") public boolean isExplicit() { return explicit; }
        @JsonProperty("fieldConfiguration") public ConfigurationRef getFieldConfiguration() { return fieldConfiguration; }
    }

    /** A field whose required or hidden flag differs between two field configurations. */
    public static final class FieldChange {
        private final FieldRef field;
        private final boolean requiredBefore;
        private final boolean requiredAfter;
        private final boolean hiddenBefore;
        private final boolean hiddenAfter;

        public FieldChange(FieldRef field, boolean requiredBefore, boolean requiredAfter, boolean hiddenBefore,
                           boolean hiddenAfter) {
            this.field = field; this.requiredBefore = requiredBefore; this.requiredAfter = requiredAfter;
            this.hiddenBefore = hiddenBefore; this.hiddenAfter = hiddenAfter;
        }
        @JsonProperty("field") public FieldRef getField() { return field; }
        @JsonProperty("requiredBefore") public boolean isRequiredBefore() { return requiredBefore; }
        @JsonProperty("requiredAfter") public boolean isRequiredAfter() { return requiredAfter; }
        @JsonProperty("hiddenBefore") public boolean isHiddenBefore() { return hiddenBefore; }
        @JsonProperty("hiddenAfter") public boolean isHiddenAfter() { return hiddenAfter; }
    }

    /**
     * How one issue type of one project changes: its field configuration before and after, and the fields whose
     * required/hidden flags change (at most 50; {@code truncated} when more do, counted in {@code fieldsTotal}).
     */
    public static final class IssueTypeChange {
        private final String issueTypeId;
        private final String issueTypeName;
        private final ConfigurationRef before;
        private final ConfigurationRef after;
        private final List<FieldChange> fields;
        private final int fieldsTotal;

        public IssueTypeChange(String issueTypeId, String issueTypeName, ConfigurationRef before,
                               ConfigurationRef after, List<FieldChange> fields, int fieldsTotal) {
            this.issueTypeId = issueTypeId; this.issueTypeName = issueTypeName; this.before = before;
            this.after = after; this.fields = Immutables.listOf(fields); this.fieldsTotal = fieldsTotal;
        }
        @JsonProperty("issueTypeId") public String getIssueTypeId() { return issueTypeId; }
        @JsonProperty("issueTypeName") public String getIssueTypeName() { return issueTypeName; }
        @JsonProperty("before") public ConfigurationRef getBefore() { return before; }
        @JsonProperty("after") public ConfigurationRef getAfter() { return after; }
        @JsonProperty("fields") public List<FieldChange> getFields() { return fields; }
        @JsonProperty("fieldsTotal") public int getFieldsTotal() { return fieldsTotal; }
        @JsonProperty("truncated") public boolean isTruncated() { return fieldsTotal > fields.size(); }
    }

    /** The issue types of one project whose effective field configuration changes. */
    public static final class ProjectImpact {
        private final ProjectRef project;
        private final List<IssueTypeChange> issueTypes;

        public ProjectImpact(ProjectRef project, List<IssueTypeChange> issueTypes) {
            this.project = project; this.issueTypes = Immutables.listOf(issueTypes);
        }
        @JsonProperty("project") public ProjectRef getProject() { return project; }
        @JsonProperty("issueTypes") public List<IssueTypeChange> getIssueTypes() { return issueTypes; }
    }

    /** A consequence the user must hear before confirming: NEWLY_REQUIRED, NEWLY_HIDDEN, SHARED_SCHEME, ... */
    public static final class Warning {
        private final String code;
        private final String message;

        public Warning(String code, String message) {
            this.code = code; this.message = message;
        }
        @JsonProperty("code") public String getCode() { return code; }
        @JsonProperty("message") public String getMessage() { return message; }
    }
}
