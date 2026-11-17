package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.FieldScreen;
import com.atlassian.jira.issue.fields.screen.FieldScreenFactory;
import com.atlassian.jira.issue.fields.screen.FieldScreenManager;
import com.atlassian.jira.issue.fields.screen.FieldScreenScheme;
import com.atlassian.jira.issue.fields.screen.FieldScreenSchemeManager;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenScheme;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeEntity;
import com.atlassian.jira.issue.fields.screen.issuetype.IssueTypeScreenSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import org.junit.Test;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse.CopyView;
import ru.rasulov.screens.api.dto.ScreenCloneScope;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ScreenCloneWriterTest {
    @Test
    public void privateTopRemapsDefaultWithoutChangingItsIdentity() {
        PrivateTopFixture f = new PrivateTopFixture(ScreenCloneScope.ALL);
        ScreenCloneWriter.Written result = f.writer.write(f.plan);
        org.junit.Assert.assertSame(f.top, result.scheme);
        org.junit.Assert.assertSame(f.copy, f.defaultEntity.getFieldScreenScheme());
        verify(f.topManager, org.mockito.Mockito.never()).createIssueTypeScreenScheme(
                org.mockito.ArgumentMatchers.any(IssueTypeScreenScheme.class));
        verify(f.topManager, org.mockito.Mockito.never()).addSchemeAssociation(f.project, f.top);
        org.junit.Assert.assertNull(result.schemeView(f.plan.schemeView).getNewId());
    }

    @Test
    public void privateTopSelectedInheritedTypeDoesNotChangeDefault() {
        PrivateTopFixture f = new PrivateTopFixture(ScreenCloneScope.SELECTED);
        f.writer.write(f.plan);
        org.junit.Assert.assertSame(f.source, f.defaultEntity.getFieldScreenScheme());
        org.junit.Assert.assertEquals("10001", f.addedEntity.getIssueTypeId());
        org.junit.Assert.assertSame(f.copy, f.addedEntity.getFieldScreenScheme());
    }

    @Test
    public void aFailedInPlaceUpdateIsAPartialOutcomeThatNamesTheProjectsOwnSchemeAndPromisesNoRollback() {
        PrivateTopFixture f = new PrivateTopFixture(ScreenCloneScope.ALL);
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(f.topManager)
                .updateIssueTypeScreenSchemeEntity(org.mockito.ArgumentMatchers.any(IssueTypeScreenSchemeEntity.class));
        try {
            f.writer.write(f.plan);
            org.junit.Assert.fail("a failed native update must be reported");
        } catch (ScreenCloneWriter.Interrupted interrupted) {
            org.junit.Assert.assertTrue("the project's own scheme may be partly changed: never a plain failure",
                    interrupted.partial());
            org.junit.Assert.assertTrue(interrupted.explain(), interrupted.explain().contains("partly updated"));
            org.junit.Assert.assertTrue(interrupted.explain(), interrupted.explain().contains("Nothing was rolled back"));
            org.junit.Assert.assertEquals("the copy made before the failure is known", 1, interrupted.written.screenSchemes.size());
        }
    }


    private static final class PrivateTopFixture {
        final Project project = mock(Project.class);
        final IssueTypeScreenScheme top = mock(IssueTypeScreenScheme.class);
        final FieldScreenScheme source = mock(FieldScreenScheme.class);
        final FieldScreenScheme copy = mock(FieldScreenScheme.class);
        final IssueTypeScreenSchemeEntity defaultEntity = mutableEntity();
        final IssueTypeScreenSchemeEntity addedEntity = mutableEntity();
        final FieldScreenSchemeManager fieldManager = mock(FieldScreenSchemeManager.class);
        final IssueTypeScreenSchemeManager topManager = mock(IssueTypeScreenSchemeManager.class);
        final ScreenCloneWriter writer;
        final ScreenClonePlan plan;

        PrivateTopFixture(ScreenCloneScope scope) {
            FieldScreenFactory factory = mock(FieldScreenFactory.class);
            when(source.getId()).thenReturn(20L);
            when(copy.getId()).thenReturn(21L);
            when(top.getId()).thenReturn(10L);
            when(top.getName()).thenReturn("Private top");
            defaultEntity.setFieldScreenScheme(source);
            when(top.getEntities()).thenReturn(Collections.singletonList(defaultEntity));
            IssueType bug = mock(IssueType.class);
            when(bug.getId()).thenReturn("10001");
            when(project.getIssueTypes()).thenReturn(Collections.singletonList(bug));
            when(top.getEffectiveFieldScreenScheme(bug)).thenReturn(source);
            when(factory.createFieldScreenScheme()).thenReturn(copy);
            when(factory.createIssueTypeScreenSchemeEntity()).thenReturn(addedEntity);
            writer = new ScreenCloneWriter(factory, mock(FieldScreenManager.class), fieldManager, topManager);
            Map<FieldScreenScheme, String> copies = new LinkedHashMap<FieldScreenScheme, String>();
            copies.put(source, "ABC: Shared");
            plan = new ScreenClonePlan(project, top, scope, false, null, Collections.singleton("10001"),
                    copies, Collections.<FieldScreen, String>emptyMap(), true,
                    new CopyView(10L, "Private top", null, null,
                            ru.rasulov.screens.api.dto.CloneProjectScreensResponse.Verdict.KEEP, 0,
                            Collections.emptyList()), Collections.emptyList(), Collections.emptyList());
        }
    }

    private static IssueTypeScreenSchemeEntity mutableEntity() {
        IssueTypeScreenSchemeEntity entity = mock(IssueTypeScreenSchemeEntity.class);
        java.util.concurrent.atomic.AtomicReference<FieldScreenScheme> scheme =
                new java.util.concurrent.atomic.AtomicReference<FieldScreenScheme>();
        java.util.concurrent.atomic.AtomicReference<String> type = new java.util.concurrent.atomic.AtomicReference<String>();
        org.mockito.Mockito.doAnswer(call -> { scheme.set(call.getArgument(0)); return null; })
                .when(entity).setFieldScreenScheme(org.mockito.ArgumentMatchers.any(FieldScreenScheme.class));
        org.mockito.Mockito.doAnswer(call -> { type.set(call.getArgument(0)); return null; })
                .when(entity).setIssueTypeId(org.mockito.ArgumentMatchers.nullable(String.class));
        when(entity.getFieldScreenScheme()).thenAnswer(call -> scheme.get());
        when(entity.getIssueTypeId()).thenAnswer(call -> type.get());
        return entity;
    }
}
