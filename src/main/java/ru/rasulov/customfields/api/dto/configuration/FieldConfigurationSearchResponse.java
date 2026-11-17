package ru.rasulov.customfields.api.dto.configuration;

import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ConfigurationRef;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.ErrorCode;
import ru.rasulov.customfields.api.dto.configuration.FieldConfigurationViews.SchemeRef;
import ru.rasulov.common.api.dto.ProjectRef;

import java.util.List;

/**
 * Result of the search mode of {@code get_field_configurations}: Field Configurations or Field Configuration Schemes
 * found by id or name, independent of any project, with where each is used. Names are not unique: several items with
 * one name are candidates, never a guess. A usage that could not be read is {@code UNAVAILABLE}, which is not
 * "unused" and never a reason to delete.
 */
public final class FieldConfigurationSearchResponse {
    public enum Status { OK, FAILED }

    public enum Kind { CONFIGURATION, SCHEME }

    /** READ: Jira's usage was read successfully (the page may still be truncated); UNAVAILABLE: it could not be read. */
    public enum UsageStatus { READ, UNAVAILABLE }

    private final Status status;
    private final Kind kind;
    private final String id;
    private final String name;
    private final int total;
    private final int offset;
    private final int limit;
    private final Integer nextOffset;
    private final boolean usageComplete;
    private final List<Item> items;
    private final ErrorCode errorCode;
    private final String message;

    private FieldConfigurationSearchResponse(Status status, Kind kind, String id, String name, int total, int offset,
                                             int limit, Integer nextOffset, boolean usageComplete, List<Item> items,
                                             ErrorCode errorCode, String message) {
        this.status = status;
        this.kind = kind;
        this.id = id;
        this.name = name;
        this.total = total;
        this.offset = offset;
        this.limit = limit;
        this.nextOffset = nextOffset;
        this.usageComplete = usageComplete;
        this.items = Immutables.listOf(items);
        this.errorCode = errorCode;
        this.message = message;
    }

    public static FieldConfigurationSearchResponse ok(Kind kind, String id, String name, int total, int offset,
                                                      int limit, Integer nextOffset, List<Item> items) {
        boolean complete = true;
        for (Item item : items) complete &= item.getUsage().getStatus() == UsageStatus.READ && !item.getUsage().isTruncated();
        return new FieldConfigurationSearchResponse(Status.OK, kind, id, name, total, offset, limit, nextOffset,
                complete, items, null, null);
    }

    public static FieldConfigurationSearchResponse failed(Kind kind, String id, String name, ErrorCode code,
                                                          String message) {
        return new FieldConfigurationSearchResponse(Status.FAILED, kind, id, name, 0, 0, 0, null, false, null, code,
                message);
    }

    @JsonProperty("status") public Status getStatus() { return status; }
    @JsonProperty("kind") public Kind getKind() { return kind; }
    @JsonProperty("id") public String getId() { return id; }
    @JsonProperty("name") public String getName() { return name; }
    @JsonProperty("total") public int getTotal() { return total; }
    @JsonProperty("offset") public int getOffset() { return offset; }
    @JsonProperty("limit") public int getLimit() { return limit; }
    /** The offset of the next page; null on the last page. */
    @JsonProperty("nextOffset") public Integer getNextOffset() { return nextOffset; }
    /** True only when every usage on this page was read AND is fully included (nothing truncated or unavailable). */
    @JsonProperty("usageComplete") public boolean isUsageComplete() { return usageComplete; }
    @JsonProperty("items") public List<Item> getItems() { return items; }
    @JsonProperty("errorCode") public ErrorCode getErrorCode() { return errorCode; }
    @JsonProperty("message") public String getMessage() { return message; }

    /** One found object: {@code fieldConfiguration} for kind CONFIGURATION, {@code scheme} for kind SCHEME. */
    public static final class Item {
        private final ConfigurationRef fieldConfiguration;
        private final SchemeRef scheme;
        private final UsageView usage;

        public Item(ConfigurationRef fieldConfiguration, SchemeRef scheme, UsageView usage) {
            this.fieldConfiguration = fieldConfiguration; this.scheme = scheme; this.usage = usage;
        }
        @JsonProperty("fieldConfiguration") public ConfigurationRef getFieldConfiguration() { return fieldConfiguration; }
        @JsonProperty("scheme") public SchemeRef getScheme() { return scheme; }
        @JsonProperty("usage") public UsageView getUsage() { return usage; }
    }

    /**
     * Where an object is used, as a page of its links. A configuration: {@code links} (scheme row -> this
     * configuration); a scheme: {@code projects}. {@code status=READ} says Jira was read successfully, NOT that every
     * link is listed: {@code truncated=true} means {@code total} counts more than the page shows; read the rest with
     * {@code nextUsageOffset} (kind and id, {@code usageOffset}). {@code UNAVAILABLE}: nothing is known; lists and
     * counts are null.
     */
    public static final class UsageView {
        private final UsageStatus status;
        private final String message;
        private final Integer total;
        private final Integer schemesTotal;
        private final int usageOffset;
        private final Integer nextUsageOffset;
        private final List<LinkView> links;
        private final List<ProjectRef> projects;

        private UsageView(UsageStatus status, String message, Integer total, Integer schemesTotal, int usageOffset,
                          Integer nextUsageOffset, List<LinkView> links, List<ProjectRef> projects) {
            this.status = status; this.message = message; this.total = total; this.schemesTotal = schemesTotal;
            this.usageOffset = usageOffset; this.nextUsageOffset = nextUsageOffset;
            this.links = links == null ? null : Immutables.listOf(links);
            this.projects = projects == null ? null : Immutables.listOf(projects);
        }

        public static UsageView ofLinks(int total, int schemesTotal, int offset, Integer next, List<LinkView> links) {
            return new UsageView(UsageStatus.READ, null, total, schemesTotal, offset, next, links, null);
        }

        public static UsageView ofProjects(int total, int offset, Integer next, List<ProjectRef> projects) {
            return new UsageView(UsageStatus.READ, null, total, null, offset, next, null, projects);
        }

        public static UsageView unavailable(String message) {
            return new UsageView(UsageStatus.UNAVAILABLE, message, null, null, 0, null, null, null);
        }

        @JsonProperty("status") public UsageStatus getStatus() { return status; }
        @JsonProperty("message") public String getMessage() { return message; }
        /** All links (configuration) or all projects (scheme); null when UNAVAILABLE. */
        @JsonProperty("total") public Integer getTotal() { return total; }
        /** Configuration only: how many distinct schemes the links belong to. */
        @JsonProperty("schemesTotal") public Integer getSchemesTotal() { return schemesTotal; }
        @JsonProperty("usageOffset") public int getUsageOffset() { return usageOffset; }
        @JsonProperty("nextUsageOffset") public Integer getNextUsageOffset() { return nextUsageOffset; }
        @JsonProperty("truncated") public boolean isTruncated() { return nextUsageOffset != null; }
        @JsonProperty("links") public List<LinkView> getLinks() { return links; }
        @JsonProperty("projects") public List<ProjectRef> getProjects() { return projects; }
    }

    /**
     * One way a scheme reaches a configuration. {@code defaultRow=true}: the scheme's DEFAULT row (no issue type id);
     * {@code fallback=true}: the scheme has no DEFAULT row at all, so its issue types without their own row get Jira's
     * Default Field Configuration; no physical row exists.
     */
    public static final class LinkView {
        private final SchemeRef scheme;
        private final String issueTypeId;
        private final String issueTypeName;
        private final boolean defaultRow;
        private final boolean fallback;

        public LinkView(SchemeRef scheme, String issueTypeId, String issueTypeName, boolean defaultRow,
                        boolean fallback) {
            this.scheme = scheme; this.issueTypeId = issueTypeId; this.issueTypeName = issueTypeName;
            this.defaultRow = defaultRow; this.fallback = fallback;
        }
        @JsonProperty("scheme") public SchemeRef getScheme() { return scheme; }
        @JsonProperty("issueTypeId") public String getIssueTypeId() { return issueTypeId; }
        @JsonProperty("issueTypeName") public String getIssueTypeName() { return issueTypeName; }
        @JsonProperty("defaultRow") public boolean isDefaultRow() { return defaultRow; }
        @JsonProperty("fallback") public boolean isFallback() { return fallback; }
    }
}
