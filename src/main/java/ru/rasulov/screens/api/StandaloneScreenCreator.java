package ru.rasulov.screens.api;

import ru.rasulov.screens.api.dto.CreateScreenRequest;
import ru.rasulov.screens.api.dto.CreateScreenResponse;

/** Creates an unassigned Jira screen, reusing exact fields and creating missing custom fields in a global context. */
public interface StandaloneScreenCreator {
    CreateScreenResponse create(CreateScreenRequest request);
}
