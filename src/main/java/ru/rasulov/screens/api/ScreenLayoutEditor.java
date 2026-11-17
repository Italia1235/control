package ru.rasulov.screens.api;

import ru.rasulov.screens.api.dto.layout.EditScreenLayoutRequest;
import ru.rasulov.screens.api.dto.layout.EditScreenLayoutResponse;

public interface ScreenLayoutEditor {
    EditScreenLayoutResponse edit(EditScreenLayoutRequest request);
}
