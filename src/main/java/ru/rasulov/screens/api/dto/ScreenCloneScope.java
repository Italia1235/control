package ru.rasulov.screens.api.dto;

/** Which field screen schemes below the project's issue type screen scheme get copies. */
public enum ScreenCloneScope {
    /** Copy only the issue type screen scheme; keep every existing field screen scheme and screen. */
    SCHEME_ONLY,
    /** Copy every field screen scheme used by the project's issue types. */
    ALL,
    /** Copy the effective field screen schemes only for the issue types named in the request. */
    SELECTED
}
