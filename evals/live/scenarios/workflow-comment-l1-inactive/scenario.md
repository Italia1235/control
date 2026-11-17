# Required comment L1 — inactive comment validator, exact XML and idempotence

Human-operated only (`--human-user`); never a paid unattended run. Setup is read-only and requires LOCAL005_EVIDENCE_FILE describing a fresh, previously approved LOCAL-005- workflow and screens. `prepared.known` supplies actual identifiers returned by Jira; do not guess IDs. Initial workflow is inactive, without draft/consumers. Target has usable meta + fieldscreen view, existing required-fields and OTHER validators, conditions and post-functions. Screen layout must not contain Comment. A source copy may supply these foreign rules; copying must itself have a shown preview and human approval. No raw XML write, REST mutation, automatic setup apply, or ScriptRunner configuration write.

## Первая реплика

В изолированном workflow из согласованной подготовки сделай новый комментарий обязательным на указанном переходе. Не меняй экран, соседние правила, другие переходы или проекты. Сначала покажи план и дождись моего подтверждения.

## Operator sequence

1. Consult `prepared.known`; read get_workflow_validators ACTIVE and its current revision. Preview ONE `configure_workflow` request: `existing:{workflow,version:"ACTIVE",expectedRevision:<that revision>}` and one `transitionRules` block `{actionId, addValidators:[{template:"comment_required"}]}` with no screen block and no other rule. The preview diff must be exactly `ADD_VALIDATOR: <actionId> comment_required`, with the transition's final validators (errorText) and `targetVersion` ACTIVE. Show one consequences table (target, error text, consumers, warnings), ask one question. Only a real human yes permits start_operation; observe terminal COMPLETED/UPDATED_INACTIVE (targetVersion ACTIVE).
2. Read back validator view and exported XML. Preserve ordered existing validators, conditions/post-functions, foreign action attributes and other actions; only append the exact three arguments with a new UUID.
3. Ask for a repeated preview, after fresh ACTIVE read. Expect UNCHANGED, no planId, no apply, draft or duplicate.
4. Record step `comment-add` XML before/after, previewSeq and operationId. Hooks retain read-only snapshots. Missing artifact is NOT_VERIFIED, never a caveated pass.

## Evidence contract

Manifest schemaVersion=1, scenario=L1, workflow, actionId, screenIds; setupTrace/setupConversation refer to approved preparation JSON artifacts. Each artifact is `{ "path": "capture.xml", "sha256": "<actual 64 hex digest>" }`, relative to the manifest directory. `steps` contains `{name:"comment-add",beforeXml:<ref>,afterXml:<ref>,previewSeq:<trace seq>,operationId:<real operation id>}`. Captures must be raw exported XML, not tool status text. Setup conversation user approvals carry `source:"human"`; this records provenance, not cryptographic proof. Save run normally; deterministic read-only regrade: `node evals/live/run.mjs --regrade <saved-run-folder>` (no model calls). Regrade needs setup.json, tool-trace.json, conversation.md, lifecycle.json, evidence.json plus referenced artifacts. Do not replace the runner or invent a second smoke runner.
