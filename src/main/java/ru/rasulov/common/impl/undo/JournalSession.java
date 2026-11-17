package ru.rasulov.common.impl.undo;

import net.java.ao.Entity;
import net.java.ao.schema.Unique;
import net.java.ao.schema.Indexed;

public interface JournalSession extends Entity {
    @Unique
    String getSessionKey();
    void setSessionKey(String value);

    @Indexed
    String getActorKey();
    void setActorKey(String value);

    @Indexed
    long getCreatedAt();
    void setCreatedAt(long value);

    long getBytes();
    void setBytes(long value);
}
