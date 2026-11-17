package ru.rasulov.projects.impl;

import com.atlassian.jira.bc.project.ProjectCreationData;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.impl.ChangeLinks;
import com.atlassian.jira.bc.project.ProjectService;
import com.atlassian.jira.bc.user.search.UserSearchParams;
import com.atlassian.jira.bc.user.search.UserSearchService;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.project.template.ProjectTemplateKey;
import com.atlassian.jira.project.template.ProjectTemplate;
import com.atlassian.jira.project.template.ProjectTemplateManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.issuetype.IssueTypeSets;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.projects.api.ProjectCreator;
import ru.rasulov.projects.api.dto.CreateProjectRequest;
import ru.rasulov.projects.api.dto.CreateProjectResponse;
import ru.rasulov.projects.api.dto.CreateProjectResponse.ErrorCode;
import ru.rasulov.projects.api.dto.ProjectLeadView;
import ru.rasulov.projects.api.dto.ProjectTemplatesResponse;

import javax.inject.Inject;
import javax.inject.Named;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Optional;

/** Jira ProjectService creates the project and invokes the hook of the selected supported template. */
@ExportAsService(ProjectCreator.class)
@Named
public class ProjectCreatorImpl implements ProjectCreator {
    static final String LOCK_NAME = "ru.rasulov.control.create-project";


    private static final Logger log = LoggerFactory.getLogger(ProjectCreatorImpl.class);
    private final ProjectService projectService;
    private final ProjectManager projectManager;
    private final ProjectTemplateManager templateManager;
    private final UserManager userManager;
    private final UserSearchService userSearchService;
    private final AdministratorGuard administratorGuard;
    private final NamedLock namedLock;
    private final ActionPlanStore planStore;
    private final ActionPlanCodec planCodec;
    private final ChangeJournal journal;
    private final ProjectUndoSupport undoSupport;
    private final ChangeLinks links;
    private final IssueTypeSets typeSets;

    @Inject
    public ProjectCreatorImpl(@ComponentImport ProjectService projectService,
                              @ComponentImport ProjectManager projectManager,
                              @ComponentImport ProjectTemplateManager templateManager,
                              @ComponentImport UserManager userManager,
                              @ComponentImport UserSearchService userSearchService,
                              AdministratorGuard administratorGuard, NamedLock namedLock,
                              ActionPlanStore planStore, ActionPlanCodec planCodec, ChangeJournal journal,
                              ProjectUndoSupport undoSupport, ChangeLinks links, IssueTypeSets typeSets) {
        this.projectService = projectService;
        this.projectManager = projectManager;
        this.templateManager = templateManager;
        this.userManager = userManager;
        this.userSearchService = userSearchService;
        this.administratorGuard = administratorGuard;
        this.namedLock = namedLock;
        this.planStore = planStore;
        this.planCodec = planCodec;
        this.journal = journal; this.undoSupport = undoSupport;
        this.links = links;
        this.typeSets = typeSets;
    }

    @Override
    public CreateProjectResponse create(CreateProjectRequest request) {
        ApplicationUser administrator = administratorGuard.requireAdministrator();
        if (request == null || request.isDryRun() == null) {
            throw new InvalidRequestException("Send a project request with dryRun explicitly set to true or false.");
        }
        if (request.isDryRun()) {
            if (request.getPlanId() != null) {
                throw new InvalidRequestException("A preview takes action, templateKey, name, key, lead and the "
                        + "issue-type set, not planId.");
            }
            requireCreateAction(request);
            return preview(request, administrator);
        }
        if (request.getAction() != null || request.getTemplateKey() != null
                || request.getName() != null || request.getKey() != null || request.getLead() != null
                || request.hasExplicitTypeSet() || request.getDefaultIssueType() != null
                || trim(request.getPlanId()) == null || trim(request.getPlanId()).isEmpty()) {
            throw new InvalidRequestException("An apply request takes dryRun=false and the planId from preview only.");
        }
        // Lock order is fixed everywhere (global types, set of a project, project): the undo handler uses the same order.
        return journal.execute("create_project", administrator.getKey(), () ->
                typeSets.underLocks(() ->
                        namedLock.run(LOCK_NAME, "projects", () -> apply(trim(request.getPlanId()), administrator))));
    }

    @Override
    public ProjectTemplatesResponse listTemplates() {
        administratorGuard.requireAdministrator();
        List<ProjectTemplate> found;
        try {
            found = templateManager.getProjectTemplates();
        } catch (RuntimeException e) {
            log.error("Could not read the Jira project template catalog", e);
            return ProjectTemplatesResponse.failed("Jira could not read its project template catalog (details are in "
                    + "the Jira log). This does not say which templates exist: retry, and do not guess a templateKey.");
        }
        List<ProjectTemplatesResponse.Template> rows = new ArrayList<ProjectTemplatesResponse.Template>();
        if (found != null) {
            for (ProjectTemplate template : found) {
                if (template == null || template.getKey() == null) {
                    continue;
                }
                String type = template.getProjectTypeKey() == null ? null : template.getProjectTypeKey().getKey();
                String key = template.getKey().getKey();
                rows.add(new ProjectTemplatesResponse.Template(key, type, template.getName(),
                        template.getDescription(), ProjectTemplates.supported(key, type)));
            }
        }
        rows.sort(Comparator.comparing(ProjectTemplatesResponse.Template::getProjectTypeKey,
                Comparator.nullsFirst(Comparator.<String>naturalOrder()))
                .thenComparing(ProjectTemplatesResponse.Template::getTemplateKey));
        return ProjectTemplatesResponse.ok(rows);
    }

    private static void requireCreateAction(CreateProjectRequest request) {
        if (!CreateProjectRequest.ACTION_CREATE.equals(request.getAction())) {
            throw new InvalidRequestException("Creating a project takes action=\"create\" with a templateKey taken "
                    + "from create_project {action:\"list_templates\"}; the form without them is not accepted. "
                    + "list_templates takes no other argument.");
        }
        String templateKey = trim(request.getTemplateKey());
        if (templateKey == null || templateKey.isEmpty()) {
            throw new InvalidRequestException("action=\"create\" needs a templateKey taken from "
                    + "create_project {action:\"list_templates\"} (only a template with supported=true).");
        }
    }

    @Override
    public List<ProjectLeadView> listLeads(String query) {
        administratorGuard.requireAdministrator();
        if (query == null || query.trim().isEmpty()) {
            throw new InvalidRequestException("Enter a non-empty username or display name to find project leads.");
        }
        UserSearchParams params = UserSearchParams.builder().allowEmptyQuery(false).includeActive(true)
                .includeInactive(false).canMatchEmail(false).limitResults(20).build();
        List<ApplicationUser> users = userSearchService.findUsers(query.trim(), params);
        List<ProjectLeadView> result = new ArrayList<ProjectLeadView>();
        if (users != null) {
            for (ApplicationUser user : users) {
                if (user != null && user.isActive()) {
                    result.add(ProjectLeadView.of(user));
                    if (result.size() == 20) {
                        break;
                    }
                }
            }
        }
        return result;
    }


    private CreateProjectResponse preview(CreateProjectRequest request, ApplicationUser administrator) {
        Preparation preparation = prepare(trim(request.getName()), trim(request.getKey()), trim(request.getLead()),
                trim(request.getTemplateKey()), administrator, true);
        if (preparation.failure != null) {
            return request.hasExplicitTypeSet() && preparation.failure.getStatus() == CreateProjectResponse.Status.EXISTS
                    ? failed(true, preparation.failure.getName(), preparation.failure.getKey(), preparation.failure.getLead(),
                    ErrorCode.KEY_CONFLICT, "Project '" + preparation.failure.getKey() + "' already exists. Its issue "
                            + "types were not checked or changed. To give an existing project this set use "
                            + "set_project_issue_types; nothing was changed.")
                    : preparation.failure;
        }
        PreparedProject project = preparation.project;
        IssueTypeSets.Resolved set = null;
        if (request.hasExplicitTypeSet()) {
            set = typeSets.resolve(request.getIssueTypes(), request.getNewIssueTypes(), request.getDefaultIssueType());
            if (!set.valid()) {
                return CreateProjectResponse.failed(true, project.name, project.key, ProjectLeadView.of(project.lead),
                        code(set.errorCode()), set.message(), set.suggestions());
            }
        } else if (request.getDefaultIssueType() != null) {
            return failed(true, project.name, project.key, ProjectLeadView.of(project.lead), ErrorCode.VALIDATION_ERROR,
                    "defaultIssueType needs issueTypes or newIssueTypes: without them the project is created in "
                            + "template mode and keeps the template's own issue types.");
        }
        ActionPlanStore.Plan plan = planStore.issue("create_project", administrator.getKey(),
                planCodec.request(request), fingerprint(project, set));
        CreateProjectResponse response = CreateProjectResponse.wouldCreate(project.name, project.key,
                ProjectLeadView.of(project.lead), plan.getId(), plan.getExpiresAt())
                .withTemplate(project.projectType, project.templateKey);
        return set == null ? response : response.withIssueTypeSet(set.preview());
    }

    private CreateProjectResponse apply(String planId, ApplicationUser administrator) {
        ActionPlanStore.Plan plan = planStore.find("create_project", administrator.getKey(), planId);
        if (plan == null) {
            return stale();
        }
        CreateProjectRequest request = planCodec.mode(plan.getRequest(), true, CreateProjectRequest.class);
        Preparation preparation = prepare(trim(request.getName()), trim(request.getKey()), trim(request.getLead()),
                trim(request.getTemplateKey()), administrator, false);
        IssueTypeSets.Resolved set = null;
        if (preparation.failure == null && request.hasExplicitTypeSet()) {
            set = typeSets.resolve(request.getIssueTypes(), request.getNewIssueTypes(), request.getDefaultIssueType());
        }
        if (preparation.failure != null || (set != null && !set.valid())
                || !plan.getFingerprint().equals(fingerprint(preparation.project, set))) {
            planStore.remove(planId);
            return stale();
        }
        PreparedProject project = preparation.project;
        try {
            Map<String,Object> before = undoSupport.before(project.key);
            UndoStep step = journal.prepare("project.created", "project:" + project.key,
                    undoSupport.json(undoSupport.intent(before)), undoSupport.json(undoSupport.intent(before)));
            Project created = projectService.createProject(project.validation);
            if (created == null) throw new IllegalStateException("Jira did not return the created project.");
            journal.applied(step, undoSupport.json(undoSupport.after(before, created)));
            journal.checkpoint();
            log.info("Created project '{}' with template '{}' (type '{}', user {})", project.key,
                    project.templateKey, project.projectType, administrator.getUsername());
            ProjectLeadView leadView = ProjectLeadView.of(created.getLead() == null ? project.lead : created.getLead());
            ChangeLinks.Report report = links.report().created(ChangeKind.PROJECT, String.valueOf(created.getId()),
                    created.getName(), created.getKey());
            if (set == null) {
                return CreateProjectResponse.created(created.getName(), created.getKey(), leadView, created.getId())
                        .withTemplate(project.projectType, project.templateKey).withReport(report.build());
            }
            // The project exists and is journaled. From here every failure is a result: nothing is undone behind it.
            IssueTypeSets.Applied applied = typeSets.applyToNewProject(created, set, administrator, report);
            if (applied.complete()) {
                return CreateProjectResponse.created(created.getName(), created.getKey(), leadView, created.getId())
                        .withTemplate(project.projectType, project.templateKey)
                        .withReport(report.build()).withIssueTypeSet(applied.view());
            }
            return CreateProjectResponse.incomplete(created.getName(), created.getKey(), leadView, created.getId(),
                    code(applied.errorCode()), "Project " + created.getKey() + " (id " + created.getId() + ") WAS "
                            + "created, but the requested final issue-type set is NOT completely in place. "
                            + applied.message()).withTemplate(project.projectType, project.templateKey)
                    .withReport(report.build()).withIssueTypeSet(applied.view());
        } finally {
            planStore.remove(planId);
        }
    }

    private Preparation prepare(String name, String key, String leadInput, String templateKey,
                                ApplicationUser administrator, boolean dryRun) {
        ProjectLeadView leadView = null;
        try {
            if (name == null || name.isEmpty() || key == null || key.isEmpty()) {
                return Preparation.failed(failed(dryRun, name, key, null, ErrorCode.VALIDATION_ERROR,
                        "Project name and key are required."));
            }
            if (leadInput == null || leadInput.isEmpty()) {
                return Preparation.failed(failed(dryRun, name, key, null, ErrorCode.UNKNOWN_LEAD,
                        "Enter the exact username or user key of an active project lead."));
            }
            ApplicationUser byName = userManager.getUserByName(leadInput);
            ApplicationUser byKey = userManager.getUserByKey(leadInput);
            if (byName != null && !leadInput.equals(byName.getUsername())) {
                byName = null;
            }
            if (byKey != null && !leadInput.equals(byKey.getKey())) {
                byKey = null;
            }
            if (byName != null && byKey != null && !byName.getKey().equals(byKey.getKey())) {
                return Preparation.failed(failed(dryRun, name, key, null, ErrorCode.AMBIGUOUS_LEAD,
                        "That identifier matches different users' usernames and user keys. Ask for another exact identifier."));
            }
            ApplicationUser lead = byName != null ? byName : byKey;
            if (lead == null || !lead.isActive()) {
                return Preparation.failed(failed(dryRun, name, key, null, ErrorCode.UNKNOWN_LEAD,
                        "No active user has that exact username or user key. Search for a lead and ask the user to choose."));
            }
            leadView = ProjectLeadView.of(lead);
            Optional<ProjectTemplate> template = templateManager.getProjectTemplate(new ProjectTemplateKey(templateKey));
            if (template == null || !template.isPresent()) {
                return Preparation.failed(failed(dryRun, name, key, leadView, ErrorCode.TEMPLATE_UNAVAILABLE,
                        "The Jira project template '" + templateKey + "' is unavailable. Take templateKey only from "
                                + "create_project {action:\"list_templates\"}; ask a Jira administrator to enable the template."));
            }
            String projectType = template.get().getProjectTypeKey() == null ? null
                    : template.get().getProjectTypeKey().getKey();
            if (!ProjectTemplates.supported(templateKey, projectType)) {
                return Preparation.failed(failed(dryRun, name, key, leadView, ErrorCode.UNSUPPORTED_TEMPLATE,
                        "Control creates Business and Software projects only from its supported templates "
                                + "(supported=true in create_project {action:\"list_templates\"}). Template '"
                                + templateKey + "' (project type '" + projectType + "') is not one of them; nothing was changed."));
            }
            Project nameCollision = null;
            for (Project project : projectsIncludingArchived()) {
                if (hasKey(project, key)) {
                    return Preparation.failed(sameKey(dryRun, project, name, key, leadView, templateKey, projectType));
                }
                if (nameCollision == null && project.getName() != null && project.getName().equalsIgnoreCase(name)) {
                    nameCollision = project;
                }
            }
            if (nameCollision != null) {
                return Preparation.failed(failed(dryRun, name, key, leadView, ErrorCode.NAME_CONFLICT,
                        "Project name '" + name + "' already belongs to project '" + nameCollision.getKey()
                                + "'. Ask the user for a different name; the existing project was not changed."));
            }
            ProjectCreationData data = new ProjectCreationData.Builder().withName(name).withKey(key)
                    .withLead(lead).withType(projectType).withProjectTemplateKey(templateKey).build();
            ProjectService.CreateProjectValidationResult validation =
                    projectService.validateCreateProject(administrator, data);
            if (!validation.isValid()) {
                return Preparation.failed(failed(dryRun, name, key, leadView, ErrorCode.VALIDATION_ERROR,
                        validationMessage(validation.getErrorCollection())));
            }
            return Preparation.ready(new PreparedProject(name, key, lead, validation,
                    fingerprint(administrator, name, key, lead, templateKey, projectType), templateKey, projectType));
        } catch (RuntimeException e) {
            log.error("Could not build project creation plan for key '{}'", key, e);
            return Preparation.failed(failed(dryRun, name, key, leadView, ErrorCode.JIRA_ERROR,
                    "Jira could not build the project creation plan. Nothing was changed; retry preview."));
        }
    }

    private CreateProjectResponse sameKey(boolean dryRun, Project project, String name, String key,
                                          ProjectLeadView lead, String templateKey, String projectType) {
        if (project.isArchived()) {
            return failed(dryRun, name, key, lead, ErrorCode.KEY_CONFLICT,
                    "Project key '" + key + "' belongs to an archived project. Ask the user for a new key "
                            + "or to restore that project in Jira; nothing was changed.");
        }
        if (!project.getKey().equalsIgnoreCase(key)) {
            return failed(dryRun, name, key, lead, ErrorCode.KEY_CONFLICT,
                    "Project key '" + key + "' is a former key of project '" + project.getKey()
                            + "'. Ask the user for another key; nothing was changed.");
        }
        if (!project.getName().equalsIgnoreCase(name) || project.getProjectTypeKey() == null
                || !projectType.equals(project.getProjectTypeKey().getKey())) {
            return failed(dryRun, name, key, lead, ErrorCode.KEY_CONFLICT,
                    "Project key '" + key + "' already belongs to a project with a different name or type. "
                            + "Ask the user how to resolve the conflict; nothing was changed.");
        }
        String existingLeadKey = project.getLeadUserKey();
        if (existingLeadKey == null || !existingLeadKey.equals(lead.getUserKey())) {
            return failed(dryRun, name, key, lead, ErrorCode.LEAD_CONFLICT,
                    "Project '" + key + "' already exists with a different lead. Ask the user how to resolve "
                            + "the conflict; nothing was changed.");
        }
        return CreateProjectResponse.exists(dryRun, project.getName(), project.getKey(), lead, project.getId())
                .withTemplate(projectType, templateKey);
    }

    private static ErrorCode code(String code) {
        try {
            return code == null ? ErrorCode.JIRA_ERROR : ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ErrorCode.JIRA_ERROR;
        }
    }

    private String fingerprint(PreparedProject project, IssueTypeSets.Resolved set) {
        return set == null ? project.fingerprint
                : planCodec.fingerprint(java.util.Arrays.asList(project.fingerprint, set.fingerprintSource()));
    }

    private static CreateProjectResponse stale() {
        return failed(false, null, null, null, ErrorCode.PREVIEW_STALE,
                "The preview is missing, expired, belongs to another actor, was already applied, or Jira state "
                        + "changed. Run preview again; nothing was changed.");
    }

    private static String fingerprint(ApplicationUser actor, String name, String key, ApplicationUser lead,
                                      String templateKey, String projectType) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            add(digest, actor.getKey());
            add(digest, name);
            add(digest, key);
            add(digest, lead.getKey());
            add(digest, lead.getUsername());
            add(digest, lead.getDisplayName());
            add(digest, Boolean.toString(lead.isActive()));
            add(digest, projectType);
            add(digest, templateKey);
            byte[] bytes = digest.digest();
            char[] hex = new char[bytes.length * 2];
            char[] digits = "0123456789abcdef".toCharArray();
            for (int i = 0; i < bytes.length; i++) {
                int value = bytes[i] & 0xff;
                hex[i * 2] = digits[value >>> 4];
                hex[i * 2 + 1] = digits[value & 0x0f];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void add(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
    }

    private static final class Preparation {
        private final PreparedProject project;
        private final CreateProjectResponse failure;

        private Preparation(PreparedProject project, CreateProjectResponse failure) {
            this.project = project;
            this.failure = failure;
        }

        private static Preparation ready(PreparedProject project) {
            return new Preparation(project, null);
        }

        private static Preparation failed(CreateProjectResponse failure) {
            return new Preparation(null, failure);
        }
    }

    private static final class PreparedProject {
        private final String name;
        private final String key;
        private final ApplicationUser lead;
        private final ProjectService.CreateProjectValidationResult validation;
        private final String fingerprint;

        private final String templateKey;
        private final String projectType;

        private PreparedProject(String name, String key, ApplicationUser lead,
                                ProjectService.CreateProjectValidationResult validation, String fingerprint,
                                String templateKey, String projectType) {
            this.name = name;
            this.key = key;
            this.lead = lead;
            this.validation = validation;
            this.fingerprint = fingerprint;
            this.templateKey = templateKey;
            this.projectType = projectType;
        }
    }


    private List<Project> projectsIncludingArchived() {
        List<Project> projects = new ArrayList<Project>();
        Set<Long> seen = new HashSet<Long>();
        for (Project project : projectManager.getProjectObjects()) {
            if (project != null && seen.add(project.getId())) {
                projects.add(project);
            }
        }
        for (Project project : projectManager.getArchivedProjects()) {
            if (project != null && seen.add(project.getId())) {
                projects.add(project);
            }
        }
        return projects;
    }

    private boolean hasKey(Project project, String key) {
        if (project.getKey() != null && project.getKey().equalsIgnoreCase(key)) {
            return true;
        }
        Set<String> previousKeys = projectManager.getAllProjectKeys(project.getId());
        if (previousKeys != null) {
            for (String previousKey : previousKeys) {
                if (previousKey != null && previousKey.equalsIgnoreCase(key)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String validationMessage(ErrorCollection errors) {
        List<String> parts = new ArrayList<String>();
        if (errors != null) {
            Map<String, String> fields = errors.getErrors();
            if (fields != null) {
                for (Map.Entry<String, String> error : new TreeMap<String, String>(fields).entrySet()) {
                    parts.add(error.getKey() + ": " + error.getValue());
                }
            }
            if (errors.getErrorMessages() != null) {
                parts.addAll(errors.getErrorMessages());
            }
        }
        return parts.isEmpty() ? "Jira rejected the project details. Check the name, key, and lead."
                : "Jira rejected the project details: " + String.join("; ", parts);
    }

    private static CreateProjectResponse failed(boolean dryRun, String name, String key, ProjectLeadView lead,
                                                ErrorCode code, String message) {
        return CreateProjectResponse.failed(dryRun, name, key, lead, code, message, Collections.<String>emptyList());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
