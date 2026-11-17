package ru.rasulov.issuetypes.impl;

import com.atlassian.jira.avatar.Avatar;
import com.atlassian.jira.avatar.AvatarManager;
import com.atlassian.jira.config.IssueTypeManager;
import com.atlassian.jira.issue.issuetype.IssueType;
import com.atlassian.jira.user.ApplicationUser;
import org.junit.Before;
import org.junit.Test;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;
import ru.rasulov.common.impl.undo.ChangeJournal;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesRequest;
import ru.rasulov.common.api.dto.issuetype.IssueTypeDefinition;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.ErrorCode;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.ItemResult;
import ru.rasulov.common.api.dto.issuetype.ItemStatus;
import ru.rasulov.issuetypes.api.dto.CreateIssueTypesResponse.Status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Batch preview/apply against a stateful in-memory Jira catalog: every assertion on writes looks at what the fake
 * catalog really contains, not only at the returned DTO.
 */
public class IssueTypeCreatorImplTest {
    private final IssueTypeManager manager = mock(IssueTypeManager.class);
    private final AvatarManager avatars = mock(AvatarManager.class);
    private final AdministratorGuard guard = mock(AdministratorGuard.class);
    private final NamedLock lock = mock(NamedLock.class);
    private final ActionPlanStore plans = mock(ActionPlanStore.class);
    private final ChangeJournal journal = mock(ChangeJournal.class);
    private final IssueTypeUndoSupport undo = mock(IssueTypeUndoSupport.class);
    private final ApplicationUser admin = user("admin-key");
    private final AtomicReference<ActionPlanStore.Plan> issued = new AtomicReference<ActionPlanStore.Plan>();
    private final AtomicReference<String> issuedOperation = new AtomicReference<String>();
    private final AtomicReference<String> issuedActor = new AtomicReference<String>();
    private final AtomicBoolean insideLock = new AtomicBoolean();
    private final IssueTypeCreatorImpl creator = new IssueTypeCreatorImpl(guard, lock,
            new ru.rasulov.common.impl.PreviewPlanExecutor(plans, new ActionPlanCodec()), new ActionPlanCodec(),
            journal, new IssueTypeBatchPlanner(manager, avatars), new IssueTypeBatchWriter(manager, journal, undo),
            ru.rasulov.common.impl.TestLinks.links());

    /** The fake Jira catalog and its failure/race hooks. */
    private final List<IssueType> catalog = new ArrayList<IssueType>();
    private final List<String> createCalls = new ArrayList<String>();
    private int nextId = 100;
    private String failCreationOf;
    private boolean failReadAfterFailure;
    private boolean failureLeavesTypeBehind;
    private String externallyCreateAfterFirstWrite;
    private boolean readsFailing;
    private IssueType removeOnSecondRead;
    private int readsSinceArmed;

    @Before
    @SuppressWarnings("unchecked")
    public void setUp() {
        when(guard.requireAdministrator()).thenReturn(admin);
        when(avatars.getDefaultAvatarId(Avatar.Type.ISSUETYPE)).thenReturn(17L);
        when(manager.getIssueTypes()).thenAnswer(call -> {
            if (readsFailing) throw new IllegalStateException("catalog unreadable");
            if (removeOnSecondRead != null && ++readsSinceArmed == 2) catalog.remove(removeOnSecondRead);
            return new ArrayList<IssueType>(catalog);
        });
        when(manager.createIssueType(anyString(), anyString(), any(Long.class))).thenAnswer(call -> create(call.getArgument(0), call.getArgument(1), false));
        when(manager.createSubTaskIssueType(anyString(), anyString(), any(Long.class))).thenAnswer(call -> create(call.getArgument(0), call.getArgument(1), true));
        when(journal.execute(anyString(), anyString(), any())).thenAnswer(call ->
                ((Supplier<Object>) call.getArgument(2)).get());
        when(lock.run(anyString(), anyString(), any())).thenAnswer(call -> {
            insideLock.set(true);
            try {
                return ((Supplier<Object>) call.getArgument(2)).get();
            } finally {
                insideLock.set(false);
            }
        });
        when(plans.issue(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            issuedOperation.set(call.getArgument(0));
            issuedActor.set(call.getArgument(1));
            ActionPlanStore.Plan plan = new ActionPlanStore.Plan("batch-plan", call.getArgument(0),
                    call.getArgument(1), call.getArgument(2), call.getArgument(3), Long.MAX_VALUE);
            issued.set(plan);
            return plan;
        });
        when(plans.find(anyString(), anyString(), anyString())).thenAnswer(call -> {
            ActionPlanStore.Plan plan = issued.get();
            return plan != null && issuedOperation.get().equals(call.getArgument(0))
                    && issuedActor.get().equals(call.getArgument(1)) && plan.getId().equals(call.getArgument(2)) ? plan : null;
        });
        doAnswer(call -> {
            ActionPlanStore.Plan plan = issued.get();
            if (plan != null && plan.getId().equals(call.getArgument(0))) issued.set(null);
            return null;
        }).when(plans).remove(anyString());
    }

    // --- the cause: two separate previews on one catalog -------------------------------------------------------

    @Test
    public void twoSeparatePreviewsStillInvalidateEachOtherButOneBatchDoesNot() {
        existing("Задача", false);
        // Separate single-item previews: applying the first changes the catalog fingerprint of the second.
        CreateIssueTypesResponse first = creator.create(preview(def("a", "Заказ", false)));
        ActionPlanStore.Plan firstPlan = issued.get();
        CreateIssueTypesResponse second = creator.create(preview(def("b", "Возврат", false)));
        ActionPlanStore.Plan secondPlan = issued.get();
        issued.set(firstPlan);
        assertEquals(Status.CREATED, creator.create(apply(first.getPlanId())).getStatus());
        issued.set(secondPlan);
        assertEquals("the protection against external catalog changes stays on", ErrorCode.PREVIEW_STALE,
                creator.create(apply(second.getPlanId())).getErrorCode());
        assertEquals(2, catalog.size());
        assertNull(find("Возврат"));

        // The same two types as ONE batch: the first creation is an expected change and does not stale the rest.
        CreateIssueTypesResponse batch = creator.create(preview(def("b", "Возврат", false), def("c", "Склад", false)));
        CreateIssueTypesResponse applied = creator.create(apply(batch.getPlanId()));
        assertEquals(applied.getMessage(), Status.CREATED, applied.getStatus());
        assertNotNull(find("Возврат"));
        assertNotNull(find("Склад"));
        assertEquals(4, catalog.size());
    }

    // --- batch resolution ------------------------------------------------------------------------------------

    @Test
    public void fourMissingTypesAndAnExistingOneAreConfirmedOnceAndAppliedOnce() {
        IssueType consult = existing("Консультация", false);
        CreateIssueTypesResponse preview = creator.create(preview(def("pay", "Оплаты", false),
                def("consult", "консультация", false), def("paper", "Бумажные документы", false),
                def("supply", "Снабжение", false), def("ka", "Работа с КА", false)));

        assertEquals(preview.getMessage(), Status.WOULD_CREATE, preview.getStatus());
        assertTrue(preview.isDryRun());
        assertNotNull(preview.getPlanId());
        assertTrue("the global effect on the default scheme is stated", preview.getMessage().contains("default issue type scheme"));
        assertEquals(Arrays.asList("pay", "consult", "paper", "supply", "ka"), keys(preview));
        assertEquals(ItemStatus.EXISTS, preview.getResults().get(1).getStatus());
        assertEquals(consult.getId(), preview.getResults().get(1).getIssueType().getId());
        assertEquals(ItemStatus.WOULD_CREATE, preview.getResults().get(0).getStatus());
        assertNull(preview.getResults().get(0).getIssueType().getId());
        assertNull(preview.getReport());
        assertEquals("a preview writes nothing", 1, catalog.size());
        assertTrue(createCalls.isEmpty());

        CreateIssueTypesResponse applied = creator.create(apply(preview.getPlanId()));

        assertEquals(applied.getMessage(), Status.CREATED, applied.getStatus());
        assertFalse(applied.isDryRun());
        assertEquals("exactly the four missing types were created", 5, catalog.size());
        assertEquals(Arrays.asList("Оплаты", "Бумажные документы", "Снабжение", "Работа с КА"), createCalls);
        for (ItemResult result : applied.getResults()) {
            IssueType real = byId(result.getIssueType().getId());
            assertNotNull(result.getKey() + " has a canonical id that exists in Jira", real);
            assertEquals(real.getName(), result.getIssueType().getName());
        }
        assertEquals("Консультация", byId(consult.getId()).getName());
        assertEquals(ItemStatus.CREATED, applied.getResults().get(0).getStatus());
        assertEquals(ItemStatus.EXISTS, applied.getResults().get(1).getStatus());
        assertEquals(4, applied.getReport().getCreated().size());
        assertEquals(ChangeKind.ISSUE_TYPE, applied.getReport().getCreated().get(0).getKind());
        assertEquals(applied.getResults().get(0).getIssueType().getId(), applied.getReport().getCreated().get(0).getId());
        assertEquals(1, applied.getReport().getReused().size());
        assertEquals(consult.getId(), applied.getReport().getReused().get(0).getId());
        verify(journal, times(4)).prepare(anyString(), anyString(), any(), any());
        verify(journal, times(4)).applied(any(), any());
    }

    @Test
    public void completedPlanCannotBeReplayedAndNeverDuplicatesTypes() {
        CreateIssueTypesResponse preview = creator.create(preview(def("a", "Заказ", false), def("b", "Возврат", true)));
        assertEquals(Status.CREATED, creator.create(apply(preview.getPlanId())).getStatus());
        assertEquals(2, catalog.size());

        CreateIssueTypesResponse replay = creator.create(apply(preview.getPlanId()));

        assertEquals(ErrorCode.PREVIEW_STALE, replay.getErrorCode());
        assertEquals(2, catalog.size());
        assertEquals(2, createCalls.size());
        assertTrue(find("Возврат").isSubTask());
        assertFalse(find("Заказ").isSubTask());
    }

    @Test
    public void oneTypeIsABatchOfOneAndHoldsTheClusterLockWhileWriting() {
        CreateIssueTypesResponse preview = creator.create(preview(new IssueTypeDefinition("release", "Release", "Release work", false)));
        doAnswer(call -> {
            assertTrue("creation must hold the cluster lock", insideLock.get());
            return create("Release", "Release work", false);
        }).when(manager).createIssueType("Release", "Release work", 17L);

        CreateIssueTypesResponse applied = creator.create(apply(preview.getPlanId()));

        assertEquals(Status.CREATED, applied.getStatus());
        assertEquals(1, applied.getResults().size());
        assertEquals("release", applied.getResults().get(0).getKey());
        assertEquals("Release work", find("Release").getDescription());
    }

    @Test
    public void fullyExistingSetIssuesNoPlanAndWritesNothing() {
        IssueType task = existing("Задача", false);
        IssueType bug = existing("Ошибка", false);

        CreateIssueTypesResponse response = creator.create(preview(def("t", "задача", false), def("b", "ОШИБКА", false)));

        assertEquals(Status.EXISTS, response.getStatus());
        assertNull(response.getPlanId());
        assertEquals(task.getId(), response.getResults().get(0).getIssueType().getId());
        assertEquals(bug.getId(), response.getResults().get(1).getIssueType().getId());
        verify(plans, never()).issue(anyString(), anyString(), anyString(), anyString());
        assertTrue(createCalls.isEmpty());
        assertEquals(2, catalog.size());
    }

    @Test
    public void invalidBatchesFailWholeBeforeAnyPlanWithTheOffendingItemsMarked() {
        existing("Задача", false);
        existing("Дубль", false);
        existing("дубль", false);
        existing("Подзадача", true);
        // duplicate keys
        assertRejected(ErrorCode.VALIDATION_ERROR, 1, preview(def("k", "Один", false), def("k", "Два", false)));
        // duplicate names (case-insensitive) with different keys
        assertRejected(ErrorCode.VALIDATION_ERROR, 1, preview(def("a", "Один", false), def("b", "ОДИН", true)));
        // ambiguous existing match
        assertRejected(ErrorCode.NAME_CONFLICT, 1, preview(def("x", "ДУБЛЬ", false)));
        // standard/sub-task conflict with an existing type
        assertRejected(ErrorCode.NAME_CONFLICT, 1, preview(def("t", "Задача", true)));
        assertRejected(ErrorCode.NAME_CONFLICT, 1, preview(def("s", "Подзадача", false)));
        // required parameters
        assertRejected(ErrorCode.VALIDATION_ERROR, 1, preview(new IssueTypeDefinition(null, "Нет ключа", null, false)));
        assertRejected(ErrorCode.VALIDATION_ERROR, 1, preview(new IssueTypeDefinition("k", "  ", null, false)));
        assertRejected(ErrorCode.VALIDATION_ERROR, 1, preview(new IssueTypeDefinition("k", "Без вида", null, null)));
        assertRejected(ErrorCode.VALIDATION_ERROR, 1, preview(new IssueTypeDefinition("bad key!", "Ключ", null, false)));
        assertRejected(ErrorCode.VALIDATION_ERROR, 1, preview(new IssueTypeDefinition("k", repeat("n", 61), null, false)));
        assertRejected(ErrorCode.VALIDATION_ERROR, 1, preview((IssueTypeDefinition) null));
        // limits
        List<IssueTypeDefinition> many = new ArrayList<IssueTypeDefinition>();
        for (int i = 0; i < 21; i++) many.add(def("k" + i, "Тип " + i, false));
        assertEquals(ErrorCode.VALIDATION_ERROR, creator.create(new CreateIssueTypesRequest(true, many, null)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, creator.create(new CreateIssueTypesRequest(true, new ArrayList<IssueTypeDefinition>(), null)).getErrorCode());
        // one bad item rejects the good ones too: nothing is planned
        CreateIssueTypesResponse mixed = creator.create(preview(def("ok", "Хороший", false), def("t", "Задача", true)));
        assertEquals(ItemStatus.WOULD_CREATE, mixed.getResults().get(0).getStatus());
        assertEquals(ItemStatus.FAILED, mixed.getResults().get(1).getStatus());
        assertNull(mixed.getPlanId());

        verify(plans, never()).issue(anyString(), anyString(), anyString(), anyString());
        assertTrue(createCalls.isEmpty());
        assertEquals(4, catalog.size());
    }

    @Test
    public void modeAndArgumentMixesAreRejectedWithoutTouchingJira() {
        assertEquals(ErrorCode.VALIDATION_ERROR, creator.create(new CreateIssueTypesRequest(null, null, null)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, creator.create(new CreateIssueTypesRequest(true,
                Arrays.asList(def("a", "A", false)), "old-plan")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, creator.create(new CreateIssueTypesRequest(false,
                Arrays.asList(def("a", "A", false)), "plan")).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, creator.create(new CreateIssueTypesRequest(false, null, null)).getErrorCode());
        assertEquals(ErrorCode.VALIDATION_ERROR, creator.create(new CreateIssueTypesRequest(false, null, "  ")).getErrorCode());
        verify(manager, never()).getIssueTypes();
        assertTrue(createCalls.isEmpty());
    }

    // --- plan protection ---------------------------------------------------------------------------------------

    @Test
    public void anotherActorMissingPlanAndChangedAvatarCannotApply() {
        CreateIssueTypesResponse preview = creator.create(preview(def("a", "Заказ", false)));
        when(guard.requireAdministrator()).thenAnswer(call -> user("other-admin"));
        assertEquals(ErrorCode.PREVIEW_STALE, creator.create(apply(preview.getPlanId())).getErrorCode());
        verify(plans, never()).remove(preview.getPlanId());
        when(guard.requireAdministrator()).thenReturn(admin);
        assertEquals(ErrorCode.PREVIEW_STALE, creator.create(apply("unknown")).getErrorCode());
        when(avatars.getDefaultAvatarId(Avatar.Type.ISSUETYPE)).thenReturn(18L);
        assertEquals(ErrorCode.PREVIEW_STALE, creator.create(apply(preview.getPlanId())).getErrorCode());
        assertTrue(createCalls.isEmpty());
        assertEquals(0, catalog.size());
    }

    @Test
    public void unrelatedExternalCatalogChangeBetweenPreviewAndApplyStalesTheWholeBatch() {
        CreateIssueTypesResponse preview = creator.create(preview(def("a", "Заказ", false), def("b", "Возврат", false)));
        existing("Кто-то добавил", false);

        CreateIssueTypesResponse response = creator.create(apply(preview.getPlanId()));

        assertEquals(ErrorCode.PREVIEW_STALE, response.getErrorCode());
        assertTrue("nothing is written on a stale plan", createCalls.isEmpty());
        assertNull("the stale plan is consumed", issued.get());
        assertEquals(1, catalog.size());
    }

    @Test
    public void sameNameCreatedExternallyBetweenPreviewAndApplyIsStaleBeforeAnyWrite() {
        CreateIssueTypesResponse preview = creator.create(preview(def("a", "Заказ", false), def("b", "Возврат", false)));
        existing("возврат", false);

        assertEquals(ErrorCode.PREVIEW_STALE, creator.create(apply(preview.getPlanId())).getErrorCode());
        assertTrue(createCalls.isEmpty());
    }

    // --- failure in the middle ---------------------------------------------------------------------------------

    @Test
    public void nameTakenInJiraDuringTheBatchStopsItHonestlyWithoutOverwritingAnything() {
        CreateIssueTypesResponse preview = creator.create(preview(def("a", "Заказ", false), def("b", "Возврат", false),
                def("c", "Склад", false)));
        externallyCreateAfterFirstWrite = "Возврат";

        CreateIssueTypesResponse applied = creator.create(apply(preview.getPlanId()));

        assertEquals(applied.getMessage(), Status.PARTIALLY_CHANGED, applied.getStatus());
        assertEquals(ErrorCode.NAME_CONFLICT, applied.getErrorCode());
        assertEquals(Arrays.asList(ItemStatus.CREATED, ItemStatus.FAILED, ItemStatus.NOT_EXECUTED), statuses(applied));
        assertEquals(ErrorCode.NAME_CONFLICT, applied.getResults().get(1).getErrorCode());
        assertEquals(Arrays.asList("Заказ"), createCalls);
        assertEquals("the first type remains, the conflicting external one is untouched", 2, catalog.size());
        assertNotNull(find("Заказ"));
        assertNull(find("Склад"));
        assertEquals(1, applied.getReport().getCreated().size());
        assertEquals(find("Заказ").getId(), applied.getReport().getCreated().get(0).getId());
        assertTrue(applied.getMessage().contains("REMAIN"));
    }

    @Test
    public void nativeFailureAfterPartOfTheBatchKeepsCreatedTypesAndRerunDoesNotDuplicate() {
        CreateIssueTypesResponse preview = creator.create(preview(def("a", "Заказ", false), def("b", "Возврат", false),
                def("c", "Склад", true), def("d", "Закупки", false)));
        failCreationOf = "Склад";

        CreateIssueTypesResponse applied = creator.create(apply(preview.getPlanId()));

        assertEquals(applied.getMessage(), Status.PARTIALLY_CHANGED, applied.getStatus());
        assertEquals(ErrorCode.JIRA_ERROR, applied.getErrorCode());
        assertEquals(Arrays.asList(ItemStatus.CREATED, ItemStatus.CREATED, ItemStatus.FAILED, ItemStatus.NOT_EXECUTED),
                statuses(applied));
        assertEquals("Jira failed: read back and absent", 2, catalog.size());
        assertEquals(find("Заказ").getId(), applied.getResults().get(0).getIssueType().getId());
        assertEquals(find("Возврат").getId(), applied.getResults().get(1).getIssueType().getId());
        assertNull(applied.getResults().get(2).getIssueType().getId());
        assertEquals(2, applied.getReport().getCreated().size());
        verify(journal, times(3)).prepare(anyString(), anyString(), any(), any());
        verify(journal, times(2)).applied(any(), any());
        verify(journal, times(1)).discard(any());
        assertEquals("no automatic retry", Arrays.asList("Заказ", "Возврат", "Склад"), createCalls);

        // Jira is healthy again: previewing the same list reuses what exists and plans only the missing types.
        failCreationOf = null;
        createCalls.clear();
        CreateIssueTypesResponse again = creator.create(preview(def("a", "Заказ", false), def("b", "Возврат", false),
                def("c", "Склад", true), def("d", "Закупки", false)));
        assertEquals(Arrays.asList(ItemStatus.EXISTS, ItemStatus.EXISTS, ItemStatus.WOULD_CREATE, ItemStatus.WOULD_CREATE),
                statuses(again));
        CreateIssueTypesResponse finished = creator.create(apply(again.getPlanId()));
        assertEquals(Status.CREATED, finished.getStatus());
        assertEquals(Arrays.asList("Склад", "Закупки"), createCalls);
        assertEquals(4, catalog.size());
        assertEquals(4, names().size());
    }

    @Test
    public void failureOfTheFirstWriteCreatesNothing() {
        CreateIssueTypesResponse preview = creator.create(preview(def("a", "Заказ", false), def("b", "Возврат", false)));
        failCreationOf = "Заказ";

        CreateIssueTypesResponse applied = creator.create(apply(preview.getPlanId()));

        assertEquals(Status.FAILED, applied.getStatus());
        assertEquals(Arrays.asList(ItemStatus.FAILED, ItemStatus.NOT_EXECUTED), statuses(applied));
        assertEquals(0, catalog.size());
        assertTrue(applied.getReport().getCreated().isEmpty());
    }

    @Test
    public void typeFoundByNameAfterAnExceptionIsAnObservationNotACreationAndIsNeverUsedOrRemoved() {
        CreateIssueTypesResponse preview = creator.create(preview(def("a", "Заказ", false), def("b", "Возврат", false)));
        failCreationOf = "Заказ";
        failureLeavesTypeBehind = true;

        CreateIssueTypesResponse applied = creator.create(apply(preview.getPlanId()));

        assertEquals("not 'nothing was created': the outcome is uncertain", Status.PARTIALLY_CHANGED, applied.getStatus());
        assertFalse(applied.getMessage().contains("No issue type was created"));
        assertTrue(applied.getMessage().contains("UNKNOWN"));
        ItemResult first = applied.getResults().get(0);
        assertEquals(ItemStatus.UNKNOWN, first.getStatus());
        assertEquals("its id is observed and shown", find("Заказ").getId(), first.getIssueType().getId());
        assertTrue(first.getMessage().contains("must not be used by dependent steps"));
        assertTrue("never in report.created", applied.getReport().getCreated().isEmpty());
        assertTrue("and not in report.reused either", applied.getReport().getReused().isEmpty());
        verify(journal, never()).applied(any(), any());
        verify(journal, never()).discard(any());
        verify(manager, never()).removeIssueType(anyString(), any());
        assertNotNull("the observed type is left alone", find("Заказ"));
        assertEquals(ItemStatus.NOT_EXECUTED, applied.getResults().get(1).getStatus());
    }

    @Test
    public void unreadableJiraAfterAFailureIsUnknownAndNeverSaysNothingWasCreated() {
        CreateIssueTypesResponse preview = creator.create(preview(def("a", "Заказ", false)));
        failCreationOf = "Заказ";
        failReadAfterFailure = true;

        CreateIssueTypesResponse applied = creator.create(apply(preview.getPlanId()));

        assertEquals(Status.PARTIALLY_CHANGED, applied.getStatus());
        assertEquals(ItemStatus.UNKNOWN, applied.getResults().get(0).getStatus());
        assertNull("no id can be shown", applied.getResults().get(0).getIssueType().getId());
        assertTrue(applied.getResults().get(0).getMessage().contains("MAY have been created"));
        assertFalse(applied.getMessage().contains("No issue type was created"));
        assertFalse(applied.getMessage().contains("Nothing was changed"));
        assertTrue(applied.getReport().getCreated().isEmpty());
        verify(journal, never()).discard(any());
        verify(journal, never()).applied(any(), any());
    }

    @Test
    public void reusedTypeRemovedMidBatchStopsBeforeTheNextWrite() {
        IssueType consult = existing("Консультация", false);
        CreateIssueTypesResponse preview = creator.create(preview(def("c", "Консультация", false), def("a", "Заказ", false)));
        // the plan matches at apply start; the type disappears right before the first write
        removeOnSecondRead = consult;

        CreateIssueTypesResponse applied = creator.create(apply(preview.getPlanId()));

        assertEquals(Status.FAILED, applied.getStatus());
        assertEquals(ErrorCode.NAME_CONFLICT, applied.getErrorCode());
        assertTrue(createCalls.isEmpty());
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    private IssueType create(String name, String description, boolean subtask) {
        if (find(name) != null && !failureLeavesTypeBehind) {
            throw new AssertionError("The writer created a type whose name is already taken in Jira: " + name);
        }
        createCalls.add(name);
        if (failCreationOf != null && failCreationOf.equals(name)) {
            if (failureLeavesTypeBehind) catalog.add(type(String.valueOf(nextId++), name, description, subtask));
            if (failReadAfterFailure) readsFailing = true;
            throw new IllegalStateException("Jira storage error");
        }
        IssueType created = type(String.valueOf(nextId++), name, description, subtask);
        catalog.add(created);
        // hook: someone else creates the next planned name right after this write (Jira UI, no plugin lock)
        if (externallyCreateAfterFirstWrite != null) {
            catalog.add(type(String.valueOf(nextId++), externallyCreateAfterFirstWrite, "", false));
            externallyCreateAfterFirstWrite = null;
        }
        return created;
    }

    private IssueType existing(String name, boolean subtask) {
        IssueType type = type(String.valueOf(nextId++), name, "Existing " + name, subtask);
        catalog.add(type);
        return type;
    }

    private IssueType find(String name) {
        for (IssueType type : catalog) if (type.getName().equals(name)) return type;
        return null;
    }

    private IssueType byId(String id) {
        for (IssueType type : catalog) if (type.getId().equals(id)) return type;
        return null;
    }

    private List<String> names() {
        List<String> names = new ArrayList<String>();
        for (IssueType type : catalog) if (!names.contains(type.getName())) names.add(type.getName());
        return names;
    }

    private static List<String> keys(CreateIssueTypesResponse response) {
        List<String> keys = new ArrayList<String>();
        for (ItemResult result : response.getResults()) keys.add(result.getKey());
        return keys;
    }

    private static List<ItemStatus> statuses(CreateIssueTypesResponse response) {
        List<ItemStatus> statuses = new ArrayList<ItemStatus>();
        for (ItemResult result : response.getResults()) statuses.add(result.getStatus());
        return statuses;
    }

    private void assertRejected(ErrorCode code, int atLeastFailedItems, CreateIssueTypesRequest request) {
        CreateIssueTypesResponse response = creator.create(request);
        assertEquals(response.getMessage(), Status.FAILED, response.getStatus());
        assertEquals(response.getMessage(), code, response.getErrorCode());
        assertNull(response.getPlanId());
        int failed = 0;
        for (ItemResult result : response.getResults()) if (result.getStatus() == ItemStatus.FAILED) failed++;
        assertTrue("the offending item is marked: " + response.getMessage(), failed >= atLeastFailedItems);
    }

    private static IssueTypeDefinition def(String key, String name, boolean subtask) {
        return new IssueTypeDefinition(key, name, null, subtask);
    }

    private static CreateIssueTypesRequest preview(IssueTypeDefinition... definitions) {
        return new CreateIssueTypesRequest(true, Arrays.asList(definitions), null);
    }

    private static CreateIssueTypesRequest apply(String planId) {
        return new CreateIssueTypesRequest(false, null, planId);
    }

    private static String repeat(String text, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) builder.append(text);
        return builder.toString();
    }

    private static ApplicationUser user(String key) {
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getKey()).thenReturn(key);
        return user;
    }

    private static IssueType type(String id, String name, String description, boolean subtask) {
        IssueType type = mock(IssueType.class);
        when(type.getId()).thenReturn(id);
        when(type.getName()).thenReturn(name);
        when(type.getDescription()).thenReturn(description);
        when(type.isSubTask()).thenReturn(subtask);
        return type;
    }
}
