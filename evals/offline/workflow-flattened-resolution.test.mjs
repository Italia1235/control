import './offline-network.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import { runFlattenedResolution, scriptedResolutionModel, violations } from './workflow-flattened-resolution.mjs';

test('flattened metadata reaches the scripted model; catalog ID drives a zero-error preview', async () => {
  for (const id of ['offline-fixed-73', 'offline-fixed-91']) {
    const result = await runFlattenedResolution(scriptedResolutionModel(), { fixedId: id });
    assert.equal(result.passed, true);
    assert.equal(result.validation_errors_before_first_successful_preview, 0);
    const surface = result.modelInput.tools.find(t => t.name === 'configure_workflow').parameters;
    assert.equal(surface.create, 'object');
    assert.equal(surface.transitions, undefined, 'the deliberately flattened surface does not invent nested graph details');
    assert.match(result.modelInput.request, /Released = DONE.*Fixed/);
    const edges = result.preview.transitions;
    assert.equal(edges.find(t => t.to === 'Released').resolution.value, id);
    assert.equal(edges.find(t => t.from === 'Released').resolution.action, 'CLEAR');
    const submitted = result.trace.find(t => t.tool === 'configure_workflow').arguments.create.transitions;
    for (const t of submitted.filter(t => t.to !== 'Released')) assert.equal(Object.hasOwn(t, 'resolution'), false);
  }
});

for (const violation of violations) test(`flattened runner rejects ${violation} even after successful repair`, async () => {
  const result = await runFlattenedResolution(scriptedResolutionModel(violation));
  assert.equal(result.passed, false);
  assert.equal(result.validation_errors_before_first_successful_preview, 1);
  assert.equal(result.preview.status, 'WOULD_CREATE');
  const failure = result.trace.find(t => t.result.status === 'FAILED');
  assert.equal(failure.result.errorCode, 'VALIDATION_ERROR');
  assert.match(failure.result.message, /transitions/);
});

test('no successful preview cannot masquerade as zero-error success', async () => {
  const result = await runFlattenedResolution(async () => null);
  assert.equal(result.passed, false);
  assert.equal(result.preview, null);
});
