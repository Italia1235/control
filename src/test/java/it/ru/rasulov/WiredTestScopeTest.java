package it.ru.rasulov;

import org.junit.After;
import org.junit.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

/** Plain JUnit, no Atlassian runner or Jira calls. */
public class WiredTestScopeTest {
    private final String previous = System.getProperty(WiredTestScope.ENABLE_PROPERTY);

    @After public void restoreOptIn() {
        if (previous == null) System.clearProperty(WiredTestScope.ENABLE_PROPERTY);
        else System.setProperty(WiredTestScope.ENABLE_PROPERTY, previous);
    }

    @Test public void missingOrNonTrueOptInRefusesDestructiveTests() {
        WiredTestScope scope = new WiredTestScope();
        System.setProperty(WiredTestScope.ENABLE_PROPERTY, "true");
        scope.requireEnabled();
        for (String value : new String[]{null, "false", "yes", "1"}) {
            if (value == null) System.clearProperty(WiredTestScope.ENABLE_PROPERTY);
            else System.setProperty(WiredTestScope.ENABLE_PROPERTY, value);
            try {
                scope.requireEnabled();
                fail("Destructive tests must require an explicit true flag");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains(WiredTestScope.ENABLE_PROPERTY));
            }
        }
    }

    @Test public void namespacesSeparateFixturesAndStayWithinJiraProjectKeyLimit() {
        WiredTestScope first = new WiredTestScope(new UUID(0, 1));
        WiredTestScope second = new WiredTestScope(new UUID(0, 2));
        assertFalse(first.prefix("workflow").equals(second.prefix("workflow")));
        assertFalse(first.projectKey("WTA").equals(second.projectKey("WTA")));
        assertFalse(first.projectKey("WTA").equals(first.projectKey("WTB")));
        assertTrue(first.projectKey("WTA").matches("[A-Z][A-Z0-9]{9}"));
    }

    @Test public void cleanupAuthorityRequiresRecordedIdentityNotMatchingNamespace() {
        WiredTestScope scope = new WiredTestScope();
        String name = scope.prefix("fields") + "example";
        assertFalse(scope.owns("field", "100", name));
        scope.record("field", "100", name);
        assertTrue(scope.owns("field", "100", name));
        assertFalse(scope.owns("field", "101", name));
        assertFalse(scope.owns("project", "100", name));
        assertFalse(scope.owns("field", "100", name + "renamed"));
        assertFalse(scope.owns("project", "WTA", "foreign project"));
    }

    @Test public void ownershipCannotBeReassignedOrChangedThroughAnIdsSnapshot() {
        WiredTestScope scope = new WiredTestScope();
        scope.record("scheme", "20", "owned scheme");
        Set<String> ids = scope.ids("scheme");
        ids.add("21");
        assertFalse(scope.ids("scheme").contains("21"));
        try {
            scope.record("scheme", "20", "replacement scheme");
            fail("An object identity must not be silently replaced");
        } catch (IllegalStateException expected) {
            assertTrue(scope.owns("scheme", "20", "owned scheme"));
            assertFalse(scope.owns("scheme", "20", "replacement scheme"));
        }
    }
}
