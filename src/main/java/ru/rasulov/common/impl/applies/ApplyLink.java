package ru.rasulov.common.impl.applies;

/** An administrative link reported by the tool; {@code name} may be absent. */
public final class ApplyLink {
    public final String name;
    public final String url;

    public ApplyLink(String name, String url) {
        this.name = name;
        this.url = url;
    }
}
