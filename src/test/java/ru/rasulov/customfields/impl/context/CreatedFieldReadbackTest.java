package ru.rasulov.customfields.impl.context;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.customfields.option.Options;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.issue.fields.config.FieldConfigScheme;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.Project;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.dto.field.OptionDefinition;
import ru.rasulov.customfields.impl.JiraMocks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What Jira reports after creation is compared with the plan axis by axis; nothing is assumed from the plan. */
public class CreatedFieldReadbackTest {
    private IssueType bug;
    private IssueType task;
    private Project pay;
    private Project shop;
    private CustomFieldManager manager;
    private OptionsManager optionsManager;
    private CustomField field;
    private CreatedFieldReadback readback;
    private final List<FieldConfigScheme> schemes = new ArrayList<FieldConfigScheme>();

    @Before
    public void setUp() {
        bug = JiraMocks.issueType("10004", "Bug", false);
        task = JiraMocks.issueType("10002", "Task", false);
        pay = JiraMocks.project(1, "PAY", "Payments", bug, task);
        shop = JiraMocks.project(2, "SHOP", "Webshop", bug);
        manager = mock(CustomFieldManager.class);
        optionsManager = mock(OptionsManager.class);
        field = mock(CustomField.class);
        when(manager.getCustomFieldObject("customfield_10800")).thenReturn(field);
        when(field.getConfigurationSchemes()).thenReturn(schemes);
        readback = new CreatedFieldReadback(manager, optionsManager, new ContextSummarizer());
    }

    @Test
    public void storedStateEqualToThePlanMatches() {
        FieldConfig config = config(101L);
        scheme(1L, "PAY", config, false, Arrays.asList(pay), false, Arrays.asList(bug, task));
        options(config, "Low", "High");

        CreatedFieldReadback.Result result = readback.read("customfield_10800",
                Arrays.asList(planned("PAY", Arrays.asList(pay), false, Arrays.asList(task, bug), "Low", "High")),
                Arrays.asList(config));

        assertTrue(result.getDifferences().toString(), result.matches());
        assertEquals(1, result.getActualContexts().size());
    }

    @Test
    public void aProjectThatJiraDidNotKeepIsReportedWithBothScopes() {
        FieldConfig first = config(101L);
        FieldConfig second = config(102L);
        scheme(1L, "PAY", first, false, Arrays.asList(pay), false, Arrays.asList(bug));
        // Jira's second context lost PAY: the plan wanted PAY with Task as a second context
        scheme(2L, "PAY (2)", second, false, Collections.<Project>emptyList(), false, Arrays.asList(task));

        CreatedFieldReadback.Result result = readback.read("customfield_10800", Arrays.asList(
                planned("PAY", Arrays.asList(pay), false, Arrays.asList(bug)),
                planned("PAY (2)", Arrays.asList(pay), false, Arrays.asList(task))), Arrays.asList(first, second));

        assertFalse(result.matches());
        assertEquals(1, result.getDifferences().size());
        String difference = result.getDifferences().get(0);
        assertTrue(difference, difference.contains("'PAY (2)'") && difference.contains("(id 2)"));
        assertTrue(difference, difference.contains("projects [PAY]") && difference.contains("projects []"));
        assertEquals(2, result.getActualContexts().size());
    }

    @Test
    public void differentIssueTypesAndOptionsAreReported() {
        FieldConfig config = config(101L);
        scheme(1L, "PAY", config, false, Arrays.asList(pay), false, Arrays.asList(task));
        options(config, "Low");

        CreatedFieldReadback.Result result = readback.read("customfield_10800",
                Arrays.asList(planned("PAY", Arrays.asList(pay), false, Arrays.asList(bug), "Low", "High")),
                Arrays.asList(config));

        assertEquals(2, result.getDifferences().size());
        assertTrue(result.getDifferences().toString(), result.getDifferences().get(0).contains("Bug (10004)"));
        assertTrue(result.getDifferences().toString(), result.getDifferences().get(1).contains("[Low, High]"));
    }

    @Test
    public void anUnplannedContextAndAMissingContextAreReported() {
        FieldConfig planned = config(101L);
        FieldConfig foreign = config(999L);
        scheme(7L, "Other", foreign, false, Arrays.asList(shop), true, Collections.<IssueType>emptyList());

        CreatedFieldReadback.Result result = readback.read("customfield_10800",
                Arrays.asList(planned("PAY", Arrays.asList(pay), true, Collections.<IssueType>emptyList())),
                Arrays.asList(planned));

        assertEquals(2, result.getDifferences().size());
        assertTrue(result.getDifferences().get(0).contains("config 101"));
        assertTrue(result.getDifferences().get(1).contains("unplanned context 'Other' (id 7)"));
    }

    @Test
    public void aFieldJiraDoesNotReturnIsReportedNotAssumed() {
        when(manager.getCustomFieldObject("customfield_10800")).thenReturn(null);

        CreatedFieldReadback.Result result = readback.read("customfield_10800",
                Arrays.asList(planned("PAY", Arrays.asList(pay), true, Collections.<IssueType>emptyList())),
                Arrays.asList(config(101L)));

        assertFalse(result.matches());
        assertTrue(result.getActualContexts().isEmpty());
    }

    private static ResolvedContext planned(String name, List<Project> projects, boolean allTypes, List<IssueType> types,
                                           String... options) {
        List<OptionDefinition> wanted = new ArrayList<OptionDefinition>();
        for (String option : options) wanted.add(new OptionDefinition(option, null));
        return new ResolvedContext(name, false, projects, allTypes, types, wanted, Collections.<String>emptyList());
    }

    private static FieldConfig config(long id) {
        FieldConfig config = mock(FieldConfig.class);
        when(config.getId()).thenReturn(id);
        return config;
    }

    private void scheme(long id, String name, FieldConfig config, boolean allProjects, List<Project> projects,
                        boolean allTypes, List<IssueType> types) {
        FieldConfigScheme scheme = mock(FieldConfigScheme.class);
        when(scheme.getId()).thenReturn(id);
        when(scheme.getName()).thenReturn(name);
        Map<String, FieldConfig> configs = new HashMap<String, FieldConfig>();
        configs.put(null, config);
        when(scheme.getConfigs()).thenReturn(configs);
        when(scheme.isAllProjects()).thenReturn(allProjects);
        when(scheme.getAssociatedProjectObjects()).thenReturn(projects);
        when(scheme.isAllIssueTypes()).thenReturn(allTypes);
        when(scheme.getAssociatedIssueTypes()).thenReturn(types);
        schemes.add(scheme);
    }

    private void options(FieldConfig config, String... values) {
        List<Option> roots = new ArrayList<Option>();
        for (String value : values) {
            Option option = mock(Option.class);
            when(option.getValue()).thenReturn(value);
            when(option.getChildOptions()).thenReturn(Collections.<Option>emptyList());
            roots.add(option);
        }
        Options options = mock(Options.class);
        when(options.getRootOptions()).thenReturn(roots);
        when(optionsManager.getOptions(config)).thenReturn(options);
    }
}
