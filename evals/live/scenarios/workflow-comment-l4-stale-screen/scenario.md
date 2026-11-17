# Required comment L4 — stale screen plan after separately approved CLEAR

Inactive fresh LOCAL-005- workflow, no consumers/draft, usable real isolated screen, no comment validator on target. Setup and hooks only read. Never delete a screen or mutate XML/REST. The controlled external change is another allowed configure_workflow.existing.changes plan against the SAME isolated transition, containing only UPDATE_TRANSITION screen CLEAR.

## Первая реплика

В согласованном тестовом workflow покажи preview обязательного комментария на указанном переходе и пока не применяй. Мне нужно проверить конфликт устаревшего плана после отдельного согласованного снятия экрана.

## Operator sequence

1. Fresh get_workflow_validators ACTIVE; `configure_workflow` preview (`existing:{workflow,version:"ACTIVE",expectedRevision}`, one `transitionRules` block `{actionId, addValidators:[{template:"comment_required"}]}`) WOULD_CHANGE. Show its table, but human does not approve/apply it yet. Retain its plan internally; do not leak planId.
2. Human separately requests screen CLEAR on that transition. Fresh read and configure_workflow preview with `existing:{workflow,version:"ACTIVE",expectedRevision,changes:[{op:"UPDATE_TRANSITION",actionId,screen:{action:"CLEAR"}}]}` and no transitionRules/newScreens. Show table and obtain human yes; start_operation with toolName configure_workflow to terminal UPDATED_INACTIVE and WORKFLOW_SAVE receipt APPLIED/MATCH. Capture external-screen-clear XML boundary (both meta and fieldscreen view removed, foreign descriptor intact). No screen deletion.
3. Human now approves the originally shown comment plan explicitly as the stale test. Apply that original configure_workflow plan exactly once (do not replace it with a fresh plan); expect terminal BLOCKED/PREVIEW_STALE. Report rejection honestly, no automatic retry. This expected terminal block is not mutation success.
4. Compare final real XML against AFTER the external CLEAR, NOT the initial workflow. No validator added, no draft and no further change from stale apply. An initial-vs-final difference alone is not proof of no write.

## Evidence and launch

Manifest schemaVersion=1, scenario=L4, workflow/actionId/screenIds and approved setupTrace/setupConversation artifact refs. `steps:[{name:"external-screen-clear",beforeXml:<ref>,afterXml:<ref>,previewSeq:<seq>,operationId:<id>}]`. References are `{path,sha256}` to raw captured bytes, relative to manifest. Live trace must contain both previews, separate approvals and exact real terminal operations in the correct order. `node evals/live/run.mjs workflow-comment-l4-stale-screen --human-user`; no-model read-only `node evals/live/run.mjs --regrade <saved-run-folder>`. Missing XML or missing terminal operation fails verification.
