package ru.rasulov.common.impl.lookup;

import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static ru.rasulov.customfields.impl.JiraMocks.project;

public class ProjectResolverTest {
    @Test
    public void duplicateExactNamesAreRejectedWithCanonicalKeysRegardlessOfCatalogOrder() {
        ProjectManager manager = mock(ProjectManager.class);
        Project first = project(1, "ONE", "Shared name");
        Project second = project(2, "TWO", "SHARED NAME");
        ProjectResolver resolver = new ProjectResolver(manager);
        for (java.util.List<Project> catalog : Arrays.asList(Arrays.asList(first, second), Arrays.asList(second, first))) {
            when(manager.getProjects()).thenReturn(catalog);
            try {
                resolver.resolve(" shared name ");
                fail("An ambiguous name must not select an arbitrary project");
            } catch (LookupFailure expected) {
                assertEquals(LookupFailure.Kind.INVALID, expected.getKind());
                assertEquals(Arrays.asList("ONE", "TWO"), expected.getSuggestions());
            }
        }
    }

    @Test
    public void currentKeyStillWinsOverAnotherProjectsNameWithoutReadingTheCatalog() {
        ProjectManager manager = mock(ProjectManager.class);
        Project byKey = project(1, "ONE", "First");
        Project byName = project(2, "TWO", "one");
        when(manager.getProjects()).thenReturn(Arrays.asList(byName, byKey));
        when(manager.getProjectByCurrentKeyIgnoreCase("one")).thenReturn(byKey);

        assertSame(byKey, new ProjectResolver(manager).resolve(" one "));
        verify(manager, never()).getProjects();
    }
}
