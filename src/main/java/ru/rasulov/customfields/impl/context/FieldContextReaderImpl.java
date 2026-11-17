package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItem;
import com.atlassian.jira.config.managedconfiguration.ManagedConfigurationItemService;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.MultipleSettableCustomFieldType;
import com.atlassian.jira.issue.customfields.manager.GenericConfigManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.api.FieldContextReader;
import ru.rasulov.customfields.api.dto.context.FieldContextsResponse;
import ru.rasulov.customfields.api.dto.context.FieldContextsResponse.*;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.customfields.impl.filefield.FileFieldConfiguration;
import ru.rasulov.customfields.impl.grid.GridFieldConfiguration;
import ru.rasulov.customfields.impl.validation.TypeKeys;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

@Named
@ExportAsService(FieldContextReader.class)
public class FieldContextReaderImpl implements FieldContextReader {
    private static final int PROJECT_SUMMARY_LIMIT = 50;
    private final AdministratorGuard guard;
    private final FieldSelector selector;
    private final ProjectResolver projects;
    private final ManagedConfigurationItemService managed;
    private final OptionsManager options;
    private final GenericConfigManager generic;
    private final AssetsFieldConfiguration assets;
    private final FileFieldConfiguration files;

    @Inject
    public FieldContextReaderImpl(AdministratorGuard guard, FieldSelector selector, ProjectResolver projects,
                                  @ComponentImport ManagedConfigurationItemService managed,
                                  @ComponentImport OptionsManager options,
                                  @ComponentImport GenericConfigManager generic, AssetsFieldConfiguration assets,
                                  FileFieldConfiguration files) {
        this.guard = guard;
        this.selector = selector;
        this.projects = projects;
        this.managed = managed;
        this.options = options;
        this.generic = generic;
        this.assets = assets;
        this.files = files;
    }

    @Override
    public FieldContextsResponse read(String fieldSelector, String projectSelector, Long contextId, boolean includeOptions) {
        ApplicationUser user = guard.requireAdministrator();
        if (contextId != null && contextId <= 0) return FieldContextsResponse.failed(ErrorCode.VALIDATION_ERROR,
                "contextId must be a positive integer from get_field_contexts.", null, null);
        if (includeOptions && contextId == null) return FieldContextsResponse.failed(ErrorCode.VALIDATION_ERROR,
                "includeOptions needs a contextId: read the contexts first, then ask for the options of one context.", null, null);
        CustomField field;
        try {
            field = selector.resolve(fieldSelector);
        } catch (FieldSelector.Failure e) {
            List<FieldView> candidates = new ArrayList<FieldView>();
            for (CustomField candidate : e.getCandidates()) candidates.add(view(candidate, user));
            return FieldContextsResponse.failed(ErrorCode.valueOf(e.getCode().name()), e.getMessage(), null, candidates);
        }
        FieldView view = view(field, user);
        Project project;
        try {
            project = projectSelector == null ? null : projects.resolve(projectSelector);
        } catch (LookupFailure e) {
            return FieldContextsResponse.projectFailed(e.getKind() == LookupFailure.Kind.UNKNOWN_PROJECT
                    ? ErrorCode.UNKNOWN_PROJECT : ErrorCode.VALIDATION_ERROR, e.getMessage(), view, e.getSuggestions());
        }
        List<FieldConfigScheme> schemes = new ArrayList<FieldConfigScheme>(field.getConfigurationSchemes());
        schemes.sort(Comparator.comparing(FieldConfigScheme::getId));
        List<ContextView> contexts = new ArrayList<ContextView>();
        Map<Long, Observation> observed = new HashMap<Long, Observation>();
        boolean contextFound = contextId == null;
        for (FieldConfigScheme scheme : schemes) {
            if (contextId != null && !contextId.equals(scheme.getId())) continue;
            contextFound = true;
            Collection<Project> scopeProjects = scheme.isAllProjects() ? Collections.<Project>emptyList()
                    : scheme.getAssociatedProjectObjects();
            if (project != null && !scheme.isAllProjects() && !containsProject(scopeProjects, project.getId())) continue;
            contexts.add(context(field, view.getTypeClass(), scheme, scopeProjects, contextId != null, observed, includeOptions));
        }
        if (!contextFound) return FieldContextsResponse.failed(ErrorCode.UNKNOWN_CONTEXT,
                "Context " + contextId + " does not belong to field " + field.getId() + ".", view, null);
        return FieldContextsResponse.read(view, contexts);
    }

    private static boolean containsProject(Collection<Project> projects, Long id) {
        for (Project project : projects) if (id.equals(project.getId())) return true;
        return false;
    }

    /**
     * {@code locked} is Jira's own test of its Edit Context form: the item exists and the current user may not edit
     * it. A managed item that the administrator is allowed to edit is managed, not locked.
     */
    private FieldView view(CustomField field, ApplicationUser user) {
        CustomFieldType<?, ?> type = field.getCustomFieldType();
        String key = type == null ? null : type.getKey();
        ManagedConfigurationItem item = managed.getManagedCustomField(field);
        boolean isManaged = item != null && item.isManaged();
        boolean locked = item != null && !managed.doesUserHavePermission(user, item);
        return new FieldView(field.getId(), field.getName(), key, type == null ? null : type.getName(),
                FieldContextTypes.typeClass(key), isManaged, locked);
    }


    private ContextView context(CustomField field, TypeClass typeClass, FieldConfigScheme scheme, Collection<Project> scopeProjects,
                                boolean fullProjects, Map<Long, Observation> observed, boolean includeOptions) {
        List<Project> projects = new ArrayList<Project>(scopeProjects);
        projects.sort(Comparator.comparing(Project::getId));
        int shown = fullProjects ? projects.size() : Math.min(PROJECT_SUMMARY_LIMIT, projects.size());
        List<ProjectView> projectViews = new ArrayList<ProjectView>(shown);
        for (int i = 0; i < shown; i++) {
            Project project = projects.get(i);
            projectViews.add(new ProjectView(project.getId(), project.getKey(), project.getName()));
        }
        List<IssueTypeRef> issueTypes = new ArrayList<IssueTypeRef>();
        if (!scheme.isAllIssueTypes()) {
            List<IssueType> types = new ArrayList<IssueType>(scheme.getAssociatedIssueTypes());
            types.sort(Comparator.comparing(IssueType::getId));
            for (IssueType type : types) issueTypes.add(new IssueTypeRef(type.getId(), type.getName()));
        }
        Map<Long, FieldConfig> configs = new TreeMap<Long, FieldConfig>();
        Map<Long, List<String>> mappings = new HashMap<Long, List<String>>();
        for (Map.Entry<String, FieldConfig> entry : scheme.getConfigs().entrySet()) {
            FieldConfig config = entry.getValue();
            configs.put(config.getId(), config);
            mappings.computeIfAbsent(config.getId(), ignored -> new ArrayList<String>()).add(entry.getKey());
        }
        List<ConfigView> configViews = new ArrayList<ConfigView>();
        int count = 0, disabled = 0;
        boolean hasOptions = field.getCustomFieldType() instanceof MultipleSettableCustomFieldType;
        Presence defaultValue = Presence.ABSENT;
        PluginSettings plugin = typeClass == TypeClass.SYSTEM ? PluginSettings.NOT_APPLICABLE : PluginSettings.ABSENT;
        AssetsFieldSettings contextAssets = null;
        for (FieldConfig config : configs.values()) {
            List<String> ids = mappings.get(config.getId());
            if (ids.contains(null)) ids = null;
            else Collections.sort(ids);
            configViews.add(new ConfigView(config.getId(), ids));
            Observation state = observed.computeIfAbsent(config.getId(), ignored -> observe(field, typeClass, config));
            count += state.count; disabled += state.disabled;
            hasOptions |= state.count > 0;
            defaultValue = merge(defaultValue, state.defaultValue);
            if (state.pluginSettings == PluginSettings.UNKNOWN || plugin == PluginSettings.UNKNOWN) plugin = PluginSettings.UNKNOWN;
            else if (state.pluginSettings == PluginSettings.PRESENT) plugin = PluginSettings.PRESENT;
            // Settings belong to one FieldConfig; a context spanning several configs has no single value to report.
            if (configs.size() == 1) contextAssets = state.assets;
        }
        // No config to inspect is not evidence that a plugin has no settings.
        if (configs.isEmpty() && typeClass != TypeClass.SYSTEM) plugin = PluginSettings.UNKNOWN;
        return new ContextView(scheme.getId(), scheme.getName(), scheme.getDescription(), scheme.isAllProjects(),
                projects.size(), projectViews, shown == projects.size(), scheme.isAllIssueTypes(), issueTypes, configViews,
                hasOptions ? new OptionsView(count, disabled) : null, defaultValue, plugin, contextAssets,
                includeOptions ? optionList(field, configs.values(), hasOptions) : null);
    }

    /** Every option of the context's configs as an exact list, parents before their children, each level in order. */
    private OptionListView optionList(CustomField field, Collection<FieldConfig> configs, boolean hasOptions) {
        List<OptionItem> items = new ArrayList<OptionItem>();
        if (!hasOptions) return new OptionListView(false, items);
        for (FieldConfig config : configs) {
            Set<Long> defaults;
            try {
                defaults = OptionTree.defaultIds(field, config);
            } catch (RuntimeException e) {
                defaults = null; // an unreadable default is left out, never reported as "not the default"
            }
            Map<Long, Integer> next = new HashMap<Long, Integer>();
            for (OptionTree.Node node : OptionTree.read(options.getOptions(config))) {
                int position = next.merge(node.parentId, 1, Integer::sum) - 1;
                items.add(new OptionItem(config.getId(), node.id, node.parentId, node.value, node.disabled,
                        defaults == null ? null : defaults.contains(node.id), position));
            }
        }
        return new OptionListView(true, items);
    }

    private Observation observe(CustomField field, TypeClass typeClass, FieldConfig config) {
        int count = 0, disabled = 0;
        Options values = options.getOptions(config);
        Set<Long> seen = new HashSet<Long>();
        Deque<Option> pending = new ArrayDeque<Option>();
        if (values != null) for (Option option : values) pending.add(option);
        while (!pending.isEmpty()) {
            Option option = pending.poll();
            if (option == null || !seen.add(option.getOptionId())) continue;
            count++;
            if (Boolean.TRUE.equals(option.getDisabled())) disabled++;
            List<Option> children = option.getChildOptions();
            if (children != null) pending.addAll(children);
        }
        Presence defaultValue;
        try {
            defaultValue = typeClass == TypeClass.SYSTEM ? storedDefault(config) : typedDefault(field, config);
        } catch (RuntimeException e) {
            defaultValue = Presence.UNKNOWN;
        }
        CustomFieldType<?, ?> fieldType = field.getCustomFieldType();
        String key = fieldType == null ? null : fieldType.getKey(); // null: the type's plugin is disabled or removed
        PluginSettings plugin;
        AssetsFieldSettings stored = null;
        if (typeClass != TypeClass.SYSTEM && AssetsFieldConfiguration.TYPE_KEY.equals(key)) {
            try {
                String snapshot = assets.snapshot(config.getId());
                if (assets.initialized(snapshot)) { stored = assets.settings(snapshot); plugin = PluginSettings.PRESENT; }
                else plugin = PluginSettings.ABSENT;
            } catch (RuntimeException e) {
                plugin = PluginSettings.UNKNOWN; stored = null;
            }
        } else plugin = pluginSettings(key, typeClass, config.getId());
        return new Observation(count, disabled, defaultValue, plugin, stored);
    }

    /**
     * Standard types keep their default as one "DefaultValue" row per FieldConfig. The row is what a copy carries over,
     * so it is the truth here: the typed read comes back empty when the row points at a deleted user or version.
     */
    private Presence storedDefault(FieldConfig config) {
        return generic.retrieve("DefaultValue", config.getId().toString()) != null ? Presence.PRESENT : Presence.ABSENT;
    }

    private static Presence typedDefault(CustomField field, FieldConfig config) {
        Object value = field.getCustomFieldType().getDefaultValue(config);
        boolean absent = value == null || (value instanceof Collection && ((Collection<?>) value).isEmpty())
                || (value instanceof Map && ((Map<?, ?>) value).isEmpty()) || "".equals(value);
        return absent ? Presence.ABSENT : Presence.PRESENT;
    }

    private PluginSettings pluginSettings(String key, TypeClass typeClass, Long configId) {
        if (typeClass == TypeClass.SYSTEM) return PluginSettings.NOT_APPLICABLE;
        try {
            if (FileFieldConfiguration.TYPE_KEY.equals(key))
                return files.initialized(configId) ? PluginSettings.PRESENT : PluginSettings.ABSENT;
            // Grid has no safe read adapter yet; never infer absence from the lack of a reader.
            return PluginSettings.UNKNOWN;
        } catch (RuntimeException e) {
            return PluginSettings.UNKNOWN;
        }
    }

    private static Presence merge(Presence a, Presence b) {
        if (a == Presence.UNKNOWN || b == Presence.UNKNOWN) return Presence.UNKNOWN;
        return a == Presence.PRESENT || b == Presence.PRESENT ? Presence.PRESENT : Presence.ABSENT;
    }

    private static final class Observation {
        private final int count, disabled;
        private final Presence defaultValue;
        private final PluginSettings pluginSettings;
        private final AssetsFieldSettings assets;
        private Observation(int count, int disabled, Presence defaultValue, PluginSettings pluginSettings,
                            AssetsFieldSettings assets) {
            this.count = count; this.disabled = disabled; this.defaultValue = defaultValue;
            this.pluginSettings = pluginSettings; this.assets = assets;
        }
    }
}
