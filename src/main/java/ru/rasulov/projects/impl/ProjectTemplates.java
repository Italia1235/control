package ru.rasulov.projects.impl;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The only templates Control creates projects from (Business and Software; JSM and third-party templates are not
 * supported). A template is supported only when both its key and the type Jira reports for it match this table.
 */
final class ProjectTemplates {
    static final String BUSINESS = "business";
    static final String SOFTWARE = "software";

    static final String PROJECT_MANAGEMENT = "com.atlassian.jira-core-project-templates:jira-core-project-management";
    static final String TASK_MANAGEMENT = "com.atlassian.jira-core-project-templates:jira-core-task-management";
    static final String PROCESS_MANAGEMENT = "com.atlassian.jira-core-project-templates:jira-core-process-management";
    static final String SCRUM = "com.pyxis.greenhopper.jira:gh-scrum-template";
    static final String KANBAN = "com.pyxis.greenhopper.jira:gh-kanban-template";
    static final String BASIC = "com.pyxis.greenhopper.jira:basic-software-development-template";

    private static final Map<String, String> TYPE_BY_KEY;

    static {
        Map<String, String> table = new LinkedHashMap<String, String>();
        table.put(PROJECT_MANAGEMENT, BUSINESS);
        table.put(TASK_MANAGEMENT, BUSINESS);
        table.put(PROCESS_MANAGEMENT, BUSINESS);
        table.put(SCRUM, SOFTWARE);
        table.put(KANBAN, SOFTWARE);
        table.put(BASIC, SOFTWARE);
        TYPE_BY_KEY = Collections.unmodifiableMap(table);
    }

    private ProjectTemplates() {
    }

    /** True only when the key is in the table and Jira's type for that template is the table's type. */
    static boolean supported(String templateKey, String projectTypeKey) {
        return templateKey != null && projectTypeKey != null && projectTypeKey.equals(TYPE_BY_KEY.get(templateKey));
    }
}
