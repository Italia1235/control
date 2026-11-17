package ru.rasulov.workflow.api.dto.configure;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import ru.rasulov.workflow.api.dto.WorkflowStatusView;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One domain preview or terminal result, independent of the background operation's state. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class ConfigureWorkflowResponse {
    @JsonProperty private final boolean dryRun;
    @JsonProperty private final String status, outcome, workflow, version, targetVersion, revision, errorCode, message, planId;
    @JsonProperty private final List<String> diff, warnings;
    @JsonProperty private final List<Map<String,Object>> transitions, objects;
    @JsonProperty private final List<WorkflowStatusView> statuses;
    @JsonProperty private final Object consumers;
    @JsonProperty private final Map<String,Object> execution;
    public ConfigureWorkflowResponse(boolean dryRun, String status, String workflow, String version,
            String targetVersion, String revision, String errorCode, String message, List<String> diff,
            List<String> warnings, List<Map<String,Object>> transitions, List<Map<String,Object>> objects,
            List<WorkflowStatusView> statuses, Object consumers, Map<String,Object> execution, String planId) {
        this.dryRun=dryRun; this.status=status; this.workflow=workflow; this.version=version;
        this.targetVersion=targetVersion; this.revision=revision; this.errorCode=errorCode; this.message=message;
        this.diff=List.copyOf(diff); this.warnings=List.copyOf(warnings); this.transitions=List.copyOf(transitions);
        this.objects=List.copyOf(objects); this.statuses=List.copyOf(statuses); this.consumers=consumers;
        this.execution=execution == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(execution)); this.planId=planId;
        this.outcome=List.of("CREATED","UPDATED_INACTIVE","DRAFT_SAVED").contains(status) ? "SUCCESS" : status;
    }
    public static ConfigureWorkflowResponse failed(boolean dryRun,String workflow,String code,String message) {
        return new ConfigureWorkflowResponse(dryRun,"FAILED",workflow,null,null,null,code,message,List.of(),List.of(),List.of(),List.of(),List.of(),null,null,null);
    }
    public static ConfigureWorkflowResponse stale() { return failed(false,null,"PREVIEW_STALE","The confirmed dependencies changed. Nothing was written; preview again and ask for confirmation."); }
    public ConfigureWorkflowResponse withPlan(String planId) { return copy(true,status,errorCode,message,execution,planId); }
    public ConfigureWorkflowResponse withPlan(String planId, long expiresAt) { return withPlan(planId); }
    public ConfigureWorkflowResponse result(String status,String code,String message,Map<String,Object> execution) { return copy(false,status,code,message,execution,null); }
    /**
     * Terminal result of a run that did not reach SUCCESS. The planned transitions, diff and non-receipt objects are
     * withheld so that nothing planned reads as saved; the receipt (execution) is the only account of what happened.
     */
    public ConfigureWorkflowResponse failure(String status,String code,String message,Map<String,Object> execution) {
        List<Map<String,Object>> receiptObjects=new java.util.ArrayList<>();
        List<WorkflowStatusView> confirmedStatuses=new java.util.ArrayList<>();
        Object entries=execution.get("steps");
        for(Map<String,Object> object:objects) if(object.get("receiptKey")!=null && entries instanceof List<?> steps) {
            for(Object entry:steps) if(entry instanceof Map<?,?> step && object.get("receiptKey").equals(step.get("key"))
                    && "APPLIED".equals(step.get("writeStatus")) && step.get("id")!=null) {
                Map<String,Object> confirmed=new LinkedHashMap<>(object);
                confirmed.put("id",step.get("id"));confirmed.put("writeStatus",step.get("writeStatus"));confirmed.put("verification",step.get("verification"));
                if("STATUS".equals(object.get("kind"))) {
                    confirmed.remove("stepId");
                    if(!"MATCH".equals(step.get("verification"))) {
                        confirmed.remove("name");confirmed.remove("category");confirmed.remove("description");
                    }
                }
                receiptObjects.add(confirmed);
                if("STATUS".equals(object.get("kind")) && "MATCH".equals(step.get("verification"))) confirmedStatuses.add(new WorkflowStatusView((String)object.get("name"),
                        step.get("id").toString(),(String)object.get("category"),true));
            }
        }
        return new ConfigureWorkflowResponse(false,status,workflow,version,targetVersion,revision,code,
                message+" Planned rules, transitions and diff are not confirmed as saved and are not returned; read the workflow for its actual state.",
                List.of(),warnings,List.of(),receiptObjects,confirmedStatuses,consumers,execution,null);
    }
    private ConfigureWorkflowResponse copy(boolean dryRun,String status,String code,String message,Map<String,Object> execution,String planId) {
        return new ConfigureWorkflowResponse(dryRun,status,workflow,version,targetVersion,revision,code,message,diff,warnings,transitions,objects,statuses,consumers,execution,planId);
    }
    public boolean isDryRun(){return dryRun;} public String getStatus(){return status;} public String getOutcome(){return outcome;}
    public String getWorkflow(){return workflow;} public String getVersion(){return version;} public String getTargetVersion(){return targetVersion;}
    public String getRevision(){return revision;} public String getErrorCode(){return errorCode;} public String getMessage(){return message;}
    public String getPlanId(){return planId;} public List<String> getDiff(){return diff;} public List<String> getWarnings(){return warnings;}
    public List<Map<String,Object>> getTransitions(){return transitions;} public List<Map<String,Object>> getObjects(){return objects;}
    public List<WorkflowStatusView> getStatuses(){return statuses;} public Object getConsumers(){return consumers;} public Map<String,Object> getExecution(){return execution;}
}
