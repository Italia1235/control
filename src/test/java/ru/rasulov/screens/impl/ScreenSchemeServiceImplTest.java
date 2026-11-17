package ru.rasulov.screens.impl;

import com.atlassian.jira.issue.fields.screen.*;
import com.atlassian.jira.issue.fields.screen.issuetype.*;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Test;
import org.ofbiz.core.entity.GenericValue;
import ru.rasulov.common.impl.*;
import ru.rasulov.common.impl.lookup.IssueTypeResolver;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.screens.api.dto.*;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual clone planner/writer over mutable native associations, without Jira. */
public class ScreenSchemeServiceImplTest {
    @Test
    public void applyChangesOnlyTheProjectWhosePlanWasChecked() {
        Fixture f = new Fixture();
        when(f.projects.resolve("ABC")).thenReturn(f.alpha, f.alpha, f.beta);
        CloneProjectScreensResponse preview = f.preview();
        assertEquals(CloneProjectScreensResponse.Status.WOULD_CLONE, preview.getStatus());
        assertEquals(CloneProjectScreensResponse.Status.CLONED, f.apply(preview).getStatus());
        assertSame("unchecked project must remain untouched", f.source, f.associations.get(f.beta));
        assertNotSame("checked project must receive the copy", f.source, f.associations.get(f.alpha));
        assertSame(f.first, f.associations.get(f.alpha).getEntity(null).getFieldScreenScheme());
    }

    @Test
    public void aFailureWhileSwitchingTheProjectReportsTheCreatedCopyInsteadOfLosingIt() {
        Fixture f = new Fixture();
        CloneProjectScreensResponse preview = f.preview();
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(f.tops).addSchemeAssociation(any(Project.class), any());

        CloneProjectScreensResponse response = f.apply(preview);

        assertEquals(CloneProjectScreensResponse.Status.PARTIALLY_CLONED, response.getStatus());
        assertEquals(CloneProjectScreensResponse.ErrorCode.JIRA_ERROR, response.getErrorCode());
        assertEquals("the copy that exists is named by id", Long.valueOf(101), response.getIssueTypeScreenScheme().getNewId());
        assertSame("the project was not switched", f.source, f.associations.get(f.alpha));
        assertEquals("the created scheme really exists", 2, f.catalog.size());
    }

    @Test
    public void aFailedFirstWriteIsNotClaimedAsNothingChanged() {
        Fixture f = new Fixture();
        CloneProjectScreensResponse preview = f.preview();
        org.mockito.Mockito.doThrow(new IllegalStateException("db down")).when(f.tops).createIssueTypeScreenScheme(any());

        CloneProjectScreensResponse response = f.apply(preview);

        assertEquals(CloneProjectScreensResponse.Status.FAILED, response.getStatus());
        assertEquals(CloneProjectScreensResponse.ErrorCode.JIRA_ERROR, response.getErrorCode());
        assertTrue(response.getMessage(), response.getMessage().toLowerCase().contains("not confirmed"));
        assertSame(f.source, f.associations.get(f.alpha));
    }

    static final class Fixture {
        final FieldScreenFactory factory = mock(FieldScreenFactory.class);
        final FieldScreenManager screens = mock(FieldScreenManager.class);
        final FieldScreenSchemeManager fields = mock(FieldScreenSchemeManager.class);
        final IssueTypeScreenSchemeManager tops = mock(IssueTypeScreenSchemeManager.class);
        final ProjectManager projectManager = mock(ProjectManager.class);
        final ProjectResolver projects = mock(ProjectResolver.class);
        final Project alpha = project(1L, "ABC");
        final Project beta = project(2L, "BBB");
        final FieldScreenScheme first = field(20L, "First");
        final FieldScreenScheme second = field(21L, "Second");
        final IssueTypeScreenScheme source = top(10L, "Shared");
        final Map<Project, IssueTypeScreenScheme> associations = new HashMap<Project, IssueTypeScreenScheme>();
        final Map<Long, IssueTypeScreenScheme> catalog = new LinkedHashMap<Long, IssueTypeScreenScheme>();
        final AtomicLong ids = new AtomicLong(100);
        final ScreenSchemeServiceImpl service;

        @SuppressWarnings("unchecked")
        Fixture() {
            IssueType bug = mock(IssueType.class);
            when(bug.getId()).thenReturn("bug"); when(bug.getName()).thenReturn("Bug");
            when(alpha.getIssueTypes()).thenReturn(Collections.singletonList(bug));
            when(beta.getIssueTypes()).thenReturn(Collections.singletonList(bug));
            source.getEntities().add(entity(source, null, first));
            source.getEntities().add(entity(source, "bug", second));
            associations.put(alpha, source); associations.put(beta, source); catalog.put(10L, source);
            when(projects.resolve("ABC")).thenReturn(alpha);
            when(projectManager.getProjectObj(1L)).thenReturn(alpha);
            when(projectManager.getProjectObj(2L)).thenReturn(beta);
            when(tops.getIssueTypeScreenScheme(any(Project.class))).thenAnswer(c -> associations.get(c.getArgument(0)));
            when(tops.getIssueTypeScreenSchemes()).thenAnswer(c -> catalog.values());
            List<GenericValue> projectValues = Arrays.asList(value(1L), value(2L));
            when(tops.getProjects(source)).thenReturn(projectValues);
            when(fields.getFieldScreenSchemes()).thenReturn(Arrays.asList(first, second));
            when(factory.createIssueTypeScreenScheme()).thenAnswer(c -> top(null, null));
            when(factory.createIssueTypeScreenSchemeEntity()).thenAnswer(c -> entity(null, null, null));
            doAnswer(c -> { IssueTypeScreenScheme v = c.getArgument(0); v.setId(ids.incrementAndGet()); catalog.put(v.getId(), v); return null; })
                    .when(tops).createIssueTypeScreenScheme(any());
            doAnswer(c -> { IssueTypeScreenSchemeEntity v = c.getArgument(0); v.getIssueTypeScreenScheme().getEntities().add(v); return null; })
                    .when(tops).createIssueTypeScreenSchemeEntity(any());
            doAnswer(c -> { associations.put(c.getArgument(0), c.getArgument(1)); return null; }).when(tops).addSchemeAssociation(any(Project.class), any());
            AdministratorGuard guard = mock(AdministratorGuard.class);
            ApplicationUser admin = mock(ApplicationUser.class); when(admin.getKey()).thenReturn("admin");
            when(guard.requireAdministrator()).thenReturn(admin);
            NamedLock lock = mock(NamedLock.class);
            when(lock.run(anyString(), anyString(), any())).thenAnswer(c -> ((Supplier<Object>) c.getArgument(2)).get());
            ActionPlanStore plans = mock(ActionPlanStore.class);
            Map<String, ActionPlanStore.Plan> saved = new HashMap<String, ActionPlanStore.Plan>();
            when(plans.issue(anyString(), anyString(), anyString(), anyString())).thenAnswer(c -> {
                ActionPlanStore.Plan p = new ActionPlanStore.Plan("plan", c.getArgument(0), c.getArgument(1), c.getArgument(2), c.getArgument(3), Long.MAX_VALUE);
                saved.put(p.getId(), p); return p;
            });
            when(plans.find(anyString(), anyString(), anyString())).thenAnswer(c -> saved.get(c.getArgument(2)));
            doAnswer(c -> { saved.remove(c.getArgument(0)); return null; }).when(plans).remove(anyString());
            service = new ScreenSchemeServiceImpl(guard, lock, projects, projectManager, fields, tops,
                    new ScreenClonePlanner(mock(IssueTypeResolver.class), projectManager, screens, fields, tops),
                    new ScreenCloneWriter(factory, screens, fields, tops),
                    new ru.rasulov.common.impl.PreviewPlanExecutor(plans, new ActionPlanCodec()), new ActionPlanCodec());
        }

        CloneProjectScreensResponse preview() {
            return service.cloneProjectScreens(new CloneProjectScreensRequest(true, "ABC", ScreenCloneScope.SCHEME_ONLY, null, false));
        }
        CloneProjectScreensResponse apply(CloneProjectScreensResponse preview) {
            return service.cloneProjectScreens(new CloneProjectScreensRequest(false, null, null, null, null, preview.getPlanId()));
        }
    }

    private static Project project(long id, String key) {
        Project p = mock(Project.class); when(p.getId()).thenReturn(id); when(p.getKey()).thenReturn(key); when(p.getName()).thenReturn(key); return p;
    }
    private static GenericValue value(long id) {
        GenericValue v = mock(GenericValue.class); when(v.getLong("id")).thenReturn(id); return v;
    }
    private static FieldScreenScheme field(long id, String name) {
        FieldScreenScheme v = mock(FieldScreenScheme.class); when(v.getId()).thenReturn(id); when(v.getName()).thenReturn(name); return v;
    }
    private static IssueTypeScreenScheme top(Long initialId, String initialName) {
        IssueTypeScreenScheme v = mock(IssueTypeScreenScheme.class);
        AtomicReference<Long> id = new AtomicReference<Long>(initialId);
        AtomicReference<String> name = new AtomicReference<String>(initialName);
        AtomicReference<String> description = new AtomicReference<String>();
        List<IssueTypeScreenSchemeEntity> entities = new ArrayList<IssueTypeScreenSchemeEntity>();
        when(v.getId()).thenAnswer(c -> id.get()); doAnswer(c -> { id.set(c.getArgument(0)); return null; }).when(v).setId(any());
        when(v.getName()).thenAnswer(c -> name.get()); doAnswer(c -> { name.set(c.getArgument(0)); return null; }).when(v).setName(any());
        when(v.getDescription()).thenAnswer(c -> description.get()); doAnswer(c -> { description.set(c.getArgument(0)); return null; }).when(v).setDescription(any());
        when(v.getEntities()).thenReturn(entities);
        when(v.getEntity(any())).thenAnswer(c -> { for (IssueTypeScreenSchemeEntity e : entities) if (Objects.equals(e.getIssueTypeId(), c.getArgument(0))) return e; return null; });
        when(v.getEffectiveFieldScreenScheme(any())).thenAnswer(c -> {
            IssueType type = c.getArgument(0); IssueTypeScreenSchemeEntity e = v.getEntity(type.getId());
            if (e == null) e = v.getEntity(null); return e == null ? null : e.getFieldScreenScheme();
        });
        return v;
    }
    private static IssueTypeScreenSchemeEntity entity(IssueTypeScreenScheme initialTop, String initialType, FieldScreenScheme initialField) {
        IssueTypeScreenSchemeEntity v = mock(IssueTypeScreenSchemeEntity.class);
        AtomicReference<IssueTypeScreenScheme> top = new AtomicReference<IssueTypeScreenScheme>(initialTop);
        AtomicReference<String> type = new AtomicReference<String>(initialType);
        AtomicReference<FieldScreenScheme> field = new AtomicReference<FieldScreenScheme>(initialField);
        when(v.getIssueTypeScreenScheme()).thenAnswer(c -> top.get()); doAnswer(c -> { top.set(c.getArgument(0)); return null; }).when(v).setIssueTypeScreenScheme(any());
        when(v.getIssueTypeId()).thenAnswer(c -> type.get()); doAnswer(c -> { type.set(c.getArgument(0)); return null; }).when(v).setIssueTypeId(any());
        when(v.getFieldScreenScheme()).thenAnswer(c -> field.get()); doAnswer(c -> { field.set(c.getArgument(0)); return null; }).when(v).setFieldScreenScheme(any());
        return v;
    }
}
