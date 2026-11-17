package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.project.Project;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse;
import ru.rasulov.screens.api.dto.AssignIssueTypeScreenSchemeResponse.ErrorCode;

/**
 * One planned switch of ONE project from its current Issue Type Screen Scheme to another existing one: the preview and
 * its fingerprint. Only the request and the fingerprint enter the distributed plan store; apply plans again.
 */
final class IssueTypeScreenSchemeAssignmentPlan {
    final Project project;
    final IssueTypeScreenScheme current;
    final IssueTypeScreenScheme target;
    final AssignIssueTypeScreenSchemeResponse response;
    final String fingerprint;

    IssueTypeScreenSchemeAssignmentPlan(Project project, IssueTypeScreenScheme current, IssueTypeScreenScheme target,
            AssignIssueTypeScreenSchemeResponse response, String fingerprint) {
        this.project = project; this.current = current; this.target = target;
        this.response = response; this.fingerprint = fingerprint;
    }

    /** A request that cannot be planned: the code and the reason. */
    static final class Failure extends RuntimeException {
        final ErrorCode code;

        Failure(ErrorCode code, String message) {
            super(message, null, false, false);
            this.code = code;
        }
    }
}
