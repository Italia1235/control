package ru.rasulov.common.impl;

import org.junit.Test;
import ru.rasulov.common.api.dto.report.ChangeKind;
import ru.rasulov.common.api.dto.report.ChangeReport;
import ru.rasulov.common.api.dto.report.ChangedObject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ChangeLinksTest {

    private static final String BASE = "http://jira.test/jira";

    @Test
    public void linksKeepTheContextPathOfTheBaseUrl() {
        assertEquals(BASE + "/secure/admin/EditStatus!default.jspa?id=10001",
                ChangeLinks.build(BASE, ChangeKind.STATUS, "10001"));
        assertEquals(BASE + "/plugins/servlet/project-config/ABC", ChangeLinks.build(BASE + "/", ChangeKind.PROJECT, "ABC"));
    }

    @Test
    public void spacesAndSpecialCharactersAreEncoded() {
        assertEquals(BASE + "/secure/admin/workflows/ViewWorkflowXml.jspa?workflowMode=live&workflowName=Bug%20flow%20%26%20%D0%A2%D0%B5%D1%81%D1%82",
                ChangeLinks.build(BASE, ChangeKind.WORKFLOW, "Bug flow & Тест"));
    }

    @Test
    public void customFieldPagesNeedACustomFieldIdAndOtherIdsHaveNoPage() {
        assertEquals(BASE + "/secure/admin/ConfigureCustomField!default.jspa?customFieldId=10201",
                ChangeLinks.build(BASE, ChangeKind.CUSTOM_FIELD, "customfield_10201"));
        assertNull(ChangeLinks.build(BASE, ChangeKind.CUSTOM_FIELD, "assignee"));
        assertNull(ChangeLinks.build(BASE, ChangeKind.STATUS, " "));
        assertNull(ChangeLinks.build(BASE, ChangeKind.STATUS, null));
        assertNull(ChangeLinks.build(null, ChangeKind.STATUS, "1"));
    }

    @Test
    public void reportKeepsCreatedAndReusedApartAndMergesAnotherReport() {
        ChangeReport nested = TestLinks.links().report().created(ChangeKind.CUSTOM_FIELD, "customfield_1", "F", "customfield_1").build();

        ChangeReport report = TestLinks.links().report()
                .merge(nested)
                .reused(ChangeKind.STATUS, "3", "In Progress", "3")
                .created(ChangeKind.WORKFLOW, null, "Flow", "Flow")
                .createdOptions("77", "F / Global", 12)
                .build();

        assertEquals(3, report.getCreated().size());
        assertEquals(1, report.getReused().size());
        assertEquals("In Progress", report.getReused().get(0).getName());
        ChangedObject options = report.getCreated().get(2);
        assertEquals(ChangeKind.OPTION, options.getKind());
        assertEquals(Integer.valueOf(12), options.getCount());
        assertEquals(TestLinks.BASE_URL + "/secure/admin/EditCustomFieldOptions!default.jspa?fieldConfigId=77", options.getUrl());
    }
}
