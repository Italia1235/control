package ru.rasulov.workflow.impl;

import com.atlassian.jira.bc.ServiceResult;
import com.atlassian.jira.bc.config.StatusService;
import com.atlassian.jira.config.StatusCategoryManager;
import com.atlassian.jira.config.StatusManager;
import com.atlassian.jira.issue.status.Status;
import com.atlassian.jira.issue.status.category.StatusCategory;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.ErrorCollection;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.LookupLimits;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.workflow.api.WorkflowLimits;
import ru.rasulov.workflow.api.dto.StatusCategoryKey;
import ru.rasulov.workflow.api.dto.StatusListResponse;
import ru.rasulov.workflow.api.dto.StatusResponse;
import ru.rasulov.workflow.api.dto.StatusLookupEntry;
import ru.rasulov.workflow.api.dto.StatusLookupResponse;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ru.rasulov.common.impl.Normalizer.normalize;

/** The statuses Jira already has: what {@code list_statuses} shows, and how a name in a request finds its status. */
@Named
public class StatusCatalog {

    static final String UNDEFINED = "UNDEFINED";
    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final int MAX_LOOKUP_NAMES = 30;
    static final String ICON_URL = "/images/icons/status_generic.gif";

    private final StatusManager statusManager;
    private final StatusService statusService;
    private final StatusCategoryManager categoryManager;

    @Inject
    public StatusCatalog(@ComponentImport StatusManager statusManager, @ComponentImport StatusService statusService,
                         @ComponentImport StatusCategoryManager categoryManager) {
        this.statusManager = statusManager;
        this.statusService = statusService;
        this.categoryManager = categoryManager;
    }

    StatusListResponse list(String query, Integer limit) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int size = limit == null || limit < 1 ? LookupLimits.DEFAULT_REFERENCE_LIST_SIZE
                : Math.min(limit, LookupLimits.MAX_REFERENCE_LIST_SIZE);
        Collection<Status> all = statusManager.getStatuses();
        List<StatusResponse> matching = new ArrayList<StatusResponse>(all.size());
        for (Status status : all) {
            if (matches(status, needle)) {
                matching.add(toResponse(status));
            }
        }
        Collections.sort(matching, new Comparator<StatusResponse>() {
            @Override
            public int compare(StatusResponse a, StatusResponse b) {
                int byName = a.getName().compareToIgnoreCase(b.getName());
                return byName != 0 ? byName : a.getId().compareTo(b.getId());
            }
        });
        return new StatusListResponse(matching.size(), matching.subList(0, Math.min(size, matching.size())));
    }

    /**
     * Each name against the whole catalog, read once, by the same trimmed case-insensitive exact match as
     * {@link #findAll}. A name that cannot be looked up is refused before the catalog is read.
     */
    StatusLookupResponse lookup(List<String> names) {
        checkLookupNames(names);
        Map<String, List<Status>> byName = new HashMap<String, List<Status>>();
        for (Status status : statusManager.getStatuses()) {
            byName.computeIfAbsent(normalize(status.getName()), key -> new ArrayList<Status>()).add(status);
        }
        List<StatusLookupEntry> lookup = new ArrayList<StatusLookupEntry>(names.size());
        for (String name : names) {
            List<Status> found = new ArrayList<Status>(byName.getOrDefault(normalize(name), Collections.<Status>emptyList()));
            found.sort(Comparator.comparing(Status::getId));
            List<StatusResponse> statuses = new ArrayList<StatusResponse>(found.size());
            for (Status status : found) {
                statuses.add(toResponse(status));
            }
            lookup.add(new StatusLookupEntry(name, found.isEmpty() ? StatusLookupEntry.Result.NOT_FOUND
                    : found.size() == 1 ? StatusLookupEntry.Result.FOUND : StatusLookupEntry.Result.AMBIGUOUS, statuses));
        }
        return new StatusLookupResponse(lookup);
    }

    private static void checkLookupNames(List<String> names) {
        if (names == null || names.isEmpty() || names.size() > MAX_LOOKUP_NAMES) {
            throw new InvalidRequestException("'names' must hold 1 to " + MAX_LOOKUP_NAMES + " names"
                    + (names == null ? "." : "; got " + names.size() + "."));
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i) == null ? "" : names.get(i).trim();
            if (name.isEmpty()) {
                throw new InvalidRequestException("'names[" + i + "]' is required and must not be blank.");
            }
            if (name.length() > WorkflowLimits.MAX_STATUS_NAME_LENGTH) {
                throw new InvalidRequestException("'names[" + i + "]' has " + name.length()
                        + " characters; the maximum is " + WorkflowLimits.MAX_STATUS_NAME_LENGTH + ".");
            }
        }
    }

    private static StatusResponse toResponse(Status status) {
        return new StatusResponse(status.getId(), status.getName(), categoryOf(status), truncate(status.getDescription()));
    }

    private static boolean matches(Status status, String needle) {
        if (needle.isEmpty()) return true;
        String name = status.getName() == null ? "" : status.getName().toLowerCase(Locale.ROOT);
        return status.getId().toLowerCase(Locale.ROOT).contains(needle) || name.contains(needle);
    }

    private static String truncate(String text) {
        if (text == null) return "";
        return text.length() <= MAX_DESCRIPTION_LENGTH ? text : text.substring(0, MAX_DESCRIPTION_LENGTH);
    }

    /** The status of this name (trimmed, case-insensitive), or {@code null}. */
    public Status find(String name) {
        String wanted = normalize(name);
        for (Status status : statusManager.getStatuses()) {
            if (normalize(status.getName()).equals(wanted)) {
                return status;
            }
        }
        return null;
    }

    /** Every CI-exact candidate, ordered by canonical ID rather than catalog iteration order. */
    public List<Status> findAll(String name) {
        String wanted = normalize(name);
        List<Status> matches = new ArrayList<>();
        for (Status status : statusManager.getStatuses()) {
            if (normalize(status.getName()).equals(wanted)) matches.add(status);
        }
        matches.sort(Comparator.comparing(Status::getId));
        return matches;
    }

    /** Read-back by returned native ID; never infer ownership from a status name. */
    public Status findById(String id) {
        return statusManager.getStatus(id);
    }

    public Object categoryFacts(StatusCategoryKey key) {
        StatusCategory category = categoryOf(key);
        return category == null ? null : java.util.Arrays.asList(category.getId(), category.getKey(), category.getName());
    }

    /**
     * Whether Jira would accept this status as new, checked without writing.
     *
     * @return the reasons Jira gives, or {@code null} when it would accept the status
     */
    public String creationProblems(ApplicationUser user, String name, String description, StatusCategoryKey category) {
        ServiceResult result = statusService.validateCreateStatus(user, name, description, ICON_URL,
                categoryOf(category));
        if (result.isValid()) {
            return null;
        }
        return reasons(result.getErrorCollection());
    }

    public StatusCategory categoryOf(StatusCategoryKey key) {
        return categoryManager.getStatusCategoryByKey(key.jiraKey());
    }

    public static String categoryOf(Status status) {
        StatusCategory category = status.getStatusCategory();
        StatusCategoryKey key = category == null ? null : StatusCategoryKey.fromJiraKey(category.getKey());
        return key == null ? UNDEFINED : key.name();
    }

    public static String reasons(ErrorCollection errors) {
        List<String> reasons = new ArrayList<String>(errors.getErrorMessages());
        reasons.addAll(errors.getErrors().values());
        return reasons.isEmpty() ? "no reason given" : String.join("; ", reasons);
    }
}
