package ru.rasulov.common.api.dto.issuetype;

/** Status of one requested issue type: of a create_issue_types item and of an entry of an issue-type set. */
public enum ItemStatus { WOULD_CREATE, EXISTS, CREATED, FAILED, UNKNOWN, NOT_EXECUTED }
