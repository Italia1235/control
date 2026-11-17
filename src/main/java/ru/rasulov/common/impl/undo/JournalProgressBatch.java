package ru.rasulov.common.impl.undo;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Table;

/** One bounded intent/receipt pair for up to 50 issues or one whole scheme step. Not undo. */
@Table("JournalBatch")
public interface JournalProgressBatch extends Entity {
    @Indexed
    String getOperationKey();
    void setOperationKey(String value);

    String getKind();
    void setKind(String value);

    @StringLength(StringLength.UNLIMITED)
    String getBeforeJson();
    void setBeforeJson(String value);

    @StringLength(StringLength.UNLIMITED)
    String getAfterJson();
    void setAfterJson(String value);

    long getAllocatedBytes();
    void setAllocatedBytes(long value);
}
