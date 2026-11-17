package ru.rasulov.screens.api;

import ru.rasulov.screens.api.dto.SetProjectOperationScreensRequest;
import ru.rasulov.screens.api.dto.SetProjectOperationScreensResponse;

/** Assigns existing operation screens to one project through private scheme copies. */
public interface ProjectOperationScreenAssigner {
    SetProjectOperationScreensResponse assign(SetProjectOperationScreensRequest request);
}
