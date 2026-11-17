import test from 'node:test';
import assert from 'node:assert/strict';
import { confirmationScope, itssIds, itssProjectKeys, itssTable, screenSchemeIds } from './itss-mappings-common.mjs';

// Fragments follow Jira 9 admin pages (ViewIssueTypeScreenSchemes, ConfigureIssueTypeScreenScheme) as read from the stand.
const list = `<tr data-issue-type-screen-scheme-name="Default Issue Type Screen Scheme"><td><a href="ConfigureIssueTypeScreenScheme.jspa?id=1"
  title="x"></a></td><td>&nbsp;</td><td><a href="ConfigureIssueTypeScreenScheme.jspa?id=1" id="c">Configure</a></td></tr>
<tr data-issue-type-screen-scheme-name="P"><td><a  href="ConfigureIssueTypeScreenScheme.jspa?id=10110"
  title="y"></a></td><td><ul><li><a id="view_project" href="/jira/plugins/servlet/project-config/AJHNG/summary">A</a></li>
  <li><a id="view_project" href="/jira/plugins/servlet/project-config/L016AB12/summary">B</a></li></ul></td>
  <td><a href="ConfigureIssueTypeScreenScheme.jspa?id=10110">Configure</a></td></tr>`;
const configure = `<table id="issue-type-table" class="aui"><tbody>
<tr data-default-association="true"><td><strong data-scheme-field=issue-type>Default</strong></td>
  <td><a data-scheme-field="screen-scheme" href="ConfigureFieldScreenScheme.jspa?id=10005">D</a></td></tr>
<tr data-default-association="false"><td><strong data-scheme-field=issue-type>L016-X-Type</strong></td>
  <td><a data-scheme-field="screen-scheme" href="ConfigureFieldScreenScheme.jspa?id=10007">S</a></td></tr>
</tbody></table>`;
const pages = { '/secure/admin/ViewIssueTypeScreenSchemes.jspa': list, '/secure/admin/ConfigureIssueTypeScreenScheme.jspa?id=10110': configure,
  '/secure/admin/ViewFieldScreenSchemes.jspa': '<a href="ConfigureFieldScreenScheme.jspa?id=2">a</a><a href="ConfigureFieldScreenScheme.jspa?id=10">b</a>' };
const adminPage = async (route) => ({ text: pages[route] ?? '' });

test('admin page readers parse ids, linked project keys and the row table with the default row marked', async () => {
  assert.deepEqual(await itssIds(adminPage), ['1', '10110']);
  assert.deepEqual(await screenSchemeIds(adminPage), ['2', '10']);
  assert.deepEqual(await itssProjectKeys(adminPage, '1'), []);
  assert.deepEqual(await itssProjectKeys(adminPage, '10110'), ['AJHNG', 'L016AB12']);
  assert.deepEqual(await itssTable(adminPage, '10110'), [{ type: 'DEFAULT', scheme: '10005' }, { type: 'L016-X-Type', scheme: '10007' }]);
});

test('setup confirmation scope names LOCAL-016 and forbids editing rows of an existing scheme during setup', () => {
  const rules = confirmationScope.rules('AB12');
  assert.match(rules, /LOCAL-016 run AB12/u);
  assert.match(rules, /REJECT any setup preview that edits rows/u);
  assert.doesNotMatch(rules, /LOCAL-013/u);
});
