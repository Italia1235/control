package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.issue.fields.layout.field.FieldConfigurationScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutItem;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.api.FieldConfigurationReader;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ConfigurationRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.FieldRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.MappingView;
import ru.rasulov.customfields.api.dto.configuration.ProjectFieldConfigurationsResponse;
import ru.rasulov.customfields.api.dto.configuration.ProjectFieldConfigurationsResponse.ConfigurationState;
import ru.rasulov.customfields.api.dto.configuration.ProjectFieldConfigurationsResponse.ConfigurationUse;
import ru.rasulov.customfields.api.dto.configuration.ProjectFieldConfigurationsResponse.FieldState;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ExportAsService(FieldConfigurationReader.class)
@Named
public class FieldConfigurationReaderImpl implements FieldConfigurationReader {
    static final int MAX_FIELDS = 50;
    private static final Logger LOG = LoggerFactory.getLogger(FieldConfigurationReaderImpl.class);

    private final AdministratorGuard guard;
    private final ProjectResolver projects;
    private final ConfigurableFieldResolver fields;
    private final FieldConfigurationCatalog catalog;
    private final FieldLayoutManager manager;
    private final FieldConfigurationSearcher searcher;

    @Inject
    public FieldConfigurationReaderImpl(AdministratorGuard guard, ProjectResolver projects,
                                        ConfigurableFieldResolver fields, FieldConfigurationCatalog catalog,
                                        @ComponentImport FieldLayoutManager manager,
                                        FieldConfigurationSearcher searcher) {
        this.guard = guard;
        this.projects = projects;
        this.fields = fields;
        this.catalog = catalog;
        this.manager = manager;
        this.searcher = searcher;
    }

    @Override
    public FieldConfigurationSearchResponse search(FieldConfigurationSearchResponse.Kind kind, String id, String name,
                                                   int offset, int limit, int usageOffset, int usageLimit) {
        return searcher.search(kind, id, name, offset, limit, usageOffset, usageLimit);
    }

    @Override
    public ProjectFieldConfigurationsResponse read(String projectText, List<String> fieldTexts) {
        guard.requireAdministrator();
        ProjectRef ref = null;
        try {
            Project project = projects.resolve(projectText);
            ref = new ProjectRef(project.getKey(), project.getName());
            List<OrderableField> requested = resolveFields(fieldTexts);
            manager.refresh();
            return read(project, ref, requested);
        } catch (LookupFailure e) {
            ErrorCode code = e.getKind() == LookupFailure.Kind.UNKNOWN_PROJECT ? ErrorCode.UNKNOWN_PROJECT
                    : ErrorCode.VALIDATION_ERROR;
            return ProjectFieldConfigurationsResponse.failed(ref, code, e.getMessage(), e.getSuggestions(), null);
        } catch (ConfigurableFieldResolver.Failure e) {
            List<FieldRef> candidates = new ArrayList<FieldRef>();
            for (OrderableField candidate : e.getCandidates()) candidates.add(FieldConfigurationCatalog.field(candidate));
            return ProjectFieldConfigurationsResponse.failed(ref, ErrorCode.valueOf(e.getCode().name()), e.getMessage(),
                    null, candidates);
        } catch (FieldConfigurationCatalog.Failure e) {
            return ProjectFieldConfigurationsResponse.failed(ref, e.getCode(), e.getMessage(), null, null);
        } catch (RuntimeException e) {
            LOG.error("Failed to read field configurations", e);
            return ProjectFieldConfigurationsResponse.failed(ref, ErrorCode.JIRA_ERROR,
                    "Jira failed while reading the field configurations. Details are in the Jira log.", null, null);
        }
    }

    private ProjectFieldConfigurationsResponse read(Project project, ProjectRef ref, List<OrderableField> requested) {
        FieldConfigurationScheme assigned = manager.getFieldConfigurationScheme(project);
        FieldLayoutScheme scheme = assigned == null ? null : manager.getMutableFieldLayoutScheme(assigned.getId());
        Map<String, FieldLayoutSchemeEntity> rows = new LinkedHashMap<String, FieldLayoutSchemeEntity>();
        if (scheme != null) for (FieldLayoutSchemeEntity entity : catalog.entities(scheme)) rows.put(entity.getIssueTypeId(), entity);

        List<IssueType> types = new ArrayList<IssueType>(project.getIssueTypes());
        types.sort(Comparator.comparing(IssueType::getName).thenComparing(IssueType::getId));
        Set<String> projectTypeIds = new HashSet<String>();
        Map<String, FieldLayout> used = new LinkedHashMap<String, FieldLayout>();
        List<MappingView> mappings = new ArrayList<MappingView>();
        for (IssueType type : types) {
            projectTypeIds.add(type.getId());
            FieldLayout layout = manager.getFieldLayout(project, type.getId());
            if (layout == null) throw new IllegalStateException("No effective field configuration for " + type.getId());
            used.putIfAbsent(FieldConfigurationCatalog.key(layout), layout);
            mappings.add(new MappingView(type.getId(), type.getName(), rows.containsKey(type.getId()),
                    FieldConfigurationCatalog.ref(layout)));
        }

        ConfigurationRef defaultMapping = null;
        List<MappingView> otherEntities = new ArrayList<MappingView>();
        for (FieldLayoutSchemeEntity entity : rows.values()) {
            FieldLayout layout = catalog.configuration(entity.getFieldLayoutId());
            ConfigurationRef target = layout == null ? new ConfigurationRef(entity.getFieldLayoutId(), null, false)
                    : FieldConfigurationCatalog.ref(layout);
            if (entity.getIssueTypeId() == null) {
                defaultMapping = target;
            } else if (!projectTypeIds.contains(entity.getIssueTypeId())) {
                IssueType type = entity.getIssueTypeObject();
                otherEntities.add(new MappingView(entity.getIssueTypeId(), type == null ? null : type.getName(), true,
                        target));
            }
        }
        if (scheme == null) defaultMapping = FieldConfigurationCatalog.ref(manager.getFieldLayout());

        List<ConfigurationUse> configurations = new ArrayList<ConfigurationUse>();
        for (FieldLayout layout : used.values()) {
            configurations.add(new ConfigurationUse(FieldConfigurationCatalog.ref(layout),
                    catalog.otherProjects(layout, project), catalog.schemesUsing(layout)));
        }

        List<FieldState> states = new ArrayList<FieldState>();
        for (OrderableField field : requested) {
            List<ConfigurationState> perLayout = new ArrayList<ConfigurationState>();
            for (FieldLayout layout : used.values()) {
                FieldLayoutItem item = layout.getFieldLayoutItem(field.getId());
                perLayout.add(new ConfigurationState(FieldConfigurationCatalog.ref(layout), item != null,
                        item != null && item.isRequired(), item != null && item.isHidden()));
            }
            states.add(new FieldState(FieldConfigurationCatalog.field(field), fields.isMandatory(field), perLayout));
        }
        return ProjectFieldConfigurationsResponse.ok(ref, FieldConfigurationCatalog.ref(assigned),
                assigned == null ? null : catalog.otherProjects(assigned, project), defaultMapping, mappings,
                otherEntities, configurations, states);
    }

    private List<OrderableField> resolveFields(List<String> texts) {
        List<OrderableField> result = new ArrayList<OrderableField>();
        if (texts == null) return result;
        if (texts.size() > MAX_FIELDS) {
            throw new FieldConfigurationCatalog.Failure(ErrorCode.VALIDATION_ERROR,
                    "'fields' has " + texts.size() + " elements; the maximum is " + MAX_FIELDS + ".");
        }
        Set<String> seen = new HashSet<String>();
        for (String text : texts) {
            OrderableField field = fields.resolve(text);
            if (seen.add(field.getId())) result.add(field);
        }
        return result;
    }
}
