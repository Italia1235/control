package ru.rasulov.common.impl.undo;

import net.java.ao.Entity;
import net.java.ao.schema.Unique;
import net.java.ao.schema.Default;
import net.java.ao.schema.Indexed;

public interface JournalChangeSet extends Entity {
    @Unique
    String getChangeSetKey();
    void setChangeSetKey(String value);

    @Indexed
    String getSessionKey();
    void setSessionKey(String value);

    @Indexed
    String getActorKey();
    void setActorKey(String value);

    @Indexed
    String getState();
    void setState(String value);

    long getCreatedAt();
    void setCreatedAt(long value);

    long getFinishedAt();
    void setFinishedAt(long value);

    @Indexed
    long getExpiresAt();
    void setExpiresAt(long value);

    long getBytes();
    void setBytes(long value);

    /** Creation intent: the operation runner owns this group's single operation and closes it. */
    @Default("false")
    boolean isRunnerOwned();
    void setRunnerOwned(boolean value);

    /** Fallback-reclamation queue for runner-owned groups; 0 for explicit and legacy groups. */
    @Default("0")
    @Indexed
    long getNextCleanupAt();
    void setNextCleanupAt(long value);
}
