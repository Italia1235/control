package ru.rasulov.customfields.api.dto.screen;

public enum ScreenStatus {
    /** The field was added to the screen. */
    ADDED,
    /** Dry run: the field would be added. */
    WOULD_ADD,
    /** The field is already on the screen; nothing was changed. */
    ALREADY_PRESENT,
    /** Nothing was changed; see {@link ScreenResult#getErrorCode()}. */
    FAILED,
    /** A write was started and its outcome was not confirmed; the screen may have been changed. Read the screen. */
    UNKNOWN,
    /** Not started: an earlier screen of this call ended with an unconfirmed or Jira failure, so the call stopped. */
    NOT_ATTEMPTED
}
