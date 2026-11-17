package ru.rasulov.common.impl.lookup;

import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.permission.GlobalPermissionKey;
import com.atlassian.jira.project.Project;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.LookupLimits;
import ru.rasulov.common.api.dto.IssueTypeListResponse;
import ru.rasulov.common.api.dto.IssueTypeResponse;
import ru.rasulov.common.api.dto.ProjectListResponse;
import ru.rasulov.common.api.exception.AccessException;
import ru.rasulov.customfields.impl.JiraMocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.issueType;
import static ru.rasulov.customfields.impl.JiraMocks.project;

/** What the caller sees when it looks up projects and issue types to name them in a context. */
public class LookupServiceTest {

    private JiraMocks.Wiring w;

    @Before
    public void setUp() {
        w = new JiraMocks.Wiring();
    }

    // --- projects ----------------------------------------------------------------------------------------------

    @Test
    public void projectsAreFilteredByKeyOrNameIgnoringCaseAndSortedByKey() {
        IssueType bug = issueType("10004", "Bug", false);
        Project shop = project(2, "SHOP", "Webshop", bug);
        Project pay = project(1, "PAY", "Payments", bug, issueType("10002", "Task", false));
        Project hr = project(3, "HR", "People and payroll");
        when(w.projectManager.getProjects()).thenReturn(Arrays.asList(shop, pay, hr));

        ProjectListResponse byName = w.lookup.listProjects("pay", null);
        ProjectListResponse exact = w.lookup.listProjects("Payments", null);
        ProjectListResponse all = w.lookup.listProjects(null, null);

        assertEquals(2, byName.getTotal());
        assertEquals("HR", byName.getProjects().get(0).getKey());
        assertEquals("PAY", byName.getProjects().get(1).getKey());
        assertFalse(byName.getProjects().get(1).isIssueTypesIncluded());
        assertEquals(2, byName.getProjects().get(1).getIssueTypesTotal());
        assertTrue(byName.getProjects().get(1).getIssueTypes().isEmpty());
        assertTrue(exact.getProjects().get(0).isIssueTypesIncluded());
        assertEquals(Arrays.asList("Bug", "Task"), exact.getProjects().get(0).getIssueTypes());
        assertEquals(Arrays.asList("HR", "PAY", "SHOP"), keys(all));
    }

    @Test
    public void archivedProjectsAreNotListed() {
        Project archived = project(3, "OLD", "Old system");
        when(archived.isArchived()).thenReturn(true);
        Project active = project(1, "PAY", "Payments");
        when(w.projectManager.getProjects()).thenReturn(Arrays.asList(active, archived));

        ProjectListResponse all = w.lookup.listProjects(null, null);

        assertEquals(1, all.getTotal());
        assertEquals(Arrays.asList("PAY"), keys(all));
    }

    @Test
    public void theListIsCutToTheLimitButTotalStillCountsEveryMatch() {
        List<Project> many = new ArrayList<Project>();
        for (int i = 0; i < 150; i++) {
            many.add(project(i, String.format("P%03d", i), "Project " + i));
        }
        when(w.projectManager.getProjects()).thenReturn(many);

        ProjectListResponse byDefault = w.lookup.listProjects(null, null);
        ProjectListResponse three = w.lookup.listProjects(null, 3);
        ProjectListResponse tooMany = w.lookup.listProjects(null, 100000);
        ProjectListResponse nonsense = w.lookup.listProjects(null, 0);

        assertEquals(150, byDefault.getTotal());
        assertEquals(LookupLimits.DEFAULT_PROJECT_LIST_SIZE, byDefault.getProjects().size());
        assertEquals(3, three.getProjects().size());
        assertEquals(LookupLimits.MAX_PROJECT_LIST_SIZE, tooMany.getProjects().size());
        assertEquals(LookupLimits.DEFAULT_PROJECT_LIST_SIZE, nonsense.getProjects().size());
    }

    // --- issue types -------------------------------------------------------------------------------------------

    @Test
    public void issueTypesAreSortedByNameAndSubtasksAreMarked() {
        char[] longText = new char[500];
        Arrays.fill(longText, 'x');
        IssueType task = issueType("10002", "task", false);
        when(task.getDescription()).thenReturn(new String(longText));
        IssueType subtask = issueType("10003", "Sub-task", true);
        IssueType bug = issueType("10004", "Bug", false);
        when(w.issueTypeManager.getIssueTypes()).thenReturn(Arrays.asList(task, subtask, bug));

        IssueTypeListResponse response = w.lookup.listIssueTypes(null, null);
        List<IssueTypeResponse> types = response.getIssueTypes();

        assertEquals(3, response.getTotal());
        assertEquals("Bug", types.get(0).getName());
        assertEquals("Sub-task", types.get(1).getName());
        assertTrue(types.get(1).isSubtask());
        assertEquals("task", types.get(2).getName());
        assertFalse(types.get(2).isSubtask());
        assertEquals(200, types.get(2).getDescription().length());
        assertEquals("", types.get(0).getDescription());
    }

    @Test
    public void issueTypeSearchIsFilteredAndBoundedButReportsEveryMatch() {
        List<IssueType> many = new ArrayList<IssueType>();
        for (int i = 0; i < 300; i++) {
            many.add(issueType(String.valueOf(10000 + i), String.format("Type %03d", i), false));
        }
        when(w.issueTypeManager.getIssueTypes()).thenReturn(many);

        IssueTypeListResponse byDefault = w.lookup.listIssueTypes(null, null);
        IssueTypeListResponse filtered = w.lookup.listIssueTypes("type 29", 3);
        IssueTypeListResponse tooMany = w.lookup.listIssueTypes(null, 100000);

        assertEquals(300, byDefault.getTotal());
        assertEquals(LookupLimits.DEFAULT_REFERENCE_LIST_SIZE, byDefault.getIssueTypes().size());
        assertEquals(10, filtered.getTotal());
        assertEquals(3, filtered.getIssueTypes().size());
        assertEquals("Type 290", filtered.getIssueTypes().get(0).getName());
        assertEquals(LookupLimits.MAX_REFERENCE_LIST_SIZE, tooMany.getIssueTypes().size());
    }

    // --- who may look ------------------------------------------------------------------------------------------

    @Test
    public void onlyAdministratorsMayLookUpProjectsAndIssueTypes() {
        when(w.permissions.hasPermission(GlobalPermissionKey.ADMINISTER, w.admin)).thenReturn(false);

        try {
            w.lookup.listProjects(null, null);
            fail("expected AccessException");
        } catch (AccessException expected) {
            assertFalse(expected.isAnonymous());
        }
        try {
            w.lookup.listIssueTypes(null, null);
            fail("expected AccessException");
        } catch (AccessException expected) {
            assertFalse(expected.isAnonymous());
        }
    }

    private static List<String> keys(ProjectListResponse response) {
        List<String> keys = new ArrayList<String>();
        for (ru.rasulov.common.api.dto.ProjectResponse project : response.getProjects()) {
            keys.add(project.getKey());
        }
        return keys;
    }
}
