package ru.rasulov.common.impl;

import java.util.Locale;

/** How names and option values are compared: trimmed and case-insensitive, so "Low" and " low " are the same. */
public final class Normalizer {

    private Normalizer() {
    }

    public static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
