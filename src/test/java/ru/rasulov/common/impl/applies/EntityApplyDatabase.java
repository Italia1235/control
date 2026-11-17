package ru.rasulov.common.impl.applies;

import net.java.ao.EntityManager;
import net.java.ao.Query;

import java.sql.SQLException;
import java.util.Map;
import java.util.function.Consumer;

/** Real AO entity manager over HSQL, used like the production store. */
final class EntityApplyDatabase implements ApplyDatabase {
    private final EntityManager entities;

    EntityApplyDatabase(EntityManager entities) {
        this.entities = entities;
    }

    public void insert(Map<String, Object> columns) {
        try { entities.create(ApplyRecord.class, columns); } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public boolean exists(String key) {
        try { return entities.count(ApplyRecord.class, Query.select().where("EVENT_KEY = ?", key)) > 0; }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public ApplyRecord[] find(Query query) {
        try { return entities.find(ApplyRecord.class, query); } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public int count(Query query) {
        try { return entities.count(ApplyRecord.class, query); } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    public void stream(Query query, Consumer<ApplyRecord> each) {
        try { entities.stream(ApplyRecord.class, query, each::accept); } catch (SQLException e) { throw new IllegalStateException(e); }
    }
}
