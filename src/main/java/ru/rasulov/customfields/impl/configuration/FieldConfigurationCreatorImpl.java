package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.EditableFieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayout;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutManager;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutScheme;
import com.atlassian.jira.issue.fields.layout.field.FieldLayoutSchemeEntity;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.ChangeLinks;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.customfields.api.FieldConfigurationCreator;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationRequest;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationResponse;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationSchemeRequest;
import ru.rasulov.customfields.api.dto.configuration.CreateFieldConfigurationSchemeResponse;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ConfigurationRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.SchemeRef;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Creates field configurations (exact copies of an existing one) and field configuration schemes (rows pointing at
 * existing configurations). Nothing created here is assigned to a project, so no issue or other project is affected.
 */
@ExportAsService(FieldConfigurationCreator.class)
@Named
public class FieldConfigurationCreatorImpl implements FieldConfigurationCreator {
    static final String CREATE_CONFIGURATION = "create_field_configuration";
    static final String CREATE_SCHEME = "create_field_configuration_scheme";
    static final int MAX_NAME = 255;
    static final int MAX_DESCRIPTION = 1000;
    private static final Logger LOG = LoggerFactory.getLogger(FieldConfigurationCreatorImpl.class);

    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final FieldConfigurationCatalog catalog;
    private final SchemeRows schemeRows;
    private final FieldLayoutManager manager;
    private final FieldLayoutCopyFactory copies;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final ChangeLinks links;

    @Inject
    public FieldConfigurationCreatorImpl(AdministratorGuard guard, NamedLock lock, FieldConfigurationCatalog catalog,
                                         SchemeRows schemeRows, @ComponentImport FieldLayoutManager manager,
                                         FieldLayoutCopyFactory copies, PreviewPlanExecutor executor, ActionPlanCodec codec,
                                         ChangeJournal journal, ChangeLinks links) {
        this.guard = guard;
        this.lock = lock;
        this.catalog = catalog;
        this.schemeRows = schemeRows;
        this.manager = manager;
        this.copies = copies;
        this.executor = executor;
        this.codec = codec;
        this.journal = journal;
        this.links = links;
    }

    // --- create_field_configuration ------------------------------------------------------------------------------

    @Override
    public CreateFieldConfigurationResponse createConfiguration(CreateFieldConfigurationRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request == null ? null : request.isDryRun(), request == null ? null : request.getPlanId(),
                request != null && (request.getName() != null || request.getDescription() != null
                        || request.getCopyFrom() != null));
        if (request.isDryRun()) {
            ConfigurationPlan current = prepare(request);
            if (current.preview.getStatus() != CreateFieldConfigurationResponse.Status.WOULD_CREATE) return current.preview;
            return executor.issue(CREATE_CONFIGURATION, user.getKey(), request, current.fingerprint(),
                    current.preview::withPlan);
        }
        return journal.execute(CREATE_CONFIGURATION, user.getKey(), () -> lock.run(FieldConfigurationLocks.EDIT,
                "field configurations", () -> applyConfiguration(request.getPlanId(), user)));
    }

    private CreateFieldConfigurationResponse applyConfiguration(String planId, ApplicationUser user) {
        return executor.apply(CREATE_CONFIGURATION, user.getKey(), planId, CreateFieldConfigurationRequest.class,
                CreateFieldConfigurationResponse::stale, this::prepare, current -> true,
                ConfigurationPlan::fingerprint, this::writeConfiguration);
    }

    private CreateFieldConfigurationResponse writeConfiguration(ConfigurationPlan current) {
        journal.checkpoint();
        EditableFieldLayout draft = copies.copyOf(current.source, current.name);
        if (current.description != null) draft.setDescription(current.description);
        EditableFieldLayout stored = manager.storeAndReturnEditableFieldLayout(draft);
        if (stored == null || stored.getId() == null) throw new IllegalStateException("The created field configuration has no id.");
        manager.refresh();
        EditableFieldLayout created = manager.getEditableFieldLayout(stored.getId());
        if (created == null || !current.name.equals(created.getName())) {
            throw new IllegalStateException("Jira did not persist the requested field configuration.");
        }
        journal.checkpoint();
        ConfigurationRef createdRef = FieldConfigurationCatalog.ref(created);
        return CreateFieldConfigurationResponse.created(current.name, created.getDescription(),
                FieldConfigurationCatalog.ref(current.source), createdRef, links.report()
                        .created(ChangeKind.FIELD_CONFIGURATION, String.valueOf(createdRef.getId()), current.name,
                                String.valueOf(createdRef.getId())).build());
    }

    private ConfigurationPlan prepare(CreateFieldConfigurationRequest request) {
        try {
            manager.refresh();
            String name = name(request.getName(), catalog.configurationNames(), "field configuration");
            String description = description(request.getDescription());
            String copyFrom = request.getCopyFrom() == null ? FieldConfigurationCatalog.DEFAULT : request.getCopyFrom();
            FieldLayout source = catalog.configuration(copyFrom);
            return new ConfigurationPlan(name, description, source, CreateFieldConfigurationResponse.planned(name,
                    description == null ? source.getDescription() : description, FieldConfigurationCatalog.ref(source)));
        } catch (FieldConfigurationCatalog.Failure e) {
            return new ConfigurationPlan(null, null, null, CreateFieldConfigurationResponse.failed(true, e.getCode(),
                    e.getMessage(), e.suggestions()));
        } catch (RuntimeException e) {
            LOG.error("Failed to plan a field configuration", e);
            return new ConfigurationPlan(null, null, null, CreateFieldConfigurationResponse.failed(true,
                    ErrorCode.JIRA_ERROR, "Jira failed while reading field configurations; nothing was changed. "
                            + "Details are in the Jira log.", null));
        }
    }

    /** Request-local: the fingerprint and writer use the same resolved source. */
    private final class ConfigurationPlan {
        private final String name;
        private final String description;
        private final FieldLayout source;
        private final CreateFieldConfigurationResponse preview;

        private ConfigurationPlan(String name, String description, FieldLayout source,
                                  CreateFieldConfigurationResponse preview) {
            this.name = name; this.description = description; this.source = source; this.preview = preview;
        }

        private String fingerprint() {
            return codec.fingerprint(Arrays.asList(preview, catalog.snapshots(Collections.singletonList(source))));
        }
    }

    // --- create_field_configuration_scheme -----------------------------------------------------------------------

    @Override
    public CreateFieldConfigurationSchemeResponse createScheme(CreateFieldConfigurationSchemeRequest request) {
        ApplicationUser user = guard.requireAdministrator();
        requireMode(request == null ? null : request.isDryRun(), request == null ? null : request.getPlanId(),
                request != null && (request.getName() != null || request.getDescription() != null
                        || !request.getMappings().isEmpty()));
        if (request.isDryRun()) {
            SchemePlan current = prepare(request);
            if (current.preview.getStatus() != CreateFieldConfigurationSchemeResponse.Status.WOULD_CREATE) {
                return current.preview;
            }
            return executor.issue(CREATE_SCHEME, user.getKey(), request, codec.fingerprint(current.preview),
                    current.preview::withPlan);
        }
        return journal.execute(CREATE_SCHEME, user.getKey(), () -> lock.run(FieldConfigurationLocks.EDIT,
                "field configurations", () -> applyScheme(request.getPlanId(), user)));
    }

    private CreateFieldConfigurationSchemeResponse applyScheme(String planId, ApplicationUser user) {
        return executor.apply(CREATE_SCHEME, user.getKey(), planId, CreateFieldConfigurationSchemeRequest.class,
                CreateFieldConfigurationSchemeResponse::stale, this::prepare, current -> true,
                current -> codec.fingerprint(current.preview), this::writeScheme);
    }

    private CreateFieldConfigurationSchemeResponse writeScheme(SchemePlan current) {
        journal.checkpoint();
        FieldLayoutScheme created = manager.createFieldLayoutScheme(current.name, current.description);
        if (created == null || created.getId() == null) throw new IllegalStateException("The created scheme has no id.");
        Long id = created.getId();
        journal.checkpoint();
        for (String issueTypeId : current.rows.rows.keySet()) {
            manager.refresh();
            FieldLayoutScheme scheme = manager.getMutableFieldLayoutScheme(id);
            Long target = current.rows.layoutId(issueTypeId);
            FieldLayoutSchemeEntity existing = scheme.getEntity(issueTypeId);
            if (existing == null) {
                manager.createFieldLayoutSchemeEntity(scheme, issueTypeId, target);
            } else {
                existing.setFieldLayoutId(target);
                manager.updateFieldLayoutSchemeEntity(existing);
            }
            journal.checkpoint();
        }
        manager.refresh();
        FieldLayoutScheme persisted = manager.getMutableFieldLayoutScheme(id);
        Map<String, Long> actual = catalog.rows(persisted);
        for (String issueTypeId : current.rows.rows.keySet()) {
            if (!actual.containsKey(issueTypeId)
                    || !java.util.Objects.equals(actual.get(issueTypeId), current.rows.layoutId(issueTypeId))) {
                throw new IllegalStateException("Jira did not persist the requested scheme rows.");
            }
        }
        SchemeRef createdRef = FieldConfigurationCatalog.ref(persisted);
        ChangeLinks.Report report = links.report();
        Set<Long> seen = new HashSet<Long>();
        for (FieldConfigurationViews.MappingView row : current.rows.views()) {
            ConfigurationRef used = row.getFieldConfiguration();
            if (used != null && used.getId() != null && seen.add(used.getId())) {
                report.reused(ChangeKind.FIELD_CONFIGURATION, String.valueOf(used.getId()), used.getName(),
                        String.valueOf(used.getId()));
            }
        }
        report.created(ChangeKind.FIELD_CONFIGURATION_SCHEME, String.valueOf(createdRef.getId()), current.name,
                String.valueOf(createdRef.getId()));
        return CreateFieldConfigurationSchemeResponse.created(createdRef, current.rows.views(), report.build());
    }

    private SchemePlan prepare(CreateFieldConfigurationSchemeRequest request) {
        try {
            manager.refresh();
            String name = name(request.getName(), catalog.schemeNames(), "field configuration scheme");
            String description = description(request.getDescription());
            SchemeRows.Resolved rows = schemeRows.resolve(request.getMappings(), false);
            if (!rows.rows.containsKey(null)) {
                throw new FieldConfigurationCatalog.Failure(ErrorCode.VALIDATION_ERROR, "'mappings' must contain the "
                        + "DEFAULT row: the field configuration of every issue type without its own row.");
            }
            return new SchemePlan(name, description, rows, CreateFieldConfigurationSchemeResponse.planned(
                    new SchemeRef(null, name, description, false), rows.views()));
        } catch (FieldConfigurationCatalog.Failure e) {
            return new SchemePlan(null, null, null, CreateFieldConfigurationSchemeResponse.failed(true, e.getCode(),
                    e.getMessage(), e.suggestions()));
        } catch (RuntimeException e) {
            LOG.error("Failed to plan a field configuration scheme", e);
            return new SchemePlan(null, null, null, CreateFieldConfigurationSchemeResponse.failed(true,
                    ErrorCode.JIRA_ERROR, "Jira failed while reading field configuration schemes; nothing was changed. "
                            + "Details are in the Jira log.", null));
        }
    }

    /** Request-local resolved scheme plan. */
    private static final class SchemePlan {
        private final String name;
        private final String description;
        private final SchemeRows.Resolved rows;
        private final CreateFieldConfigurationSchemeResponse preview;

        private SchemePlan(String name, String description, SchemeRows.Resolved rows,
                           CreateFieldConfigurationSchemeResponse preview) {
            this.name = name; this.description = description; this.rows = rows; this.preview = preview;
        }
    }

    // --- shared ----------------------------------------------------------------------------------------------------

    /** A unique (case-insensitive) trimmed name; a taken one fails with a free name as the suggestion. */
    static String name(String requested, List<String> existing, String what) {
        String name = requested == null ? "" : requested.trim();
        if (name.isEmpty()) throw new FieldConfigurationCatalog.Failure(ErrorCode.VALIDATION_ERROR,
                "'name' of the new " + what + " is required.");
        if (name.length() > MAX_NAME) throw new FieldConfigurationCatalog.Failure(ErrorCode.VALIDATION_ERROR,
                "'name' is longer than " + MAX_NAME + " characters.");
        Set<String> taken = new HashSet<String>();
        for (String other : existing) if (other != null) taken.add(other.trim().toLowerCase(Locale.ROOT));
        if (taken.contains(name.toLowerCase(Locale.ROOT))) {
            throw new FieldConfigurationCatalog.Failure(ErrorCode.NAME_TAKEN, "A " + what + " named '" + name
                    + "' already exists. Ask the user for another name; the suggestion is free.",
                    Collections.singletonList(FieldConfigurationClonePlanner.freeName(name, taken)));
        }
        return name;
    }

    static String description(String requested) {
        if (requested == null) return null;
        String description = requested.trim();
        if (description.length() > MAX_DESCRIPTION) throw new FieldConfigurationCatalog.Failure(
                ErrorCode.VALIDATION_ERROR, "'description' is longer than " + MAX_DESCRIPTION + " characters.");
        return description;
    }

    static void requireMode(Boolean dryRun, String planId, boolean hasPreviewArguments) {
        if (dryRun == null) throw new InvalidRequestException("'dryRun' must be explicitly true or false.");
        if (dryRun && planId != null) throw new InvalidRequestException("Preview takes the request, not planId.");
        if (!dryRun && (hasPreviewArguments || planId == null || planId.trim().isEmpty())) {
            throw new InvalidRequestException("Apply takes dryRun=false and planId only.");
        }
    }
}
