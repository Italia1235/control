package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.fields.layout.field.*;
import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.issue.fields.screen.issuetype.*;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.common.api.dto.IssueTypeRef;
import ru.rasulov.common.impl.screen.EffectiveScreens;
import ru.rasulov.screens.api.dto.layout.RemoveFieldFromScreensResponse.RequiredProject;
import ru.rasulov.screens.impl.ScreenUsage;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** A prepare-local session caches each distinct effective field layout, including Jira's default. */
@Named
public class RequiredOnCreate {
    static final int MAX_PROJECTS = 50;
    private final ScreenUsage usage;
    private final IssueTypeScreenSchemeManager screens;
    private final FieldLayoutManager layouts;
    @Inject
    public RequiredOnCreate(@ComponentImport ProjectManager projects, @ComponentImport FieldScreenSchemeManager schemes,
            @ComponentImport IssueTypeScreenSchemeManager screens, @ComponentImport FieldLayoutManager layouts) {
        this.usage = new ScreenUsage(projects, schemes, screens); this.screens = screens; this.layouts = layouts;
    }
    public Session session() { return new Session(); }
    public final class Session {
        private final Map<Long, List<Pair>> createPairs = new HashMap<>();
        private final Map<Long, FieldLayout> layoutCache = new HashMap<>();
        private final Map<Long, Map<String, Boolean>> requiredCache = new HashMap<>();
        private final Map<Long, FieldConfigurationScheme> configurationCache = new HashMap<>();
        private final Map<Long, IssueTypeScreenScheme> screenCache = new HashMap<>();
        public boolean usedOnCreate(FieldScreen screen) { return !pairs(screen).isEmpty(); }
        public Result check(FieldScreen screen, String fieldId) {
            Map<Long, Group> groups = new LinkedHashMap<>();
            int count = 0;
            boolean truncated = false;
            for (Pair pair : pairs(screen)) {
                FieldConfigurationScheme configuration;
                if (configurationCache.containsKey(pair.project.getId())) configuration = configurationCache.get(pair.project.getId());
                else {
                    configuration = layouts.getFieldConfigurationScheme(pair.project);
                    configurationCache.put(pair.project.getId(), configuration);
                }
                // Jira resolves the issue-type mapping and its default in getFieldLayoutId.
                // A project without a configuration scheme uses Jira's default field layout.
                Long id = configuration == null ? null : configuration.getFieldLayoutId(pair.type.getId());
                Map<String, Boolean> requirements = requiredCache.computeIfAbsent(id, ignored -> new HashMap<>());
                Boolean required = requirements.get(fieldId);
                if (required == null) {
                    FieldLayout layout = layoutCache.get(id);
                    if (layout == null) {
                        layout = id == null ? layouts.getFieldLayout() : layouts.getFieldLayout(id);
                        if (layout == null) throw new IllegalStateException("Could not read effective field configuration " + id);
                        layoutCache.put(id, layout);
                    }
                    FieldLayoutItem item = layout.getFieldLayoutItem(fieldId);
                    // Absence is not evidence that a field is optional or unavailable.
                    if (item == null) throw new IllegalStateException("Field " + fieldId + " is absent from field configuration " + id);
                    required = item.isRequired();
                    requirements.put(fieldId, required);
                }
                if (required) {
                    count++;
                    Group group = groups.get(pair.project.getId());
                    if (group == null && groups.size() >= MAX_PROJECTS) { truncated = true; continue; }
                    if (group == null) {
                        group = new Group(pair.project, new ArrayList<>());
                        groups.put(pair.project.getId(), group);
                    }
                    group.types.add(new IssueTypeRef(pair.type.getId(), pair.type.getName()));
                }
            }
            List<RequiredProject> projects = new ArrayList<>(groups.size());
            for (Group group : groups.values())
                projects.add(new RequiredProject(group.project.getKey(), group.project.getName(), group.types));
            return new Result(count, projects, truncated);
        }
        private List<Pair> pairs(FieldScreen screen) {
            return createPairs.computeIfAbsent(screen.getId(), ignored -> {
                List<Pair> result = new ArrayList<>();
                for (Project project : usage.projects(screen)) {
                    IssueTypeScreenScheme top = screenCache.computeIfAbsent(project.getId(), key -> screens.getIssueTypeScreenScheme(project));
                    if (top == null) throw new IllegalStateException("No issue type screen scheme for " + project.getKey());
                    List<IssueType> types = new ArrayList<>(project.getIssueTypes());
                    types.sort(Comparator.comparing(IssueType::getId));
                    for (IssueType type : types) if (EffectiveScreens.uses(screen, top.getEffectiveFieldScreenScheme(type), "CREATE"))
                        result.add(new Pair(project, type));
                }
                return result;
            });
        }
    }
    private record Pair(Project project, IssueType type) { }
    private record Group(Project project, List<IssueTypeRef> types) { }
    /** Required CREATE pairs grouped by project in traversal order; at most {@link #MAX_PROJECTS} projects are listed. */
    public static final class Result {
        private final int pairs;
        private final List<RequiredProject> projects;
        private final boolean truncated;
        public Result(int pairs, List<RequiredProject> projects, boolean truncated) {
            this.pairs = pairs; this.projects = Immutables.listOf(projects); this.truncated = truncated;
        }
        public int getPairs() { return pairs; }
        public List<RequiredProject> getProjects() { return projects; }
        public boolean isTruncated() { return truncated; }
    }
}
