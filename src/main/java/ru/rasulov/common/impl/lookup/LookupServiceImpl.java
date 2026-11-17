package ru.rasulov.common.impl.lookup;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import ru.rasulov.common.api.LookupService;
import ru.rasulov.common.api.dto.IssueTypeListResponse;
import ru.rasulov.common.api.dto.ProjectListResponse;
import ru.rasulov.common.impl.AdministratorGuard;

import javax.inject.Inject;
import javax.inject.Named;

/** Permission check, then the catalogs. */
@ExportAsService(LookupService.class)
@Named
public class LookupServiceImpl implements LookupService {

    private final AdministratorGuard administratorGuard;
    private final ProjectCatalog projectCatalog;
    private final IssueTypeCatalog issueTypeCatalog;

    @Inject
    public LookupServiceImpl(AdministratorGuard administratorGuard, ProjectCatalog projectCatalog,
                             IssueTypeCatalog issueTypeCatalog) {
        this.administratorGuard = administratorGuard;
        this.projectCatalog = projectCatalog;
        this.issueTypeCatalog = issueTypeCatalog;
    }

    @Override
    public ProjectListResponse listProjects(String query, Integer limit) {
        administratorGuard.requireAdministrator();
        return projectCatalog.list(query, limit);
    }

    @Override
    public IssueTypeListResponse listIssueTypes(String query, Integer limit) {
        administratorGuard.requireAdministrator();
        return issueTypeCatalog.list(query, limit);
    }
}
