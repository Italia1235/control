package ru.rasulov.common.impl.undo;

import com.atlassian.jira.ofbiz.OfBizDelegator;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.ofbiz.core.entity.GenericDAO;
import org.ofbiz.core.config.ResourceLoader;
import org.ofbiz.core.entity.GenericDelegator;
import org.ofbiz.core.entity.GenericHelperFactory;
import org.ofbiz.core.entity.GenericValue;
import org.ofbiz.core.entity.TransactionFactory;
import org.ofbiz.core.entity.config.EntityConfigUtil;
import org.ofbiz.core.entity.model.ModelFieldTypeReader;
import org.ofbiz.core.entity.model.ModelGroupReader;
import org.ofbiz.core.entity.model.ModelReader;
import org.ofbiz.core.entity.transaction.DumbFactory;
import org.ofbiz.core.util.UtilCache;
import org.w3c.dom.Document;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real GenericDelegator -> GenericHelperDAO -> GenericDAO -> SQL COUNT over native Jira column mappings. */
public class NativeEntityCountDatabaseTest {
    private static final String NAME = "undo-count";
    private static final String URL = "jdbc:hsqldb:mem:undo_native_count";
    private static Field configSlot, transactionSlot;
    private static Object originalConfig, originalTransactions;
    private static Map<String, Document> configDocuments;
    private static Document originalEngineDocument;
    private static GenericDelegator nativeDatabase;
    private final OfBizDelegator jira = mock(OfBizDelegator.class);

    @SuppressWarnings("unchecked")
    @BeforeClass public static void openNativeDatabase() throws Exception {
        // OfBiz exposes no setter for these singletons. Restore their exact prior values after this class;
        // never install a classpath-wide entityengine.xml that changes other fixtures' behavior.
        configSlot = slot(EntityConfigUtil.class, "singletonInstance");
        transactionSlot = slot(TransactionFactory.class, "transactionFactory");
        originalConfig = configSlot.get(null);
        originalTransactions = transactionSlot.get(null);
        configSlot.set(null, new EntityConfigUtil("undo-count/entityengine.xml"));
        // Native model ResourceHandlers hardcode entityengine.xml; temporarily map only that document.
        configDocuments = (Map<String, Document>) slot(ResourceLoader.class, "docSaveMap").get(null);
        originalEngineDocument = configDocuments.put("entityengine.xml",
                ResourceLoader.getXmlDocument("undo-count/entityengine.xml"));
        transactionSlot.set(null, new Connections());
        try (Connection connection = Connections.open(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE TABLE changegroup (id BIGINT PRIMARY KEY, issueid BIGINT)");
            sql.execute("CREATE TABLE OS_HISTORYSTEP (ID BIGINT PRIMARY KEY, ENTRY_ID BIGINT)");
            sql.execute("CREATE TABLE customfieldvalue (id BIGINT PRIMARY KEY, customfield BIGINT)");
            sql.execute("CREATE TABLE label (id BIGINT PRIMARY KEY, fieldid BIGINT)");
            sql.execute("CREATE TABLE searchrequest (id BIGINT PRIMARY KEY, reqcontent LONGVARCHAR)");
        }
        nativeDatabase = GenericDelegator.getGenericDelegator(NAME);
    }

    @AfterClass public static void closeNativeDatabase() throws Exception {
        try {
            GenericDelegator.removeGenericDelegator(NAME);
            GenericHelperFactory.removeHelper(NAME);
            GenericDAO.removeGenericDAO(NAME);
            ModelReader.readers.remove(NAME);
            ModelGroupReader.readers.remove(NAME);
            ModelFieldTypeReader.readers.remove(NAME);
            try (Connection connection = Connections.open(); Statement sql = connection.createStatement()) {
                sql.execute("SHUTDOWN");
            }
        } finally {
            if (transactionSlot != null) transactionSlot.set(null, originalTransactions);
            if (configSlot != null) configSlot.set(null, originalConfig);
            if (configDocuments != null) {
                if (originalEngineDocument == null) configDocuments.remove("entityengine.xml");
                else configDocuments.put("entityengine.xml", originalEngineDocument);
                configDocuments.remove("undo-count/entityengine.xml");
                ((UtilCache<?, ?>) slot(ResourceLoader.class, "loaderCache").get(null))
                        .remove("entityengine.xml::undo-count-cp");
            }
        }
    }

    @Before public void resetRows() throws Exception {
        execute("DELETE FROM changegroup", "DELETE FROM OS_HISTORYSTEP", "DELETE FROM customfieldvalue", "DELETE FROM label",
                "DELETE FROM searchrequest");
        when(jira.getDelegatorInterface()).thenReturn(nativeDatabase);
        when(jira.getCountByAnd(anyString(), anyMap())).thenThrow(new AssertionError("No Count view exists in this native schema"));
    }

    @Test public void countsOnlyMatchingNativeRowsWithoutCountViewEntities() throws Exception {
        execute("INSERT INTO changegroup VALUES (1, 11)", "INSERT INTO changegroup VALUES (2, 11)",
                "INSERT INTO changegroup VALUES (3, 12)", "INSERT INTO changegroup VALUES (4, NULL)",
                "INSERT INTO OS_HISTORYSTEP VALUES (1, 101)", "INSERT INTO OS_HISTORYSTEP VALUES (2, 102)");
        assertEquals(2L, NativeEntityCount.count(jira, "ChangeGroup", Collections.singletonMap("issue", 11L)));
        assertEquals(1L, NativeEntityCount.count(jira, "OSHistoryStep", Collections.singletonMap("entryId", 101L)));
        assertEquals(0L, NativeEntityCount.count(jira, "ChangeGroup", Collections.singletonMap("issue", 99L)));
        assertFalse(nativeDatabase.getModelReader().getEntityNames().contains("ChangeGroupCount"));
        assertFalse(nativeDatabase.getModelReader().getEntityNames().contains("OSHistoryStepCount"));
        verify(jira, never()).findByAnd(anyString(), anyMap());
    }

    @Test public void nativeFieldConditionsCountOnlyTheRequestedFieldWithoutCountViews() throws Exception {
        execute("INSERT INTO customfieldvalue VALUES (1, 10000)", "INSERT INTO customfieldvalue VALUES (2, 10000)",
                "INSERT INTO customfieldvalue VALUES (3, 10001)", "INSERT INTO customfieldvalue VALUES (4, NULL)",
                "INSERT INTO label VALUES (1, 10000)", "INSERT INTO label VALUES (2, 10001)",
                "INSERT INTO label VALUES (3, NULL)");
        assertEquals(2L, NativeEntityCount.count(jira, "CustomFieldValue", Collections.singletonMap("customfield", 10000L)));
        assertEquals(1L, NativeEntityCount.count(jira, "Label", Collections.singletonMap("fieldid", 10000L)));
        assertEquals(0L, NativeEntityCount.count(jira, "CustomFieldValue", Collections.singletonMap("customfield", 10002L)));
        assertEquals(0L, NativeEntityCount.count(jira, "Label", Collections.singletonMap("fieldid", 10002L)));
        assertFalse(nativeDatabase.getModelReader().getEntityNames().contains("CustomFieldValueCount"));
        assertFalse(nativeDatabase.getModelReader().getEntityNames().contains("LabelCount"));
    }

    @Test public void emptyFieldUndoIgnoresOtherFieldsNativeData() throws Exception {
        execute("INSERT INTO customfieldvalue VALUES (1, 10001)", "INSERT INTO label VALUES (1, 10001)");
        assertFieldRollback(false);
        assertEquals(1L, NativeEntityCount.count(jira, "CustomFieldValue", Collections.singletonMap("customfield", 10001L)));
        assertEquals(1L, NativeEntityCount.count(jira, "Label", Collections.singletonMap("fieldid", 10001L)));
    }

    @Test public void unrelatedSavedFilterDoesNotBlockFieldDeletion() throws Exception {
        execute("INSERT INTO searchrequest VALUES (1, 'project = OTHER')");
        assertFieldRollback(false);
        assertEquals("project = OTHER", nativeDatabase.findAll("SearchRequest").get(0).getString("request"));
    }

    @Test public void savedFilterReferencesBlockFieldDeletionAndPreserveTheQuery() throws Exception {
        for (String query : new String[] {"customfield_10000 is not EMPTY", "cf[10000] is not EMPTY", "Field is not EMPTY"}) {
            execute("DELETE FROM searchrequest", "INSERT INTO searchrequest VALUES (1, '" + query + "')");
            assertFieldRollback(true);
            assertEquals(query, nativeDatabase.findAll("SearchRequest").get(0).getString("request"));
        }
    }

    @Test public void issueValuesBlockDeletionAndRemainIntact() throws Exception {
        execute("INSERT INTO customfieldvalue VALUES (1, 10000)");
        assertFieldRollback(true);
        assertEquals(1L, NativeEntityCount.count(jira, "CustomFieldValue", Collections.singletonMap("customfield", 10000L)));
    }

    @Test public void nativeLabelsBlockDeletionAndRemainIntact() throws Exception {
        execute("INSERT INTO label VALUES (1, 10000)");
        assertFieldRollback(true);
        assertEquals(1L, NativeEntityCount.count(jira, "Label", Collections.singletonMap("fieldid", 10000L)));
    }

    @Test public void unreadableNativeFieldTablesBlockUndoRatherThanAuthorizeDeletion() throws Exception {
        for (String table : new String[] {"customfieldvalue", "label"}) {
            execute("DROP TABLE " + table);
            try {
                assertFieldRollback(true);
            } finally {
                execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, "
                        + ("label".equals(table) ? "fieldid" : "customfield") + " BIGINT)");
            }
        }
    }

    private static void assertFieldRollback(boolean blocked) throws Exception {
        ru.rasulov.customfields.impl.NativeFieldUndoTest.assertNativeDatabaseRollback(nativeDatabase, blocked);
    }

    @Test public void proofDetectsNewChangeGroupAndWorkflowHistoryAtUnchangedIssueTimestamp() throws Exception {
        GenericValue issue = mock(GenericValue.class);
        when(issue.getLong("workflowId")).thenReturn(101L);
        when(issue.getAllFields()).thenReturn(Collections.<String, Object>singletonMap("updated", 123L));
        when(jira.findById("Issue", 11L)).thenReturn(issue);
        when(jira.findByAnd(eq("OSCurrentStep"), anyMap(), anyList())).thenReturn(Collections.<GenericValue>emptyList());
        String initial = IssueMutationProof.hash(jira, 11L);
        execute("INSERT INTO changegroup VALUES (1, 12)", "INSERT INTO OS_HISTORYSTEP VALUES (1, 102)");
        assertEquals("Other issues' history must not invalidate this issue", initial, IssueMutationProof.hash(jira, 11L));
        execute("INSERT INTO changegroup VALUES (2, 11)");
        String changed = IssueMutationProof.hash(jira, 11L);
        assertFalse("A same-millisecond edit must invalidate ownership proof", initial.equals(changed));
        execute("INSERT INTO OS_HISTORYSTEP VALUES (2, 101)");
        assertFalse("A transition without an issue-field change must invalidate ownership proof",
                changed.equals(IssueMutationProof.hash(jira, 11L)));
    }

    @Test public void workflowMigrationSnapshotIncludesRealNativeCounts() throws Exception {
        execute("INSERT INTO changegroup VALUES (1, 11)", "INSERT INTO changegroup VALUES (2, 11)",
                "INSERT INTO OS_HISTORYSTEP VALUES (1, 101)");
        GenericValue row = mock(GenericValue.class);
        when(row.getLong("workflowId")).thenReturn(101L);
        when(row.getAllFields()).thenReturn(Collections.<String, Object>singletonMap("updated", 123L));
        when(jira.findById("Issue", 11L)).thenReturn(row);
        when(jira.findByAnd(eq("OSCurrentStep"), anyMap(), anyList())).thenReturn(Collections.<GenericValue>emptyList());
        when(jira.findByCondition(eq("OSCurrentStep"), any(), any(), any())).thenReturn(Collections.<GenericValue>emptyList());
        com.atlassian.jira.issue.MutableIssue issue = mock(com.atlassian.jira.issue.MutableIssue.class, RETURNS_DEEP_STUBS);
        when(issue.getId()).thenReturn(11L);
        when(issue.getWorkflowId()).thenReturn(101L);
        when(issue.getStatus().getId()).thenReturn("1");
        com.atlassian.jira.workflow.WorkflowManager workflows = mock(com.atlassian.jira.workflow.WorkflowManager.class, RETURNS_DEEP_STUBS);
        when(workflows.getStore().findEntry(101L).getWorkflowName()).thenReturn("Original");
        java.util.Map<String, Object> snapshot = ru.rasulov.workflow.impl.assign.IssueMigrationState.issue(issue, workflows, jira);
        assertEquals(2L, snapshot.get("changeCount"));
        assertEquals(1L, snapshot.get("historyCount"));
        assertEquals(IssueMutationProof.hash(jira, 11L), snapshot.get("stateHash"));
    }

    @Test public void databaseReadFailureFailsInsteadOfReturningZero() throws Exception {
        execute("DROP TABLE changegroup");
        try {
            NativeEntityCount.count(jira, "ChangeGroup", Collections.singletonMap("issue", 11L));
            fail("An unreadable native history table must fail closed");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getCause());
        } finally {
            execute("CREATE TABLE changegroup (id BIGINT PRIMARY KEY, issueid BIGINT)");
        }
    }

    @Test public void invalidNativeConditionFailsInsteadOfPretendingThereIsNoHistory() {
        try {
            NativeEntityCount.count(jira, "ChangeGroup", Collections.singletonMap("notARealField", 11L));
            fail("Unknown model fields must fail closed");
        } catch (IllegalStateException expected) {
            assertNotNull(expected.getCause());
            assertTrue(expected.getMessage().contains("ChangeGroup"));
        }
    }

    private static Field slot(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void execute(String... statements) throws SQLException {
        try (Connection connection = Connections.open(); Statement sql = connection.createStatement()) {
            for (String statement : statements) sql.execute(statement);
        }
    }

    /** Only the connection source is adapted; the entity model, count query and parameter binding are real OfBiz. */
    public static final class Connections extends DumbFactory {
        static Connection open() throws SQLException { return DriverManager.getConnection(URL, "SA", ""); }
        @Override public Connection getConnection(String helper) throws SQLException {
            if (!NAME.equals(helper)) throw new SQLException("Unexpected helper " + helper);
            return open();
        }
    }
}
