package ru.rasulov.common.impl.applies;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class ApplyTargetsTest {
    private static String show(ApplyTargets.Result result) {
        StringBuilder out = new StringBuilder();
        for (ApplyTarget t : result.items) {
            if (out.length() > 0) out.append(' ');
            out.append(t.type).append(':').append(t.label).append(t.id == null ? "" : "#" + t.id);
        }
        return out + (result.more > 0 ? " +" + result.more : "");
    }

    private static String of(String request) {
        return show(ApplyTargets.collect(null, request));
    }

    private static Map<String, Object> created(String kind, String id, String name, String url) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("kind", kind);
        m.put("id", id);
        m.put("name", name);
        m.put("url", url);
        return m;
    }

    private static Map<String, Object> resultWith(Map<String, Object>... objects) {
        Map<String, Object> report = new LinkedHashMap<String, Object>();
        report.put("created", Arrays.asList(objects));
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("report", report);
        return result;
    }

    @Test
    public void everyKeyOfTheTableMapsToItsType() {
        assertEquals("PROJECT:PAY", of("{\"project\":\"PAY\"}"));
        assertEquals("PROJECT:A PROJECT:B", of("{\"projects\":[\"A\",\"B\"]}"));
        assertEquals("FIELD:f", of("{\"field\":\"f\"}"));
        assertEquals("FIELD:10001#10001", of("{\"fieldId\":10001}"));
        assertEquals("FIELD:1 FIELD:2", of("{\"fieldIds\":[\"1\",\"2\"]}"));
        assertEquals("FIELD:f1", of("{\"fields\":[\"f1\"]}"));
        assertEquals("SCREEN:7#7", of("{\"screenId\":7}"));
        assertEquals("SCREEN:S1", of("{\"screens\":[\"S1\"]}"));
        assertEquals("SCHEME:s", of("{\"scheme\":\"s\"}"));
        assertEquals("SCHEME:sn", of("{\"schemeName\":\"sn\"}"));
        assertEquals("SCHEME:ns", of("{\"newScheme\":\"ns\"}"));
        assertEquals("WORKFLOW:w", of("{\"workflow\":\"w\"}"));
        assertEquals("WORKFLOW:w1", of("{\"workflows\":[\"w1\"]}"));
        assertEquals("WORKFLOW:wn", of("{\"workflowNames\":[\"wn\"]}"));
        assertEquals("ISSUE_TYPE:Bug ISSUE_TYPE:Task", of("{\"issueTypes\":[\"Bug\",\"Task\"]}"));
        assertEquals("CONTEXT:5#5", of("{\"contextId\":5}"));
        assertEquals("CONTEXT:C", of("{\"contexts\":[\"C\"]}"));
        assertEquals("OBJECT:K", of("{\"key\":\"K\"}"));
        assertEquals("OBJECT:N", of("{\"name\":\"N\"}"));
    }

    @Test
    public void keysAreTakenInTheOrderOfTheTableNotOfTheRequest() {
        assertEquals("PROJECT:P FIELD:f SCREEN:7#7 WORKFLOW:w OBJECT:n",
                of("{\"name\":\"n\",\"workflow\":\"w\",\"screenId\":7,\"field\":\"f\",\"project\":\"P\"}"));
    }

    @Test
    public void arrayOfObjectsUsesTheFirstNonEmptyNamingFieldAndId() {
        assertEquals("FIELD:Sprint#10 FIELD:epic FIELD:20#20 FIELD:30#30",
                of("{\"fields\":[{\"name\":\"Sprint\",\"id\":10},{\"name\":\" \",\"key\":\"epic\"},"
                        + "{\"fieldId\":20,\"id\":\"20\"},{\"id\":30},{\"unknown\":1},true,null,[\"nested\"]]}"));
    }

    @Test
    public void objectLabelPrefersNameThenKeyThenProjectFieldFieldIdScreenId() {
        assertEquals("OBJECT:n", of("{\"key\":{\"key\":\"k\",\"name\":\"n\"}}"));
        assertEquals("PROJECT:k", of("{\"project\":{\"key\":\"k\",\"project\":\"p\"}}"));
        assertEquals("PROJECT:p", of("{\"project\":{\"project\":\"p\",\"field\":\"f\"}}"));
        assertEquals("PROJECT:sc", of("{\"project\":{\"screenId\":\"sc\"}}"));
    }

    @Test
    public void allProjectsAndAllIssueTypesFlags() {
        assertEquals("PROJECT:All projects", of("{\"allProjects\":true}"));
        assertEquals("ISSUE_TYPE:All issue types", of("{\"allIssueTypes\":true}"));
        assertEquals("", of("{\"allProjects\":false,\"allIssueTypes\":\"yes\"}"));
        assertEquals("PROJECT:P PROJECT:All projects ISSUE_TYPE:All issue types",
                of("{\"allIssueTypes\":true,\"allProjects\":true,\"project\":\"P\"}"));
    }

    @Test
    public void twelveTargetsGiveTenAndMoreTwo() {
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < 12; i++) names.add("\"P" + i + "\"");
        ApplyTargets.Result result = ApplyTargets.collect(null, "{\"projects\":" + names + "}");
        assertEquals(10, result.items.size());
        assertEquals(2, result.more);
        assertEquals("P0", result.items.get(0).label);
        assertEquals("P9", result.items.get(9).label);
    }

    @Test
    public void duplicatesAreRemovedKeepingTheFirstOccurrenceOrder() {
        assertEquals("PROJECT:A PROJECT:B", of("{\"project\":\"A\",\"projects\":[\"B\",\"A\",\"B\"]}"));
        // Same label with a different id or type is a different target.
        assertEquals("PROJECT:A PROJECT:A#1 FIELD:A", of("{\"projects\":[\"A\",{\"name\":\"A\",\"id\":1}],\"field\":\"A\"}"));
    }

    @Test
    public void notJsonOrNotAnObjectRequestGivesNoTargetsFromTheRequestAndNoError() {
        assertEquals("", of("not json at all"));
        assertEquals("", of("[\"PAY\"]"));
        assertEquals("", of("\"PAY\""));
        assertEquals("", of(""));
        assertEquals("", of(null));
        Map<String, Object> result = resultWith(created("SCREEN", "1", "Screen", "http://j/s"));
        assertEquals("SCREEN:Screen#1", show(ApplyTargets.collect(result, "{ broken")));
    }

    @Test
    public void createdObjectsOfTheReportComeBeforeTheRequestAndGiveLinks() {
        Map<String, Object> result = resultWith(created("SCREEN", "10", "My screen", "http://j/screen/10"),
                created("PROJECT", null, "PAY", null));
        ApplyTargets.Result collected = ApplyTargets.collect(result, "{\"project\":\"PAY\",\"field\":\"f\"}");
        assertEquals("SCREEN:My screen#10 PROJECT:PAY FIELD:f", show(collected));
        assertEquals(1, collected.links.size());
        assertEquals("My screen", collected.links.get(0).name);
        assertEquals("http://j/screen/10", collected.links.get(0).url);
    }

    @Test
    public void requestTargetsNamedByTheReportUnderAnotherTypeOrIdAreDropped() {
        // create_issue_types: the report has ISSUE_TYPE "История" #10001, the request only {"name":"История"}.
        assertEquals("ISSUE_TYPE:История#10001", show(ApplyTargets.collect(
                resultWith(created("ISSUE_TYPE", "10001", "История", null)), "{\"name\":\"История\"}")));
        // create_custom_fields: CUSTOM_FIELD "Pearl" from the report, FIELD "Pearl" from the request.
        assertEquals("CUSTOM_FIELD:Pearl#customfield_10500 FIELD_CONTEXT:XGKQZ#10802 PROJECT:XGKQZ2", show(ApplyTargets.collect(
                resultWith(created("CUSTOM_FIELD", "customfield_10500", "Pearl", null), created("FIELD_CONTEXT", "10802", "XGKQZ", null)),
                "{\"fields\":[{\"name\":\"Pearl\"}],\"project\":\"XGKQZ2\"}")));
        // The same id under another label and type is the same object.
        assertEquals("SCREEN:My screen#7", show(ApplyTargets.collect(
                resultWith(created("SCREEN", "7", "My screen", null)), "{\"screenId\":7}")));
    }

    @Test
    public void resultWithoutReportOrOfAnotherShapeIsIgnored() {
        assertEquals("", show(ApplyTargets.collect(null, null)));
        assertEquals("", show(ApplyTargets.collect("text", null)));
        assertEquals("", show(ApplyTargets.collect(Collections.singletonMap("report", "x"), null)));
        assertEquals("", show(ApplyTargets.collect(Collections.singletonMap("report", Collections.singletonMap("created", 5)), null)));
    }

    @Test
    public void createdObjectsBeyondTenAreCountedToo() {
        Map<String, Object>[] many = new Map[12];
        for (int i = 0; i < 12; i++) many[i] = created("OPTION", String.valueOf(i), "o" + i, null);
        ApplyTargets.Result result = ApplyTargets.collect(resultWith(many), "{\"project\":\"P\"}");
        assertEquals(10, result.items.size());
        assertEquals(3, result.more);
    }
}
