package ru.rasulov.customfields.impl.screen;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
public class ScreenReachTest {

    @Test
    public void theUsersOfOneScreenAreReadOncePerPlanNoMatterHowOftenAskedFor() {
        FieldScreenSchemeManager screenSchemes = mock(FieldScreenSchemeManager.class);
        IssueTypeScreenSchemeManager issueTypeSchemes = mock(IssueTypeScreenSchemeManager.class);
        FieldScreen screen = mock(FieldScreen.class);
        FieldScreenScheme scheme = mock(FieldScreenScheme.class);
        when(scheme.getId()).thenReturn(1L);
        IssueTypeScreenScheme itss = mock(IssueTypeScreenScheme.class);
        when(itss.getId()).thenReturn(2L);
        GenericValue project = mock(GenericValue.class);
        when(project.getLong("id")).thenReturn(7L);
        when(screenSchemes.getFieldScreenSchemes(screen)).thenReturn(Collections.singletonList(scheme));
        when(issueTypeSchemes.getIssueTypeScreenSchemes(scheme)).thenReturn(Arrays.asList(itss));
        when(issueTypeSchemes.getProjects(itss)).thenReturn((java.util.Collection) Collections.singletonList(project));
        ScreenReach reach = new ScreenReach(screenSchemes, issueTypeSchemes);

        Set<Long> first = reach.projectIdsUsing(screen);
        Set<Long> second = reach.projectIdsUsing(screen);

        assertEquals(new LinkedHashSet<Long>(Collections.singletonList(7L)), first);
        assertEquals(first, second);
        verify(screenSchemes, times(1)).getFieldScreenSchemes(screen);
    }
}
