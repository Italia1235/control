package ru.rasulov.customfields.api;

import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsRequest;
import ru.rasulov.customfields.api.dto.context.UpdateFieldContextsResponse;

public interface FieldContextScopeService {
    UpdateFieldContextsResponse update(UpdateFieldContextsRequest request);
}
