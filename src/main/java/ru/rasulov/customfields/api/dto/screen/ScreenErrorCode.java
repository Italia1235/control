package ru.rasulov.customfields.api.dto.screen;

public enum ScreenErrorCode {
    /** No screen id given, or the same screen twice. */
    VALIDATION_ERROR,
    /** No screen has this id. */
    UNKNOWN_SCREEN,
    /** The screen has no such tab, or no tab at all. */
    UNKNOWN_TAB,
    /** The target tab has no field with the requested anchor id. */
    UNKNOWN_ANCHOR,
    /** Jira failed to change the screen. */
    JIRA_ERROR
}
