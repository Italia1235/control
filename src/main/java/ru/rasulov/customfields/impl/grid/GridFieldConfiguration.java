package ru.rasulov.customfields.impl.grid;

import com.atlassian.plugin.Plugin;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.module.ContainerManagedPlugin;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.common.api.dto.field.GridColumnDefinition;
import ru.rasulov.common.api.dto.field.GridFieldSettings;
import ru.rasulov.customfields.impl.validation.FieldFailureException;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Optional Table Grid Next Generation 6.x adapter. The app keeps one table layout per FieldConfig in its own Active
 * Objects; it is written through the app's {@code GridFieldInfoService} (the same path its admin REST uses), with the
 * layout parsed by the app's own play-json format and validated by the app before anything is stored. No Table Grid
 * class is linked when the app is absent.
 */
@Named
public class GridFieldConfiguration {
    public static final String PLUGIN_KEY = "tge.cloud";
    public static final String TYPE_KEY = PLUGIN_KEY + ":idalko-table-grid-custom-field-key";
    static final List<String> TYPES = Arrays.asList("string", "textarea", "number", "integer", "date", "datetime",
            "checkbox", "list", "multiselectlist", "userlist", "sequence");
    static final int MAX_COLUMNS = 50;
    static final int MAX_OPTIONS = 100;
    private static final Pattern COLUMN_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");
    private static final String SERVICE =
            "com.idalko.tgng.jira.server.models.duckets.grid.customfield.services.GridFieldInfoService";

    private final PluginAccessor plugins;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public GridFieldConfiguration(@ComponentImport PluginAccessor plugins) {
        this.plugins = plugins;
    }

    public void validate(String typeKey, GridFieldSettings settings) {
        if (settings == null) return;
        if (!TYPE_KEY.equals(typeKey))
            throw invalid("'grid' is supported only for the Table Grid Next Generation field type (" + TYPE_KEY + ").");
        ObjectNode columns = columns(settings);
        try {
            TableGrid grid = tableGrid();
            grid.validated(grid.parse(document(grid, settings, columns)));
            grid.service(); // Prove that this installed version exposes the configuration writer before creating a field.
        } catch (FieldFailureException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalid("Cannot configure Table Grid field: " + e.getMessage());
        }
    }

    /** Text for the preview and result: the columns exactly as they will be stored. */
    public String describe(GridFieldSettings settings) {
        List<String> columns = new ArrayList<String>(settings.getColumns().size());
        for (GridColumnDefinition column : settings.getColumns()) {
            StringBuilder text = new StringBuilder(column.getId()).append(" '").append(column.getTitle())
                    .append("' (").append(type(column));
            if (!column.getOptions().isEmpty()) text.append(": ").append(String.join(" | ", column.getOptions()));
            if (Boolean.TRUE.equals(column.getRequired())) text.append(", required");
            if (!editable(column)) text.append(", read-only");
            if (column.getDefaultValue() != null) text.append(", default '").append(column.getDefaultValue()).append('\'');
            columns.add(text.append(')').toString());
        }
        return "Table Grid columns for every new context: " + String.join("; ", columns)
                + (settings.getSearchableColumns().isEmpty() ? "" : ". Searchable columns: "
                + String.join(", ", settings.getSearchableColumns())) + ". The plugin's default columns are replaced.";
    }

    /** Replaces the layout of one new context; called after {@link #validate}. */
    public void configure(Long configId, GridFieldSettings settings) {
        TableGrid grid = tableGrid();
        Object config = grid.validated(grid.parse(document(grid, settings, columns(settings))));
        grid.upsert(configId, config, settings.getSearchableColumns());
    }

    /** The plugin's default table document with our columns: grid view settings stay the plugin's defaults. */
    private String document(TableGrid grid, GridFieldSettings settings, ObjectNode columns) {
        try {
            ObjectNode root = (ObjectNode) mapper.readTree(grid.defaultDocument());
            ObjectNode gd = root.with("gd");
            ArrayNode order = gd.putArray("columns");
            for (GridColumnDefinition column : settings.getColumns()) order.add(column.getId());
            root.put("col", columns);
            return mapper.writeValueAsString(root);
        } catch (IOException | ClassCastException e) {
            throw new IllegalStateException("Unsupported Table Grid default configuration", e);
        }
    }

    /** Validates the request and builds the plugin's {@code col} object; pure, no plugin access. */
    static ObjectNode columns(GridFieldSettings settings) {
        List<GridColumnDefinition> definitions = settings.getColumns();
        if (definitions.isEmpty() || definitions.size() > MAX_COLUMNS)
            throw invalid("'grid.columns' must contain 1 to " + MAX_COLUMNS + " columns.");
        ObjectNode result = JsonNodeFactory.instance.objectNode();
        Set<String> ids = new HashSet<String>();
        for (GridColumnDefinition column : definitions) {
            String id = column.getId();
            if (id == null || !COLUMN_ID.matcher(id).matches())
                throw invalid("Grid column id '" + id + "' must start with a letter and contain only letters, digits "
                        + "and '_' (at most 64).");
            if (!ids.add(id.toLowerCase(Locale.ROOT)))
                throw invalid("Grid column id '" + id + "' is used more than once.");
            result.put(id, column(column));
        }
        Set<String> searchable = new HashSet<String>();
        for (String id : settings.getSearchableColumns()) {
            if (id == null || !result.has(id))
                throw invalid("'grid.searchableColumns' names '" + id + "', which is not a column id of this grid.");
            if (!searchable.add(id)) throw invalid("'grid.searchableColumns' lists '" + id + "' more than once.");
        }
        return result;
    }

    private static ObjectNode column(GridColumnDefinition definition) {
        String id = definition.getId();
        String title = definition.getTitle() == null ? "" : definition.getTitle().trim();
        if (title.isEmpty() || title.length() > 255)
            throw invalid("Grid column '" + id + "' needs a title of 1 to 255 characters.");
        String type = type(definition);
        if (!TYPES.contains(type))
            throw invalid("Grid column '" + id + "' has unsupported type '" + definition.getType() + "'. Supported: "
                    + String.join(", ", TYPES) + ".");
        boolean list = "list".equals(type) || "multiselectlist".equals(type);
        if (list) options(id, definition.getOptions());
        else if (!definition.getOptions().isEmpty())
            throw invalid("Grid column '" + id + "': options are allowed only for list and multiselectlist columns.");
        if (definition.getDecimals() != null && !"number".equals(type))
            throw invalid("Grid column '" + id + "': decimals are allowed only for number columns.");
        if (definition.getDecimals() != null && (definition.getDecimals() < 0 || definition.getDecimals() > 10))
            throw invalid("Grid column '" + id + "': decimals must be between 0 and 10.");
        if (definition.getWidth() != null && (definition.getWidth() < 20 || definition.getWidth() > 2000))
            throw invalid("Grid column '" + id + "': width must be between 20 and 2000 pixels.");
        if ("sequence".equals(type) && Boolean.TRUE.equals(definition.getEditable()))
            throw invalid("Grid column '" + id + "': a sequence column is numbered automatically and cannot be editable.");

        ObjectNode column = JsonNodeFactory.instance.objectNode();
        column.put("type", type);
        column.put("title", title);
        column.put("required", Boolean.TRUE.equals(definition.getRequired()));
        column.put("editable", editable(definition));
        String defaultValue = defaultValue(definition, type);
        if (defaultValue != null) column.put("defaultvalue", defaultValue);
        column.putObject("summary");
        ObjectNode view = column.putObject("view");
        if (definition.getWidth() != null) view.put("width", definition.getWidth());
        view.put("resizable", true);
        view.put("hidden", false);
        view.put("hiddenOnCustomerPortal", false);
        column.putArray("errorHolder");
        switch (type) {
            case "string":
                column.put("alignment", "left");
                column.put("validateAsEmail", false);
                break;
            case "textarea":
                column.put("alignment", "left");
                column.put("maxLength", 2000);
                column.put("enableWikiRenderer", false);
                break;
            case "number":
                column.put("alignment", "right");
                column.put("mantissa", definition.getDecimals() == null ? 2 : definition.getDecimals());
                column.put("enableNumberFormatting", false);
                break;
            case "integer":
                column.put("alignment", "right");
                column.put("enableNumberFormatting", false);
                break;
            case "date":
                column.put("alignment", "left");
                column.putObject("format").put("date", "L");
                break;
            case "datetime":
                column.put("alignment", "left");
                ObjectNode format = column.putObject("format");
                format.put("date", "L");
                format.put("time", "LT");
                break;
            case "checkbox":
                column.put("alignment", "center");
                column.put("highlightWhenChecked", false);
                column.put("highlightColor", "#808080");
                break;
            case "list":
            case "multiselectlist":
                column.put("alignment", "left");
                ObjectNode attribute = column.putArray("attributes").addObject();
                attribute.put("key", "label");
                attribute.put("defaultValue", "");
                ArrayNode options = column.putArray("staticOptions");
                for (String option : definition.getOptions()) options.addObject().put("label", option.trim());
                break;
            case "userlist":
                column.put("alignment", "left");
                break;
            case "sequence":
                column.put("alignment", "center");
                column.put("initValue", 1);
                column.put("step", 1);
                break;
            default:
                throw new IllegalStateException(type);
        }
        return column;
    }

    private static void options(String id, List<String> options) {
        if (options.isEmpty() || options.size() > MAX_OPTIONS)
            throw invalid("Grid column '" + id + "' needs 1 to " + MAX_OPTIONS + " options.");
        Set<String> seen = new HashSet<String>();
        for (String option : options) {
            String value = option == null ? "" : option.trim();
            if (value.isEmpty() || value.length() > 255)
                throw invalid("Grid column '" + id + "': every option needs 1 to 255 characters.");
            if (!seen.add(value.toLowerCase(Locale.ROOT)))
                throw invalid("Grid column '" + id + "': option '" + value + "' is listed more than once.");
        }
    }

    private static String defaultValue(GridColumnDefinition definition, String type) {
        String value = definition.getDefaultValue();
        if (value == null) return null;
        String id = definition.getId();
        switch (type) {
            case "string":
            case "textarea":
                return value;
            case "number":
                try { return new BigDecimal(value.trim()).toPlainString(); }
                catch (NumberFormatException e) { throw invalid("Grid column '" + id + "': default must be a number."); }
            case "integer":
                try { return Long.toString(Long.parseLong(value.trim())); }
                catch (NumberFormatException e) { throw invalid("Grid column '" + id + "': default must be an integer."); }
            case "checkbox":
                if ("true".equalsIgnoreCase(value.trim()) || "false".equalsIgnoreCase(value.trim()))
                    return value.trim().toLowerCase(Locale.ROOT);
                throw invalid("Grid column '" + id + "': default must be true or false.");
            case "list":
                for (String option : definition.getOptions())
                    if (option.trim().equals(value.trim())) return option.trim();
                throw invalid("Grid column '" + id + "': default '" + value + "' is not one of its options.");
            default:
                throw invalid("Grid column '" + id + "': a default value is not supported for " + type + " columns.");
        }
    }

    private static String type(GridColumnDefinition column) {
        return column.getType() == null ? "" : column.getType().trim().toLowerCase(Locale.ROOT);
    }

    private static boolean editable(GridColumnDefinition column) {
        return !"sequence".equals(type(column)) && !Boolean.FALSE.equals(column.getEditable());
    }

    private TableGrid tableGrid() {
        Plugin plugin = plugins.getEnabledPlugin(PLUGIN_KEY);
        if (!(plugin instanceof ContainerManagedPlugin))
            throw new IllegalStateException("Table Grid Next Generation is not installed/enabled or has no service container.");
        return new TableGrid(plugin);
    }

    private static FieldFailureException invalid(String message) {
        return new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR, message);
    }

    /** Reflective access to the app's Scala API, resolved through its own class loader. */
    private final class TableGrid {
        private final Plugin plugin;
        private final Object json;
        private final Class<?> jsValue;
        private final Object gridConfigs;
        private final Class<?> gridConfig;

        TableGrid(Plugin plugin) {
            this.plugin = plugin;
            this.json = module("play.api.libs.json.Json");
            this.jsValue = load("play.api.libs.json.JsValue");
            this.gridConfigs = module("net.databuckets.ducket.common.duckets.grid.GridConfig");
            this.gridConfig = load("net.databuckets.ducket.common.duckets.grid.GridConfig");
        }

        String defaultDocument() {
            Object format = call(gridConfigs, method(gridConfigs.getClass(), "fmt"));
            Object config = call(gridConfigs, method(gridConfigs.getClass(), "defaultConfig"));
            Object document = call(format, method(load("play.api.libs.json.Writes"), "writes", Object.class), config);
            return (String) call(json, method(json.getClass(), "stringify", jsValue), document);
        }

        Object parse(String document) {
            Object value = call(json, method(json.getClass(), "parse", String.class), document);
            Object format = call(gridConfigs, method(gridConfigs.getClass(), "fmt"));
            Object result = call(format, method(load("play.api.libs.json.Reads"), "reads", jsValue), value);
            Class<?> results = load("play.api.libs.json.JsResult");
            if (!(Boolean) call(result, method(results, "isSuccess")))
                throw invalid("Table Grid rejected the column configuration: " + result);
            return call(result, method(results, "get"));
        }

        Object validated(Object config) {
            Object utils = module("net.databuckets.ducket.common.duckets.grid.TableGridConfigUtils");
            Object extension = call(utils, method(utils.getClass(), "TableGridConfigExtension", gridConfig), config);
            Object validation = call(extension, method(extension.getClass(), "validate"));
            if (!(Boolean) call(validation, method(load("cats.data.Validated"), "isValid")))
                throw invalid("Table Grid rejected the column configuration: " + validation);
            return config;
        }

        Object service() {
            Class<?> type = load(SERVICE);
            Collection<?> beans = ((ContainerManagedPlugin) plugin).getContainerAccessor().getBeansOfType(type);
            if (beans.size() != 1) throw new IllegalStateException("Table Grid service unavailable: " + SERVICE);
            return beans.iterator().next();
        }

        void upsert(Long configId, Object config, List<String> searchable) {
            Class<?> scalaList = load("scala.collection.immutable.List");
            Object ids = module("scala.collection.immutable.Nil");
            Method prepend = method(scalaList, "$colon$colon", Object.class);
            for (int i = searchable.size() - 1; i >= 0; i--) ids = call(ids, prepend, searchable.get(i));
            Object result = call(service(), method(load(SERVICE), "upsertGridConfig", long.class, gridConfig, scalaList),
                    configId.longValue(), config, ids);
            if ((Boolean) call(result, method(load("scala.util.Either"), "isLeft")))
                throw new IllegalStateException("Table Grid did not store the configuration of context " + configId
                        + ": " + result);
        }

        private Object module(String name) {
            try { return load(name + "$").getField("MODULE$").get(null); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported Table Grid API: " + name, e); }
        }

        private Class<?> load(String name) {
            try { return plugin.loadClass(name, GridFieldConfiguration.class); }
            catch (ClassNotFoundException e) { throw new IllegalStateException("Unsupported Table Grid API: " + name, e); }
        }

        private Method method(Class<?> type, String name, Class<?>... parameters) {
            try { return type.getMethod(name, parameters); }
            catch (NoSuchMethodException e) { throw new IllegalStateException("Unsupported Table Grid API: " + name, e); }
        }

        private Object call(Object target, Method method, Object... arguments) {
            try { return method.invoke(target, arguments); }
            catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw new IllegalStateException(String.valueOf(cause.getMessage()), cause);
            } catch (IllegalAccessException e) { throw new IllegalStateException("Cannot access Table Grid API", e); }
        }
    }
}
