package ru.rasulov.workflow.impl.edit;

import com.opensymphony.workflow.loader.ActionDescriptor;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;
import static ru.rasulov.workflow.impl.edit.ValidatorFixtures.*;

/** Adding missing fields never rewrites an existing validator. */
public class ConfigureWorkflowAdditiveTest {
    @Test
    public void missingFieldsBecomeASeparateValidatorAfterAllExistingValidators() {
        ActionDescriptor target = action(workflow(), 22);
        String firstBefore = DescriptorFacts.xml(validators(target).get(0));
        String secondBefore = DescriptorFacts.xml(validators(target).get(1));
        RequiredFieldsDescriptor.Change change = RequiredFieldsDescriptor.plan(target,
                Arrays.asList("assignee", "description"));
        RequiredFieldsDescriptor.apply(target, change, "local-008-new-function");
        assertEquals("an existing validator must not absorb missing fields", 3, validators(target).size());
        assertEquals(firstBefore, DescriptorFacts.xml(validators(target).get(0)));
        assertEquals(secondBefore, DescriptorFacts.xml(validators(target).get(1)));
        assertEquals("description", validators(target).get(2).getArgs().get("FIELD_REQUIRED_FIELDS"));
    }
}
