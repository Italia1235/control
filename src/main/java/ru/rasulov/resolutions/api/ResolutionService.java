package ru.rasulov.resolutions.api;

import java.util.List;
import ru.rasulov.resolutions.api.dto.CreateResolutionRequest;
import ru.rasulov.resolutions.api.dto.CreateResolutionResponse;
import ru.rasulov.resolutions.api.dto.ResolutionView;

public interface ResolutionService {
    List<ResolutionView> get(String query);
    CreateResolutionResponse create(CreateResolutionRequest request);
}
