package ru.rasulov.common.web;

import org.junit.Test;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationSearchResponse;
import ru.rasulov.customfields.api.dto.configuration.ProjectFieldConfigurationsResponse;
import ru.rasulov.customfields.api.dto.context.FieldContextsResponse;
import ru.rasulov.projects.api.dto.ProjectTemplatesResponse;
import ru.rasulov.screens.api.dto.ScreenContentsResponse;
import ru.rasulov.screens.api.dto.layout.LayoutStatus;
import ru.rasulov.workflow.api.dto.assign.ProjectWorkflowsResponse;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Every result a write tool can end with must have a text on the Audit page; without one the page shows the bare code
 * (DRAFT_SAVED, RENAMED and PARTIALLY_ASSIGNED were shown that way, the last one grey as if it were a success).
 */
public class AuditResultLabelsTest {
    /** Status enums of read-only tools: they never end an apply. */
    private static final Set<Class<?>> READ_ONLY = Set.of(ProjectFieldConfigurationsResponse.Status.class,
            FieldContextsResponse.Status.class, ScreenContentsResponse.Status.class,
            ProjectWorkflowsResponse.IssueCountsStatus.class,
            FieldConfigurationSearchResponse.Status.class, FieldConfigurationSearchResponse.UsageStatus.class,
            ProjectTemplatesResponse.Status.class);

    @Test
    public void everyTerminalToolStatusHasAnAuditLabel() throws Exception {
        Properties texts = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(AppliesAdminServlet.I18N_RESOURCE)) {
            texts.load(in);
        }
        List<Class<?>> enums = statusEnums();
        assertTrue("the scan must find the tool status enums: " + enums,
                enums.contains(CreationStatus.class) && enums.contains(LayoutStatus.class));
        Set<String> missing = new TreeSet<>();
        for (Class<?> type : enums) {
            if (READ_ONLY.contains(type)) continue;
            for (Object value : type.getEnumConstants()) {
                String code = ((Enum<?>) value).name();
                if (code.startsWith("WOULD_")) continue;
                if (texts.getProperty("control.applies.result." + code) == null) {
                    missing.add(code + " (" + type.getName() + ")");
                }
            }
        }
        assertTrue("Add control.applies.result.<CODE> (and a hint when useful) to control.properties for: " + missing,
                missing.isEmpty());
    }

    @Test
    public void historicalMovesAndUnifiedLayoutEditsRemainNamedInAudit() throws Exception {
        Set<String> keys = AppliesAdminServlet.textKeys();
        assertTrue(keys.contains("control.applies.tool.move_screen_fields"));
        assertTrue(keys.contains("control.applies.result.MOVED"));
        assertTrue(keys.contains("control.applies.result.RESTORED"));
        assertTrue(keys.contains("control.applies.tool.edit_screen_layout"));
        assertTrue(keys.contains("control.applies.result.EDITED"));
    }

    @Test
    public void validatorConfigurationIsNamedInAuditUnderItsOwnTool() throws Exception {
        Properties texts = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(AppliesAdminServlet.I18N_RESOURCE)) {
            texts.load(in);
        }
        assertEquals("Configure workflow validators", texts.getProperty("control.applies.tool.configure_workflow_validators"));
        assertTrue(AppliesAdminServlet.textKeys().contains("control.applies.tool.configure_workflow_validators"));
    }

    @Test
    public void conditionConfigurationIsNamedInAuditUnderItsOwnTool() throws Exception {
        Properties texts = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(AppliesAdminServlet.I18N_RESOURCE)) {
            texts.load(in);
        }
        assertEquals("Configure workflow conditions", texts.getProperty("control.applies.tool.configure_workflow_conditions"));
        assertTrue(AppliesAdminServlet.textKeys().contains("control.applies.tool.configure_workflow_conditions"));
    }

    @Test
    public void unifiedAndHistoricalWorkflowConfigurationRemainNamedInAudit() throws Exception {
        Properties texts = new Properties();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(AppliesAdminServlet.I18N_RESOURCE)) {
            texts.load(in);
        }
        assertEquals("Configure workflow", texts.getProperty("control.applies.tool.configure_workflow"));
        Set<String> keys = AppliesAdminServlet.textKeys();
        assertTrue(keys.contains("control.applies.tool.configure_workflow"));
        assertTrue(keys.contains("control.applies.tool.create_workflow"));
        assertTrue(keys.contains("control.applies.tool.configure_workflow_validators"));
        assertTrue(keys.contains("control.applies.tool.configure_workflow_conditions"));
    }

    /** Enums named Status or *Status in the api.dto packages of the compiled plugin classes. */
    private static List<Class<?>> statusEnums() throws Exception {
        Path root = Paths.get(CreationStatus.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ClassLoader loader = AuditResultLabelsTest.class.getClassLoader();
        try (Stream<Path> files = Files.walk(root)) {
            List<String> names = files.map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .filter(name -> name.startsWith("ru/rasulov/") && name.contains("/api/dto/") && name.endsWith(".class"))
                    .map(name -> name.substring(0, name.length() - ".class".length()).replace('/', '.'))
                    .collect(Collectors.toList());
            List<Class<?>> result = new java.util.ArrayList<>();
            for (String name : names) {
                Class<?> type = Class.forName(name, false, loader);
                if (type.isEnum() && type.getSimpleName().endsWith("Status")) result.add(type);
            }
            return result;
        }
    }
}
