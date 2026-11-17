package ru.rasulov.common.impl.undo;

import net.java.ao.Entity;
import net.java.ao.schema.Default;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.StringLength;

/** Latest forward-only execution receipt; deliberately not an undo step. */
public interface JournalProgress extends Entity {
    @Indexed
    String getOperationKey();
    void setOperationKey(String value);

    @StringLength(StringLength.UNLIMITED)
    String getExecutionJson();
    void setExecutionJson(String value);

    boolean isSettled();
    void setSettled(boolean value);

    long getAllocatedBytes();
    void setAllocatedBytes(long value);

    /** Reserved batch slots belong only to this forward-progress ledger, never to undo. */
    @Default("0")
    long getReservedBytes();
    void setReservedBytes(long value);
}
