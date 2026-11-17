package ru.rasulov.common.api.dto.field;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.field.context.ContextSummary;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/** Outcome for one {@link FieldDefinition}. */
public final class FieldCreationResult {

    private final String name;
    private final CreationStatus status;
    private final String customFieldId;
    private final CustomFieldErrorCode errorCode;
    private final String message;
    private final List<String> suggestions;
    private final List<ContextSummary> contexts;

    private FieldCreationResult(String name, CreationStatus status, String customFieldId,
                                CustomFieldErrorCode errorCode, String message, List<String> suggestions,
                                List<ContextSummary> contexts) {
        this.name = name;
        this.status = status;
        this.customFieldId = customFieldId;
        this.errorCode = errorCode;
        this.message = message;
        this.suggestions = Immutables.listOf(suggestions);
        this.contexts = Immutables.listOf(contexts);
    }

    /** @param message a warning, or {@code null} */
    public static FieldCreationResult created(String name, String customFieldId, List<ContextSummary> contexts,
                                              String message) {
        return new FieldCreationResult(name, CreationStatus.CREATED, customFieldId, null, message, null, contexts);
    }

    /** @param message a warning, or {@code null} */
    public static FieldCreationResult wouldCreate(String name, List<ContextSummary> contexts, String message) {
        return new FieldCreationResult(name, CreationStatus.WOULD_CREATE, null, null, message, null, contexts);
    }

    /**
     * @param contexts the contexts the existing field really has
     * @param message  {@code null} when the request matches the existing field; otherwise names the parts of the
     *                 request that were ignored because the field already existed
     */
    public static FieldCreationResult exists(String name, String customFieldId, List<ContextSummary> contexts,
                                             String message) {
        return new FieldCreationResult(name, CreationStatus.EXISTS, customFieldId, null, message, null, contexts);
    }

    /**
     * The field exists but what Jira reports differs from the plan.
     *
     * @param contexts the contexts Jira reports, not the planned ones
     * @param message  every difference and the real ids; nothing was undone
     */
    public static FieldCreationResult partial(String name, String customFieldId, List<ContextSummary> contexts,
                                              String message) {
        return new FieldCreationResult(name, CreationStatus.PARTIAL, customFieldId,
                CustomFieldErrorCode.CONTEXT_MISMATCH, message, null, contexts);
    }

    public static FieldCreationResult failed(String name, CustomFieldErrorCode errorCode, String message,
                                             List<String> suggestions) {
        return new FieldCreationResult(name, CreationStatus.FAILED, null, errorCode, message, suggestions, null);
    }

    @JsonProperty("name")
    public String getName() {
        return name;
    }

    @JsonProperty("status")
    public CreationStatus getStatus() {
        return status;
    }

    /** {@code customfield_10123}; {@code null} for {@code WOULD_CREATE} and {@code FAILED}. */
    @JsonProperty("customFieldId")
    public String getCustomFieldId() {
        return customFieldId;
    }

    /** Set only for {@code FAILED}. */
    @JsonProperty("errorCode")
    public CustomFieldErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * For {@code FAILED}: what to fix. For {@code EXISTS}: what of the request was ignored, if anything. For
     * {@code WOULD_CREATE} and {@code CREATED}: a warning, if there is one.
     */
    @JsonProperty("message")
    public String getMessage() {
        return message;
    }

    /** Valid values the caller may use instead; empty when none can be computed. */
    @JsonProperty("suggestions")
    public List<String> getSuggestions() {
        return suggestions;
    }

    /**
     * Where the field applies: the contexts that will be or were created, or, for {@code EXISTS}, the ones the field
     * already has. Empty for {@code FAILED}. The caller shows them to the user before confirming.
     */
    @JsonProperty("contexts")
    public List<ContextSummary> getContexts() {
        return contexts;
    }
}
