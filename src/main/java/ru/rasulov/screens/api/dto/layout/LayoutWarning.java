package ru.rasulov.screens.api.dto.layout;

import com.fasterxml.jackson.annotation.JsonProperty;

public final class LayoutWarning {
    private final String code;
    private final String message;
    public LayoutWarning(String code, String message) { this.code = code; this.message = message; }
    @JsonProperty("code") public String getCode() { return code; }
    @JsonProperty("message") public String getMessage() { return message; }
}
