package ru.rasulov.common.api.dto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Defensive copies for DTOs. Public only so that {@code api.dto.context} can use it; not part of the contract. */
public final class Immutables {

    private Immutables() {
    }

    /** Unmodifiable defensive copy; {@code null} becomes an empty list. */
    public static <T> List<T> listOf(List<? extends T> source) {
        if (source == null) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<T>(source));
    }
}
