package ru.rasulov.customfields.impl.configuration;

import com.atlassian.jira.issue.fields.layout.field.*;
import com.atlassian.jira.issue.fields.OrderableField;
import com.atlassian.jira.project.Project;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.undo.*;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsRequest;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse;
import ru.rasulov.customfields.api.dto.configuration.CloneProjectFieldConfigurationsResponse.CopyView;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Forward clone writes retain persistence validation without creating Undo steps. */
public class FieldConfigurationCloneWriterTest {
    @Test public void clonePersistsCopiesMappingsAndAssignmentWithoutUndoSteps() throws Exception {
        try (Model model = new Model()) {
            model.cloneConfiguration();
            assertEquals(Long.valueOf(120L), model.assignment.get());
            assertEquals(Long.valueOf(110L), model.mapping.getFieldLayoutId());
            assertEquals(0, model.fixture.entityManager.find(JournalStep.class).length);
            JournalOperation[] operations = model.fixture.entityManager.find(JournalOperation.class);
            assertEquals("COMPLETED", model.fixture.reopen().getOperation(operations[0].getOperationKey(), "admin").get("status"));
            verify(model.manager).storeAndReturnEditableFieldLayout(model.copy);
            verify(model.manager).updateFieldLayoutSchemeEntity(model.mapping);
        }
    }

    @Test public void foreignAssignmentObservedAfterWriteIsPreservedAndRejected() {
        try (Model model = new Model()) {
            doAnswer(call -> { model.assignment.set(200L); return null; })
                    .when(model.manager).addSchemeAssociation(eq(model.project), anyLong());
            try { model.cloneConfiguration(); fail("Foreign assignment must fail"); }
            catch (IllegalStateException expected) { }
            assertEquals(Long.valueOf(200L), model.assignment.get());
            verify(model.manager, never()).removeFieldLayoutScheme(any());
            verify(model.manager, never()).deleteFieldLayout(any());
        }
    }

    @Test public void associationWithoutPersistedEffectFailsWithoutCompensatingForwardWrites() {
        try (Model model = new Model()) {
            doNothing().when(model.manager).addSchemeAssociation(eq(model.project), anyLong());
            try { model.cloneConfiguration(); fail("Unpersisted assignment must fail"); }
            catch (IllegalStateException expected) {
                assertEquals("Jira did not persist the requested project field configuration assignment.", expected.getMessage());
            }
            assertEquals(Long.valueOf(20L), model.assignment.get());
            verify(model.manager, never()).removeFieldLayoutScheme(any());
            verify(model.manager, never()).deleteFieldLayout(any());
        }
    }

    @Test public void applyCopiesOnlyTheMappingResolvedForTheCheckedPlan() {
        try (Model model = new Model()) {
            FieldLayout replacementSource = mock(FieldLayout.class);
            EditableFieldLayout replacementCopy = mock(EditableFieldLayout.class);
            when(replacementSource.getId()).thenReturn(11L);
            when(replacementCopy.getId()).thenReturn(111L);
            when(replacementCopy.getName()).thenReturn("PAY: Replacement fields");
            when(model.factory.copyOf(replacementSource, "PAY: Replacement fields")).thenReturn(replacementCopy);
            when(model.manager.storeAndReturnEditableFieldLayout(replacementCopy)).thenReturn(replacementCopy);
            when(model.manager.getEditableFieldLayout(111L)).thenReturn(replacementCopy);
            Map<FieldLayout, String> names = new LinkedHashMap<>();
            names.put(replacementSource, "PAY: Replacement fields");
            FieldConfigurationClonePlan replacement = new FieldConfigurationClonePlan(model.project, null, 21L,
                    "Description", model.plan.newSchemeName,
                    Collections.singletonList(new FieldConfigurationClonePlan.Mapping(null, replacementSource)),
                    names, true, new CopyView(21L, "Replacement scheme", null, model.plan.newSchemeName,
                    0, Collections.emptyList()),
                    Collections.singletonList(new CopyView(11L, "Replacement fields", null,
                            "PAY: Replacement fields", 0, Collections.emptyList())));
            FieldConfigurationClonePlanner planner = mock(FieldConfigurationClonePlanner.class);
            when(planner.plan(model.project)).thenReturn(model.plan, model.plan, replacement);
            ProjectResolver resolver = mock(ProjectResolver.class);
            when(resolver.resolve("PAY")).thenReturn(model.project);
            FieldConfigurationCloneServiceImpl service = new FieldConfigurationCloneServiceImpl(
                    model.fixture.guard, model.fixture.locks, resolver, planner,
                    new FieldConfigurationCloneWriter(model.manager, model.factory, model.fixture.journal),
                    new ru.rasulov.common.impl.PreviewPlanExecutor(model.fixture.plans, new ActionPlanCodec()), new ActionPlanCodec(), model.fixture.journal);

            CloneProjectFieldConfigurationsResponse preview = service.cloneProjectFieldConfigurations(
                    new CloneProjectFieldConfigurationsRequest(true, "PAY"));
            CloneProjectFieldConfigurationsResponse applied = service.cloneProjectFieldConfigurations(
                    new CloneProjectFieldConfigurationsRequest(false, null, preview.getPlanId()));

            assertEquals(CloneProjectFieldConfigurationsResponse.Status.CLONED, applied.getStatus());
            assertEquals("native mapping must use the copy of checked layout 10, not replacement layout 11",
                    Long.valueOf(110L), model.mapping.getFieldLayoutId());
            assertEquals(Long.valueOf(10L), applied.getFieldConfigurations().get(0).getSourceId());
        }
    }

    @Test public void applyRejectsChangedRendererOrFlagsWithoutCopying() {
        for (String change : Arrays.asList("renderer", "required", "hidden", "itemDescription",
                "layoutDescription", "items")) {
            try (Model model = new Model()) {
                FieldLayoutItem item = item("description");
                when(model.source.getFieldLayoutItems()).thenReturn(Collections.singletonList(item));
                FieldConfigurationCloneServiceImpl service = service(model, model.plan, model.plan);
                CloneProjectFieldConfigurationsResponse preview = service.cloneProjectFieldConfigurations(
                        new CloneProjectFieldConfigurationsRequest(true, "PAY"));
                assertEquals(CloneProjectFieldConfigurationsResponse.Status.WOULD_CLONE, preview.getStatus());
                switch (change) {
                    case "renderer": when(item.getRendererType()).thenReturn("text-renderer"); break;
                    case "required": when(item.isRequired()).thenReturn(true); break;
                    case "hidden": when(item.isHidden()).thenReturn(true); break;
                    case "itemDescription": when(item.getRawFieldDescription()).thenReturn("Changed"); break;
                    case "layoutDescription": when(model.source.getDescription()).thenReturn("Changed"); break;
                    case "items":
                        FieldLayoutItem added = item("summary");
                        when(model.source.getFieldLayoutItems()).thenReturn(Arrays.asList(item, added));
                        break;
                    default: throw new AssertionError(change);
                }
                assertStaleWithoutCopies(model, service, preview, change);
            }
        }
    }

    @Test public void applyRejectsMappingSwapBetweenTheSameLayoutsWithoutCopying() {
        try (Model model = new Model()) {
            FieldLayout second = mock(FieldLayout.class);
            when(second.getId()).thenReturn(11L);
            EditableFieldLayout secondCopy = mock(EditableFieldLayout.class);
            when(secondCopy.getId()).thenReturn(111L);
            when(secondCopy.getName()).thenReturn("PAY: Second fields");
            when(model.factory.copyOf(second, "PAY: Second fields")).thenReturn(secondCopy);
            when(model.manager.storeAndReturnEditableFieldLayout(secondCopy)).thenReturn(secondCopy);
            when(model.manager.getEditableFieldLayout(111L)).thenReturn(secondCopy);
            FieldLayoutSchemeEntity explicitMapping = mock(FieldLayoutSchemeEntity.class);
            AtomicReference<Long> explicitLayoutId = new AtomicReference<>(999L);
            when(model.scheme.getEntity("10001")).thenReturn(explicitMapping);
            when(explicitMapping.getFieldLayoutId()).thenAnswer(call -> explicitLayoutId.get());
            doAnswer(call -> { explicitLayoutId.set(call.getArgument(0)); return null; })
                    .when(explicitMapping).setFieldLayoutId(anyLong());
            Map<FieldLayout, String> names = new LinkedHashMap<>(model.plan.layouts);
            names.put(second, "PAY: Second fields");
            List<CopyView> views = Arrays.asList(model.plan.layoutViews.get(0),
                    new CopyView(11L, "Second fields", null, "PAY: Second fields", 0, Collections.emptyList()));
            FieldConfigurationClonePlan checked = mappedPlan(model, names, views, Arrays.asList(
                    new FieldConfigurationClonePlan.Mapping(null, model.source),
                    new FieldConfigurationClonePlan.Mapping("10001", second)), model.plan.sourceDescription);
            FieldConfigurationClonePlan changed = mappedPlan(model, names, views, Arrays.asList(
                    new FieldConfigurationClonePlan.Mapping(null, second),
                    new FieldConfigurationClonePlan.Mapping("10001", model.source)), model.plan.sourceDescription);
            FieldConfigurationCloneServiceImpl service = service(model, checked, changed);
            CloneProjectFieldConfigurationsResponse preview = service.cloneProjectFieldConfigurations(
                    new CloneProjectFieldConfigurationsRequest(true, "PAY"));
            assertStaleWithoutCopies(model, service, preview, "mapping swap");
        }
    }

    @Test public void applyRejectsChangedSchemeDescriptionWithoutCopying() {
        try (Model model = new Model()) {
            FieldConfigurationClonePlan changed = mappedPlan(model, model.plan.layouts, model.plan.layoutViews,
                    model.plan.mappings, "Changed scheme description");
            when(model.manager.createFieldLayoutScheme(changed.newSchemeName, changed.sourceDescription))
                    .thenReturn(model.scheme);
            FieldConfigurationCloneServiceImpl service = service(model, model.plan, changed);
            CloneProjectFieldConfigurationsResponse preview = service.cloneProjectFieldConfigurations(
                    new CloneProjectFieldConfigurationsRequest(true, "PAY"));
            assertStaleWithoutCopies(model, service, preview, "scheme description");
        }
    }

    @Test public void applyAcceptsUnchangedItemsInDifferentIterationOrder() {
        try (Model model = new Model()) {
            FieldLayoutItem description = item("description"), summary = item("summary");
            when(description.isRequired()).thenReturn(true);
            when(summary.isHidden()).thenReturn(true);
            when(model.source.getFieldLayoutItems()).thenReturn(Arrays.asList(description, summary));
            FieldConfigurationCloneServiceImpl service = service(model, model.plan, model.plan);
            CloneProjectFieldConfigurationsResponse preview = service.cloneProjectFieldConfigurations(
                    new CloneProjectFieldConfigurationsRequest(true, "PAY"));
            when(model.source.getFieldLayoutItems()).thenReturn(Arrays.asList(summary, description));

            CloneProjectFieldConfigurationsResponse applied = service.cloneProjectFieldConfigurations(
                    new CloneProjectFieldConfigurationsRequest(false, null, preview.getPlanId()));

            assertEquals(CloneProjectFieldConfigurationsResponse.Status.CLONED, applied.getStatus());
            assertEquals(Long.valueOf(120L), model.assignment.get());
            assertEquals(Long.valueOf(110L), model.mapping.getFieldLayoutId());
        }
    }

    private static FieldLayoutItem item(String fieldId) {
        OrderableField field = mock(OrderableField.class);
        when(field.getId()).thenReturn(fieldId);
        FieldLayoutItem item = mock(FieldLayoutItem.class);
        when(item.getOrderableField()).thenReturn(field);
        when(item.getRendererType()).thenReturn("wiki-renderer");
        when(item.getRawFieldDescription()).thenReturn("Original description");
        return item;
    }

    private static FieldConfigurationClonePlan mappedPlan(Model model, Map<FieldLayout, String> names,
            List<CopyView> views, List<FieldConfigurationClonePlan.Mapping> mappings, String description) {
        return new FieldConfigurationClonePlan(model.project, null, model.plan.sourceSchemeId, description,
                model.plan.newSchemeName, mappings, names, true, model.plan.schemeView, views);
    }

    private static FieldConfigurationCloneServiceImpl service(Model model, FieldConfigurationClonePlan preview,
                                                               FieldConfigurationClonePlan apply) {
        ProjectResolver resolver = mock(ProjectResolver.class);
        when(resolver.resolve("PAY")).thenReturn(model.project);
        FieldConfigurationClonePlanner planner = mock(FieldConfigurationClonePlanner.class);
        when(planner.plan(model.project)).thenReturn(preview, apply);
        return new FieldConfigurationCloneServiceImpl(model.fixture.guard, model.fixture.locks, resolver, planner,
                new FieldConfigurationCloneWriter(model.manager, model.factory, model.fixture.journal),
                new ru.rasulov.common.impl.PreviewPlanExecutor(model.fixture.plans, new ActionPlanCodec()), new ActionPlanCodec(), model.fixture.journal);
    }

    private static void assertStaleWithoutCopies(Model model, FieldConfigurationCloneServiceImpl service,
                                                CloneProjectFieldConfigurationsResponse preview, String change) {
        CloneProjectFieldConfigurationsResponse applied = service.cloneProjectFieldConfigurations(
                new CloneProjectFieldConfigurationsRequest(false, null, preview.getPlanId()));
        assertEquals(change, CloneProjectFieldConfigurationsResponse.ErrorCode.PREVIEW_STALE, applied.getErrorCode());
        assertEquals(change, Long.valueOf(20L), model.assignment.get());
        assertEquals(change, Long.valueOf(999L), model.mapping.getFieldLayoutId());
        verify(model.factory, never()).copyOf(any(), anyString());
        verify(model.manager, never()).storeAndReturnEditableFieldLayout(any());
        verify(model.manager, never()).createFieldLayoutScheme(anyString(), any());
        verify(model.manager, never()).addSchemeAssociation(any(Project.class), anyLong());
    }

    private static final class Model implements AutoCloseable {
        final FieldLayoutManager manager = mock(FieldLayoutManager.class);
        final FieldLayoutCopyFactory factory = mock(FieldLayoutCopyFactory.class);
        final Project project = mock(Project.class);
        final FieldLayout source = mock(FieldLayout.class);
        final EditableFieldLayout copy = mock(EditableFieldLayout.class);
        final FieldLayoutScheme scheme = mock(FieldLayoutScheme.class);
        final FieldLayoutSchemeEntity mapping = mock(FieldLayoutSchemeEntity.class);
        final AtomicReference<Long> assignment = new AtomicReference<>(20L);
        final AtomicReference<Long> mappedLayout = new AtomicReference<>(999L);
        final UndoTestFixture fixture = UndoTestFixture.create("admin");
        final FieldConfigurationClonePlan plan;

        Model() {
            when(project.getId()).thenReturn(1L);
            when(source.getId()).thenReturn(10L);
            when(copy.getId()).thenReturn(110L);
            when(copy.getName()).thenReturn("PAY: Shared default");
            when(scheme.getId()).thenReturn(120L);
            when(scheme.getName()).thenReturn("PAY: Shared scheme");
            when(factory.copyOf(source, "PAY: Shared default")).thenReturn(copy);
            when(manager.storeAndReturnEditableFieldLayout(copy)).thenReturn(copy);
            when(manager.getEditableFieldLayout(110L)).thenReturn(copy);
            when(manager.createFieldLayoutScheme("PAY: Shared scheme", "Description")).thenReturn(scheme);
            when(manager.getMutableFieldLayoutScheme(120L)).thenReturn(scheme);
            when(scheme.getEntity((String) isNull())).thenReturn(mapping);
            when(mapping.getFieldLayoutId()).thenAnswer(call -> mappedLayout.get());
            doAnswer(call -> { mappedLayout.set(call.getArgument(0)); return null; }).when(mapping).setFieldLayoutId(anyLong());
            when(manager.getFieldConfigurationScheme(project)).thenAnswer(call -> {
                FieldConfigurationScheme current = mock(FieldConfigurationScheme.class);
                when(current.getId()).thenReturn(assignment.get());
                return current;
            });
            doAnswer(call -> { assignment.set(call.getArgument(1)); return null; })
                    .when(manager).addSchemeAssociation(eq(project), anyLong());
            Map<FieldLayout, String> names = new LinkedHashMap<>();
            names.put(source, "PAY: Shared default");
            CopyView schemeView = new CopyView(20L, "Shared scheme", null, "PAY: Shared scheme", 0, Collections.emptyList());
            CopyView layoutView = new CopyView(10L, "Shared default", null, "PAY: Shared default", 0, Collections.emptyList());
            plan = new FieldConfigurationClonePlan(project, null, 20L, "Description", "PAY: Shared scheme",
                    Collections.singletonList(new FieldConfigurationClonePlan.Mapping(null, source)), names, true,
                    schemeView, Collections.singletonList(layoutView));
        }

        void cloneConfiguration() {
            ProjectResolver resolver = mock(ProjectResolver.class);
            when(resolver.resolve("PAY")).thenReturn(project);
            FieldConfigurationClonePlanner planner = mock(FieldConfigurationClonePlanner.class);
            when(planner.plan(project)).thenReturn(plan);
            FieldConfigurationCloneServiceImpl service = new FieldConfigurationCloneServiceImpl(
                    fixture.guard, fixture.locks, resolver, planner, new FieldConfigurationCloneWriter(manager, factory, fixture.journal),
                    new ru.rasulov.common.impl.PreviewPlanExecutor(fixture.plans, new ActionPlanCodec()), new ActionPlanCodec(), fixture.journal);
            CloneProjectFieldConfigurationsResponse preview = service.cloneProjectFieldConfigurations(
                    new CloneProjectFieldConfigurationsRequest(true, "PAY"));
            service.cloneProjectFieldConfigurations(new CloneProjectFieldConfigurationsRequest(false, null, preview.getPlanId()));
        }

        public void close() { fixture.close(); }
    }
}
