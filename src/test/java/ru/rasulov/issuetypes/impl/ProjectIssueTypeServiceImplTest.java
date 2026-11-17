package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.issue.fields.config.*;
import com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.project.*;
import org.junit.Test;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.lookup.ProjectResolver;
import ru.rasulov.common.impl.undo.*;
import ru.rasulov.issuetypes.api.dto.*;
import ru.rasulov.common.api.dto.issuetype.SchemeAction;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class ProjectIssueTypeServiceImplTest {
    @Test public void privateCutoverKeepsOtherProjectOnOriginalSchemeWithoutJournalSteps() throws Exception {
        Domain domain=new Domain();
        try(UndoTestFixture fixture=UndoTestFixture.create("admin")) {
            String operation=domain.apply(fixture);
            assertEquals(2L,domain.bound.scheme.getId().longValue());assertEquals(Collections.singletonList(20L),domain.original.projects);
            assertEquals(Collections.singletonList(10L),domain.bound.projects);
            assertEquals(Collections.singletonList("B"),domain.bound.members);
            assertEquals("B",domain.bound.defaultType);
            assertEquals(Collections.singletonList("A"),domain.original.members);
            assertEquals(2,domain.catalog.size());
            assertEquals(0,fixture.entityManager.find(JournalStep.class).length);
            assertEquals("COMPLETED",fixture.journal.getOperation(operation,"admin").get("status"));
        }
    }
    @Test public void privateSchemeUpdateKeepsItsAssociationAndMigratesLateIssuesWithoutJournalSteps() throws Exception {
        Domain domain=new Domain();domain.privateSource();
        when(domain.migrator.migrate(eq(domain.project),anyList(),any(),any())).thenReturn(3,2);
        try(UndoTestFixture fixture=UndoTestFixture.create("admin")) {
            String operation=domain.apply(fixture);
            assertSame(domain.original,domain.bound);
            assertEquals(Collections.singletonList("A"),domain.original.members);
            assertEquals("A",domain.original.defaultType);
            assertEquals(Collections.singletonList(10L),domain.original.projects);
            assertEquals(1,domain.catalog.size());
            assertTrue(domain.result.getMessage().contains("2 issue(s) created while the change was running"));
            org.mockito.InOrder order=inOrder(domain.migrator,domain.schemes);
            order.verify(domain.migrator).migrate(eq(domain.project),anyList(),any(),any());
            order.verify(domain.schemes).update(eq(domain.original.scheme),eq(Collections.singletonList("A")));
            order.verify(domain.schemes).setDefaultValue(domain.original.config,"A");
            order.verify(domain.migrator).migrate(eq(domain.project),anyList(),any(),any());
            assertEquals(0,fixture.entityManager.find(JournalStep.class).length);
            assertEquals("COMPLETED",fixture.journal.getOperation(operation,"admin").get("status"));
        }
    }

    @Test public void associationFailurePropagatesWithoutRestoringSourceOrDeletingPrivateScheme() throws Exception {
        Domain domain=new Domain();domain.failAssociationAfterWrite=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin")) {
            assertNativeFailure(domain,fixture);
            assertEquals(2L,domain.bound.scheme.getId().longValue());assertEquals(Collections.singletonList(20L),domain.original.projects);
            assertEquals(Collections.singletonList(10L),domain.bound.projects);assertEquals(2,domain.catalog.size());
        }
    }
    @Test public void newSchemeDefaultFailurePropagatesWithoutDeletingCreatedScheme() throws Exception {
        Domain domain=new Domain();domain.failDefaultAfterWrite=true;
        domain.plan(SchemeAction.CREATE,Arrays.asList("B","A"),"A");
        try(UndoTestFixture fixture=UndoTestFixture.create("admin")) {
            assertNativeFailure(domain,fixture);
            assertSame(domain.original,domain.bound);assertEquals(2,domain.catalog.size());
            assertEquals("A",domain.catalog.get(1).defaultType);
        }
    }
    @Test public void existingSchemeUpdateFailurePropagatesWithoutRestoringMembers() throws Exception {
        Domain domain=new Domain();domain.privateSource();domain.failUpdateAfterWrite=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin")) {
            assertNativeFailure(domain,fixture);
            assertEquals(Collections.singletonList("A"),domain.original.members);assertEquals("B",domain.original.defaultType);
            assertEquals(Collections.singletonList(10L),domain.original.projects);
        }
    }
    @Test public void existingSchemeDefaultFailurePropagatesWithoutRestoringEarlierMemberUpdate() throws Exception {
        Domain domain=new Domain();domain.privateSource();domain.failDefaultAfterWrite=true;
        try(UndoTestFixture fixture=UndoTestFixture.create("admin")) {
            assertNativeFailure(domain,fixture);
            assertEquals(Collections.singletonList("A"),domain.original.members);assertEquals("A",domain.original.defaultType);
            assertEquals(Collections.singletonList(10L),domain.original.projects);
        }
    }
    @Test public void directApplyKeepsTheDefaultFromTheCheckedPlan() {
        Domain domain=new Domain();
        List<IssueType> desired=Arrays.asList(domain.typeCatalog.get("A"),domain.typeCatalog.get("B"));
        IssueTypeChangePlan checked=new IssueTypeChangePlan(domain.project,domain.original.scheme,
                SchemeAction.CREATE,"Private Alpha",desired,
                Collections.singletonList("A"),"A",Collections.emptyList(),Collections.emptyList(),
                Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.singletonList("Other"),null);
        IssueTypeChangePlan replacement=new IssueTypeChangePlan(domain.project,domain.original.scheme,
                checked.action,checked.newSchemeName,desired,checked.previousTypeIds,"B",checked.desiredViews,
                checked.added,checked.removed,checked.mappings,checked.mappingViews,checked.otherProjectNames,null);
        when(domain.planner.plan(eq(domain.project),any())).thenReturn(checked,checked,replacement);
        try(UndoTestFixture fixture=UndoTestFixture.create("admin")) {
            ProjectIssueTypeServiceImpl service=serviceOf(fixture.guard, fixture.locks, domain.resolver, domain.schemes, domain.planner, domain.migrator, fixture.plans, fixture.journal);
            SetProjectIssueTypesResponse preview=service.set(
                    new SetProjectIssueTypesRequest(true,"ALPHA",Arrays.asList("A","B"),null,null));
            assertEquals(SetProjectIssueTypesResponse.Status.WOULD_CHANGE,preview.getStatus());
            SetProjectIssueTypesResponse applied=service.set(
                    new SetProjectIssueTypesRequest(false,null,null,null,preview.getPlanId()));
            assertEquals(SetProjectIssueTypesResponse.Status.CHANGED,applied.getStatus());
            assertEquals(Arrays.asList("A","B"),domain.bound.members);
            assertEquals("scheme writer must preserve the checked previous default, not the replacement",
                    "A",domain.bound.defaultType);
        }
    }
    @Test public void directApplyRejectsChangedPreviousDefaultWithoutWriting() {
        Domain domain=new Domain();
        List<IssueType> desired=Arrays.asList(domain.typeCatalog.get("A"),domain.typeCatalog.get("B"));
        IssueTypeChangePlan checked=new IssueTypeChangePlan(domain.project,domain.original.scheme,
                SchemeAction.CREATE,"Private Alpha",desired,
                Collections.singletonList("A"),"A",Collections.emptyList(),Collections.emptyList(),
                Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),Collections.singletonList("Other"),null);
        IssueTypeChangePlan changed=new IssueTypeChangePlan(domain.project,domain.original.scheme,
                checked.action,checked.newSchemeName,desired,checked.previousTypeIds,"B",checked.desiredViews,
                checked.added,checked.removed,checked.mappings,checked.mappingViews,checked.otherProjectNames,null);
        when(domain.planner.plan(eq(domain.project),any())).thenReturn(checked,changed);
        try(UndoTestFixture fixture=UndoTestFixture.create("admin")) {
            ProjectIssueTypeServiceImpl service=serviceOf(fixture.guard, fixture.locks, domain.resolver, domain.schemes, domain.planner, domain.migrator, fixture.plans, fixture.journal);
            SetProjectIssueTypesResponse preview=service.set(
                    new SetProjectIssueTypesRequest(true,"ALPHA",Arrays.asList("A","B"),null,null));
            assertEquals(SetProjectIssueTypesResponse.Status.WOULD_CHANGE,preview.getStatus());
            SetProjectIssueTypesResponse applied=service.set(
                    new SetProjectIssueTypesRequest(false,null,null,null,preview.getPlanId()));
            assertEquals(SetProjectIssueTypesResponse.ErrorCode.PREVIEW_STALE,applied.getErrorCode());
            assertSame(domain.original,domain.bound);
            assertEquals(1,domain.catalog.size());
            verify(domain.migrator,never()).migrate(any(),anyList(),any(),any());
            verify(domain.schemes,never()).create(anyString(),anyString(),anyList());
            verify(domain.schemes,never()).update(any(),anyCollection());
            verify(domain.schemes,never()).setDefaultValue(any(),anyString());
            verify(domain.schemes,never()).addProjectAssociations(any(),anyCollection());
        }
    }
    @Test public void bothMigrationPassesUseTheCheckedStatusForCreateAndUpdate() throws Exception {
        for(SchemeAction action:Arrays.asList(
                SchemeAction.CREATE,SchemeAction.UPDATE)) {
            Domain domain=new Domain();
            if(action==SchemeAction.UPDATE) {
                domain.original.projects.clear();domain.original.projects.add(10L);
            }
            IssueTypeMigratorTest.Domain migration=new IssueTypeMigratorTest.Domain(1);
            migration.afterWorkflowWrite=()->migration.add(99L,"A");
            IssueTypeChangePlan.Mapping mapping=new IssueTypeChangePlan.Mapping(migration.from,migration.to,
                    migration.original,migration.target,Collections.singletonMap("A",migration.targetStatus),1);
            com.atlassian.jira.issue.status.Status replacementStatus=mock(com.atlassian.jira.issue.status.Status.class);
            when(replacementStatus.getId()).thenReturn("REPLACEMENT");
            IssueTypeChangePlan.Mapping replacementMapping=new IssueTypeChangePlan.Mapping(migration.from,migration.to,
                    migration.original,migration.target,Collections.singletonMap("A",replacementStatus),1);
            IssueTypeChangePlan checked=new IssueTypeChangePlan(domain.project,domain.original.scheme,action,
                    action==SchemeAction.CREATE?"Private Alpha":null,
                    Collections.singletonList(migration.to),Collections.singletonList("A"),"A",
                    Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),
                    Collections.singletonList(mapping),Collections.emptyList(),Collections.emptyList(),null);
            IssueTypeChangePlan replacement=new IssueTypeChangePlan(domain.project,domain.original.scheme,action,
                    checked.newSchemeName,checked.desired,checked.previousTypeIds,checked.previousDefaultTypeId,
                    checked.desiredViews,checked.added,checked.removed,Collections.singletonList(replacementMapping),
                    checked.mappingViews,checked.otherProjectNames,null);
            when(domain.planner.plan(eq(domain.project),any())).thenReturn(checked,checked,replacement);
            try(UndoTestFixture fixture=UndoTestFixture.create("admin")) {
                ProjectIssueTypeServiceImpl service=serviceOf(fixture.guard, fixture.locks, domain.resolver, domain.schemes, domain.planner, migration.migrator, fixture.plans, fixture.journal);
                SetProjectIssueTypesResponse preview=service.set(
                        new SetProjectIssueTypesRequest(true,"ALPHA",Collections.singletonList("B"),null,null));
                SetProjectIssueTypesResponse applied=service.set(
                        new SetProjectIssueTypesRequest(false,null,null,null,preview.getPlanId()));
                assertEquals(action.name(),SetProjectIssueTypesResponse.Status.CHANGED,applied.getStatus());
                for(long id:Arrays.asList(1L,99L)) {
                    IssueTypeMigratorTest.Model issue=migration.models.get(id);
                    assertEquals(action+" issue "+id+" status","B",issue.status);
                    assertEquals("B",issue.workflow);
                    assertEquals("B",issue.type);
                }
                assertEquals(Collections.singletonList("B"),domain.bound.members);
                assertEquals("B",domain.bound.defaultType);
            }
        }
    }
    @Test public void failedSecondIssueRetainsCompletedPrefixWhenFinalResultIsNull() throws Exception {
        Domain domain = new Domain();
        IssueTypeMigratorTest.Domain migration = new IssueTypeMigratorTest.Domain(3);
        when(migration.issues.updateIssue(any(), any(), any(), anyBoolean())).thenAnswer(call -> {
            com.atlassian.jira.issue.MutableIssue issue = call.getArgument(1);
            if (issue.getId() == 2L) throw migration.nativeFailure;
            return issue;
        });
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            try { runMigration(domain, migration, fixture, operation); fail("Second issue must fail"); }
            catch (IllegalStateException expected) { assertSame(migration.nativeFailure, expected); }
            assertEquals("B", migration.models.get(1L).type);
            assertEquals("A", migration.models.get(3L).type);
            Map<String, Object> execution = failedExecution(fixture, operation, "FAILED");
            assertEquals("PARTIALLY_CHANGED", execution.get("outcome"));
            assertCounts(execution, 1, 1, null);
            assertEquals(1, ((Number) ((Map<?, ?>) execution.get("stoppedAt")).get("unitIndex")).intValue());
        }
    }

    @Test public void cancellationInsideWorkflowFinishesPairAndReceiptsBeforeNextIssue() throws Exception {
        Domain domain = new Domain();
        IssueTypeMigratorTest.Domain migration = new IssueTypeMigratorTest.Domain(3);
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            migration.afterWorkflowWrite = () -> fixture.journal.cancel(operation, "admin", false);
            try { runMigration(domain, migration, fixture, operation); fail("Cancellation must stop apply"); }
            catch (JournalCancelledException expected) { /* expected at the pair boundary */ }
            assertEquals("B", migration.models.get(1L).workflow);
            assertEquals("B", migration.models.get(1L).type);
            assertEquals("A", migration.models.get(2L).workflow);
            assertEquals("A", migration.models.get(2L).type);
            Map<String, Object> execution = failedExecution(fixture, operation, "CANCELLED");
            assertEquals("PARTIALLY_CHANGED", execution.get("outcome"));
            assertCounts(execution, 1, 0, null);
            assertEquals("COMPLETED", ((Map<?, ?>) ((List<?>) execution.get("details")).get(0)).get("state"));
            verify(domain.schemes, never()).create(anyString(), anyString(), anyList());
        }
    }

    @Test public void failureBetweenWorkflowAndTypeMarksCurrentIssueUnknown() throws Exception {
        Domain domain = new Domain();
        IssueTypeMigratorTest.Domain migration = new IssueTypeMigratorTest.Domain(2);
        migration.failWorkflowAfterWrite = true;
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            try { runMigration(domain, migration, fixture, operation); fail("Workflow write must fail"); }
            catch (IllegalStateException expected) { assertSame(migration.nativeFailure, expected); }
            assertEquals("B", migration.models.get(1L).workflow);
            assertEquals("A", migration.models.get(1L).type);
            assertEquals("A", migration.models.get(2L).workflow);
            Map<String, Object> execution = failedExecution(fixture, operation, "FAILED");
            assertEquals("UNKNOWN", execution.get("outcome"));
            assertCounts(execution, 0, 1, null);
            assertEquals("UNKNOWN", ((Map<?, ?>) ((List<?>) execution.get("details")).get(0)).get("state"));
        }
    }

    @Test public void defaultFailureAfterSchemeCreationReportsRetainedSchemeChange() throws Exception {
        Domain domain = new Domain();
        domain.failDefaultAfterWrite = true;
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            try { domain.apply(fixture, operation); fail("Default write must fail"); }
            catch (IllegalStateException expected) { assertSame(domain.nativeFailure, expected); }
            assertEquals(2, domain.catalog.size());
            assertSame(domain.original, domain.bound);
            Map<String, Object> execution = failedExecution(fixture, operation, "FAILED");
            assertEquals("PARTIALLY_CHANGED", execution.get("outcome"));
            assertEquals("SET_DEFAULT", ((Map<?, ?>) execution.get("stoppedAt")).get("schemeStep"));
            assertCounts(execution, 0, 0, null);
        }
    }

    @Test public void insufficientProgressCapacityRefusesBeforeAnyNativeWrite() throws Exception {
        Domain domain = new Domain();
        try (UndoTestFixture fixture = UndoTestFixture.create("admin", ChangeJournal.RESULT_JSON_LIMIT + 16384L)) {
            try { domain.apply(fixture); fail("The complete progress budget must be acquired before mutation"); }
            catch (JournalCapacityException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("No new mutation was started"));
            }
            assertEquals(1, domain.catalog.size());
            assertSame(domain.original, domain.bound);
            verify(domain.schemes, never()).create(anyString(), anyString(), anyList());
            verify(domain.schemes, never()).update(any(), anyCollection());
        }
    }

    @Test public void successfulApplyReturnsCompletedExecutionButPreviewDoesNot() throws Exception {
        Domain domain = new Domain();
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            runMigration(domain, new IssueTypeMigratorTest.Domain(2), fixture, operation);
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<?, ?> result = mapper.readValue(mapper.writeValueAsString(domain.result), Map.class);
            Map<?, ?> execution = (Map<?, ?>) result.get("execution");
            assertNotNull("Apply must include execution", execution);
            assertEquals("COMPLETED", execution.get("outcome"));
            assertEquals(operation, execution.get("journalOperationId"));
            assertEquals(Boolean.FALSE, execution.get("compensation"));
            Map<?, ?> counts = (Map<?, ?>) execution.get("counts");
            assertEquals(2, ((Number) counts.get("completed")).intValue());
            assertEquals(0, ((Number) counts.get("unknown")).intValue());
            assertEquals(0, ((Number) counts.get("notStarted")).intValue());
            assertEquals(Boolean.TRUE, execution.get("selectionComplete"));
            assertFalse(execution.containsKey("stoppedAt"));
            assertEquals(execution, fixture.journal.getOperation(operation, "admin").get("execution"));
        }
    }

    @Test public void unresolvedProgressSurvivesExpiryAndCleanupWithoutAnUndoStep() throws Exception {
        Domain domain = new Domain();
        domain.failDefaultAfterWrite = true;
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            try { domain.apply(fixture, operation); fail("Default write must fail"); }
            catch (IllegalStateException expected) { assertSame(domain.nativeFailure, expected); }
            String set = (String) fixture.journal.getOperation(operation, "admin").get("changeSetId");
            fixture.journal.finishChangeSet(set, "admin");
            fixture.clock.addAndGet(JournalStore.UNDO_WINDOW_MILLIS + 1);
            fixture.journal.cleanupBatch(100);
            Map<String, Object> execution = failedExecution(fixture, operation, "FAILED");
            assertEquals("PARTIALLY_CHANGED", execution.get("outcome"));
            assertEquals(1, fixture.entityManager.count(JournalProgress.class));
        }
    }

    @Test public void completedProgressAllocationIsReleasedByBoundedCleanup() throws Exception {
        Domain domain = new Domain();
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = domain.apply(fixture);
            String set = (String) fixture.journal.getOperation(operation, "admin").get("changeSetId");
            fixture.journal.finishChangeSet(set, "admin");
            fixture.clock.addAndGet(JournalStore.UNDO_WINDOW_MILLIS + 1);
            for (int i = 0; i < 10; i++) fixture.journal.cleanupBatch(1);
            assertEquals(0, fixture.entityManager.count(JournalProgress.class));
            assertEquals(0, fixture.entityManager.count(JournalOperation.class));
            assertEquals(0L, fixture.store.usedBytes());
        }
    }

    @Test public void progressReceiptsAdvanceOnlyAfterWholeBatchesOfFifty() throws Exception {
        Domain domain = new Domain();
        IssueTypePagingFixture migration = new IssueTypePagingFixture(51);
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            migration.afterUpdate = () -> {
                int writes = migration.written.size();
                if (writes == 49 || writes == 51) {
                    Map<?, ?> execution = (Map<?, ?>) fixture.reopen().getOperation(operation, "admin").get("execution");
                    Map<?, ?> counts = (Map<?, ?>) execution.get("counts");
                    assertEquals(writes == 49 ? 0L : 50L, ((Number) counts.get("completed")).longValue());
                    assertEquals(writes == 49 ? 50L : 1L, ((Number) counts.get("unknown")).longValue());
                }
            };
            runPagedMigration(domain, migration, fixture, operation);
            assertEquals(51L, domain.result.getExecution().getCounts().getCompleted());
            assertEquals(0L, domain.result.getExecution().getCounts().getUnknown());
        }
    }

    @Test public void cancellationDuringTheFiftiethIssueStopsBetweenTheBatchesWithFiftyCompletedAndTheRestUntouched() throws Exception {
        Domain domain = new Domain();
        IssueTypePagingFixture migration = new IssueTypePagingFixture(51);
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            migration.afterUpdate = () -> {
                if (migration.written.size() == 50) fixture.journal.cancel(operation, "admin", false);
            };
            try { runPagedMigration(domain, migration, fixture, operation); fail("Cancellation must stop the migration"); }
            catch (JournalCancelledException expected) { /* observed when the 50th issue is done, before batch two starts */ }
            assertEquals("exactly the first batch was written", 50, migration.written.size());
            assertFalse("the 51st issue was not touched", migration.written.contains(51L));
            Map<String, Object> execution = failedExecution(fixture, operation, "CANCELLED");
            assertEquals("PARTIALLY_CHANGED", execution.get("outcome"));
            assertCounts(execution, 50, 0, null);
            assertEquals(50, ((Number) ((Map<?, ?>) execution.get("stoppedAt")).get("unitIndex")).intValue());
            JournalProgressBatch[] records = fixture.entityManager.find(JournalProgressBatch.class);
            assertEquals("the first batch is closed, no second batch was begun", 1, records.length);
            verify(domain.schemes, never()).create(anyString(), anyString(), anyList());
        }
    }

    @Test public void tenThousandIncludingLateIssueFitPreallocatedJournalAndBoundedDetails() throws Exception {
        Domain domain = new Domain();
        IssueTypePagingFixture migration = new IssueTypePagingFixture(9999);
        domain.afterAssociation = () -> migration.add(10000L);
        long capacity = ChangeJournal.RESULT_JSON_LIMIT + 700L * 1024;
        try (UndoTestFixture fixture = UndoTestFixture.create("admin", capacity)) {
            String operation = openOperation(fixture);
            java.util.concurrent.atomic.AtomicLong undoReservation = new java.util.concurrent.atomic.AtomicLong();
            migration.afterWorkflow = () -> {
                if (migration.written.isEmpty()) {
                    assertTrue("All batch capacity must be reserved before the first native write",
                            fixture.store.usedBytes() >= ChangeJournal.RESULT_JSON_LIMIT + 500L * 1024);
                    long remaining = capacity - fixture.store.usedBytes();
                    fixture.journal.reserve(remaining);
                    undoReservation.set(remaining);
                }
            };
            migration.afterUpdate = () -> {
                if (migration.written.size() == 5001) {
                    try {
                        JournalOperation[] rows = fixture.entityManager.find(JournalOperation.class,
                                net.java.ao.Query.select().where("OPERATION_KEY = ?", operation));
                        assertEquals("Progress must not consume the existing undo reservation",
                                undoReservation.get(), rows[0].getReservedBytes());
                    } catch (java.sql.SQLException failure) { throw new AssertionError(failure); }
                }
            };
            runPagedMigration(domain, migration, fixture, operation);
            for (long id = 1; id <= 10000; id++) {
                assertEquals("issue " + id, 1, migration.states.get(id).writes);
                assertEquals(id, migration.written.get((int) id - 1).longValue());
            }
            ru.rasulov.common.api.dto.issuetype.ExecutionSummary execution = domain.result.getExecution();
            assertEquals(10000L, execution.getCounts().getCompleted());
            assertEquals(0L, execution.getCounts().getUnknown());
            assertEquals(Long.valueOf(0L), execution.getCounts().getNotStarted());
            assertTrue(execution.isSelectionComplete());
            assertTrue(execution.isDetailsTruncated());
            assertTrue(execution.getDetails().size() <= 100);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(execution);
            assertTrue(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= ChangeJournal.PROGRESS_JSON_LIMIT);
            assertTrue("Progress/result accounting must fit hundreds of KiB", fixture.store.usedBytes() < 700L * 1024);
            assertEquals(50, migration.maxRows);
            assertTrue(domain.result.getMessage().contains("1 issue(s) created while the change was running"));
            JournalProgressBatch[] records = fixture.entityManager.find(JournalProgressBatch.class,
                    net.java.ao.Query.select().where("OPERATION_KEY = ?", operation).order("\"ID\" ASC"));
            Set<Long> recordedIds = new HashSet<Long>();
            Set<String> schemeSteps = new HashSet<String>();
            long nextIndex = 0;
            int issueBatches = 0;
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (JournalProgressBatch record : records) {
                assertNotNull("Each completed batch/step needs a durable receipt", record.getAfterJson());
                assertTrue(record.getBeforeJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 1536);
                assertTrue(record.getAfterJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 512);
                Map<?, ?> before = mapper.readValue(record.getBeforeJson(), Map.class);
                Map<?, ?> after = mapper.readValue(record.getAfterJson(), Map.class);
                if ("ISSUES".equals(record.getKind())) {
                    List<?> ids = (List<?>) before.get("issueIds");
                    assertTrue(ids.size() <= 50);
                    assertEquals(nextIndex, ((Number) before.get("firstUnitIndex")).longValue());
                    assertEquals(nextIndex == 9999 ? 2 : 1, ((Number) before.get("pass")).intValue());
                    for (Object id : ids) assertTrue("Duplicate ID in durable batches", recordedIds.add(((Number) id).longValue()));
                    assertEquals(ids.size(), ((Number) after.get("completedPrefix")).intValue());
                    assertEquals(0, ((Number) after.get("unknown")).intValue());
                    assertEquals(0, ((Number) after.get("notStarted")).intValue());
                    nextIndex += ids.size();
                    issueBatches++;
                } else {
                    assertEquals("SCHEME", record.getKind());
                    assertTrue(schemeSteps.add((String) before.get("schemeStep")));
                    assertEquals(Boolean.TRUE, after.get("completed"));
                    assertEquals("2", after.get("resourceId"));
                }
            }
            assertEquals(10000, recordedIds.size());
            assertEquals(201, issueBatches);
            assertEquals(new HashSet<String>(Arrays.asList("CREATE_SCHEME", "SET_DEFAULT", "ASSOCIATE_PROJECT")), schemeSteps);
            assertEquals(0L, fixture.entityManager.find(JournalProgress.class)[0].getReservedBytes());
        }
    }

    @Test public void lostWorkerBeforeStopReceiptLeavesWholeBatchUnknownAndReleasesUnusedReserve() throws Exception {
        IssueTypePagingFixture migration = new IssueTypePagingFixture(50);
        IllegalStateException lost = new IllegalStateException("Worker stopped before its stop receipt");
        migration.afterUpdate = () -> { throw lost; };
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            try {
                fixture.journal.runOperation(operation, () -> migration.migrate(
                        new IssueTypeExecution(fixture.journal, true, 0)));
                fail("Worker loss must propagate");
            } catch (IllegalStateException expected) { assertSame(lost, expected); }
            Map<String, Object> execution = failedExecution(fixture, operation, "FAILED");
            assertCounts(execution, 0, 50, null);
            assertEquals("UNKNOWN", execution.get("outcome"));
            assertEquals(1, migration.written.size());
            JournalProgressBatch[] records = fixture.entityManager.find(JournalProgressBatch.class);
            assertEquals(1, records.length);
            assertNull(records[0].getAfterJson());
            assertEquals(0L, fixture.entityManager.find(JournalProgress.class)[0].getReservedBytes());
            assertTrue("Only retained data, not the unused full-call reservation, remains charged",
                    fixture.store.usedBytes() < 64L * 1024);
        }
    }

    @Test public void partialThirdBatchKeepsCurrentUnknownVisibleWithinBoundedDetails() throws Exception {
        Domain domain = new Domain();
        IssueTypePagingFixture migration = new IssueTypePagingFixture(150);
        IllegalStateException failure = new IllegalStateException("Third batch failed");
        migration.afterUpdate = () -> { if (migration.written.size() == 101) throw failure; };
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            try { runPagedMigration(domain, migration, fixture, operation); fail("Issue 101 must fail"); }
            catch (IllegalStateException expected) { assertSame(failure, expected); }
            Map<String, Object> execution = failedExecution(fixture, operation, "FAILED");
            assertCounts(execution, 100, 1, null);
            assertEquals("PARTIALLY_CHANGED", execution.get("outcome"));
            assertEquals(Boolean.TRUE, execution.get("detailsTruncated"));
            List<?> details = (List<?>) execution.get("details");
            assertTrue(details.size() <= 100);
            boolean found = false;
            for (Object value : details) {
                Map<?, ?> detail = (Map<?, ?>) value;
                if ("101".equals(detail.get("issueId"))) {
                    assertEquals("UNKNOWN", detail.get("state"));
                    found = true;
                }
            }
            assertTrue("The uncertain current issue must not disappear behind truncated completed details", found);
            assertEquals(1, migration.states.get(101L).writes);
            assertEquals(0, migration.states.get(102L).writes);
        }
    }

    @Test public void externallyRestoredSourceTypeBetweenPassesDoesNotRepeatACompletedWrite() throws Exception {
        Domain domain = new Domain();
        IssueTypePagingFixture migration = new IssueTypePagingFixture(51);
        domain.afterAssociation = () -> {
            IssueTypePagingFixture.State state = migration.states.get(1L);
            state.type = "A"; state.workflow = "A"; state.status = "A"; state.updated++;
        };
        try (UndoTestFixture fixture = UndoTestFixture.create("admin")) {
            String operation = openOperation(fixture);
            try { runPagedMigration(domain, migration, fixture, operation); fail("Previously completed IDs need read-only reconciliation"); }
            catch (IssueTypeChangeFailure expected) {
                assertEquals(SetProjectIssueTypesResponse.ErrorCode.JIRA_ERROR, expected.getCode());
            }
            assertEquals(51, migration.written.size());
            assertEquals(1, migration.states.get(1L).writes);
            assertEquals("A", migration.states.get(1L).type);
            Map<String, Object> execution = failedExecution(fixture, operation, "FAILED");
            assertCounts(execution, 51, 0, null);
        }
    }

    @Test public void lateIssueBeyondTenThousandStopsBeforeItsWriteWithoutExhaustingTheJournal() throws Exception {
        Domain domain = new Domain();
        IssueTypePagingFixture migration = new IssueTypePagingFixture(10000);
        domain.afterAssociation = () -> migration.add(10001L);
        try (UndoTestFixture fixture = UndoTestFixture.create("admin", ChangeJournal.RESULT_JSON_LIMIT + 700L * 1024)) {
            String operation = openOperation(fixture);
            try { runPagedMigration(domain, migration, fixture, operation); fail("Late issues share the 10000 limit"); }
            catch (IssueTypeChangeFailure expected) {
                assertEquals(SetProjectIssueTypesResponse.ErrorCode.TOO_MANY_ISSUES, expected.getCode());
            }
            assertEquals(10000, migration.written.size());
            assertEquals(0, migration.states.get(10001L).writes);
            assertEquals("A", migration.states.get(10001L).workflow);
            Map<String, Object> execution = failedExecution(fixture, operation, "FAILED");
            assertCounts(execution, 10000, 0, null);
            assertEquals("PARTIALLY_CHANGED", execution.get("outcome"));
            assertTrue(fixture.store.usedBytes() < 700L * 1024);
        }
    }

    private static void runPagedMigration(Domain domain, IssueTypePagingFixture migration,
                                          UndoTestFixture fixture, String operation) {
        IssueTypeChangePlan plan = new IssueTypeChangePlan(domain.project, domain.original.scheme,
                SchemeAction.CREATE, "Private Alpha",
                Collections.singletonList(migration.to), Collections.singletonList("A"), "A",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(migration.mapping()), Collections.emptyList(), Collections.singletonList("Other"),null);
        when(domain.planner.plan(eq(domain.project), any())).thenReturn(plan);
        ProjectIssueTypeServiceImpl service = serviceOf(fixture.guard, fixture.locks, domain.resolver, domain.schemes, domain.planner, migration.migrator, fixture.plans, fixture.journal);
        SetProjectIssueTypesResponse preview = service.set(
                new SetProjectIssueTypesRequest(true, "ALPHA", Collections.singletonList("B"), null, null));
        assertEquals(SetProjectIssueTypesResponse.Status.WOULD_CHANGE, preview.getStatus());
        domain.result = fixture.journal.runOperation(operation, () -> service.set(
                new SetProjectIssueTypesRequest(false, null, null, null, preview.getPlanId())));
    }

    private static String openOperation(UndoTestFixture fixture) {
        String session = fixture.journal.createSession("admin");
        String set = fixture.journal.createChangeSet(session, "admin");
        return fixture.journal.openOperation("set_project_issue_types", "admin", session, set);
    }

    private static void runMigration(Domain domain, IssueTypeMigratorTest.Domain migration,
                                     UndoTestFixture fixture, String operation) {
        IssueTypeChangePlan.Mapping mapping = new IssueTypeChangePlan.Mapping(migration.from, migration.to,
                migration.original, migration.target, Collections.singletonMap("A", migration.targetStatus),
                migration.models.size());
        IssueTypeChangePlan plan = new IssueTypeChangePlan(domain.project, domain.original.scheme,
                SchemeAction.CREATE, "Private Alpha",
                Collections.singletonList(migration.to), Collections.singletonList("A"), "A",
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(mapping), Collections.emptyList(), Collections.singletonList("Other"),null);
        when(domain.planner.plan(eq(domain.project), any())).thenReturn(plan);
        ProjectIssueTypeServiceImpl service = serviceOf(fixture.guard, fixture.locks, domain.resolver, domain.schemes, domain.planner, migration.migrator, fixture.plans, fixture.journal);
        SetProjectIssueTypesResponse preview = service.set(
                new SetProjectIssueTypesRequest(true, "ALPHA", Collections.singletonList("B"), null, null));
        assertEquals(SetProjectIssueTypesResponse.Status.WOULD_CHANGE, preview.getStatus());
        Map<?, ?> previewJson = new com.fasterxml.jackson.databind.ObjectMapper().convertValue(preview, Map.class);
        assertFalse("Preview must not expose execution", previewJson.containsKey("execution"));
        domain.result = fixture.journal.runOperation(operation, () -> service.set(
                new SetProjectIssueTypesRequest(false, null, null, null, preview.getPlanId())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> failedExecution(UndoTestFixture fixture, String operation, String status) {
        Map<String, Object> response = fixture.reopen().getOperation(operation, "admin");
        assertEquals(status, response.get("status"));
        assertNull("Final result must remain absent on unexpected failure/cancel", response.get("result"));
        Map<String, Object> execution = (Map<String, Object>) response.get("execution");
        assertNotNull("Durable execution must survive missing result and journal reopen", execution);
        assertEquals(operation, execution.get("journalOperationId"));
        return execution;
    }

    private static void assertCounts(Map<String, Object> execution, long completed, long unknown, Long notStarted) {
        Map<?, ?> counts = (Map<?, ?>) execution.get("counts");
        assertEquals(completed, ((Number) counts.get("completed")).longValue());
        assertEquals(unknown, ((Number) counts.get("unknown")).longValue());
        if (notStarted == null) assertNull(counts.get("notStarted"));
        else assertEquals(notStarted.longValue(), ((Number) counts.get("notStarted")).longValue());
    }
    private static void assertNativeFailure(Domain domain,UndoTestFixture fixture) throws Exception {
        try {domain.apply(fixture);fail("Native write must fail");}
        catch(IllegalStateException expected) {assertSame(domain.nativeFailure,expected);}
        assertEquals(0,fixture.entityManager.find(JournalStep.class).length);
    }
    private static final class SchemeModel {
        final FieldConfigScheme scheme=mock(FieldConfigScheme.class);
        final FieldConfig config=mock(FieldConfig.class);
        final List<Long> projects=new ArrayList<Long>();
        final List<String> members=new ArrayList<String>();String defaultType;
        SchemeModel(long id,String name,List<String> members,List<Long> projects) {
            this.members.addAll(members);this.projects.addAll(projects);defaultType=members.get(0);
            when(scheme.getId()).thenReturn(id);when(scheme.getName()).thenReturn(name);when(scheme.getDescription()).thenReturn("");
            when(scheme.getOneAndOnlyConfig()).thenReturn(config);when(scheme.getAssociatedProjectIds()).thenAnswer(call->new ArrayList<Long>(this.projects));
        }
    }
    private static final class Domain {
        final IssueTypeSchemeManager schemes=mock(IssueTypeSchemeManager.class);
        final Project project=mock(Project.class);
        final ProjectResolver resolver=mock(ProjectResolver.class);
        final IssueTypeChangePlanner planner=mock(IssueTypeChangePlanner.class);
        final SchemeModel original=new SchemeModel(1L,"Shared",Collections.singletonList("A"),Arrays.asList(10L,20L));
        final List<SchemeModel> catalog=new ArrayList<SchemeModel>(Collections.singletonList(original));
        SchemeModel bound=original;
        boolean failDefaultAfterWrite,failAssociationAfterWrite,failUpdateAfterWrite;
        Runnable afterAssociation;
        final IllegalStateException nativeFailure=new IllegalStateException("Native scheme write failed after mutation");
        final Map<String,IssueType> typeCatalog=new HashMap<String,IssueType>();
        final IssueTypeMigrator migrator=mock(IssueTypeMigrator.class);
        SetProjectIssueTypesResponse result;
        Domain() {
            for(String id:Arrays.asList("A","B")) {IssueType type=mock(IssueType.class);when(type.getId()).thenReturn(id);when(type.getName()).thenReturn(id);typeCatalog.put(id,type);}
            when(project.getId()).thenReturn(10L);when(project.getKey()).thenReturn("ALPHA");when(project.getName()).thenReturn("Alpha");
            when(resolver.resolve("ALPHA")).thenReturn(project);
            when(schemes.getConfigScheme(project)).thenAnswer(call->bound.scheme);
            when(schemes.getAllSchemes()).thenAnswer(call->{List<FieldConfigScheme> list=new ArrayList<FieldConfigScheme>();for(SchemeModel model:catalog)list.add(model.scheme);return list;});
            when(schemes.create(anyString(),anyString(),anyList())).thenAnswer(call->{SchemeModel model=new SchemeModel(2L,call.getArgument(0),new ArrayList<String>((Collection<String>)call.getArgument(2)),Collections.emptyList());catalog.add(model);return model.scheme;});
            doAnswer(call->{
                for(SchemeModel model:catalog)if(model.config==call.getArgument(0))model.defaultType=call.getArgument(1);
                if(failDefaultAfterWrite) {failDefaultAfterWrite=false;throw nativeFailure;}
                return null;
            }).when(schemes).setDefaultValue(any(),anyString());
            doAnswer(call->{
                SchemeModel target=model(call.getArgument(0));bound.projects.remove(10L);target.projects.add(10L);Collections.sort(target.projects);bound=target;
                if(failAssociationAfterWrite) {failAssociationAfterWrite=false;throw nativeFailure;}
                if (afterAssociation != null) afterAssociation.run();
                return null;
            }).when(schemes).addProjectAssociations(any(),anyCollection());
            when(schemes.update(any(),anyCollection())).thenAnswer(call->{
                SchemeModel target=model(call.getArgument(0));target.members.clear();target.members.addAll(call.getArgument(1));
                if(failUpdateAfterWrite) {failUpdateAfterWrite=false;throw nativeFailure;}
                return target.scheme;
            });
            plan(SchemeAction.CREATE,Collections.singletonList("B"),"A");
        }
        void plan(SchemeAction action,List<String> desired,String previousDefault) {
            List<IssueType> selected=new ArrayList<IssueType>();for(String id:desired)selected.add(typeCatalog.get(id));
            IssueTypeChangePlan plan=new IssueTypeChangePlan(project,original.scheme,action,
                    action==SchemeAction.CREATE?"Private Alpha":null,
                    selected,new ArrayList<String>(original.members),previousDefault,Collections.emptyList(),Collections.emptyList(),Collections.emptyList(),
                    Collections.emptyList(),Collections.emptyList(),action==SchemeAction.CREATE?Collections.singletonList("Other"):Collections.emptyList(),null);
            when(planner.plan(eq(project),any())).thenReturn(plan);
        }
        void privateSource() {
            original.projects.clear();original.projects.add(10L);original.members.add("B");original.defaultType="B";
            plan(SchemeAction.UPDATE,Collections.singletonList("A"),"B");
        }
        SchemeModel model(FieldConfigScheme scheme) {for(SchemeModel model:catalog)if(model.scheme==scheme)return model;throw new IllegalStateException("Unknown scheme");}
        String apply(UndoTestFixture fixture) {
            return apply(fixture, openOperation(fixture));
        }
        String apply(UndoTestFixture fixture, String operation) {
            ProjectIssueTypeServiceImpl service=serviceOf(fixture.guard, fixture.locks, resolver, schemes, planner, migrator, fixture.plans, fixture.journal);
            SetProjectIssueTypesResponse preview=service.set(new SetProjectIssueTypesRequest(true,"ALPHA",Collections.singletonList("B"),null,null));
            assertEquals(SetProjectIssueTypesResponse.Status.WOULD_CHANGE,preview.getStatus());
            result=fixture.journal.runOperation(operation,()->service.set(new SetProjectIssueTypesRequest(false,null,null,null,preview.getPlanId())));
            assertEquals(SetProjectIssueTypesResponse.Status.CHANGED,result.getStatus());return operation;
        }
    }

    /** The service over real writer/codec; the typed-set collaborators are doubles because these tests use the plain path. */
    static ProjectIssueTypeServiceImpl serviceOf(ru.rasulov.common.impl.AdministratorGuard guard, ru.rasulov.common.impl.NamedLock locks,
            ru.rasulov.common.impl.lookup.ProjectResolver resolver, com.atlassian.jira.issue.fields.config.manager.IssueTypeSchemeManager schemes,
            IssueTypeChangePlanner planner, IssueTypeMigrator migrator, ru.rasulov.common.impl.ActionPlanStore plans,
            ru.rasulov.common.impl.undo.ChangeJournal journal) {
        return new ProjectIssueTypeServiceImpl(guard, locks, resolver, planner, new IssueTypeSetWriter(schemes, migrator, journal),
                mock(IssueTypeSetPlanner.class), mock(IssueTypeSetExecutor.class),
                new ru.rasulov.common.impl.PreviewPlanExecutor(plans, new ActionPlanCodec()), new ActionPlanCodec(), journal,
                ru.rasulov.common.impl.TestLinks.links());
    }
}
