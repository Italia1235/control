package ru.rasulov.common.impl;

import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.security.GlobalPermissionManager;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import ru.rasulov.common.api.exception.AccessException;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * The service is exported to OSGi and can be called without REST, so it checks the caller itself: a logged-in Jira
 * administrator (global ADMINISTER permission).
 */
@Named
public class AdministratorGuard {

    private final JiraAuthenticationContext authenticationContext;
    private final GlobalPermissionManager globalPermissionManager;

    @Inject
    public AdministratorGuard(@ComponentImport JiraAuthenticationContext authenticationContext,
                              @ComponentImport GlobalPermissionManager globalPermissionManager) {
        this.authenticationContext = authenticationContext;
        this.globalPermissionManager = globalPermissionManager;
    }

    /**
     * @return the current user
     * @throws AccessException the user is anonymous or not an administrator
     */
    public ApplicationUser requireAdministrator() {
        ApplicationUser user = authenticationContext.getLoggedInUser();
        if (user == null) {
            throw new AccessException("Authentication is required.", true);
        }
        if (!globalPermissionManager.hasPermission(GlobalPermissionKey.ADMINISTER, user)) {
            throw new AccessException("Jira administrator permission is required.", false);
        }
        return user;
    }
}
