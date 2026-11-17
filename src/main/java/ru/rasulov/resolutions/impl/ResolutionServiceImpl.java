package ru.rasulov.resolutions.impl;

import com.atlassian.jira.config.ResolutionManager;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.impl.ChangeLinks;
import com.atlassian.jira.issue.resolution.Resolution;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.PreviewPlanExecutor;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.common.impl.undo.JournalJson;
import ru.rasulov.common.impl.undo.UndoStep;
import ru.rasulov.resolutions.api.ResolutionService;
import ru.rasulov.resolutions.api.dto.CreateResolutionRequest;
import ru.rasulov.resolutions.api.dto.CreateResolutionResponse;
import ru.rasulov.resolutions.api.dto.ResolutionView;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;
import static ru.rasulov.resolutions.api.dto.CreateResolutionResponse.ErrorCode.*;
import static ru.rasulov.resolutions.api.dto.CreateResolutionResponse.Status.WOULD_CREATE;

@Named
@ExportAsService(ResolutionService.class)
public class ResolutionServiceImpl implements ResolutionService {
    static final String LOCK_NAME = "ru.rasulov.control.create-resolution";
    private static final String OPERATION = "create_resolution";
    private static final Logger log = LoggerFactory.getLogger(ResolutionServiceImpl.class);
    private final ResolutionManager manager;
    private final AdministratorGuard guard;
    private final NamedLock lock;
    private final PreviewPlanExecutor executor;
    private final ActionPlanCodec codec;
    private final ChangeJournal journal;
    private final ChangeLinks links;

    @Inject public ResolutionServiceImpl(@ComponentImport ResolutionManager manager, AdministratorGuard guard,
                                        NamedLock lock, PreviewPlanExecutor executor, ActionPlanCodec codec, ChangeJournal journal,
                                        ChangeLinks links) {
        this.manager = manager; this.guard = guard; this.lock = lock;
        this.executor = executor; this.codec = codec; this.journal = journal;
        this.links = links;
    }

    @Override public List<ResolutionView> get(String query) {
        guard.requireAdministrator();
        String term = query == null ? "" : query.trim();
        List<ResolutionView> values = new ArrayList<ResolutionView>();
        for (Resolution value : manager.getResolutions()) {
            if (term.isEmpty() || term.equals(value.getId()) || term.equalsIgnoreCase(value.getName())) {
                values.add(ResolutionView.of(value));
            }
        }
        return values;
    }

    @Override public CreateResolutionResponse create(CreateResolutionRequest request) {
        ApplicationUser actor = guard.requireAdministrator();
        CreateResolutionResponse invalid = validateMode(request);
        if (invalid != null) return invalid;
        if (!request.isDryRun()) return journal.execute(OPERATION, actor.getKey(), () ->
                lock.run(LOCK_NAME, "global resolutions", () -> apply(request.getPlanId().trim(), actor)));
        try {
            Preparation prepared = prepare(request);
            if (prepared.response.getStatus() != WOULD_CREATE) return prepared.response;
            return executor.issue(OPERATION, actor.getKey(), request, prepared.fingerprint, prepared.response::withPlan);
        } catch (RuntimeException e) {
            log.error("Could not preview global Resolution creation", e);
            return CreateResolutionResponse.failed(true, JIRA_ERROR,
                    "Jira could not build the Resolution preview. Nothing was changed; retry preview.");
        }
    }

    private CreateResolutionResponse apply(String planId, ApplicationUser actor) {
        return executor.apply(OPERATION, actor.getKey(), planId, CreateResolutionRequest.class,
                ResolutionServiceImpl::stale,
                this::prepare, current -> current.response.getStatus() == WOULD_CREATE,
                current -> current.fingerprint, this::write);
    }

    private CreateResolutionResponse write(Preparation current) {
        ResolutionView wanted = current.response.getResolution();
        String before = JournalJson.write(Collections.singletonMap("name", wanted.getName()));
        UndoStep step = journal.prepare("resolution.created", "resolution-name:" + wanted.getName(), before, before);
        Resolution created = manager.createResolution(wanted.getName(), wanted.getDescription());
        if (created == null || created.getId() == null || created.getId().trim().isEmpty())
            throw new IllegalStateException("Jira did not return the created Resolution's confirmed ID.");
        journal.applied(step, JournalJson.write(ResolutionView.of(created)));
        journal.checkpoint();
        return CreateResolutionResponse.created(ResolutionView.of(created)).withReport(links.report()
                .created(ChangeKind.RESOLUTION, created.getId(), created.getName(), created.getId()).build());
    }

    private Preparation prepare(CreateResolutionRequest request) {
        String name = request.getName() == null ? "" : request.getName().trim();
        if (name.isEmpty() || "unresolved".equalsIgnoreCase(name) || "Нерешено".equalsIgnoreCase(name)) {
            return result(CreateResolutionResponse.failed(true, VALIDATION_ERROR,
                    "Provide a non-blank Resolution name. Unresolved / Нерешено is absence of Resolution, not a catalog value."));
        }
        String description = request.getDescription() == null ? "" : request.getDescription();
        Map<String, ResolutionView> snapshot = new TreeMap<String, ResolutionView>();
        Resolution match = null;
        for (Resolution value : manager.getResolutions()) {
            snapshot.put(value.getId(), ResolutionView.of(value));
            if (name.equalsIgnoreCase(value.getName())) {
                if (match != null) return result(CreateResolutionResponse.failed(true, NAME_CONFLICT,
                        "Several Resolutions match this name; resolve the ambiguity without overwriting existing values."));
                match = value;
            }
        }
        if (match != null) {
            String existingDescription = match.getDescription() == null ? "" : match.getDescription();
            if (!description.equals(existingDescription)) return result(CreateResolutionResponse.failed(true, NAME_CONFLICT,
                    "A Resolution with this name has a different description; the existing value was not changed."));
            return result(CreateResolutionResponse.exists(ResolutionView.of(match)));
        }
        ResolutionView wanted = new ResolutionView(null, name, description);
        return new Preparation(CreateResolutionResponse.wouldCreate(wanted), codec.fingerprint(Arrays.asList(wanted, snapshot)));
    }

    private static CreateResolutionResponse validateMode(CreateResolutionRequest request) {
        if (request == null || request.isDryRun() == null) return CreateResolutionResponse.failed(true, VALIDATION_ERROR,
                "Send dryRun explicitly as true or false.");
        if (request.isDryRun()) {
            if (request.getPlanId() != null) return CreateResolutionResponse.failed(true, VALIDATION_ERROR,
                    "Preview takes name and description, not planId.");
        } else if (request.getName() != null || request.getDescription() != null
                || request.getPlanId() == null || request.getPlanId().trim().isEmpty()) {
            return CreateResolutionResponse.failed(false, VALIDATION_ERROR,
                    "Apply takes only dryRun=false and the confirmed preview's planId.");
        }
        return null;
    }
    private static CreateResolutionResponse stale() {
        return CreateResolutionResponse.failed(false, PREVIEW_STALE,
                "Preview is missing, expired, consumed, belongs to another administrator, or the Resolution catalog changed. "
                        + "Build a fresh preview and obtain confirmation.");
    }
    private static Preparation result(CreateResolutionResponse response) { return new Preparation(response, null); }
    private static final class Preparation {
        final CreateResolutionResponse response;
        final String fingerprint;
        Preparation(CreateResolutionResponse response, String fingerprint) { this.response = response; this.fingerprint = fingerprint; }
    }
}
