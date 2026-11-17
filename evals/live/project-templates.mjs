// create_project is {action:'create', templateKey, ...}. Live setups create one Business project from
// the Project Management template; this is the only place that names its key.
export const PROJECT_MANAGEMENT_TEMPLATE = 'com.atlassian.jira-core-project-templates:jira-core-project-management';

/** Arguments of a setup `create_project` preview: the Project Management template plus the project's own fields. */
export const projectManagement = (project) => ({ action: 'create', templateKey: PROJECT_MANAGEMENT_TEMPLATE, ...project });
