package ru.rasulov.common.impl.applies;

import java.util.List;

/** One apply result to journal. Raw values: {@link ApplyJournal} applies the storage limits. */
public final class ApplyEvent {
    public static final String APPLY = "APPLY";
    public static final String ROLLBACK = "ROLLBACK";

    public final String eventKey;
    public final String eventType;
    public final long recordedAt;
    public final String actorKey;
    public final String actorName;
    public final String toolName;
    public final String toolTitle;
    public final String operationRef;
    public final String operationState;
    public final String domainStatus;
    public final String errorCode;
    public final String errorMessage;
    public final boolean rollbackRequested;
    public final List<ApplyTarget> targets;
    /** Targets already dropped by the caller. */
    public final int moreTargets;
    public final List<ApplyLink> links;
    public final ApplyFieldChanges fieldChanges;

    public ApplyEvent(String eventKey, String eventType, long recordedAt, String actorKey, String actorName,
                      String toolName, String toolTitle, String operationRef, String operationState,
                      String domainStatus, String errorCode, String errorMessage, boolean rollbackRequested,
                      List<ApplyTarget> targets, int moreTargets, List<ApplyLink> links) {
        this(eventKey, eventType, recordedAt, actorKey, actorName, toolName, toolTitle, operationRef, operationState,
                domainStatus, errorCode, errorMessage, rollbackRequested, targets, moreTargets, links, null);
    }

    public ApplyEvent(String eventKey, String eventType, long recordedAt, String actorKey, String actorName,
                      String toolName, String toolTitle, String operationRef, String operationState,
                      String domainStatus, String errorCode, String errorMessage, boolean rollbackRequested,
                      List<ApplyTarget> targets, int moreTargets, List<ApplyLink> links, ApplyFieldChanges fieldChanges) {
        this.eventKey = eventKey;
        this.eventType = eventType;
        this.recordedAt = recordedAt;
        this.actorKey = actorKey;
        this.actorName = actorName;
        this.toolName = toolName;
        this.toolTitle = toolTitle;
        this.operationRef = operationRef;
        this.operationState = operationState;
        this.domainStatus = domainStatus;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.rollbackRequested = rollbackRequested;
        this.targets = targets == null ? java.util.Collections.<ApplyTarget>emptyList() : targets;
        this.moreTargets = Math.max(0, moreTargets);
        this.links = links == null ? java.util.Collections.<ApplyLink>emptyList() : links;
        this.fieldChanges = fieldChanges;
    }
}
