package ru.rasulov.common.impl.undo;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.StringLength;

public interface JournalStep extends Entity {
    @Indexed
    String getOperationKey();
    void setOperationKey(String value);

    String getKind();
    void setKind(String value);

    @StringLength(450)
    String getResource();
    void setResource(String value);

    @StringLength(StringLength.UNLIMITED)
    String getBeforeJson();
    void setBeforeJson(String value);

    @StringLength(StringLength.UNLIMITED)
    String getAfterJson();
    void setAfterJson(String value);

    /** Fits the reserved error allowance once the step is UNDONE and cannot fail again. */
    @StringLength(StringLength.UNLIMITED)
    String getUndoReceiptJson();
    void setUndoReceiptJson(String value);

    @Indexed
    String getState();
    void setState(String value);

    @StringLength(StringLength.UNLIMITED)
    String getError();
    void setError(String value);

    long getCreatedAt();
    void setCreatedAt(long value);

    long getAllocatedBytes();
    void setAllocatedBytes(long value);

    int getMaxAfterBytes();
    void setMaxAfterBytes(int value);
}
