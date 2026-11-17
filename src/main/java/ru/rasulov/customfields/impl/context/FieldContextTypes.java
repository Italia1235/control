package ru.rasulov.customfields.impl.context;

import ru.rasulov.customfields.api.dto.context.FieldContextsResponse.TypeClass;
import ru.rasulov.customfields.impl.assets.AssetsFieldConfiguration;
import ru.rasulov.customfields.impl.filefield.FileFieldConfiguration;
import ru.rasulov.customfields.impl.grid.GridFieldConfiguration;
import ru.rasulov.customfields.impl.validation.TypeKeys;

/** How context tools classify a field type; the single place, shared by the reader and the context writers. */
final class FieldContextTypes {
    private FieldContextTypes() { }

    static TypeClass typeClass(String key) {
        if (key != null && key.startsWith(TypeKeys.SYSTEM_PLUGIN + ":")) return TypeClass.SYSTEM;
        if (AssetsFieldConfiguration.TYPE_KEY.equals(key) || FileFieldConfiguration.TYPE_KEY.equals(key)
                || GridFieldConfiguration.TYPE_KEY.equals(key)) return TypeClass.KNOWN_PLUGIN;
        return TypeClass.THIRD_PARTY;
    }
}
