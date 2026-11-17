package ru.rasulov.customfields.impl.assets;

import com.atlassian.plugin.Plugin;
import com.atlassian.plugin.PluginAccessor;
import com.atlassian.plugin.module.ContainerManagedPlugin;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.fasterxml.jackson.databind.ObjectMapper;
import ru.rasulov.common.api.dto.field.AssetsFieldSettings;
import ru.rasulov.common.api.dto.field.CustomFieldErrorCode;
import ru.rasulov.customfields.impl.validation.FieldFailureException;

import javax.inject.Inject;
import javax.inject.Named;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Optional Assets 8.x adapter. No Assets classes are linked when the app is absent. */
@Named
public class AssetsFieldConfiguration {
    public static final String PLUGIN_KEY = "com.riadalabs.jira.plugins.insight";
    public static final String TYPE_KEY = PLUGIN_KEY + ":rlabs-customfield-default-object";
    private static final String PREFIX = "com.riadalabs.jira.plugins.insight.";
    private static final String SERVICE = "services.core.CustomFieldService";
    private final PluginAccessor plugins;
    private static final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public AssetsFieldConfiguration(@ComponentImport PluginAccessor plugins) {
        this.plugins = plugins;
    }

    public void validate(String typeKey, AssetsFieldSettings settings) {
        if (settings == null) return;
        if (!TYPE_KEY.equals(typeKey)) throw invalid("'assets' is supported only for the Assets object field type.");
        if (settings.getObjectSchemaId() == null || settings.getObjectSchemaId() <= 0)
            throw invalid("'assets.objectSchemaId' must identify an existing Assets schema. Call list_assets_schemas.");
        try {
            Service schemas = service("channel.external.api.facade.ObjectSchemaFacade");
            if (schemas.call("loadObjectSchemaBean", new Class<?>[]{int.class}, settings.getObjectSchemaId()) == null)
                throw invalid("Assets schema " + settings.getObjectSchemaId() + " does not exist.");
            String aql = settings.getFilterScopeAql();
            if (aql != null && !aql.trim().isEmpty())
                service("channel.external.api.facade.IQLFacade").call("validateIQL",
                        new Class<?>[]{int.class, String.class}, settings.getObjectSchemaId(), aql);
            service(SERVICE); // Prove that this installed version exposes the configuration writer before creating a field.
        } catch (RuntimeException e) {
            throw invalid("Cannot configure Assets field: " + e.getMessage());
        }
    }

    public List<Map<String, Object>> listSchemas() {
        Collection<?> schemas = (Collection<?>) service("channel.external.api.facade.ObjectSchemaFacade")
                .call("findObjectSchemaBeans", new Class<?>[0]);
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>(schemas.size());
        for (Object schema : schemas) {
            Map<String, Object> item = new TreeMap<String, Object>();
            item.put("id", getter(schema, "getId"));
            item.put("name", getter(schema, "getName"));
            item.put("description", getter(schema, "getDescription"));
            result.add(item);
        }
        return result;
    }

    public String snapshot(Long configId) {
        Object bean = load(configId);
        Map<String, Object> state = new TreeMap<String, Object>();
        state.put("configId", configId);
        Map<String, Object> values = new TreeMap<String, Object>();
        if (bean != null) for (Method method : bean.getClass().getMethods()) {
            String name = method.getName();
            if (method.getParameterTypes().length == 0 && !"getClass".equals(name)
                    && (name.startsWith("get") || name.startsWith("is"))) {
                values.put(name, invoke(method, bean));
            }
        }
        state.put("bean", bean == null ? null : values);
        try { return mapper.writeValueAsString(state); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot snapshot Assets configuration", e); }
    }

    public void configure(Long configId, Long fieldId, AssetsFieldSettings settings) {
        Object before = load(configId);
        Object after;
        if (before == null) {
            try {
                Class<?> beanType = plugins.getEnabledPlugin(PLUGIN_KEY).loadClass(
                        PREFIX + "services.model.customfield.DefaultCustomFieldConnectBean", getClass());
                after = beanType.getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot initialize Assets field configuration", e);
            }
            set(after, "setCustomFieldConfigId", Long.class, configId);
            set(after, "setCustomFieldId", Long.class, fieldId);
        } else {
            after = getter(before, "clone");
        }
        set(after, "setObjectSchemaId", Integer.class, settings.getObjectSchemaId());
        set(after, "setScopeIql", String.class, settings.getFilterScopeAql() == null ? "" : settings.getFilterScopeAql());
        store(before, after);
    }

    /** Called only after the undo handler proves the entire current configuration matches its recorded poststate. */
    public void restore(String beforeSnapshot) {
        try {
            Map<?, ?> state = mapper.readValue(beforeSnapshot, Map.class);
            Long configId = ((Number) state.get("configId")).longValue();
            Map<?, ?> values = (Map<?, ?>) state.get("bean");
            if (values == null) throw new IllegalStateException("Cannot restore an uninitialized Assets configuration");
            Object before = load(configId);
            if (before == null) throw new IllegalStateException("Assets configuration was removed: " + configId);
            Object after = getter(before, "clone");
            Number schema = (Number) values.get("getObjectSchemaId");
            set(after, "setObjectSchemaId", Integer.class, schema == null ? null : schema.intValue());
            set(after, "setScopeIql", String.class, values.get("getScopeIql"));
            store(before, after);
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read Assets undo state", e); }
    }

    public Long configId(String snapshot) {
        try { return ((Number) mapper.readValue(snapshot, Map.class).get("configId")).longValue(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read Assets undo state", e); }
    }
    public boolean initialized(String snapshot) {
        try { return mapper.readValue(snapshot, Map.class).get("bean") != null; }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot read Assets undo state", e); }
    }
    /** Stored settings of the snapshot, or null when the configuration is not initialized. */
    public AssetsFieldSettings settings(String snapshot) {
        try {
            Object bean = mapper.readValue(snapshot, Map.class).get("bean");
            if (bean == null) return null;
            Map<?, ?> values = (Map<?, ?>) bean;
            Number schema = (Number) values.get("getObjectSchemaId");
            Object aql = values.get("getScopeIql");
            return new AssetsFieldSettings(schema == null ? null : schema.intValue(), aql == null ? null : aql.toString());
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read Assets settings", e); }
    }

    /** The plugin stores a missing scope AQL as an empty string, so null and empty are the same setting. */
    public static boolean sameSettings(AssetsFieldSettings a, AssetsFieldSettings b) {
        return java.util.Objects.equals(a.getObjectSchemaId(), b.getObjectSchemaId())
                && (a.getFilterScopeAql() == null ? "" : a.getFilterScopeAql())
                        .equals(b.getFilterScopeAql() == null ? "" : b.getFilterScopeAql());
    }

    /** The snapshot minus the schema and AQL, so what else the plugin touched on the bean can be compared. */
    public static String withoutSettings(String snapshot) {
        try {
            Map<String, Object> state = new TreeMap<String, Object>(mapper.readValue(snapshot, Map.class));
            Object bean = state.get("bean");
            if (!(bean instanceof Map)) return snapshot;
            Map<String, Object> values = new TreeMap<String, Object>((Map<String, Object>) bean);
            values.remove("getObjectSchemaId"); values.remove("getScopeIql");
            state.put("bean", values);
            return mapper.writeValueAsString(state);
        } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read Assets snapshot", e); }
    }


    private Object load(Long configId) {
        return service(SERVICE).call("loadCustomFieldConnectBean", new Class<?>[]{long.class}, configId);
    }

    private void store(Object before, Object after) {
        service(SERVICE).call("storeCustomFieldConnectBean", new Class<?>[]{after.getClass(), after.getClass()}, before, after);
    }

    private Service service(String name) {
        Plugin plugin = plugins.getEnabledPlugin(PLUGIN_KEY);
        if (!(plugin instanceof ContainerManagedPlugin))
            throw new IllegalStateException("Assets plugin is not installed/enabled or has no service container.");
        try {
            Class<?> type = plugin.loadClass(PREFIX + name, getClass());
            Collection<?> beans = ((ContainerManagedPlugin) plugin).getContainerAccessor().getBeansOfType(type);
            if (beans.size() != 1) throw new IllegalStateException("Assets service unavailable: " + name);
            return new Service(type, beans.iterator().next());
        } catch (ClassNotFoundException e) { throw new IllegalStateException("Unsupported Assets API: " + name, e); }
    }

    private static Object getter(Object bean, String method) {
        try { return invoke(bean.getClass().getMethod(method), bean); }
        catch (NoSuchMethodException e) { throw new IllegalStateException("Unsupported Assets bean: " + method, e); }
    }

    private static void set(Object bean, String method, Class<?> type, Object value) {
        try { invoke(bean.getClass().getMethod(method, type), bean, value); }
        catch (NoSuchMethodException e) { throw new IllegalStateException("Unsupported Assets bean: " + method, e); }
    }

    private static Object invoke(Method method, Object target, Object... arguments) {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw new IllegalStateException(cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Cannot access Assets API", e); }
    }

    private static FieldFailureException invalid(String message) {
        return new FieldFailureException(CustomFieldErrorCode.VALIDATION_ERROR, message);
    }

    private static final class Service {
        private final Class<?> type;
        private final Object bean;
        Service(Class<?> type, Object bean) { this.type = type; this.bean = bean; }
        Object call(String name, Class<?>[] parameters, Object... arguments) {
            try { return invoke(type.getMethod(name, parameters), bean, arguments); }
            catch (NoSuchMethodException e) { throw new IllegalStateException("Unsupported Assets API: " + name, e); }
        }
    }
}
