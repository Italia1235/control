package it.ru.rasulov.customfields;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.customfields.manager.OptionsManager;
import com.atlassian.jira.issue.customfields.option.Option;
import com.atlassian.jira.issue.fields.CustomField;
import com.atlassian.jira.issue.fields.config.FieldConfig;
import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import com.atlassian.plugins.osgi.test.AtlassianPluginsTestRunner;
import it.ru.rasulov.WiredTestScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ru.rasulov.customfields.api.CustomFieldCreator;
import ru.rasulov.common.api.dto.field.CreateCustomFieldsRequest;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;
import ru.rasulov.common.api.dto.field.OptionDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Select-like fields in a real Jira: the options Jira stored, their order and the cascading structure.
 * Fields use a unique per-test namespace and only fields created by this test are removed afterwards.
 */
@RunWith(AtlassianPluginsTestRunner.class)
public class CustomFieldOptionsWiredTest {

    private final WiredTestScope scope = new WiredTestScope();
    private final String PREFIX = scope.prefix("options");
    private static final String SYSTEM = "com.atlassian.jira.plugin.system.customfieldtypes:";

    private final CustomFieldCreator creator;
    private final CustomFieldManager customFieldManager;
    private final OptionsManager optionsManager;
    private final JiraAuthenticationContext authenticationContext;
    private final UserManager userManager;
    private ApplicationUser previousUser;
    private boolean loggedIn;

    public CustomFieldOptionsWiredTest(CustomFieldCreator creator, CustomFieldManager customFieldManager,
                                       OptionsManager optionsManager, JiraAuthenticationContext authenticationContext,
                                       UserManager userManager) {
        this.creator = ru.rasulov.common.impl.LegacyWriteCalls.fields(creator);
        this.customFieldManager = customFieldManager;
        this.optionsManager = optionsManager;
        this.authenticationContext = authenticationContext;
        this.userManager = userManager;
    }

    @Before
    public void logInAsAdmin() throws Exception {
        scope.requireEnabled();
        previousUser = authenticationContext.getLoggedInUser();
        ApplicationUser admin = userManager.getUserByName("admin");
        assertNotNull("the 'admin' user is required by this test", admin);
        authenticationContext.setLoggedInUser(admin);
        loggedIn = true;
    }

    @After
    public void cleanUpAndRestoreUser() throws Exception {
        if (!loggedIn) {
            return;
        }
        try {
            removeTestFields();
        } finally {
            loggedIn = false;
            authenticationContext.setLoggedInUser(previousUser);
        }
    }

    @Test
    public void selectAndMultiselectStoreTheirOptionsInRequestOrder() {
        for (String type : Arrays.asList("select", "multiselect", "radiobuttons", "multicheckboxes")) {
            FieldCreationResult result = create(type, opt("Low"), opt("Medium"), opt("High"));

            assertEquals(type, CreationStatus.CREATED, result.getStatus());
            CustomField field = customFieldManager.getCustomFieldObject(result.getCustomFieldId());
            assertEquals(SYSTEM + type, field.getCustomFieldType().getKey());
            assertEquals(type, Arrays.asList("Low", "Medium", "High"), values(rootOptionsOf(field)));
            assertTrue(type + " must be in the global context", field.isGlobal());
        }
    }

    @Test
    public void cascadingSelectStoresChildrenUnderTheirParents() {
        FieldCreationResult result = create("cascadingselect",
                opt("EU", opt("Berlin"), opt("Paris")), opt("US", opt("Boston")));

        assertEquals(CreationStatus.CREATED, result.getStatus());
        List<Option> roots = rootOptionsOf(customFieldManager.getCustomFieldObject(result.getCustomFieldId()));
        assertEquals(Arrays.asList("EU", "US"), values(roots));
        assertEquals(Arrays.asList("Berlin", "Paris"), values(roots.get(0).getChildOptions()));
        assertEquals(Collections.singletonList("Boston"), values(roots.get(1).getChildOptions()));
    }

    @Test
    public void selectWithoutOptionsIsCreatedEmpty() {
        FieldCreationResult result = create("select");

        assertEquals(CreationStatus.CREATED, result.getStatus());
        assertNotNull("an empty select must come with a warning for the user", result.getMessage());
        assertTrue(result.getMessage(), result.getMessage().contains("NO OPTIONS"));
        assertTrue(rootOptionsOf(customFieldManager.getCustomFieldObject(result.getCustomFieldId())).isEmpty());
    }

    @Test
    public void secondCallReturnsExistsAndKeepsTheOriginalOptions() {
        FieldCreationResult first = create("select", opt("Low"));

        FieldCreationResult second = create("select", opt("Low"), opt("Other"));

        assertEquals(CreationStatus.EXISTS, second.getStatus());
        assertEquals(first.getCustomFieldId(), second.getCustomFieldId());
        assertEquals(Collections.singletonList("Low"),
                values(rootOptionsOf(customFieldManager.getCustomFieldObject(first.getCustomFieldId()))));
    }

    @Test
    public void invalidOptionsCreateNoField() {
        int before = customFieldManager.getCustomFieldObjects().size();

        FieldCreationResult duplicate = create("select", opt("Low"), opt("low"));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, duplicate.getErrorCode());
        assertEquals(before, customFieldManager.getCustomFieldObjects().size());
    }

    @Test
    public void optionsOnATextFieldAreRejected() {
        FieldCreationResult result = create("textfield", opt("Low"));

        assertEquals(CustomFieldErrorCode.VALIDATION_ERROR, result.getErrorCode());
        assertTrue(result.getSuggestions().contains(SYSTEM + "select"));
    }

    private List<Option> rootOptionsOf(CustomField field) {
        FieldConfig config = field.getConfigurationSchemes().get(0).getOneAndOnlyConfig();
        return optionsManager.getOptions(config).getRootOptions();
    }

    private FieldCreationResult create(String typeKey, OptionDefinition... options) {
        String name = PREFIX + typeKey;
        FieldCreationResult result = creator.createAll(new CreateCustomFieldsRequest(false, Collections.singletonList(
                new FieldDefinition(name, null, typeKey, null, Arrays.asList(options), Contexts.GLOBAL, null)))).getResults().get(0);
        if (result.getStatus() == CreationStatus.CREATED) {
            scope.record("field", result.getCustomFieldId(), name);
        }
        return result;
    }

    private static OptionDefinition opt(String value, OptionDefinition... children) {
        return new OptionDefinition(value, Arrays.asList(children));
    }

    private static List<String> values(List<Option> options) {
        List<String> values = new ArrayList<String>();
        for (Option option : options) {
            values.add(option.getValue());
        }
        return values;
    }

    private void removeTestFields() throws Exception {
        for (String id : scope.ids("field")) {
            CustomField field = customFieldManager.getCustomFieldObject(id);
            if (field != null && scope.owns("field", id, field.getUntranslatedName())) {
                customFieldManager.removeCustomField(field);
            }
        }
    }
}
