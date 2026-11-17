package ru.rasulov.common.impl.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.LookupService;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.impl.lookup.LookupTools;
import ru.rasulov.customfields.api.CustomFieldCreator;
import ru.rasulov.customfields.api.FieldConfigurationCloneService;
import ru.rasulov.customfields.api.FieldScreenService;
import ru.rasulov.customfields.api.FieldRequirementService;
import ru.rasulov.customfields.impl.CustomFieldTools;
import ru.rasulov.issuetypes.api.IssueTypeCreator;
import ru.rasulov.issuetypes.api.ProjectIssueTypeService;
import ru.rasulov.issuetypes.impl.IssueTypeTools;
import ru.rasulov.projects.api.ProjectCreator;
import ru.rasulov.projects.impl.ProjectTools;
import ru.rasulov.screens.api.ScreenSchemeService;
import ru.rasulov.screens.api.StandaloneScreenCreator;
import ru.rasulov.screens.api.ProjectOperationScreenAssigner;
import ru.rasulov.screens.impl.ScreenTools;
import ru.rasulov.workflow.api.WorkflowAssigner;
import ru.rasulov.workflow.api.WorkflowCloner;
import ru.rasulov.workflow.api.WorkflowConfigurer;
import ru.rasulov.workflow.api.WorkflowStatusReader;
import ru.rasulov.workflow.api.WorkflowDraftPublisher;
import ru.rasulov.workflow.api.dto.publish.PublishWorkflowDraftRequest;
import ru.rasulov.workflow.impl.WorkflowTools;
import ru.rasulov.resolutions.api.ResolutionService;
import ru.rasulov.resolutions.impl.ResolutionTools;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Tool discovery and argument validation at the MCP provider boundary. */
public class ToolProvidersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LookupService lookup = mock(LookupService.class);
    private final CustomFieldCreator creator = mock(CustomFieldCreator.class);
    private final FieldConfigurationCloneService configurationCloner = mock(FieldConfigurationCloneService.class);
    private final FieldScreenService screens = mock(FieldScreenService.class);
    private final FieldRequirementService requirements = mock(FieldRequirementService.class);
    private final ProjectCreator projectCreator = mock(ProjectCreator.class);
    private final ProjectIssueTypeService issueTypes = mock(ProjectIssueTypeService.class);
    private final IssueTypeCreator issueTypeCreator = mock(IssueTypeCreator.class);
    private final ScreenSchemeService screenSchemes = mock(ScreenSchemeService.class);
    private final StandaloneScreenCreator standaloneScreenCreator = mock(StandaloneScreenCreator.class);
    private final ProjectOperationScreenAssigner operationAssigner = mock(ProjectOperationScreenAssigner.class);
    private final ru.rasulov.screens.api.ScreenFieldRemover fieldRemover = mock(ru.rasulov.screens.api.ScreenFieldRemover.class);
    private final ru.rasulov.screens.api.ScreenLayoutEditor fieldMover = mock(ru.rasulov.screens.api.ScreenLayoutEditor.class);
    private final ru.rasulov.screens.api.IssueTypeScreenSchemeMappingEditor mappingEditor = mock(ru.rasulov.screens.api.IssueTypeScreenSchemeMappingEditor.class);
    private final ru.rasulov.screens.api.ScreenSchemeOperationEditor operationEditor = mock(ru.rasulov.screens.api.ScreenSchemeOperationEditor.class);
    private final ru.rasulov.screens.api.IssueTypeScreenSchemeAssigner schemeAssigner = mock(ru.rasulov.screens.api.IssueTypeScreenSchemeAssigner.class);
    private final WorkflowConfigurer workflowConfigurer = mock(WorkflowConfigurer.class);
    private final WorkflowStatusReader workflowStatuses = mock(WorkflowStatusReader.class);
    private final WorkflowAssigner assigner = mock(WorkflowAssigner.class);
    private final WorkflowCloner cloner = mock(WorkflowCloner.class);
    private final WorkflowDraftPublisher publisher = mock(WorkflowDraftPublisher.class);
    private final ResolutionService resolutions = mock(ResolutionService.class);
    private ToolRegistry registry;

    @Before
    public void setUp() {
        registry = new ToolRegistry(Arrays.<ToolProvider>asList(new LookupTools(lookup),
                new CustomFieldTools(creator, screens, requirements, configurationCloner, org.mockito.Mockito.mock(ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration.class), org.mockito.Mockito.mock(ru.rasulov.common.impl.AdministratorGuard.class),
                        mock(ru.rasulov.customfields.api.FieldContextReader.class),
                        mock(ru.rasulov.customfields.api.FieldContextScopeService.class),
                        mock(ru.rasulov.customfields.api.FieldContextCreator.class)),
                new WorkflowTools(workflowConfigurer, workflowStatuses, assigner, cloner, publisher,
                        mock(ru.rasulov.workflow.api.WorkflowReader.class),
                        mock(ru.rasulov.workflow.api.WorkflowRenamer.class),
                        mock(ru.rasulov.workflow.api.StatusUpdater.class),
                        mock(ru.rasulov.workflow.api.WorkflowCopier.class),
                        mock(ru.rasulov.workflow.api.WorkflowValidatorReader.class),
                        mock(ru.rasulov.workflow.api.WorkflowConditionReader.class)),
                new IssueTypeTools(issueTypes, issueTypeCreator),
                new ScreenTools(screenSchemes, standaloneScreenCreator, operationAssigner,
                        mock(ru.rasulov.screens.api.ScreenContentsReader.class), fieldRemover, fieldMover, mappingEditor, operationEditor,
                        schemeAssigner), new ProjectTools(projectCreator),
                new ResolutionTools(resolutions)));
    }

    private void call(String tool, String arguments) throws Exception {
        registry.find(tool).call(MAPPER.readTree(arguments));
    }

    private static String refusal(ToolCall call, String arguments) throws Exception {
        try {
            call.call(MAPPER.readTree(arguments));
        } catch (InvalidRequestException e) {
            return e.getMessage();
        }
        throw new AssertionError("expected the call to be refused");
    }


    @Test
    public void twoProvidersOfTheSameToolStopTheStart() {
        try {
            new ToolRegistry(Arrays.<ToolProvider>asList(new LookupTools(lookup), new LookupTools(lookup)));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("list_projects"));
        }
    }


    @Test
    public void aLimitThatIsNotAWholeNumberIsRefusedAndNeverReachesTheService() throws Exception {
        String message = refusal(registry.find("list_projects"), "{\"limit\":\"many\"}");

        assertTrue(message, message.contains("'limit' must be a whole number"));
        verifyNoInteractions(lookup);
    }

    @Test
    public void anArgumentTheToolDoesNotTakeIsNamed() throws Exception {
        String message = refusal(registry.find("list_projects"), "{\"qeury\":\"pay\"}");

        assertTrue(message, message.contains("Unknown argument 'qeury'"));
        assertTrue(message, message.contains("limit, query"));
        String statuses = refusal(registry.find("list_statuses"), "{\"x\":1}");
        assertTrue(statuses, statuses.contains("Unknown argument 'x'"));
        assertTrue(statuses, statuses.contains("limit, names, query"));
    }

    @Test
    public void aRequiredTextArgumentThatIsMissingOrNotTextIsRefused() throws Exception {
        assertTrue(refusal(registry.find("get_project_workflows"), "{}").contains("'project' is required"));
        assertTrue(refusal(registry.find("get_project_screens"), "{}").contains("'project' is required"));
        assertTrue(refusal(registry.find("plan_field_screens"), "{\"fieldId\":10123}").contains("must be text"));
        verifyNoInteractions(assigner, screens, screenSchemes, requirements);
    }


    @Test
    public void aRequestToolRefusesAnUnknownArgumentLikeTheRestBodyDoes() throws Exception {
        String message = refusal(registry.find("assign_workflow"), "{\"project\":\"ABC\",\"bogus\":1}");

        assertTrue(message, message.contains("bogus"));
        verifyNoInteractions(assigner);
    }

    @Test
    public void theDraftPublicationIsRoutedWithItsPreviewArgumentsAndRefusesOthers() throws Exception {
        call("publish_workflow_draft", "{\"dryRun\":true,\"workflow\":\"Lot Buy\"}");

        verify(publisher).publish(argThat((PublishWorkflowDraftRequest request) ->
                Boolean.TRUE.equals(request.isDryRun()) && "Lot Buy".equals(request.getWorkflow())));
        String message = refusal(registry.find("publish_workflow_draft"), "{\"dryRun\":true,\"project\":\"ABC\"}");
        assertTrue(message, message.contains("project"));
    }

    @Test
    public void workflowConfigurationRoutesTheNativeCombinedRequestAndReplacesLegacyTools() throws Exception {
        call("configure_workflow", "{\"dryRun\":true,\"existing\":{\"workflow\":\"W\",\"version\":\"DRAFT\","
                + "\"expectedRevision\":\"r\"},\"transitionRules\":[{\"actionId\":21,"
                + "\"addValidators\":[{\"template\":\"comment_required\"}]}]}");
        verify(workflowConfigurer).configure(argThat(request -> Boolean.TRUE.equals(request.isDryRun())
                && "W".equals(request.existing().workflow())
                && "DRAFT".equals(request.existing().version())
                && "r".equals(request.existing().expectedRevision())
                && request.create() == null && request.transitionRules().size() == 1
                && Integer.valueOf(21).equals(request.transitionRules().get(0).actionId())
                && "comment_required".equals(request.transitionRules().get(0).addValidators().get(0).template())));
        org.junit.Assert.assertNull(registry.find("create_workflow"));
        org.junit.Assert.assertNull(registry.find("configure_workflow_validators"));
        org.junit.Assert.assertNull(registry.find("configure_workflow_conditions"));
    }

    @Test
    public void statusDiscoveryUsesTheIndependentReaderAfterWorkflowCreationCutover() throws Exception {
        call("list_statuses", "{\"query\":\"Done\",\"limit\":7}");
        verify(workflowStatuses).listStatuses("Done", Integer.valueOf(7));
        verifyNoInteractions(workflowConfigurer);
    }

    @Test
    public void statusNamesAreForwardedAsAListToTheReader() throws Exception {
        call("list_statuses", "{\"names\":[\"Done\",\" In Review\"]}");
        verify(workflowStatuses).lookupStatuses(Arrays.asList("Done", " In Review"));
        verifyNoInteractions(workflowConfigurer);
    }

    @Test
    public void workflowConfigurationRejectsUnknownNestedPropertiesBeforeReachingTheService() throws Exception {
        String message = refusal(registry.find("configure_workflow"),
                "{\"dryRun\":true,\"existing\":{\"workflow\":\"W\",\"version\":\"ACTIVE\","
                        + "\"expectedRevision\":\"r\",\"script\":\"untrusted\"}}");
        assertTrue(message, message.contains("script"));
        verifyNoInteractions(workflowConfigurer);
    }

    @Test
    public void fieldAdditionRoutesNestedPlacementAndLegacyTargets() throws Exception {
        call("add_field_to_screens", "{\"dryRun\":true,\"fieldIds\":[\"labels\"],\"screens\":["
                + "{\"screenId\":5,\"tab\":\"Details\",\"place\":\"AFTER\",\"anchor\":\"assignee\"},"
                + "{\"screenId\":6}]}");
        verify(screens).addToScreens(argThat(request -> Boolean.TRUE.equals(request.isDryRun())
                && request.getFieldIds().equals(Arrays.asList("labels")) && request.getScreens().size() == 2
                && request.getScreens().get(0).getScreenId() == 5L
                && "Details".equals(request.getScreens().get(0).getTab())
                && ru.rasulov.common.api.dto.screen.FieldPlace.AFTER == request.getScreens().get(0).getPlace()
                && "assignee".equals(request.getScreens().get(0).getAnchor())
                && request.getScreens().get(1).getScreenId() == 6L
                && request.getScreens().get(1).getPlace() == null
                && request.getScreens().get(1).getAnchor() == null));
    }

    @Test
    public void fieldAdditionRejectsInvalidEnumAndUnknownNestedOrRootProperties() throws Exception {
        for (String extra : Arrays.asList("place", "anchor", "tab", "bogus")) {
            String message = refusal(registry.find("add_field_to_screens"),
                    "{\"dryRun\":true,\"fieldIds\":[\"labels\"],\"screens\":[{\"screenId\":5}],\""
                            + extra + "\":\"unexpected\"}");
            assertTrue(message, message.contains(extra));
        }
        for (String extra : Arrays.asList("fieldId", "position", "steps", "bogus")) {
            String message = refusal(registry.find("add_field_to_screens"),
                    "{\"dryRun\":true,\"fieldIds\":[\"labels\"],\"screens\":[{\"screenId\":5,\""
                            + extra + "\":\"unexpected\"}]}");
            assertTrue(message, message.contains(extra));
        }
        assertTrue(refusal(registry.find("add_field_to_screens"),
                "{\"dryRun\":true,\"fieldIds\":[\"labels\"],\"screens\":[{\"screenId\":5,\"place\":\"MIDDLE\"}]}")
                .contains("MIDDLE"));
        verifyNoInteractions(screens);
    }

    @Test
    public void fieldRemovalUsesExplicitTargetsAndRefusesExtraArguments() throws Exception {
        call("remove_field_from_screens", "{\"dryRun\":true,\"fieldIds\":[\"labels\"],\"screens\":[{\"screenId\":5}]}");
        verify(fieldRemover).remove(argThat(request -> Boolean.TRUE.equals(request.isDryRun())
                && request.getFieldIds().equals(Arrays.asList("labels")) && request.getScreens().get(0).getScreenId() == 5L));
        assertTrue(refusal(registry.find("remove_field_from_screens"),
                "{\"dryRun\":true,\"project\":\"ABC\"}").contains("project"));
    }

    @Test
    public void fieldRemovalRejectsUnknownRootAndNestedPropertiesBeforeCallingTheService() throws Exception {
        for (String extra : Arrays.asList("project", "bogus", "tab", "anchor", "place")) {
            String message = refusal(registry.find("remove_field_from_screens"),
                    "{\"dryRun\":true,\"fieldIds\":[\"labels\"],\"screens\":[{\"screenId\":5}],\""
                            + extra + "\":\"unexpected\"}");
            assertTrue(message, message.contains(extra));
        }
        for (String extra : Arrays.asList("project", "bogus", "tab", "anchor", "place")) {
            String message = refusal(registry.find("remove_field_from_screens"),
                    "{\"dryRun\":true,\"fieldIds\":[\"labels\"],\"screens\":[{\"screenId\":5,\""
                            + extra + "\":\"unexpected\"}]}");
            assertTrue(message, message.contains(extra));
        }
        verifyNoInteractions(fieldRemover);
    }

    @Test
    public void confirmedFieldRemovalRoutesItsPlanWithoutPreviewTargets() throws Exception {
        call("remove_field_from_screens", "{\"dryRun\":false,\"planId\":\"confirmed-removal-plan\"}");
        verify(fieldRemover).remove(argThat(request -> Boolean.FALSE.equals(request.isDryRun())
                && "confirmed-removal-plan".equals(request.getPlanId())
                && request.getFieldIds() == null && request.getScreens() == null));
    }

    @Test
    public void fieldMoveRoutesFlatSequentialMovesWithTheirExactArguments() throws Exception {
        call("edit_screen_layout", "{\"dryRun\":true,\"screenId\":5,"
                + "\"add\":[{\"fieldId\":\"priority\",\"place\":\"LAST\"}],\"remove\":[\"duedate\"],\"moves\":["
                + "{\"fieldId\":\"labels\",\"tab\":\"Details\",\"place\":\"AFTER\",\"anchor\":\"assignee\"},"
                + "{\"fieldId\":\"summary\",\"place\":\"UP\",\"steps\":2}]}");
        verify(fieldMover).edit(argThat(request -> Boolean.TRUE.equals(request.isDryRun())
                && request.getScreenId() == 5L && request.getMoves().size() == 2
                && request.getAdd().size() == 1 && "priority".equals(request.getAdd().get(0).getFieldId())
                && request.getRemove().equals(Arrays.asList("duedate"))
                && "labels".equals(request.getMoves().get(0).getFieldId())
                && "Details".equals(request.getMoves().get(0).getTab())
                && ru.rasulov.common.api.dto.screen.FieldPlace.AFTER == request.getMoves().get(0).getPlace()
                && "assignee".equals(request.getMoves().get(0).getAnchor())
                && "summary".equals(request.getMoves().get(1).getFieldId())
                && ru.rasulov.common.api.dto.screen.FieldPlace.UP == request.getMoves().get(1).getPlace()
                && Integer.valueOf(2).equals(request.getMoves().get(1).getSteps())));
    }

    @Test
    public void fieldMoveRejectsUnknownRootAndNestedPropertiesBeforeCallingTheService() throws Exception {
        for (String extra : Arrays.asList("project", "screens", "fieldIds", "tab", "anchor", "place", "bogus")) {
            String message = refusal(registry.find("edit_screen_layout"),
                    "{\"dryRun\":true,\"screenId\":5,\"moves\":[{\"fieldId\":\"labels\",\"place\":\"FIRST\"}],\""
                            + extra + "\":\"unexpected\"}");
            assertTrue(message, message.contains(extra));
        }
        for (String extra : Arrays.asList("screenId", "name", "position", "bogus")) {
            String message = refusal(registry.find("edit_screen_layout"),
                    "{\"dryRun\":true,\"screenId\":5,\"moves\":[{\"fieldId\":\"labels\",\"place\":\"FIRST\",\""
                            + extra + "\":\"unexpected\"}]}");
            assertTrue(message, message.contains(extra));
        }
        verifyNoInteractions(fieldMover);
    }

    @Test
    public void confirmedFieldMoveRoutesItsPlanWithoutPreviewTargets() throws Exception {
        call("edit_screen_layout", "{\"dryRun\":false,\"planId\":\"confirmed-move-plan\"}");
        verify(fieldMover).edit(argThat(request -> Boolean.FALSE.equals(request.isDryRun())
                && "confirmed-move-plan".equals(request.getPlanId())
                && request.getScreenId() == null && request.getAdd() == null && request.getMoves() == null && request.getRemove() == null));
    }

    @Test
    public void catalogAutomaticallyAllowsRemovalAndMoveAsOperations() {
        ToolCatalog catalog = new ToolCatalog(null);
        assertTrue(catalog.isPublished("remove_field_from_screens"));
        assertTrue(catalog.isDomainMutation("remove_field_from_screens"));
        assertTrue(registry.find("remove_field_from_screens") != null);
        assertTrue(catalog.isPublished("edit_screen_layout"));
        assertTrue(catalog.isDomainMutation("edit_screen_layout"));
        assertTrue(registry.find("edit_screen_layout") != null);
        assertTrue(!catalog.isPublished("move_screen_fields"));
        assertTrue(!catalog.isDomainMutation("move_screen_fields"));
        assertTrue(registry.find("move_screen_fields") == null);
    }

    @Test
    public void itssMappingsRouteRowsWithTheirExactArgumentsAndAreOperationsOnlyThroughTheCatalog() throws Exception {
        call("set_issue_type_screen_scheme_mappings", "{\"dryRun\":true,\"issueTypeScreenScheme\":\"Shared\",\"rows\":["
                + "{\"action\":\"REPLACE\",\"issueType\":\"DEFAULT\",\"screenScheme\":\"10100\"},"
                + "{\"action\":\"REMOVE\",\"issueType\":\"Bug\"}]}");
        verify(mappingEditor).edit(argThat(request -> Boolean.TRUE.equals(request.isDryRun())
                && "Shared".equals(request.getIssueTypeScreenScheme()) && request.getPlanId() == null
                && request.getRows().size() == 2
                && "REPLACE".equals(request.getRows().get(0).getAction())
                && "DEFAULT".equals(request.getRows().get(0).getIssueType())
                && "10100".equals(request.getRows().get(0).getScreenScheme())
                && "REMOVE".equals(request.getRows().get(1).getAction())
                && "Bug".equals(request.getRows().get(1).getIssueType())
                && request.getRows().get(1).getScreenScheme() == null));
        call("set_issue_type_screen_scheme_mappings", "{\"dryRun\":false,\"planId\":\"confirmed-itss-plan\"}");
        verify(mappingEditor).edit(argThat(request -> Boolean.FALSE.equals(request.isDryRun())
                && "confirmed-itss-plan".equals(request.getPlanId())
                && request.getIssueTypeScreenScheme() == null && request.getRows() == null));
        ToolCatalog catalog = new ToolCatalog(null);
        assertTrue(catalog.isPublished("set_issue_type_screen_scheme_mappings"));
        assertTrue(catalog.isDomainMutation("set_issue_type_screen_scheme_mappings"));
    }

    @Test
    public void itssMappingsRejectUnknownRootAndRowPropertiesBeforeCallingTheService() throws Exception {
        for (String extra : Arrays.asList("project", "bogus", "screenScheme")) {
            String message = refusal(registry.find("set_issue_type_screen_scheme_mappings"),
                    "{\"dryRun\":true,\"issueTypeScreenScheme\":\"Shared\",\"rows\":[{\"action\":\"REMOVE\","
                            + "\"issueType\":\"Bug\"}],\"" + extra + "\":\"unexpected\"}");
            assertTrue(message, message.contains(extra));
        }
        String nested = refusal(registry.find("set_issue_type_screen_scheme_mappings"),
                "{\"dryRun\":true,\"issueTypeScreenScheme\":\"Shared\",\"rows\":[{\"action\":\"REMOVE\","
                        + "\"issueType\":\"Bug\",\"bogus\":1}]}");
        assertTrue(nested, nested.contains("bogus"));
        verifyNoInteractions(mappingEditor);
    }

    @Test
    public void screenSchemeOperationsRouteRowsWithTheirExactArgumentsAndAreOperationsOnlyThroughTheCatalog() throws Exception {
        call("set_screen_scheme_operations", "{\"dryRun\":true,\"screenScheme\":\"Shared SS\",\"rows\":["
                + "{\"action\":\"REPLACE\",\"operation\":\"CREATE\",\"screen\":\"10100\"},"
                + "{\"action\":\"REMOVE\",\"operation\":\"EDIT\"}]}");
        verify(operationEditor).edit(argThat(request -> Boolean.TRUE.equals(request.isDryRun())
                && "Shared SS".equals(request.getScreenScheme()) && request.getPlanId() == null
                && request.getRows().size() == 2
                && "REPLACE".equals(request.getRows().get(0).getAction())
                && "CREATE".equals(request.getRows().get(0).getOperation())
                && "10100".equals(request.getRows().get(0).getScreen())
                && "REMOVE".equals(request.getRows().get(1).getAction())
                && "EDIT".equals(request.getRows().get(1).getOperation())
                && request.getRows().get(1).getScreen() == null));
        call("set_screen_scheme_operations", "{\"dryRun\":false,\"planId\":\"confirmed-ss-plan\"}");
        verify(operationEditor).edit(argThat(request -> Boolean.FALSE.equals(request.isDryRun())
                && "confirmed-ss-plan".equals(request.getPlanId())
                && request.getScreenScheme() == null && request.getRows() == null));
        ToolCatalog catalog = new ToolCatalog(null);
        assertTrue(catalog.isPublished("set_screen_scheme_operations"));
        assertTrue(catalog.isDomainMutation("set_screen_scheme_operations"));
    }

    @Test
    public void screenSchemeOperationsRejectUnknownRootAndRowPropertiesBeforeCallingTheService() throws Exception {
        for (String extra : Arrays.asList("project", "bogus", "issueType")) {
            String message = refusal(registry.find("set_screen_scheme_operations"),
                    "{\"dryRun\":true,\"screenScheme\":\"Shared SS\",\"rows\":[{\"action\":\"REMOVE\","
                            + "\"operation\":\"EDIT\"}],\"" + extra + "\":\"unexpected\"}");
            assertTrue(message, message.contains(extra));
        }
        String nested = refusal(registry.find("set_screen_scheme_operations"),
                "{\"dryRun\":true,\"screenScheme\":\"Shared SS\",\"rows\":[{\"action\":\"REMOVE\","
                        + "\"operation\":\"EDIT\",\"bogus\":1}]}");
        assertTrue(nested, nested.contains("bogus"));
        verifyNoInteractions(operationEditor);
    }

    @Test
    public void itssAssignmentRoutesItsArgumentsAndIsAnOperationOnlyThroughTheCatalog() throws Exception {
        call("assign_issue_type_screen_scheme", "{\"dryRun\":true,\"project\":\"ABC\",\"issueTypeScreenScheme\":\"DEFAULT\"}");
        verify(schemeAssigner).assign(argThat(request -> Boolean.TRUE.equals(request.isDryRun())
                && "ABC".equals(request.getProject()) && "DEFAULT".equals(request.getIssueTypeScreenScheme())
                && request.getPlanId() == null));
        call("assign_issue_type_screen_scheme", "{\"dryRun\":false,\"planId\":\"confirmed-assign-plan\"}");
        verify(schemeAssigner).assign(argThat(request -> Boolean.FALSE.equals(request.isDryRun())
                && "confirmed-assign-plan".equals(request.getPlanId())
                && request.getProject() == null && request.getIssueTypeScreenScheme() == null));
        ToolCatalog catalog = new ToolCatalog(null);
        assertTrue(catalog.isPublished("assign_issue_type_screen_scheme"));
        assertTrue(catalog.isDomainMutation("assign_issue_type_screen_scheme"));
    }

    @Test
    public void itssAssignmentRejectsUnknownPropertiesBeforeCallingTheService() throws Exception {
        for (String extra : Arrays.asList("rows", "bogus", "screenScheme")) {
            String message = refusal(registry.find("assign_issue_type_screen_scheme"),
                    "{\"dryRun\":true,\"project\":\"ABC\",\"issueTypeScreenScheme\":\"Shared\",\"" + extra + "\":\"unexpected\"}");
            assertTrue(message, message.contains(extra));
        }
        verifyNoInteractions(schemeAssigner);
    }
}
