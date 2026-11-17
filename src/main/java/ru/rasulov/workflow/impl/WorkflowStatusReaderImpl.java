package ru.rasulov.workflow.impl;

import com.atlassian.plugin.spring.scanner.annotation.export.ExportAsService;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.workflow.api.WorkflowStatusReader;
import ru.rasulov.workflow.api.dto.StatusListResponse;
import ru.rasulov.workflow.api.dto.StatusLookupResponse;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.List;

@ExportAsService(WorkflowStatusReader.class)
@Named
public final class WorkflowStatusReaderImpl implements WorkflowStatusReader {
    private final AdministratorGuard guard;
    private final StatusCatalog catalog;
    @Inject public WorkflowStatusReaderImpl(AdministratorGuard guard,StatusCatalog catalog){this.guard=guard;this.catalog=catalog;}
    @Override public StatusListResponse listStatuses(String query,Integer limit){guard.requireAdministrator();return catalog.list(query,limit);}
    @Override public StatusLookupResponse lookupStatuses(List<String> names){guard.requireAdministrator();return catalog.lookup(names);}
}
