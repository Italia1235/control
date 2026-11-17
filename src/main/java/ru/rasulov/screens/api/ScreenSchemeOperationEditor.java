package ru.rasulov.screens.api;

import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsRequest;
import ru.rasulov.screens.api.dto.SetScreenSchemeOperationsResponse;

/** Edits the rows (operation to screen) of an existing Screen Scheme in place, for every project that uses it. */
public interface ScreenSchemeOperationEditor {
    SetScreenSchemeOperationsResponse edit(SetScreenSchemeOperationsRequest request);
}
