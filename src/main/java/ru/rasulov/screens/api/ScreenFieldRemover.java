package ru.rasulov.screens.api;

import ru.rasulov.screens.api.dto.layout.RemoveFieldFromScreensRequest;
import ru.rasulov.screens.api.dto.layout.RemoveFieldFromScreensResponse;

public interface ScreenFieldRemover {
    RemoveFieldFromScreensResponse remove(RemoveFieldFromScreensRequest request);
}
