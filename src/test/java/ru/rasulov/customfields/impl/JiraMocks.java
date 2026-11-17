package ru.rasulov.customfields.impl;

import com.atlassian.beehive.ClusterLock;
import com.atlassian.beehive.ClusterLockService;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.CustomFieldSearcher;
import com.atlassian.jira.issue.customfields.CustomFieldType;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.fields.config.manager.FieldConfigSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.plugin.customfield.CustomFieldSearcherModuleDescriptor;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.customfields.impl.context.CreatedFieldReadback;
import ru.rasulov.customfields.impl.context.ContextSchemeFactory;
import ru.rasulov.customfields.impl.context.ContextSummarizer;
import ru.rasulov.customfields.impl.context.ContextValidator;
import ru.rasulov.customfields.impl.context.ContextWriter;
import ru.rasulov.common.impl.lookup.IssueTypeCatalog;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectCatalog;
import ru.rasulov.common.impl.lookup.LookupServiceImpl;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.impl.validation.OptionsValidator;
import ru.rasulov.customfields.impl.validation.TypeSuggester;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Mock factories for Jira objects and the wiring of the service, shared by the unit tests. */
public final class JiraMocks {

    public static final String SYSTEM = "com.atlassian.jira.plugin.system.customfieldtypes:";
    public static final String TEXTFIELD = SYSTEM + "textfield";
    public static final String TEXTAREA = SYSTEM + "textarea";
    public static final String TEXT_SEARCHER = SYSTEM + "textsearcher";

    /** The context a test uses when it does not care: all projects, all issue types. */
    public static final List<ContextDefinition> GLOBAL = Collections.singletonList(
            new ContextDefinition(null, true, null, true, null, null));
    private static final ThreadLocal<java.util.Map<String, CustomField>> CREATED =
            ThreadLocal.withInitial(java.util.HashMap::new);
    public static void closeJournals() {
        try { FieldJournalTestSupport.close(); }
        finally { CREATED.remove(); }
    }

    private JiraMocks() {
    }

    public static CreateCustomFieldsResponse previewAndApply(CustomFieldCreatorImpl creator,
                                                             CreateCustomFieldsRequest request) {
        CreateCustomFieldsResponse preview = creator.createAll(
                new CreateCustomFieldsRequest(true, request.getFields()));
        if (preview.getPlanId() == null) return preview;
        return creator.createAll(new CreateCustomFieldsRequest(false, null, preview.getPlanId()));
    }

    @SuppressWarnings("rawtypes")
    public static <T extends CustomFieldType> T type(Class<T> kind, String key, String name) {
        T type = mock(kind);
        when(type.getKey()).thenReturn(key);
        when(type.getName()).thenReturn(name);
        return type;
    }

    public static CustomFieldSearcher searcher(String key, String... validTypeKeys) {
        CustomFieldSearcher searcher = mock(CustomFieldSearcher.class);
        CustomFieldSearcherModuleDescriptor descriptor = mock(CustomFieldSearcherModuleDescriptor.class);
        when(searcher.getDescriptor()).thenReturn(descriptor);
        when(descriptor.getCompleteKey()).thenReturn(key);
        when(descriptor.getValidCustomFieldKeys()).thenReturn(new HashSet<Object>(Arrays.<Object>asList(validTypeKeys)));
        return searcher;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static CustomField field(String id, String name, CustomFieldType<?, ?> type) {
        CustomField field = mock(CustomField.class);
        when(field.getId()).thenReturn(id);
        when(field.getName()).thenReturn(name);
        when(field.getUntranslatedName()).thenReturn(name);
        when(field.getCustomFieldType()).thenReturn((CustomFieldType) type);
        return field;
    }

    /**
     * A field as Jira returns it right after {@code createCustomField} with one context: one configuration scheme
     * that has one configuration.
     */
    public static CustomField createdField(String id) {
        CustomField field = mock(CustomField.class);
        when(field.getId()).thenReturn(id);
        when(field.getName()).thenReturn("Created field");
        when(field.getUntranslatedName()).thenReturn("Created field");
        CustomFieldType<?, ?> fieldType = type(CustomFieldType.class, TEXTFIELD, "Text Field");
        when(field.getCustomFieldType()).thenReturn((CustomFieldType) fieldType);
        CREATED.get().put(id, field);
        withScheme(field);
        return field;
    }

    /** Describes the one context an existing field has, as Jira reports it. */
    public static void withContext(CustomField field, String name, boolean allProjects, List<Project> projects,
                                   boolean allIssueTypes, List<IssueType> issueTypes) {
        FieldConfigScheme scheme = mock(FieldConfigScheme.class);
        when(scheme.getName()).thenReturn(name);
        when(scheme.isAllProjects()).thenReturn(allProjects);
        when(scheme.getAssociatedProjectObjects()).thenReturn(projects);
        when(scheme.isAllIssueTypes()).thenReturn(allIssueTypes);
        when(scheme.getAssociatedIssueTypes()).thenReturn(issueTypes);
        when(field.getConfigurationSchemes()).thenReturn(Collections.singletonList(scheme));
    }

    /** Gives the field one context with one configuration and returns that configuration. */
    public static FieldConfig withScheme(CustomField field) {
        FieldConfig config = mock(FieldConfig.class);
        FieldConfigScheme scheme = mock(FieldConfigScheme.class);
        String fieldId = field.getId();
        long numeric = Long.parseLong(fieldId.substring("customfield_".length()));
        when(config.getId()).thenReturn(numeric + 100000);
        when(config.getCustomField()).thenReturn(field);
        when(config.getFieldId()).thenReturn(fieldId);
        when(scheme.getId()).thenReturn(numeric + 200000);
        when(scheme.getName()).thenReturn("Default context");
        when(scheme.getConfigs()).thenReturn(Collections.singletonMap(null, config));
        when(scheme.getOneAndOnlyConfig()).thenReturn(config);
        when(field.getConfigurationSchemes()).thenReturn(Collections.singletonList(scheme));
        return config;
    }

    public static Project project(long id, String key, String name, IssueType... issueTypes) {
        Project project = mock(Project.class);
        when(project.getId()).thenReturn(id);
        when(project.getKey()).thenReturn(key);
        when(project.getName()).thenReturn(name);
        when(project.getIssueTypes()).thenReturn(Arrays.asList(issueTypes));
        return project;
    }

    public static IssueType issueType(String id, String name, boolean subtask) {
        IssueType issueType = mock(IssueType.class);
        when(issueType.getId()).thenReturn(id);
        when(issueType.getName()).thenReturn(name);
        when(issueType.isSubTask()).thenReturn(subtask);
        return issueType;
    }

    /** A cluster lock service whose lock is always granted; the lock is returned by {@code getLockForName}. */
    public static ClusterLockService lockService(ClusterLock lock) {
        ClusterLockService service = mock(ClusterLockService.class);
        when(service.getLockForName(anyString())).thenReturn(lock);
        try {
            when(lock.tryLock(eq(NamedLock.WAIT_SECONDS), eq(TimeUnit.SECONDS))).thenReturn(true);
            when(lock.tryLock(anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        } catch (InterruptedException impossible) {
            throw new AssertionError(impossible);
        }
        return service;
    }

    public static CustomFieldRequestValidator validator(CustomFieldManager manager) {
        return new Wiring(manager).validator;
    }

    /**
     * The service wired exactly like Spring does, with mocked Jira. Projects and issue types are empty and the
     * scheme manager passes contexts through unchanged.
     */
    public static CustomFieldCreatorImpl creator(CustomFieldManager manager, OptionsManager optionsManager,
                                                 JiraAuthenticationContext authenticationContext,
                                                 GlobalPermissionManager permissions, ClusterLockService lockService) {
        return new Wiring(manager, optionsManager, authenticationContext, permissions, lockService).creator;
    }

    /** Everything the service needs, as mocks, with the current user an administrator. */
    public static final class Wiring {
        public final CustomFieldManager manager;
        public final OptionsManager optionsManager;
        public final ProjectManager projectManager = mock(ProjectManager.class);
        public final IssueTypeManager issueTypeManager = mock(IssueTypeManager.class);
        public final FieldConfigSchemeManager schemeManager = mock(FieldConfigSchemeManager.class);
        public final ContextSchemeFactory schemeFactory = mock(ContextSchemeFactory.class);
        public final ClusterLock lock = mock(ClusterLock.class);
        public final ClusterLockService lockService;
        public final JiraAuthenticationContext authenticationContext;
        public final GlobalPermissionManager permissions;
        public final ApplicationUser admin = mock(ApplicationUser.class);
        public final CustomFieldRequestValidator validator;
        public final CustomFieldCreatorImpl creator;
        /** Builds a creator around any readback, e.g. {@link #realReadback()} to prove the integration. */
        public final java.util.function.Function<CreatedFieldReadback, CustomFieldCreatorImpl> creators;
        /** Reports a match by default; a test overrides {@code read} to simulate Jira storing something else. */
        public final CreatedFieldReadback readback = mock(CreatedFieldReadback.class);
        public final LookupServiceImpl lookup;

        /** The production readback over this wiring's Jira managers; only the Jira API boundary is mocked. */
        public CreatedFieldReadback realReadback() {
            return new CreatedFieldReadback(manager, optionsManager, new ContextSummarizer());
        }

        /** A fresh wiring where the current user is an administrator and there are no fields yet. */
        public Wiring() {
            this(mock(CustomFieldManager.class), mock(OptionsManager.class), mock(JiraAuthenticationContext.class),
                    mock(GlobalPermissionManager.class), null, true);
        }

        /** Wiring around mocks the test has set up itself: no user, permission or field stubbing is added. */
        Wiring(CustomFieldManager manager) {
            this(manager, mock(OptionsManager.class), mock(JiraAuthenticationContext.class),
                    mock(GlobalPermissionManager.class), null, false);
        }

        Wiring(CustomFieldManager manager, OptionsManager optionsManager,
               JiraAuthenticationContext authenticationContext, GlobalPermissionManager permissions,
               ClusterLockService lockService) {
            this(manager, optionsManager, authenticationContext, permissions, lockService, false);
        }

        private Wiring(CustomFieldManager manager, OptionsManager optionsManager,
                       JiraAuthenticationContext authenticationContext, GlobalPermissionManager permissions,
                       ClusterLockService lockService, boolean administratorAndNoFields) {
            this.manager = manager;
            this.optionsManager = optionsManager;
            this.authenticationContext = authenticationContext;
            this.permissions = permissions;
            this.lockService = lockService != null ? lockService : lockService(lock);

            if (administratorAndNoFields) {
                when(admin.getUsername()).thenReturn("admin");
                when(admin.getKey()).thenReturn("admin");
                when(authenticationContext.getLoggedInUser()).thenReturn(admin);
                when(permissions.hasPermission(GlobalPermissionKey.ADMINISTER, admin)).thenReturn(true);
                when(manager.getCustomFieldObjects()).thenReturn(Collections.<CustomField>emptyList());
            }
            // renaming and storing a context changes nothing the tests look at
            when(schemeFactory.renamed(any(FieldConfigScheme.class), anyString(), nullable(String.class))).thenAnswer(returnsFirstArg());
            when(schemeManager.updateFieldConfigScheme(any(FieldConfigScheme.class))).thenAnswer(returnsFirstArg());

            TypeSuggester suggester = new TypeSuggester(manager);
            ContextValidator contextValidator = new ContextValidator(new ProjectResolver(projectManager),
                    new IssueTypeResolver(issueTypeManager));
            this.validator = new CustomFieldRequestValidator(manager, suggester, new OptionsValidator(suggester),
                    contextValidator);
            AdministratorGuard guard = new AdministratorGuard(authenticationContext, permissions);
            ApplicationUser actor = authenticationContext.getLoggedInUser();
            if (actor != null && actor.getKey() == null) when(actor.getKey()).thenReturn("admin");
            String actorKey = actor == null ? "admin" : actor.getKey();
            when(manager.getCustomFieldObject(anyString())).thenAnswer(call -> CREATED.get().get(call.getArgument(0)));
            ru.rasulov.customfields.impl.undo.CustomFieldUndoHandler undo =
                    FieldJournalTestSupport.fields(manager, optionsManager, schemeManager, schemeFactory);
            ru.rasulov.common.impl.undo.ChangeJournal journal = FieldJournalTestSupport.journal(actorKey, undo);
            ru.rasulov.customfields.impl.undo.FieldMutationJournal mutations =
                    new ru.rasulov.customfields.impl.undo.FieldMutationJournal(journal, undo);
            when(readback.read(anyString(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList())).thenReturn(
                    new CreatedFieldReadback.Result(Collections.<ru.rasulov.common.api.dto.field.context.ContextSummary>emptyList(),
                            Collections.<String>emptyList()));
            this.creators = reader -> new CustomFieldCreatorImpl(manager,
                    guard,
                    new NamedLock(this.lockService),
                    validator,
                    new ContextWriter(projectManager, schemeManager, schemeFactory, mutations),
                    new ContextSummarizer(),
                    new CustomFieldOptionsWriter(optionsManager, mutations),
                    new CustomFieldTypeCatalog(manager),
                    new ru.rasulov.common.impl.PreviewPlanExecutor(ru.rasulov.common.impl.InMemoryActionPlans.create(),
                            new ru.rasulov.common.impl.ActionPlanCodec()),
                    new ru.rasulov.common.impl.ActionPlanCodec(), journal, undo, new ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration(org.mockito.Mockito.mock(com.atlassian.plugin.PluginAccessor.class)),
                    new ru.rasulov.customfields.impl.filefield.FileFieldConfiguration(org.mockito.Mockito.mock(com.atlassian.jira.issue.customfields.manager.GenericConfigManager.class)),
                    new ru.rasulov.customfields.impl.grid.GridFieldConfiguration(org.mockito.Mockito.mock(com.atlassian.plugin.PluginAccessor.class)),
                    ru.rasulov.common.impl.TestLinks.links(), reader);
            this.creator = creators.apply(this.readback);
            this.lookup = new LookupServiceImpl(guard, new ProjectCatalog(projectManager),
                    new IssueTypeCatalog(issueTypeManager));
        }
    }
}
