package ru.rasulov.screens.impl.layout;

import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.issue.fields.screen.issuetype.*;
import com.atlassian.jira.issue.issuetype.IssueType;
import ru.rasulov.common.impl.screen.EffectiveScreens;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.screens.api.dto.layout.*;
import ru.rasulov.screens.impl.ScreenUsage;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.*;

/** Only effective CREATE/EDIT/VIEW use; deliberately does not traverse workflows. */
@Named
public class ScreenLayoutImpactReader {
    private final ScreenUsage usage;
    private final IssueTypeScreenSchemeManager schemes;
    @Inject
    public ScreenLayoutImpactReader(@ComponentImport ProjectManager projects,
            @ComponentImport FieldScreenSchemeManager screens, @ComponentImport IssueTypeScreenSchemeManager schemes) {
        this.usage = new ScreenUsage(projects, screens, schemes); this.schemes = schemes;
    }
    public ScreenImpact read(FieldScreen screen) {
        Set<String> operations = new LinkedHashSet<>();
        List<Project> projects = new ArrayList<>();
        for (Project project : usage.projects(screen)) {
            IssueTypeScreenScheme top = schemes.getIssueTypeScreenScheme(project);
            if (top == null) throw new IllegalStateException("Project has no issue type screen scheme: " + project.getKey());
            boolean used = false;
            for (IssueType type : project.getIssueTypes()) {
                FieldScreenScheme scheme = top.getEffectiveFieldScreenScheme(type);
                for (String operation : List.of("CREATE", "EDIT", "VIEW")) if (EffectiveScreens.uses(screen, scheme, operation)) {
                    operations.add(operation); used = true;
                }
            }
            if (used) projects.add(project);
        }
        List<String> ordered = new ArrayList<>();
        for (String operation : List.of("CREATE", "EDIT", "VIEW")) if (operations.contains(operation)) ordered.add(operation);
        List<LayoutWarning> warnings = new ArrayList<>();
        if (projects.size() > 1) warnings.add(new LayoutWarning("SHARED_SCREEN",
                "This screen is used by " + projects.size() + " projects. No project scope was supplied; review the project names before confirming."));
        boolean defaultScreen = Objects.equals(FieldScreen.DEFAULT_SCREEN_ID, screen.getId());
        if (defaultScreen) warnings.add(new LayoutWarning("DEFAULT_SCREEN", "This is Jira's default screen; changes affect every project using it."));
        return new ScreenImpact(screen.getId(), screen.getName(), ordered, projects.size(), usage.refs(projects), defaultScreen, warnings);
    }
}
