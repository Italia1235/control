package ru.rasulov.workflow.api.dto.clone;

/** What happens to one workflow of the project's scheme. */
public enum CloneVerdict {
    /** The project gets a copy under a new name. */
    COPY,
    /** Not asked for: the project keeps using the workflow, together with the projects that use it. */
    KEEP,
    /** No other project uses it, so a copy would change nothing. */
    ALREADY_OWN
}
