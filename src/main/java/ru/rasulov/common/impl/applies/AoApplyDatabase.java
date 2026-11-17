package ru.rasulov.common.impl.applies;

import com.atlassian.activeobjects.external.ActiveObjects;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import net.java.ao.Query;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Map;
import java.util.function.Consumer;

@Named
final class AoApplyDatabase implements ApplyDatabase {
    private final ActiveObjects ao;

    @Inject
    public AoApplyDatabase(@ComponentImport ActiveObjects ao) { this.ao = ao; }

    public void insert(Map<String, Object> columns) {
        ao.executeInTransaction(() -> ao.create(ApplyRecord.class, columns));
    }

    public boolean exists(String eventKey) {
        return ao.count(ApplyRecord.class, Query.select().where("EVENT_KEY = ?", eventKey)) > 0;
    }

    public ApplyRecord[] find(Query query) {
        return ao.find(ApplyRecord.class, query);
    }

    public int count(Query query) {
        return ao.count(ApplyRecord.class, query);
    }

    public void stream(Query query, Consumer<ApplyRecord> each) {
        ao.stream(ApplyRecord.class, query, each::accept);
    }
}
