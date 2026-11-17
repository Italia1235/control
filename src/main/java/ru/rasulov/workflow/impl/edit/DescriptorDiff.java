package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.loader.StepDescriptor;
import com.opensymphony.workflow.loader.WorkflowDescriptor;
import ru.rasulov.workflow.impl.edit.DescriptorFacts.Located;
import ru.rasulov.workflow.impl.edit.DescriptorFacts.ResolutionRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/** Line-by-line difference of two descriptors of one workflow (active to draft), by step and action id. */
public final class DescriptorDiff {

    private DescriptorDiff() { }

    static List<String> lines(WorkflowDescriptor from, WorkflowDescriptor to) {
        List<String> lines = new ArrayList<String>();
        String oldDescription = DescriptorFacts.meta(from).get(DescriptorFacts.DESCRIPTION_META);
        String newDescription = DescriptorFacts.meta(to).get(DescriptorFacts.DESCRIPTION_META);
        if (!Objects.equals(oldDescription == null ? "" : oldDescription, newDescription == null ? "" : newDescription)) {
            lines.add("description '" + oldDescription + "' -> '" + newDescription + "'");
        }
        Map<Integer, StepDescriptor> before = steps(from);
        Map<Integer, StepDescriptor> after = steps(to);
        for (Map.Entry<Integer, StepDescriptor> e : after.entrySet()) {
            StepDescriptor old = before.get(e.getKey());
            StepDescriptor now = e.getValue();
            if (old == null) {
                lines.add("step " + e.getKey() + " '" + now.getName() + "' (status " + DescriptorFacts.statusId(now)
                        + ") added");
                continue;
            }
            if (!Objects.equals(DescriptorFacts.statusId(old), DescriptorFacts.statusId(now))) {
                lines.add("step " + e.getKey() + " status " + DescriptorFacts.statusId(old) + " -> "
                        + DescriptorFacts.statusId(now));
            }
            if (!Objects.equals(old.getName(), now.getName())) {
                lines.add("step " + e.getKey() + " name '" + old.getName() + "' -> '" + now.getName() + "'");
            }
        }
        for (Map.Entry<Integer, StepDescriptor> e : before.entrySet()) {
            if (!after.containsKey(e.getKey())) lines.add("step " + e.getKey() + " '" + e.getValue().getName() + "' removed");
        }
        Map<Integer, Located> oldActions = DescriptorFacts.locate(from);
        Map<Integer, Located> newActions = DescriptorFacts.locate(to);
        for (Map.Entry<Integer, Located> e : newActions.entrySet()) {
            Located now = e.getValue();
            Located old = oldActions.get(e.getKey());
            if (old == null) {
                lines.add("action " + e.getKey() + " '" + now.action().getName() + "' (" + now.kind() + ") added");
                continue;
            }
            List<String> parts = new ArrayList<String>();
            if (!Objects.equals(old.action().getName(), now.action().getName())) {
                parts.add("name '" + old.action().getName() + "' -> '" + now.action().getName() + "'");
            }
            if (old.kind() != now.kind() || !old.sources().equals(now.sources())) {
                parts.add("source " + old.kind() + old.sources() + " -> " + now.kind() + now.sources());
            }
            if (!DescriptorFacts.targets(old.action()).equals(DescriptorFacts.targets(now.action()))) {
                parts.add("target " + DescriptorFacts.targets(old.action()) + " -> "
                        + DescriptorFacts.targets(now.action()));
            }
            String oldScreen = DescriptorFacts.meta(old.action()).get(DescriptorFacts.SCREEN_META);
            String newScreen = DescriptorFacts.meta(now.action()).get(DescriptorFacts.SCREEN_META);
            if (!Objects.equals(oldScreen, newScreen)) parts.add("screen " + oldScreen + " -> " + newScreen);
            ResolutionRule oldRule = DescriptorFacts.rule(old.action());
            ResolutionRule newRule = DescriptorFacts.rule(now.action());
            if (!oldRule.equals(newRule)) parts.add("resolution " + oldRule.action()
                    + (oldRule.value() == null ? "" : " " + oldRule.value()) + " -> " + newRule.action()
                    + (newRule.value() == null ? "" : " " + newRule.value()));
            if (parts.isEmpty() && !DescriptorFacts.xml(old.action()).equals(DescriptorFacts.xml(now.action()))) {
                parts.add("definition changed (conditions, validators, functions or properties)");
            }
            if (!parts.isEmpty()) lines.add("action " + e.getKey() + " '" + now.action().getName() + "': "
                    + String.join("; ", parts));
        }
        for (Map.Entry<Integer, Located> e : oldActions.entrySet()) {
            if (!newActions.containsKey(e.getKey())) {
                lines.add("action " + e.getKey() + " '" + e.getValue().action().getName() + "' removed");
            }
        }
        return lines;
    }

    private static Map<Integer, StepDescriptor> steps(WorkflowDescriptor d) {
        Map<Integer, StepDescriptor> steps = new TreeMap<Integer, StepDescriptor>();
        for (StepDescriptor s : DescriptorFacts.steps(d)) steps.put(s.getId(), s);
        return steps;
    }

    /** Step ids of a descriptor, sorted. */
    public static TreeSet<Integer> stepIds(WorkflowDescriptor d) {
        return new TreeSet<Integer>(steps(d).keySet());
    }
}
