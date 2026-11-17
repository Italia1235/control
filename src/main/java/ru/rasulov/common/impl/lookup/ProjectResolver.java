package ru.rasulov.common.impl.lookup;

import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.LookupLimits;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Finds the project a caller means: by key, else by name, both ignoring case. The key wins if a name equals another
 * project's key. Words that are only part of a name are not guessed: that is what {@code list_projects} is for.
 */
@Named
public class ProjectResolver {

    private final ProjectManager projectManager;

    @Inject
    public ProjectResolver(@ComponentImport ProjectManager projectManager) {
        this.projectManager = projectManager;
    }

    /**
     * @throws LookupFailure blank, unknown or archived project
     */
    public Project resolve(String keyOrName) {
        return session().resolve(keyOrName);
    }

    /**
     * Resolver for ONE request: the project catalog is read at most once, on the first name lookup, and shared by
     * every later lookup of the same session. Never keep a session beyond the request that made it: the next request
     * (and its apply) must read the catalog again.
     */
    public Session session() {
        return new Session();
    }

    public final class Session {

        private List<Project> catalog;

        /**
         * @throws LookupFailure blank, unknown or archived project
         */
        public Project resolve(String keyOrName) {
            return ProjectResolver.this.resolve(keyOrName, this);
        }

        private List<Project> catalog() {
            if (catalog == null) {
                catalog = projectManager.getProjects();
            }
            return catalog;
        }
    }

    private Project resolve(String keyOrName, Session session) {
        String text = keyOrName == null ? "" : keyOrName.trim();
        if (text.isEmpty()) {
            throw new LookupFailure(LookupFailure.Kind.INVALID,
                    "A project must be given by its key or name, not blank. Use list_projects to find it.");
        }
        Project project = projectManager.getProjectByCurrentKeyIgnoreCase(text);
        if (project == null) {
            project = findByName(text, session);
        }
        if (project == null) {
            throw new LookupFailure(LookupFailure.Kind.UNKNOWN_PROJECT,
                    "No project has the key or name '" + text + "'. Use one of suggestions or call list_projects.",
                    suggest(text, session));
        }
        if (project.isArchived()) {
            throw new LookupFailure(LookupFailure.Kind.INVALID,
                    "Project " + project.getKey() + " is archived; nothing can be added to an archived project. "
                            + "Tell the user and ask which project to use instead.");
        }
        return project;
    }

    private Project findByName(String name, Session session) {
        Project match = null;
        List<String> keys = null;
        for (Project candidate : session.catalog()) {
            if (name.equalsIgnoreCase(candidate.getName())) {
                if (match == null) match = candidate;
                else {
                    if (keys == null) {
                        keys = new ArrayList<String>();
                        keys.add(match.getKey());
                    }
                    keys.add(candidate.getKey());
                }
            }
        }
        if (keys != null) {
            Collections.sort(keys);
            throw new LookupFailure(LookupFailure.Kind.INVALID,
                    "Several projects have the name '" + name + "'. Use an exact project key.", keys);
        }
        return match;
    }

    /** Up to {@value LookupLimits#MAX_SUGGESTIONS} keys of active projects whose key or name resembles the text. */
    private List<String> suggest(String text, Session session) {
        String needle = text.toLowerCase(Locale.ROOT);
        List<String> keys = new ArrayList<String>();
        for (Project candidate : session.catalog()) {
            if (candidate.isArchived()) {
                continue;
            }
            String key = candidate.getKey().toLowerCase(Locale.ROOT);
            String name = candidate.getName() == null ? "" : candidate.getName().toLowerCase(Locale.ROOT);
            if (key.contains(needle) || name.contains(needle) || needle.contains(key)) {
                keys.add(candidate.getKey());
            }
        }
        Collections.sort(keys);
        return keys.size() > LookupLimits.MAX_SUGGESTIONS
                ? new ArrayList<String>(keys.subList(0, LookupLimits.MAX_SUGGESTIONS)) : keys;
    }
}
