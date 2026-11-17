package it.ru.rasulov.customfields;

import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Builders of contexts for the wired tests, which run inside Jira and cannot use the Mockito-based unit fixtures. */
final class Contexts {

    /** All projects, all issue types. */
    static final List<ContextDefinition> GLOBAL = Collections.singletonList(
            new ContextDefinition(null, true, null, true, null, null));

    private Contexts() {
    }

    /** The given projects (keys or names), all issue types. */
    static ContextDefinition inProjects(String... projects) {
        return new ContextDefinition(null, false, Arrays.asList(projects), true, null, null);
    }

    /** The given projects and the given issue types (names or ids). */
    static ContextDefinition inProjects(List<String> projects, List<String> issueTypes) {
        return new ContextDefinition(null, false, projects, false, issueTypes, null);
    }

    /** The given projects, all issue types, with options of their own. */
    static ContextDefinition withOptions(ContextDefinition context, OptionDefinition... options) {
        return new ContextDefinition(context.getName(), context.isAllProjects(), context.getProjects(),
                context.isAllIssueTypes(), context.getIssueTypes(), Arrays.asList(options));
    }
}
