package ru.rasulov.workflow.api.dto.configure;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import ru.rasulov.common.api.exception.InvalidRequestException;
import ru.rasulov.common.rest.RequestBodies;
import ru.rasulov.workflow.api.WorkflowLimits;
import ru.rasulov.workflow.api.dto.edit.WorkflowChange;
import java.util.Arrays;
import java.util.List;

/** Closed typed input with explicit absent-versus-null block validation. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigureWorkflowRequest(@JsonProperty Boolean dryRun, @JsonProperty WorkflowDefinition create, @JsonProperty Existing existing,
        @JsonProperty List<NewScreen> newScreens, @JsonProperty List<TransitionRule> transitionRules, @JsonProperty String planId) {
    public ConfigureWorkflowRequest {
        if (newScreens != null) newScreens = ru.rasulov.common.api.dto.Immutables.listOf(newScreens);
        if (transitionRules != null) transitionRules = ru.rasulov.common.api.dto.Immutables.listOf(transitionRules);
    }
    @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
    public static ConfigureWorkflowRequest parse(JsonNode value) {
        if (!value.isObject()) throw new InvalidRequestException("configure_workflow takes an object.");
        value.fieldNames().forEachRemaining(key -> {
            if (!List.of("dryRun","create","existing","newScreens","transitionRules","planId").contains(key))
                throw new InvalidRequestException("Unknown configure_workflow property '" + key + "'.");
        });
        checkBlocks(value,"");
        JsonNode mode=value.get("dryRun"), token=value.get("planId");
        if (mode==null || !mode.isBoolean()) throw new InvalidRequestException("dryRun must be explicitly true or false.");
        if (token!=null && !token.isTextual()) throw new InvalidRequestException("planId must be text.");
        return new ConfigureWorkflowRequest(mode.booleanValue(), child(value,"create",WorkflowDefinition.class),
                child(value,"existing",Existing.class), array(value,"newScreens",NewScreen[].class),
                array(value,"transitionRules",TransitionRule[].class),token==null?null:token.textValue());
    }
    private static void checkBlocks(JsonNode value,String path) {
        if(value.isObject()) value.fields().forEachRemaining(entry -> {
            String at=path.isEmpty()?entry.getKey():path+"."+entry.getKey();
            if(entry.getValue().isNull() && List.of("create","existing","changes","newScreens","transitionRules","screen","addValidators","addConditions","parameters","rules").contains(entry.getKey()))
                throw new InvalidRequestException("Block '"+entry.getKey()+"' cannot be null; omit it instead (request path: "+at+").");
            if(entry.getKey().equals("addConditions") && !entry.getValue().isObject())
                throw new InvalidRequestException("Block 'addConditions' must be an object with operator and rules, not "+entry.getValue().getNodeType().name().toLowerCase()+" (request path: "+at+").");
            if(List.of("addValidators","rules").contains(entry.getKey()) && !entry.getValue().isArray() && !entry.getValue().isNull())
                throw new InvalidRequestException("Block '"+entry.getKey()+"' must be an array, not "+entry.getValue().getNodeType().name().toLowerCase()+" (request path: "+at+").");
            if (at.equals("existing.changes") && (!entry.getValue().isArray() || entry.getValue().isEmpty()
                    || entry.getValue().size() > WorkflowLimits.MAX_CHANGES))
                throw new InvalidRequestException("existing.changes must be an array of 1 to "+WorkflowLimits.MAX_CHANGES+" changes (request path: "+at+").");
            if (entry.getKey().equals("transitionKey") && !entry.getValue().isTextual())
                throw new InvalidRequestException("transitionKey must be text (request path: "+at+").");
            checkBlocks(entry.getValue(),at);
        });
        else if(value.isArray()) for(int i=0;i<value.size();i++) {
            JsonNode item=value.get(i);
            if(item.isNull()) throw new InvalidRequestException("Arrays cannot contain null entries (request path: "+path+"["+i+"]).");
            if (path.equals("existing.changes") && item.isObject()) {
                JsonNode status = item.get("newStatus");
                if (item.has("statusId") && (!item.get("statusId").isTextual() || item.get("statusId").textValue().isBlank()))
                    throw new InvalidRequestException("statusId must be nonblank text.");
                if (status != null) {
                    if (!"ADD_STATUS".equals(item.path("op").asText()) || item.has("statusId") || !status.isObject())
                        throw new InvalidRequestException("ADD_STATUS requires exactly one of statusId or newStatus; newStatus is forbidden for other operations.");
                    status.fieldNames().forEachRemaining(key -> {
                        if (!List.of("name","category","description").contains(key))
                            throw new InvalidRequestException("Unknown newStatus property '" + key + "'.");
                    });
                    for (String key : List.of("name","category")) if (!status.has(key) || !status.get(key).isTextual())
                        throw new InvalidRequestException("newStatus." + key + " must be text.");
                    if (status.has("description") && !status.get("description").isTextual())
                        throw new InvalidRequestException("newStatus.description must be text.");
                }
            }
            checkBlocks(item,path+"["+i+"]");
        }
    }
    private static <T> T child(JsonNode value,String key,Class<T> type) { return value.has(key)?RequestBodies.parse(value.get(key).toString(),type,key):null; }
    private static <T> List<T> array(JsonNode value,String key,Class<T[]> type) { return value.has(key)?Arrays.asList(RequestBodies.parse(value.get(key).toString(),type,key)):null; }
    public Boolean isDryRun() { return dryRun; }
    public String getPlanId() { return planId; }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Existing(@JsonProperty String workflow, @JsonProperty String version, @JsonProperty String expectedRevision,
            @JsonProperty List<WorkflowChange> changes) {
        public Existing { if (changes != null) changes = ru.rasulov.common.api.dto.Immutables.listOf(changes); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NewScreen(@JsonProperty String key, @JsonProperty String name, @JsonProperty String description, @JsonProperty List<String> fieldIds) {
        public NewScreen { if (fieldIds != null) fieldIds = ru.rasulov.common.api.dto.Immutables.listOf(fieldIds); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TransitionRule(@JsonProperty String transitionKey, @JsonProperty Integer actionId, @JsonProperty ScreenChoice screen,
            @JsonProperty List<Validator> addValidators, @JsonProperty Conditions addConditions) {
        public TransitionRule { if (addValidators != null) addValidators = ru.rasulov.common.api.dto.Immutables.listOf(addValidators); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Validator(@JsonProperty String template, @JsonProperty Parameters parameters) { }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Condition(@JsonProperty String template, @JsonProperty Parameters parameters) { }
    public record Conditions(@JsonProperty String operator, @JsonProperty List<Condition> rules) {
        public Conditions { if (rules != null) rules = ru.rasulov.common.api.dto.Immutables.listOf(rules); }
    }
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Parameters(@JsonProperty List<String> fieldIds, @JsonProperty String group, @JsonProperty List<String> roles, @JsonProperty Boolean invert) {
        public Parameters {
            if (fieldIds != null) fieldIds = ru.rasulov.common.api.dto.Immutables.listOf(fieldIds);
            if (roles != null) roles = ru.rasulov.common.api.dto.Immutables.listOf(roles);
        }
    }
    public record ScreenChoice(@JsonProperty String action, @JsonProperty String screenId, @JsonProperty String screenName, @JsonProperty String screenKey) {
        @JsonCreator(mode=JsonCreator.Mode.DELEGATING)
        public static ScreenChoice parse(JsonNode value) {
            if (value.isTextual() && "KEEP".equals(value.textValue())) return new ScreenChoice("KEEP",null,null,null);
            if (!value.isObject()) throw new InvalidRequestException("screen must be KEEP or a SET/CLEAR object.");
            value.fieldNames().forEachRemaining(key -> {
                if (!List.of("action","screenId","screenName","screenKey").contains(key))
                    throw new InvalidRequestException("Unknown screen property '" + key + "'.");
            });
            String action = text(value,"action"), id = text(value,"screenId"), name = text(value,"screenName"), key = text(value,"screenKey");
            int references = (id == null ? 0 : 1) + (name == null ? 0 : 1) + (key == null ? 0 : 1);
            if (!("SET".equals(action) && references == 1 || "CLEAR".equals(action) && references == 0))
                throw new InvalidRequestException("SET needs exactly one of screenId, screenName, screenKey; CLEAR accepts none.");
            return new ScreenChoice(action,id,name,key);
        }
        private static String text(JsonNode object, String key) {
            JsonNode value = object.get(key);
            if (value == null) return null;
            if (!value.isTextual() || value.textValue().isBlank()) throw new InvalidRequestException(key + " must be nonblank text.");
            return value.textValue();
        }
        @JsonValue public Object json() {
            if ("KEEP".equals(action)) return "KEEP";
            java.util.Map<String,String> value = new java.util.LinkedHashMap<>();value.put("action", action);
            if (screenId != null) value.put("screenId", screenId);
            if (screenName != null) value.put("screenName", screenName);
            if (screenKey != null) value.put("screenKey", screenKey);
            return value;
        }
    }
}
