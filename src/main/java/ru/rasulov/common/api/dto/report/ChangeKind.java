package ru.rasulov.common.api.dto.report;

/** What a create tool made or reused; closed on purpose, so the model never invents a kind. */
public enum ChangeKind {
    PROJECT, CUSTOM_FIELD, FIELD_CONTEXT, OPTION, SCREEN, SCREEN_TAB, WORKFLOW, STATUS, ISSUE_TYPE, RESOLUTION,
    FIELD_CONFIGURATION, FIELD_CONFIGURATION_SCHEME
}
