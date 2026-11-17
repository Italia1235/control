package it.ru.rasulov.customfields;

import com.atlassian.jira.issue.CustomFieldManager;
import com.atlassian.jira.issue.fields.CustomField;
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
import ru.rasulov.common.api.dto.field.CreateCustomFieldsResponse;
import ru.rasulov.common.api.dto.field.CreationStatus;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.FieldCreationResult;
import ru.rasulov.common.api.dto.field.FieldDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Creates every supported type in a real Jira and checks what Jira actually stored: type, default searcher and the
 * global context. Fields use a unique per-test namespace and only fields created by this test are removed afterwards.
 */
@RunWith(AtlassianPluginsTestRunner.class)
public class CustomFieldTypesWiredTest {

    private final WiredTestScope scope = new WiredTestScope();
    private final String PREFIX = scope.prefix("types");
    private static final String SYSTEM = "com.atlassian.jira.plugin.system.customfieldtypes:";

    /** {short key, default searcher module}. */
    private static final String[][] TYPES = {
            {"textfield", "textsearcher"},
            {"textarea", "textsearcher"},
            {"datepicker", "daterange"},
            {"datetime", "datetimerange"},
            {"float", "exactnumber"},
            {"url", "exacttextsearcher"},
            {"userpicker", "userpickergroupsearcher"},
            {"labels", "labelsearcher"},
            {"multiuserpicker", "userpickergroupsearcher"},
            {"grouppicker", "grouppickersearcher"},
            {"multigrouppicker", "grouppickersearcher"},
            {"project", "projectsearcher"},
            {"version", "versionsearcher"},
            {"multiversion", "versionsearcher"},
    };

    private final CustomFieldCreator creator;
    private final CustomFieldManager customFieldManager;
    private final JiraAuthenticationContext authenticationContext;
    private final UserManager userManager;
    private ApplicationUser previousUser;
    private boolean loggedIn;

    public CustomFieldTypesWiredTest(CustomFieldCreator creator, CustomFieldManager customFieldManager,
                                     JiraAuthenticationContext authenticationContext, UserManager userManager) {
        this.creator = ru.rasulov.common.impl.LegacyWriteCalls.fields(creator);
        this.customFieldManager = customFieldManager;
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
    public void everySupportedTypeIsStoredWithItsTypeDefaultSearcherAndGlobalContext() {
        CreateCustomFieldsResponse response = createFields(request(false));

        for (int i = 0; i < TYPES.length; i++) {
            FieldCreationResult result = response.getResults().get(i);
            assertEquals(TYPES[i][0], CreationStatus.CREATED, result.getStatus());

            CustomField field = customFieldManager.getCustomFieldObject(result.getCustomFieldId());
            assertNotNull(TYPES[i][0], field);
            assertEquals(SYSTEM + TYPES[i][0], field.getCustomFieldType().getKey());
            assertEquals(SYSTEM + TYPES[i][1], field.getCustomFieldSearcher().getDescriptor().getCompleteKey());
            assertTrue(TYPES[i][0] + " must be in the global context", field.isGlobal());
            assertTrue(TYPES[i][0] + " must apply to all issue types", field.isAllIssueTypes());
        }
    }

    @Test
    public void secondCallReturnsExistsForEveryTypeAndCreatesNothing() {
        CreateCustomFieldsResponse first = createFields(request(false));
        int before = customFieldManager.getCustomFieldObjects().size();

        CreateCustomFieldsResponse second = createFields(request(false));

        for (int i = 0; i < TYPES.length; i++) {
            assertEquals(CreationStatus.EXISTS, second.getResults().get(i).getStatus());
            assertEquals(first.getResults().get(i).getCustomFieldId(), second.getResults().get(i).getCustomFieldId());
        }
        assertEquals(before, customFieldManager.getCustomFieldObjects().size());
    }

    @Test
    public void dryRunWritesNothing() {
        int before = customFieldManager.getCustomFieldObjects().size();

        CreateCustomFieldsResponse response = createFields(request(true));

        for (FieldCreationResult result : response.getResults()) {
            assertEquals(CreationStatus.WOULD_CREATE, result.getStatus());
        }
        assertEquals(before, customFieldManager.getCustomFieldObjects().size());
    }

    @Test
    public void numberFieldAcceptsItsOwnSearcherAndRejectsADateSearcher() {
        CreateCustomFieldsResponse response = createFields(new CreateCustomFieldsRequest(true, Arrays.asList(
                new FieldDefinition(PREFIX + "number-ok", null, "float", SYSTEM + "numberrange", null, Contexts.GLOBAL, null),
                new FieldDefinition(PREFIX + "number-bad", null, "float", SYSTEM + "daterange", null, Contexts.GLOBAL, null))));

        assertEquals(CreationStatus.WOULD_CREATE, response.getResults().get(0).getStatus());
        FieldCreationResult rejected = response.getResults().get(1);
        assertEquals(CustomFieldErrorCode.INCOMPATIBLE_SEARCHER, rejected.getErrorCode());
        assertTrue(rejected.getSuggestions().contains(SYSTEM + "numberrange"));
    }

    @Test
    public void overlappingCallsWithTheSameNameCreateExactlyOneField() throws Exception {
        final ApplicationUser admin = authenticationContext.getLoggedInUser();
        final int callers = 6;
        final CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Future<CreationStatus>> statuses = new ArrayList<Future<CreationStatus>>();
            for (int i = 0; i < callers; i++) {
                statuses.add(pool.submit(new Callable<CreationStatus>() {
                    @Override
                    public CreationStatus call() throws Exception {
                        authenticationContext.setLoggedInUser(admin);
                        go.await();
                        return createFields(new CreateCustomFieldsRequest(false, Arrays.asList(
                                new FieldDefinition(PREFIX + "race", null, "textfield", null, null, Contexts.GLOBAL, null))))
                                .getResults().get(0).getStatus();
                    }
                }));
            }
            go.countDown();

            int created = 0;
            int existing = 0;
            for (Future<CreationStatus> status : statuses) {
                CreationStatus result = status.get(120, TimeUnit.SECONDS);
                if (result == CreationStatus.CREATED) {
                    created++;
                } else if (result == CreationStatus.EXISTS) {
                    existing++;
                }
            }
            assertEquals("exactly one caller creates the field", 1, created);
            assertEquals("all the others see it", callers - 1, existing);
        } finally {
            pool.shutdownNow();
            assertTrue("concurrent creators must finish before owned fields are cleaned up",
                    pool.awaitTermination(120, TimeUnit.SECONDS));
        }
        int fieldsNamedLikeThat = 0;
        for (CustomField field : customFieldManager.getCustomFieldObjects()) {
            if ((PREFIX + "race").equals(field.getUntranslatedName())) {
                fieldsNamedLikeThat++;
            }
        }
        assertEquals(1, fieldsNamedLikeThat);
    }

    private CreateCustomFieldsResponse createFields(CreateCustomFieldsRequest request) {
        CreateCustomFieldsResponse response = creator.createAll(request);
        for (FieldCreationResult result : response.getResults()) {
            if (result.getStatus() == CreationStatus.CREATED) {
                scope.record("field", result.getCustomFieldId(), result.getName());
            }
        }
        return response;
    }

    private CreateCustomFieldsRequest request(boolean dryRun) {
        List<FieldDefinition> fields = new ArrayList<FieldDefinition>();
        for (String[] type : TYPES) {
            fields.add(new FieldDefinition(PREFIX + type[0], null, type[0], null, null, Contexts.GLOBAL, null));
        }
        return new CreateCustomFieldsRequest(dryRun, fields);
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
