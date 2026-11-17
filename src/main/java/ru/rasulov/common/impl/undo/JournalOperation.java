package ru.rasulov.common.impl.undo;

import net.java.ao.Entity;
import net.java.ao.schema.Default;
import net.java.ao.schema.Unique;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.StringLength;

public interface JournalOperation extends Entity {
    @Unique
    String getOperationKey();
    void setOperationKey(String value);

    @Indexed
    String getSessionKey();
    void setSessionKey(String value);

    @Indexed
    String getChangeSetKey();
    void setChangeSetKey(String value);

    @Indexed
    String getActorKey();
    void setActorKey(String value);

    /** SHA-256 avoids retaining a reusable preview token in the durable journal. Null on legacy rows. */
    @Indexed
    @StringLength(64)
    String getPlanHash();
    void setPlanHash(String value);

    String getName();
    void setName(String value);

    /**
     * Execution lock slot held by the live worker for its whole call ({@code ChangeJournal.EXECUTION_SLOTS}); -1 before
     * the worker starts and on legacy rows. Recovery tests this slot instead of a per-operation lock name.
     */
    @Default("-1")
    int getExecutionSlot();
    void setExecutionSlot(int value);

    @Indexed
    String getState();
    void setState(String value);

    boolean isCancelRequested();
    void setCancelRequested(boolean value);

    boolean isRollbackRequested();
    void setRollbackRequested(boolean value);

    @StringLength(StringLength.UNLIMITED)
    String getResultJson();
    void setResultJson(String value);

    @StringLength(StringLength.UNLIMITED)
    String getError();
    void setError(String value);

    long getCreatedAt();
    void setCreatedAt(long value);

    long getStartedAt();
    void setStartedAt(long value);

    long getFinishedAt();
    void setFinishedAt(long value);

    @Indexed
    long getHeartbeatAt();
    void setHeartbeatAt(long value);

    long getReservedBytes();
    void setReservedBytes(long value);

    long getBytes();
    void setBytes(long value);
}
