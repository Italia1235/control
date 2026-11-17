package ru.rasulov.common.api;

import ru.rasulov.common.api.dto.IssueTypeListResponse;
import ru.rasulov.common.api.dto.ProjectListResponse;
import ru.rasulov.common.api.exception.AccessException;

import java.util.List;

/**
 * Transport-independent lookups that turn the user's words into the projects and issue types Jira knows, for every
 * feature that has to name them (contexts of a field, workflow schemes, ...).
 * <p>
 * All operations require the global {@code ADMINISTER} permission of the current user. Limits are in
 * {@link LookupLimits}.
 */
public interface LookupService {

    /**
     * Finds active projects whose key or name contains {@code query}, to turn the user's words into project keys.
     *
     * @param query substring of key or name, ignoring case; {@code null} or blank lists everything
     * @param limit how many to return; {@code null} means {@link LookupLimits#DEFAULT_PROJECT_LIST_SIZE}
     * @throws AccessException the current user is anonymous or not a Jira administrator
     */
    ProjectListResponse listProjects(String query, Integer limit);

    /**
     * Finds issue types whose id or name contains {@code query}, sub-task types included.
     *
     * @param query substring of id or name, ignoring case; {@code null} or blank matches everything
     * @param limit how many to return; {@code null} means {@link LookupLimits#DEFAULT_REFERENCE_LIST_SIZE}
     * @throws AccessException the current user is anonymous or not a Jira administrator
     */
    IssueTypeListResponse listIssueTypes(String query, Integer limit);
}
