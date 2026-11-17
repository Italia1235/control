package ru.rasulov.projects.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * Result of {@code create_project {action:"list_templates"}}: the project templates Jira reports, sorted by
 * projectTypeKey then templateKey. {@code OK} with an empty list means Jira reported no template; {@code FAILED}
 * means the catalog could not be read, which says nothing about which templates exist.
 */
public final class ProjectTemplatesResponse {
    public enum Status { OK, FAILED }

    /** One template Jira reports. {@code supported} is true only when both its key and its type are in Control's table. */
    public static final class Template {
        private final String templateKey;
        private final String projectTypeKey;
        private final String name;
        private final String description;
        private final boolean supported;

        public Template(String templateKey, String projectTypeKey, String name, String description, boolean supported) {
            this.templateKey = templateKey;
            this.projectTypeKey = projectTypeKey;
            this.name = name;
            this.description = description;
            this.supported = supported;
        }

        @JsonProperty("templateKey")
        public String getTemplateKey() { return templateKey; }

        @JsonProperty("projectTypeKey")
        public String getProjectTypeKey() { return projectTypeKey; }

        @JsonProperty("name")
        public String getName() { return name; }

        @JsonProperty("description")
        public String getDescription() { return description; }

        @JsonProperty("supported")
        public boolean isSupported() { return supported; }
    }

    private final Status status;
    private final List<Template> templates;
    private final String errorCode;
    private final String message;

    private ProjectTemplatesResponse(Status status, List<Template> templates, String errorCode, String message) {
        this.status = status;
        this.templates = Immutables.listOf(templates);
        this.errorCode = errorCode;
        this.message = message;
    }

    public static ProjectTemplatesResponse ok(List<Template> templates) {
        return new ProjectTemplatesResponse(Status.OK, templates, null, templates.isEmpty()
                ? "Jira reports no project templates. Nothing can be created until an administrator enables one."
                : "Create only from a template with supported=true; use its templateKey and nothing else.");
    }

    public static ProjectTemplatesResponse failed(String message) {
        return new ProjectTemplatesResponse(Status.FAILED, null, "JIRA_ERROR", message);
    }

    @JsonProperty("status")
    public Status getStatus() { return status; }

    @JsonProperty("templates")
    public List<Template> getTemplates() { return templates; }

    @JsonProperty("errorCode") @JsonInclude(JsonInclude.Include.NON_NULL)
    public String getErrorCode() { return errorCode; }

    @JsonProperty("message")
    public String getMessage() { return message; }
}
