package ru.rasulov.customfields.api.dto.context;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.common.api.dto.Immutables;

import java.util.List;

/**
 * The {@code options} block of one {@code update_field_contexts} entry: changes of the options of an EXISTING context.
 * Entries and their order survive parsing unchanged (nulls included) so that the service can name exactly what is wrong.
 * There is no removal: options leave the choice only by being disabled.
 */
public final class OptionsChange {
    private final List<Add> add;
    private final List<Rename> rename;
    private final List<Long> disable;
    private final List<Long> enable;
    private final List<Order> order;

    @JsonCreator
    public OptionsChange(@JsonProperty("add") List<Add> add, @JsonProperty("rename") List<Rename> rename,
                         @JsonProperty("disable") List<Long> disable, @JsonProperty("enable") List<Long> enable,
                         @JsonProperty("order") List<Order> order) {
        this.add = Immutables.listOf(add);
        this.rename = Immutables.listOf(rename);
        this.disable = Immutables.listOf(disable);
        this.enable = Immutables.listOf(enable);
        this.order = Immutables.listOf(order);
    }

    @JsonProperty("add") public List<Add> getAdd() { return add; }
    @JsonProperty("rename") public List<Rename> getRename() { return rename; }
    @JsonProperty("disable") public List<Long> getDisable() { return disable; }
    @JsonProperty("enable") public List<Long> getEnable() { return enable; }
    @JsonProperty("order") public List<Order> getOrder() { return order; }

    /** True when at least one of the five lists names an operation. */
    public boolean hasOperations() {
        return !add.isEmpty() || !rename.isEmpty() || !disable.isEmpty() || !enable.isEmpty() || !order.isEmpty();
    }

    /** A new, enabled option; without a parent it is a top-level option, otherwise a child of that existing option. */
    public static final class Add {
        private final String value;
        private final Long parentOptionId;

        @JsonCreator
        public Add(@JsonProperty("value") String value, @JsonProperty("parentOptionId") Long parentOptionId) {
            this.value = value;
            this.parentOptionId = parentOptionId;
        }

        @JsonProperty("value") public String getValue() { return value; }
        @JsonProperty("parentOptionId") public Long getParentOptionId() { return parentOptionId; }
    }

    public static final class Rename {
        private final Long optionId;
        private final String value;

        @JsonCreator
        public Rename(@JsonProperty("optionId") Long optionId, @JsonProperty("value") String value) {
            this.optionId = optionId;
            this.value = value;
        }

        @JsonProperty("optionId") public Long getOptionId() { return optionId; }
        @JsonProperty("value") public String getValue() { return value; }
    }

    /** The complete order of the EXISTING options of one level; no parent means the top-level options. */
    public static final class Order {
        private final Long parentOptionId;
        private final List<Long> optionIds;

        @JsonCreator
        public Order(@JsonProperty("parentOptionId") Long parentOptionId,
                     @JsonProperty("optionIds") List<Long> optionIds) {
            this.parentOptionId = parentOptionId;
            this.optionIds = Immutables.listOf(optionIds);
        }

        @JsonProperty("parentOptionId") public Long getParentOptionId() { return parentOptionId; }
        @JsonProperty("optionIds") public List<Long> getOptionIds() { return optionIds; }
    }
}
