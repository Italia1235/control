package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeItem;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.ProjectRef;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.LookupFailure;
import ru.rasulov.screens.api.dto.CloneProjectScreensRequest;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse.CopyView;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse.ErrorCode;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse.Verdict;
import ru.rasulov.screens.api.dto.ScreenCloneScope;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Named
public class ScreenClonePlanner {
    private static final int MAX_NAME = 255;
    private final IssueTypeResolver issueTypeResolver;
    private final FieldScreenManager screenManager;
    private final FieldScreenSchemeManager screenSchemeManager;
    private final IssueTypeScreenSchemeManager issueTypeScreenSchemeManager;
    private final ScreenUsage usage;

    @Inject
    public ScreenClonePlanner(IssueTypeResolver issueTypeResolver,
                              @ComponentImport ProjectManager projectManager,
                              @ComponentImport FieldScreenManager screenManager,
                              @ComponentImport FieldScreenSchemeManager screenSchemeManager,
                              @ComponentImport IssueTypeScreenSchemeManager issueTypeScreenSchemeManager) {
        this.issueTypeResolver = issueTypeResolver; this.screenManager = screenManager;
        this.screenSchemeManager = screenSchemeManager;
        this.issueTypeScreenSchemeManager = issueTypeScreenSchemeManager;
        this.usage = new ScreenUsage(projectManager, screenSchemeManager, issueTypeScreenSchemeManager);
    }

    ScreenClonePlan plan(Project project, CloneProjectScreensRequest request) {
        validate(request);
        IssueTypeScreenScheme source = issueTypeScreenSchemeManager.getIssueTypeScreenScheme(project);
        if (source == null) throw new ScreenCloneFailure(ErrorCode.JIRA_ERROR,
                "Project " + project.getKey() + " has no issue type screen scheme.");

        List<IssueType> projectTypes = sortedTypes(project.getIssueTypes());
        Set<String> selected = selectedTypes(projectTypes, request);
        Map<Long, FieldScreenScheme> all = effectiveSchemes(source, projectTypes);
        Set<Long> wantedIds = wantedSchemeIds(source, projectTypes, selected, request.getScope());

        Set<String> schemeNames = new HashSet<String>();
        for (FieldScreenScheme scheme : screenSchemeManager.getFieldScreenSchemes()) schemeNames.add(normalize(scheme.getName()));
        Set<String> screenNames = new HashSet<String>();
        for (FieldScreen screen : screenManager.getFieldScreens()) screenNames.add(normalize(screen.getName()));

        List<Project> topUsers = usage.projects(source);
        List<Project> topOthers = usage.others(topUsers, project);
        boolean copyTop = source.isDefault() || !topOthers.isEmpty() || usage.hasOtherAssociation(source, project);
        String topName = null;
        if (copyTop) {
            Set<String> topNames = new HashSet<String>();
            for (IssueTypeScreenScheme scheme : issueTypeScreenSchemeManager.getIssueTypeScreenSchemes()) {
                topNames.add(normalize(scheme.getName()));
            }
            topName = freeName(titled(source.getName(), project, topUsers), topNames);
        }
        Map<FieldScreenScheme, String> schemeCopies = new LinkedHashMap<FieldScreenScheme, String>();
        for (FieldScreenScheme scheme : sortedSchemes(all.values())) {
            if (wantedIds.contains(scheme.getId())) {
                String name = freeName(titled(scheme.getName(), project, usage.projects(scheme)), schemeNames);
                schemeNames.add(normalize(name));
                schemeCopies.put(scheme, name);
            }
        }

        Map<Long, FieldScreen> allScreens = new LinkedHashMap<Long, FieldScreen>();
        for (FieldScreenScheme scheme : schemeCopies.keySet()) {
            for (FieldScreenSchemeItem item : scheme.getFieldScreenSchemeItems()) {
                FieldScreen screen = item.getFieldScreen();
                if (screen != null) allScreens.put(screen.getId(), screen);
            }
        }
        Map<FieldScreen, String> screenCopies = new LinkedHashMap<FieldScreen, String>();
        if (Boolean.TRUE.equals(request.isCopyScreens())) {
            for (FieldScreen screen : sortedScreens(allScreens.values())) {
                String name = freeName(titled(screen.getName(), project, usage.projects(screen)), screenNames);
                screenNames.add(normalize(name));
                screenCopies.put(screen, name);
            }
        }

        boolean needed = copyTop;
        for (FieldScreenScheme scheme : schemeCopies.keySet()) {
            if (!usage.others(usage.projects(scheme), project).isEmpty()) needed = true;
        }
        for (FieldScreen screen : screenCopies.keySet()) {
            if (!usage.others(usage.projects(screen), project).isEmpty()) needed = true;
        }
        // Nothing requested is shared: the setup is kept as is, so no level may be reported or planned as a copy.
        if (!needed) { schemeCopies.clear(); screenCopies.clear(); }
        List<CopyView> schemeViews = new ArrayList<CopyView>();
        for (FieldScreenScheme scheme : sortedSchemes(all.values())) {
            boolean copy = schemeCopies.containsKey(scheme);
            schemeViews.add(view(scheme.getId(), scheme.getName(), copy ? schemeCopies.get(scheme) : null,
                    copy ? Verdict.COPY : Verdict.KEEP, usage.others(usage.projects(scheme), project)));
        }
        List<CopyView> screenViews = new ArrayList<CopyView>();
        for (FieldScreen screen : sortedScreens(allScreens.values())) {
            boolean copy = screenCopies.containsKey(screen);
            screenViews.add(view(screen.getId(), screen.getName(), copy ? screenCopies.get(screen) : null,
                    copy ? Verdict.COPY : Verdict.KEEP, usage.others(usage.projects(screen), project)));
        }
        CopyView topView = view(source.getId(), source.getName(), topName,
                copyTop ? Verdict.COPY : Verdict.KEEP, topOthers);
        return new ScreenClonePlan(project, source, request.getScope(), Boolean.TRUE.equals(request.isCopyScreens()),
                topName, selected, schemeCopies, screenCopies, needed, topView, schemeViews, screenViews);
    }

    private void validate(CloneProjectScreensRequest request) {
        if (request.getScope() == null) throw new ScreenCloneFailure(ErrorCode.VALIDATION_ERROR, "scope is required.");
        if (request.isCopyScreens() == null) throw new ScreenCloneFailure(ErrorCode.VALIDATION_ERROR,
                "copyScreens must be explicitly true or false.");
        if (request.getScope() == ScreenCloneScope.SCHEME_ONLY && request.isCopyScreens()) {
            throw new ScreenCloneFailure(ErrorCode.VALIDATION_ERROR,
                    "copyScreens=true requires scope ALL or SELECTED because screens belong to field screen schemes.");
        }
        if (request.getScope() == ScreenCloneScope.SELECTED && request.getIssueTypes().isEmpty()) {
            throw new ScreenCloneFailure(ErrorCode.VALIDATION_ERROR, "SELECTED requires issueTypes.");
        }
        if (request.getScope() != ScreenCloneScope.SELECTED && !request.getIssueTypes().isEmpty()) {
            throw new ScreenCloneFailure(ErrorCode.VALIDATION_ERROR, "issueTypes is only valid with SELECTED.");
        }
    }

    private Set<String> selectedTypes(List<IssueType> projectTypes, CloneProjectScreensRequest request) {
        Set<String> selected = new LinkedHashSet<String>();
        if (request.getScope() != ScreenCloneScope.SELECTED) return selected;
        Set<String> inProject = new HashSet<String>();
        for (IssueType type : projectTypes) inProject.add(type.getId());
        for (String value : request.getIssueTypes()) {
            IssueType type;
            try { type = issueTypeResolver.resolve(value); }
            catch (LookupFailure e) { throw lookupFailure(e); }
            if (!inProject.contains(type.getId())) throw new ScreenCloneFailure(ErrorCode.VALIDATION_ERROR,
                    "Issue type '" + type.getName() + "' is not available in project " + request.getProject() + ".",
                    typeNames(projectTypes));
            if (!selected.add(type.getId())) throw new ScreenCloneFailure(ErrorCode.VALIDATION_ERROR,
                    "Issue type '" + type.getName() + "' is repeated.");
        }
        return selected;
    }

    private static ScreenCloneFailure lookupFailure(LookupFailure e) {
        ErrorCode code = e.getKind() == LookupFailure.Kind.UNKNOWN_ISSUE_TYPE
                ? ErrorCode.UNKNOWN_ISSUE_TYPE : ErrorCode.VALIDATION_ERROR;
        return new ScreenCloneFailure(code, e.getMessage(), e.getSuggestions());
    }

    private static Map<Long, FieldScreenScheme> effectiveSchemes(IssueTypeScreenScheme source, List<IssueType> types) {
        Map<Long, FieldScreenScheme> result = new LinkedHashMap<Long, FieldScreenScheme>();
        for (IssueType type : types) {
            FieldScreenScheme scheme = source.getEffectiveFieldScreenScheme(type);
            if (scheme != null) result.put(scheme.getId(), scheme);
        }
        return result;
    }

    private static Set<Long> wantedSchemeIds(IssueTypeScreenScheme source, List<IssueType> types,
                                             Set<String> selected, ScreenCloneScope scope) {
        Set<Long> result = new LinkedHashSet<Long>();
        if (scope == ScreenCloneScope.SCHEME_ONLY) return result;
        for (IssueType type : types) {
            if (scope == ScreenCloneScope.ALL || selected.contains(type.getId())) {
                FieldScreenScheme scheme = source.getEffectiveFieldScreenScheme(type);
                if (scheme != null) result.add(scheme.getId());
            }
        }
        return result;
    }

    private CopyView view(Long id, String name, String newName, Verdict verdict, List<Project> others) {
        return new CopyView(id, name, null, newName, verdict, others.size(), usage.refs(others));
    }

    static String titled(String oldName, Project project, List<Project> users) {
        String key = project.getKey();
        if (startsWithKey(oldName, key)) return oldName;
        for (Project user : users) if (startsWithKey(oldName, user.getKey())) return key + oldName.substring(user.getKey().length());
        return key + ": " + oldName;
    }

    static String freeName(String proposed, Set<String> taken) {
        String fitted = proposed.length() > MAX_NAME ? proposed.substring(0, MAX_NAME) : proposed;
        if (!taken.contains(normalize(fitted))) return fitted;
        String stem = fitted.replaceFirst("\\s+v\\d+$", "");
        for (int n = 1; ; n++) {
            String suffix = " v" + n;
            String base = stem.length() + suffix.length() > MAX_NAME ? stem.substring(0, MAX_NAME - suffix.length()) : stem;
            if (!taken.contains(normalize(base + suffix))) return base + suffix;
        }
    }

    private static boolean startsWithKey(String name, String key) {
        return name != null && name.length() > key.length() && name.regionMatches(true, 0, key, 0, key.length())
                && !Character.isLetterOrDigit(name.charAt(key.length()));
    }
    private static String normalize(String value) { return value.trim().toLowerCase(Locale.ROOT); }
    private static List<IssueType> sortedTypes(Collection<IssueType> values) {
        List<IssueType> result = new ArrayList<IssueType>(values);
        result.sort(Comparator.comparing(IssueType::getName).thenComparing(IssueType::getId)); return result;
    }
    private static List<FieldScreenScheme> sortedSchemes(Collection<FieldScreenScheme> values) {
        List<FieldScreenScheme> result = new ArrayList<FieldScreenScheme>(values);
        result.sort(Comparator.comparing(FieldScreenScheme::getName).thenComparing(FieldScreenScheme::getId)); return result;
    }
    private static List<FieldScreen> sortedScreens(Collection<FieldScreen> values) {
        List<FieldScreen> result = new ArrayList<FieldScreen>(values);
        result.sort(Comparator.comparing(FieldScreen::getName).thenComparing(FieldScreen::getId)); return result;
    }
    private static List<String> typeNames(List<IssueType> types) {
        List<String> result = new ArrayList<String>(); for (IssueType type : types) result.add(type.getName()); return result;
    }
}
