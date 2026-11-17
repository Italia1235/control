package ru.rasulov.common.impl.undo;

import com.atlassian.beehive.ClusterLockService;
import com.atlassian.cache.Cache;
import com.atlassian.cache.CacheManager;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.user.util.UserManager;
import net.java.ao.DBParam;
import net.java.ao.EntityManager;
import net.java.ao.Query;
import net.java.ao.RawEntity;
import net.java.ao.Transaction;
import net.java.ao.builder.EntityManagerBuilder;
import net.java.ao.schema.Case;
import net.java.ao.schema.UnderscoreFieldNameConverter;
import net.java.ao.atlassian.AtlassianTableNameConverter;
import ru.rasulov.common.impl.ActionPlanStore;
import ru.rasulov.common.impl.AdministratorGuard;
import ru.rasulov.common.impl.NamedLock;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real AO EntityManager/HSQL storage; only Jira auth, cluster locks, and the preview cache are test doubles. */
public final class UndoTestFixture implements AutoCloseable {
    public final EntityManager entityManager;
    public final NamedLock locks;
    public final AdministratorGuard guard;
    public final UserManager users;
    public final ActionPlanStore plans;
    public final AtomicLong clock = new AtomicLong(1_000_000L);
    public final AtomicReference<ApplicationUser> currentActor = new AtomicReference<>();
    public final JournalStore store;
    public final ChangeJournal journal;
    private final JournalDatabase database;
    private final Map<String, InMemoryClusterLock> nativeLocks = new ConcurrentHashMap<>();
    private final long capacity;
    private final UndoHandler[] handlers;

    public static UndoTestFixture create(String actorKey, UndoHandler... handlers) {
        return new UndoTestFixture(actorKey, JournalStore.DEFAULT_CAPACITY, handlers);
    }

    public static UndoTestFixture create(String actorKey, long capacity, UndoHandler... handlers) {
        return new UndoTestFixture(actorKey, capacity, handlers);
    }

    @SuppressWarnings("unchecked")
    private UndoTestFixture(String actorKey, long capacity, UndoHandler[] handlers) {
        this.capacity = capacity;
        this.handlers = handlers;
        entityManager = EntityManagerBuilder.url("jdbc:hsqldb:mem:undo_" + UUID.randomUUID().toString().replace("-", ""))
                .username("SA").password("").none()
                .fieldNameConverter(new UnderscoreFieldNameConverter(Case.UPPER))
                .tableNameConverter(new AtlassianTableNameConverter(name -> "AO_3E3015_" + name)).build();
        try { entityManager.migrate(JournalSession.class, JournalChangeSet.class, JournalOperation.class, JournalStep.class, JournalBudget.class, JournalProgress.class, JournalProgressBatch.class); }
        catch (SQLException e) { throw new IllegalStateException(e); }
        database = new EntityDatabase(entityManager);
        ClusterLockService cluster = mock(ClusterLockService.class);
        // Every name ever requested is kept, as Beehive keeps it in clusterlockstatus.
        when(cluster.getLockForName(anyString())).thenAnswer(call ->
                nativeLocks.computeIfAbsent(call.getArgument(0), key -> new InMemoryClusterLock()));
        locks = new NamedLock(cluster);
        guard = mock(AdministratorGuard.class);
        users = mock(UserManager.class);
        ApplicationUser owner = user(actorKey);
        currentActor.set(owner);
        when(guard.requireAdministrator()).thenAnswer(call -> currentActor.get());
        when(users.getUserByKey(anyString())).thenAnswer(call -> {
            ApplicationUser current = currentActor.get();
            return current.getKey().equals(call.getArgument(0)) ? current : null;
        });
        CacheManager manager = mock(CacheManager.class);
        Map<String, Cache> caches = new ConcurrentHashMap<>();
        when(manager.getCache(anyString(),
                org.mockito.ArgumentMatchers.isNull(),
                any(com.atlassian.cache.CacheSettings.class))).thenAnswer(invocation ->
                caches.computeIfAbsent(invocation.getArgument(0), name -> {
                    Cache cache = mock(Cache.class);
                    Map<String, Object> values = new ConcurrentHashMap<>();
                    when(cache.get(anyString())).thenAnswer(call -> values.get(call.getArgument(0)));
                    doAnswer(call -> { values.put(call.getArgument(0), call.getArgument(1)); return null; })
                            .when(cache).put(anyString(), any());
                    doAnswer(call -> { values.remove(call.getArgument(0)); return null; })
                            .when(cache).remove(anyString());
                    return cache;
                }));
        plans = new ActionPlanStore(manager);
        store = new JournalStore(database, locks, capacity, clock::get);
        journal = new ChangeJournal(store, locks, plans, guard, users, Arrays.asList(handlers));
    }

    public ChangeJournal reopen() {
        return new ChangeJournal(reopenStore(), locks, plans, guard, users, Arrays.asList(handlers));
    }

    public JournalStore reopenStore() {
        return new JournalStore(database, locks, capacity, clock::get);
    }

    /** Every cluster lock name requested so far. */
    public java.util.Set<String> lockNames() {
        return nativeLocks.keySet();
    }

    private static final class InMemoryClusterLock extends ReentrantLock
            implements com.atlassian.beehive.ClusterLock {
        private static final long serialVersionUID = 1L;
    }

    public static ApplicationUser user(String key) {
        ApplicationUser user = mock(ApplicationUser.class);
        when(user.getKey()).thenReturn(key);
        when(user.getName()).thenReturn(key);
        when(user.isActive()).thenReturn(true);
        return user;
    }

    @Override public void close() { entityManager.getProvider().dispose(); }

    private static final class EntityDatabase implements JournalDatabase {
        private final EntityManager manager;
        EntityDatabase(EntityManager manager) { this.manager = manager; }
        public <T extends RawEntity<Integer>> T[] find(Class<T> type, Query query) {
            try { return manager.find(type, query); } catch (SQLException e) { throw new IllegalStateException(e); }
        }
        public <T extends RawEntity<Integer>> int count(Class<T> type, Query query) {
            try { return manager.count(type, query); } catch (SQLException e) { throw new IllegalStateException(e); }
        }
        public <T extends RawEntity<Integer>> T create(Class<T> type, DBParam... values) {
            try { return manager.create(type, values); } catch (SQLException e) { throw new IllegalStateException(e); }
        }
        public void delete(RawEntity<?>... rows) {
            try { manager.delete(rows); } catch (SQLException e) { throw new IllegalStateException(e); }
        }
        public void flushAll() { manager.flushAll(); }
        public <T> T transaction(Supplier<T> work) {
            try { return new Transaction<T>(manager) { protected T run() { return work.get(); } }.execute(); }
            catch (SQLException e) { throw new IllegalStateException(e); }
        }
    }
}
