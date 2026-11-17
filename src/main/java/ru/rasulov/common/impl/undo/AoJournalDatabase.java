package ru.rasulov.common.impl.undo;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import net.java.ao.DBParam;
import net.java.ao.Query;
import net.java.ao.RawEntity;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.function.Supplier;

@Named
final class AoJournalDatabase implements JournalDatabase {
    private final ActiveObjects ao;

    @Inject
    public AoJournalDatabase(@ComponentImport ActiveObjects ao) { this.ao = ao; }

    public <T extends RawEntity<Integer>> T[] find(Class<T> type, Query query) { return ao.find(type, query); }
    public <T extends RawEntity<Integer>> int count(Class<T> type, Query query) { return ao.count(type, query); }
    public <T extends RawEntity<Integer>> T create(Class<T> type, DBParam... values) { return ao.create(type, values); }
    public void delete(RawEntity<?>... rows) { ao.delete(rows); }
    public void flushAll() { ao.flushAll(); }
    public <T> T transaction(Supplier<T> work) { return ao.executeInTransaction(work::get); }
}
