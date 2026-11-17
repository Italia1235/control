package ru.rasulov.customfields.api;

import ru.rasulov.customfields.api.dto.context.CreateFieldContextRequest;
import ru.rasulov.customfields.api.dto.context.CreateFieldContextResponse;

/** Creates one new context of an existing custom field. */
public interface FieldContextCreator {
    CreateFieldContextResponse create(CreateFieldContextRequest request);
}
