package ru.rasulov.customfields.api.dto.screen;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Outcome for one {@link ScreenTarget}. */
public final class ScreenResult {

    private final Long screenId;
    private final String name;
    private final ScreenStatus status;
    private final String tab;
    private final ScreenErrorCode errorCode;
    private final String message;

    private ScreenResult(Long screenId, String name, ScreenStatus status, String tab, ScreenErrorCode errorCode,
                         String message) {
        this.screenId = screenId;
        this.name = name;
        this.status = status;
        this.tab = tab;
        this.errorCode = errorCode;
        this.message = message;
    }

    public static ScreenResult added(long screenId, String name, String tab) {
        return new ScreenResult(screenId, name, ScreenStatus.ADDED, tab, null, null);
    }

    public static ScreenResult wouldAdd(long screenId, String name, String tab) {
        return new ScreenResult(screenId, name, ScreenStatus.WOULD_ADD, tab, null, null);
    }

    public static ScreenResult alreadyPresent(long screenId, String name) {
        return new ScreenResult(screenId, name, ScreenStatus.ALREADY_PRESENT, null, null, null);
    }

    public static ScreenResult failed(Long screenId, String name, ScreenErrorCode errorCode, String message) {
        return new ScreenResult(screenId, name, ScreenStatus.FAILED, null, errorCode, message);
    }

    /** A write was started and its outcome could not be confirmed: the screen may have been changed. */
    public static ScreenResult unknown(Long screenId, String name, String message) {
        return new ScreenResult(screenId, name, ScreenStatus.UNKNOWN, null, ScreenErrorCode.JIRA_ERROR, message);
    }

    /** Not started because an earlier screen of the same call stopped it. */
    public static ScreenResult notAttempted(Long screenId, String message) {
        return new ScreenResult(screenId, null, ScreenStatus.NOT_ATTEMPTED, null, null, message);
    }

    @JsonProperty("screenId")
    public Long getScreenId() {
        return screenId;
    }

    /** {@code null} when the screen does not exist. */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("status")
    public ScreenStatus getStatus() {
        return status;
    }

    /** Name of the tab the field was (or would be) added to. */
    @JsonProperty("tab")
    public String getTab() {
        return tab;
    }

    /** Set only for {@code FAILED}. */
    @JsonProperty("errorCode")
    public ScreenErrorCode getErrorCode() {
        return errorCode;
    }

    /** Set only for {@code FAILED}: what to fix. */
    @JsonProperty("message")
    public String getMessage() {
        return message;
    }
}
