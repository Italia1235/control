package ru.rasulov.workflow.api;

import ru.rasulov.workflow.api.dto.edit.UpdateStatusRequest;
import ru.rasulov.workflow.api.dto.edit.UpdateStatusResponse;

/** Changes the name, description or category of one global status, which changes it in every workflow that uses it. */
public interface StatusUpdater {

    UpdateStatusResponse update(UpdateStatusRequest request);
}
