package ru.rasulov.common.impl.applies;

import net.java.ao.Entity;
import net.java.ao.schema.Indexed;
import net.java.ao.schema.NotNull;
import net.java.ao.schema.StringLength;
import net.java.ao.schema.Unique;

/**
 * One finished apply through the plugin. Rows are inserted once and never updated or deleted by plugin code. Declared in
 * the existing {@code rollback-journal} AO module (a bundle has a single AO configuration) but independent of every
 * rollback-journal entity. Length limits are applied by {@link ApplyJournal}; AO column lengths are only the storage.
 */
public interface ApplyRecord extends Entity {
    @Unique
    @NotNull
    @StringLength(300)
    String getEventKey();
    void setEventKey(String value);

    @StringLength(20)
    String getEventType();
    void setEventType(String value);

    @Indexed
    long getRecordedAt();
    void setRecordedAt(long value);

    @Indexed
    @StringLength(255)
    String getActorKey();
    void setActorKey(String value);

    @StringLength(255)
    String getActorName();
    void setActorName(String value);

    @Indexed
    @StringLength(100)
    String getToolName();
    void setToolName(String value);

    @StringLength(255)
    String getToolTitle();
    void setToolTitle(String value);

    @StringLength(255)
    String getOperationRef();
    void setOperationRef(String value);

    @StringLength(30)
    String getOperationState();
    void setOperationState(String value);

    @StringLength(100)
    String getDomainStatus();
    void setDomainStatus(String value);

    @StringLength(100)
    String getErrorCode();
    void setErrorCode(String value);

    @StringLength(StringLength.UNLIMITED)
    String getErrorMessage();
    void setErrorMessage(String value);

    boolean isRollbackRequested();
    void setRollbackRequested(boolean value);

    @StringLength(StringLength.UNLIMITED)
    String getTargetsJson();
    void setTargetsJson(String value);

    @StringLength(StringLength.UNLIMITED)
    String getLinksJson();
    void setLinksJson(String value);

    @StringLength(StringLength.UNLIMITED)
    String getFieldChangesJson();
    void setFieldChangesJson(String value);
}
