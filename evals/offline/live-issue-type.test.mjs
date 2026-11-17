import test from 'node:test';
import assert from 'node:assert/strict';
import { pickIssueType } from '../live/issue-type.mjs';

const types = [
  { id: '10000', name: 'Задача', subtask: false },
  { id: '10001', name: 'Подзадача', subtask: true },
  { id: '10105', name: 'Инцидент', subtask: false },
  { id: '10200', name: 'Дубль', subtask: false },
  { id: '10201', name: 'дубль', subtask: false },
];

test('an id and an exact name (any case) resolve to the same standard type', () => {
  assert.deepEqual(pickIssueType(types, '10000'), { id: '10000', name: 'Задача' });
  assert.deepEqual(pickIssueType(types, ' задача '), { id: '10000', name: 'Задача' });
});

test('nothing chosen is an error with no default', () => {
  assert.throws(() => pickIssueType(types, undefined), /LIVE_ISSUE_TYPE не задан/u);
  assert.throws(() => pickIssueType(types, '  '), /LIVE_ISSUE_TYPE не задан/u);
});

test('an unknown type is refused and the standard types of this Jira are listed, never replaced by another one', () => {
  assert.throws(() => pickIssueType(types, 'Bug'), (error) => /не найден/u.test(error.message)
    && error.message.includes('Задача') && error.message.includes('Инцидент') && !error.message.includes('Подзадача'));
});

test('a subtask is refused', () => {
  assert.throws(() => pickIssueType(types, '10001'), /подзадача/u);
  assert.throws(() => pickIssueType(types, 'Подзадача'), /подзадача/u);
});

test('a name shared by several types is ambiguous, and so is a type whose name another type also carries', () => {
  assert.throws(() => pickIssueType(types, 'Дубль'), /неоднозначно/u);
  assert.throws(() => pickIssueType(types, '10200'), /несколько типов/u);
});
