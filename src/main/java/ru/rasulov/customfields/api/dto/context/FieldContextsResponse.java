package ru.rasulov.customfields.api.dto.context;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonInclude;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;

import java.util.Collections;
import java.util.List;

public final class FieldContextsResponse {
    public enum Status { READ, FAILED }
    public enum ErrorCode { UNKNOWN_FIELD, AMBIGUOUS_FIELD, UNKNOWN_CONTEXT, UNKNOWN_PROJECT, VALIDATION_ERROR }
    public enum TypeClass { SYSTEM, KNOWN_PLUGIN, THIRD_PARTY }
    public enum Presence { PRESENT, ABSENT, UNKNOWN }
    public enum PluginSettings { NOT_APPLICABLE, PRESENT, ABSENT, UNKNOWN }

    private final Status status;
    private final ErrorCode errorCode;
    private final String message;
    private final FieldView field;
    private final List<ContextView> contexts;
    private final List<FieldView> candidates;
    private final List<String> suggestions;

    private FieldContextsResponse(Status status, ErrorCode errorCode, String message, FieldView field,
                                  List<ContextView> contexts, List<FieldView> candidates, List<String> suggestions) {
        this.status = status; this.errorCode = errorCode; this.message = message; this.field = field;
        this.contexts = Immutables.listOf(contexts); this.candidates = Immutables.listOf(candidates);
        this.suggestions = suggestions == null ? null : Immutables.listOf(suggestions);
    }
    public static FieldContextsResponse read(FieldView field, List<ContextView> contexts) {
        return new FieldContextsResponse(Status.READ, null, "Field contexts read. Scope is projects × issue types; no configuration was changed.", field, contexts, null, null);
    }
    public static FieldContextsResponse failed(ErrorCode code, String message, FieldView field, List<FieldView> candidates) {
        return new FieldContextsResponse(Status.FAILED, code, message, field, Collections.<ContextView>emptyList(), candidates, null);
    }
    public static FieldContextsResponse projectFailed(ErrorCode code, String message, FieldView field, List<String> suggestions) {
        return new FieldContextsResponse(Status.FAILED, code, message, field, Collections.<ContextView>emptyList(), null, suggestions);
    }
    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }
    @JsonProperty("field") public FieldView getField() { return field; }
    @JsonProperty("contexts") public List<ContextView> getContexts() { return contexts; }
    @JsonProperty("candidates") public List<FieldView> getCandidates() { return candidates; }
    @JsonProperty("suggestions") @JsonInclude(JsonInclude.Include.NON_NULL)
    public List<String> getSuggestions() { return suggestions; }

    public static final class FieldView {
        private final String id, name, typeKey, typeName;
        private final TypeClass typeClass;
        private final boolean managed;
        private final boolean locked;
        public FieldView(String id, String name, String typeKey, String typeName, TypeClass typeClass,
                         boolean managed, boolean locked) {
            this.id = id; this.name = name; this.typeKey = typeKey; this.typeName = typeName;
            this.typeClass = typeClass; this.managed = managed; this.locked = locked;
        }
        @JsonProperty("id") public String getId() { return id; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("typeKey") public String getTypeKey() { return typeKey; }
        @JsonProperty("typeName") public String getTypeName() { return typeName; }
        @JsonProperty("typeClass") public TypeClass getTypeClass() { return typeClass; }
        @JsonProperty("managed") public boolean isManaged() { return managed; }
        @JsonProperty("locked") public boolean isLocked() { return locked; }
    }

    public static final class ContextView {
        private final Long contextId;
        private final String name, description;
        private final boolean allProjects, projectsComplete, allIssueTypes;
        private final int projectCount;
        private final List<ProjectView> projects;
        private final List<IssueTypeRef> issueTypes;
        private final List<ConfigView> configs;
        private final OptionsView options;
        private final Presence defaultValue;
        private final PluginSettings pluginSettings;
        private final AssetsFieldSettings assets;
        private final OptionListView optionList;
        public ContextView(Long contextId, String name, String description, boolean allProjects, int projectCount,
                           List<ProjectView> projects, boolean projectsComplete, boolean allIssueTypes,
                           List<IssueTypeRef> issueTypes, List<ConfigView> configs, OptionsView options,
                           Presence defaultValue, PluginSettings pluginSettings, AssetsFieldSettings assets,
                           OptionListView optionList) {
            this.contextId = contextId; this.name = name; this.description = description;
            this.allProjects = allProjects; this.projectCount = projectCount; this.projects = Immutables.listOf(projects);
            this.projectsComplete = projectsComplete; this.allIssueTypes = allIssueTypes;
            this.issueTypes = Immutables.listOf(issueTypes); this.configs = Immutables.listOf(configs);
            this.options = options; this.defaultValue = defaultValue; this.pluginSettings = pluginSettings;
            this.assets = assets; this.optionList = optionList;
        }
        @JsonProperty("contextId") public Long getContextId() { return contextId; }
        @JsonProperty("name") public String getName() { return name; }
        @JsonProperty("description") @JsonInclude(JsonInclude.Include.ALWAYS)
        public String getDescription() { return description; }
        @JsonProperty("allProjects") public boolean isAllProjects() { return allProjects; }
        @JsonProperty("projectCount") public int getProjectCount() { return projectCount; }
        @JsonProperty("projects") public List<ProjectView> getProjects() { return projects; }
        @JsonProperty("projectsComplete") public boolean isProjectsComplete() { return projectsComplete; }
        @JsonProperty("allIssueTypes") public boolean isAllIssueTypes() { return allIssueTypes; }
        @JsonProperty("issueTypes") public List<IssueTypeRef> getIssueTypes() { return issueTypes; }
        @JsonProperty("configs") public List<ConfigView> getConfigs() { return configs; }
        @JsonProperty("options") @JsonInclude(JsonInclude.Include.ALWAYS)
        public OptionsView getOptions() { return options; }
        @JsonProperty("defaultValue") public Presence getDefaultValue() { return defaultValue; }
        @JsonProperty("pluginSettings") public PluginSettings getPluginSettings() { return pluginSettings; }
        /** Stored Assets object settings of a context with exactly one FieldConfig; absent when not readable. */
        @JsonProperty("assets") public AssetsFieldSettings getAssets() { return assets; }
        /** Every option of the context with id, parent, order and default flag; only when includeOptions was requested. */
        @JsonProperty("optionList") @JsonInclude(JsonInclude.Include.NON_NULL)
        public OptionListView getOptionList() { return optionList; }
    }

    public static final class ProjectView {
        private final Long id;
        private final String key, name;
        public ProjectView(Long id, String key, String name) { this.id = id; this.key = key; this.name = name; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("key") public String getKey() { return key; }
        @JsonProperty("name") public String getName() { return name; }
    }

    public static final class ConfigView {
        private final Long configId;
        private final List<String> issueTypeIds;
        public ConfigView(Long configId, List<String> issueTypeIds) {
            this.configId = configId; this.issueTypeIds = issueTypeIds == null ? null : Immutables.listOf(issueTypeIds);
        }
        @JsonProperty("configId") public Long getConfigId() { return configId; }
        @JsonProperty("issueTypeIds") @JsonInclude(JsonInclude.Include.ALWAYS)
        public List<String> getIssueTypeIds() { return issueTypeIds; }
    }

    public static final class OptionsView {
        private final int count, disabledCount;
        public OptionsView(int count, int disabledCount) { this.count = count; this.disabledCount = disabledCount; }
        @JsonProperty("count") public int getCount() { return count; }
        @JsonProperty("disabledCount") public int getDisabledCount() { return disabledCount; }
    }

    /** The options of one context as an exact list. {@code hasOptions=false} says the context keeps no options at all. */
    public static final class OptionListView {
        private final boolean hasOptions;
        private final List<OptionItem> items;
        public OptionListView(boolean hasOptions, List<OptionItem> items) {
            this.hasOptions = hasOptions; this.items = Immutables.listOf(items);
        }
        @JsonProperty("hasOptions") public boolean isHasOptions() { return hasOptions; }
        @JsonProperty("items") public List<OptionItem> getItems() { return items; }
    }

    /** One option. {@code position} is the 0-based place among the options of its level; a child lists its parent. */
    public static final class OptionItem {
        private final Long configId, id, parentId;
        private final String value;
        private final boolean disabled;
        private final Boolean isDefault;
        private final int position;
        public OptionItem(Long configId, Long id, Long parentId, String value, boolean disabled, Boolean isDefault,
                          int position) {
            this.configId = configId; this.id = id; this.parentId = parentId; this.value = value;
            this.disabled = disabled; this.isDefault = isDefault; this.position = position;
        }
        @JsonProperty("configId") public Long getConfigId() { return configId; }
        @JsonProperty("id") public Long getId() { return id; }
        @JsonProperty("value") public String getValue() { return value; }
        @JsonProperty("parentId") @JsonInclude(JsonInclude.Include.ALWAYS)
        public Long getParentId() { return parentId; }
        @JsonProperty("disabled") public boolean isDisabled() { return disabled; }
        /** True for the context's default value; absent when the default could not be read. */
        @JsonProperty("default") public Boolean getDefault() { return isDefault; }
        @JsonProperty("position") public int getPosition() { return position; }
    }
}
