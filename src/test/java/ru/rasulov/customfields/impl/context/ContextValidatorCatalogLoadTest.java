package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.dto.field.context.ContextDefinition;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.customfields.impl.validation.FieldFailureException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.issueType;
import static ru.rasulov.customfields.impl.JiraMocks.project;

/** One validation resolves repeated names against one catalog load, not one load per reference. */
public class ContextValidatorCatalogLoadTest {

    private ProjectManager projectManager;
    private IssueTypeManager issueTypeManager;
    private Project pay;
    private Project shop;
    private Project ops;
    private IssueType bug;
    private IssueType task;
    private ContextValidator validator;

    @Before
    public void setUp() {
        bug = issueType("10004", "Bug", false);
        task = issueType("10002", "Task", false);
        pay = project(1, "PAY", "Payments", bug, task);
        shop = project(2, "SHOP", "Webshop", bug);
        ops = project(3, "OPS", "Operations", bug, task);
        projectManager = mock(ProjectManager.class);
        when(projectManager.getProjects()).thenReturn(Arrays.asList(pay, shop, ops));
        issueTypeManager = mock(IssueTypeManager.class);
        when(issueTypeManager.getIssueTypes()).thenReturn(Arrays.asList(bug, task));
        validator = new ContextValidator(new ProjectResolver(projectManager), new IssueTypeResolver(issueTypeManager));
    }

    @Test
    public void repeatedNamesAcrossContextsLoadEachCatalogOnce() {
        List<ResolvedContext> resolved = validator.validate(Arrays.asList(
                ctx("Payments", "Bug"), ctx("Webshop", "Bug"), ctx("Operations", "Task")));

        assertSame(pay, resolved.get(0).getProjects().get(0));
        assertSame(shop, resolved.get(1).getProjects().get(0));
        assertSame(bug, resolved.get(1).getIssueTypes().get(0));
        assertSame(task, resolved.get(2).getIssueTypes().get(0));
        verify(projectManager, times(1)).getProjects();
        verify(issueTypeManager, times(1)).getIssueTypes();
    }

    @Test
    public void aSecondValidationSeesTheCatalogAsItIsThen() {
        validator.validate(Collections.singletonList(ctx("Payments", "Bug")));
        when(projectManager.getProjects()).thenReturn(Collections.singletonList(shop));

        try {
            validator.validate(Collections.singletonList(ctx("Payments", "Bug")));
            fail("a project that is gone must be unknown in the next request");
        } catch (FieldFailureException expected) {
            assertEquals(ru.rasulov.common.api.dto.field.CustomFieldErrorCode.UNKNOWN_PROJECT, expected.getErrorCode());
        }
    }

    private static ContextDefinition ctx(String project, String issueType) {
        return new ContextDefinition(null, false, Collections.singletonList(project), false,
                Collections.singletonList(issueType), null);
    }
}
