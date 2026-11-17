package ru.rasulov.screens.api;

import ru.rasulov.screens.api.dto.ScreenContentsResponse;

/** Administrator-only read of one screen, with snapshot-bound continuation. */
public interface ScreenContentsReader {
    ScreenContentsResponse read(long screenId, String cursor);
}
