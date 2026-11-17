package ru.rasulov.screens.api;

import ru.rasulov.screens.api.dto.CloneProjectScreensRequest;
import ru.rasulov.screens.api.dto.CloneProjectScreensResponse;
import ru.rasulov.screens.api.dto.ProjectScreensResponse;

/** Reads and detaches a project's issue type screen setup from setups shared with other projects. */
public interface ScreenSchemeService {
    ProjectScreensResponse projectScreens(String project);
    CloneProjectScreensResponse cloneProjectScreens(CloneProjectScreensRequest request);
}
