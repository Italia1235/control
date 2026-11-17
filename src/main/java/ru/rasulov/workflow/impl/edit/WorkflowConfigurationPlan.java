package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.common.impl.ActionPlanCodec;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowRequest;
import ru.rasulov.workflow.api.dto.configure.ConfigureWorkflowResponse;
import ru.rasulov.workflow.impl.ResolvedWorkflow;
import ru.rasulov.common.impl.undo.WorkflowReceiptCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Request-local immutable intent plus explicitly tracked read dependencies. No native writes. */
final class WorkflowConfigurationPlan {
    static final int RECEIPT_LIMIT = 32 * 1024;
    final ConfigureWorkflowRequest request;
    final ActionPlanCodec codec;
    final Map<String,Object> dependencies = new LinkedHashMap<>();
    final Map<String,Supplier<Object>> readers = new LinkedHashMap<>();
    final Map<String,ConfigureWorkflowRequest.NewScreen> screens = new LinkedHashMap<>();
    final Map<Integer,String> screenKeys = new LinkedHashMap<>();
    final List<Map<String,Object>> steps = new ArrayList<>();
    final List<StatusIntent> statusIntents = new ArrayList<>();
    static final class StatusIntent {
        final ResolvedWorkflow.WorkflowStatus status;
        final String token, dependency, receiptKey, clientKey;
        int stepId;
        StatusIntent(ResolvedWorkflow.WorkflowStatus status, String token, String dependency,
                     String receiptKey, String clientKey) {
            this.status=status;this.token=token;this.dependency=dependency;
            this.receiptKey=receiptKey;this.clientKey=clientKey;
        }
    }
    ConfigureWorkflowResponse response;
    WorkflowDescriptor descriptor;
    ResolvedWorkflow graph;
    EditTarget target;
    String name, before, after, fingerprint;
    WorkflowConfigurationPlan(ConfigureWorkflowRequest request, ActionPlanCodec codec) {
        this.request=request; this.codec=codec;
    }
    void depend(String key, Supplier<Object> reader) {
        if (readers.containsKey(key)) return;
        readers.put(key,reader); dependencies.put(key,reader.get());
    }
    void expect(String key, Object value) { dependencies.put(key,value); }
    void refresh(String key) { dependencies.put(key,readers.get(key).get()); }
    void check() {
        for (Map.Entry<String,Supplier<Object>> dependency : readers.entrySet()) {
            if (!Objects.equals(codec.fingerprint(dependencies.get(dependency.getKey())),codec.fingerprint(dependency.getValue().get())))
                throw new StateChanged("Dependency '" + dependency.getKey() + "' changed after the confirmed plan.");
        }
    }
    void step(String key,String kind) {
        steps.add(map("key",key,"kind",kind,"writeStatus","NOT_ATTEMPTED","verification","NOT_CHECKED"));
    }
    Map<String,Object> execution() { return map("steps",steps); }
    void reserveReceiptSize() {
        if (WorkflowReceiptCodec.reservedBytes(execution()) > RECEIPT_LIMIT)
            throw new ConfigurationFailure("VALIDATION_ERROR","Execution receipt exceeds its reserved 32 KiB encoded storage limit.");
    }
    boolean applicable() { return fingerprint != null; }
    static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    static final class StateChanged extends RuntimeException { StateChanged(String message){super(message);} }
    static final class ConfigurationFailure extends RuntimeException {
        final String code;
        ConfigurationFailure(String code,String message){super(message);this.code=code;}
    }
}
