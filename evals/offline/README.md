# Offline (simulated) dialogue evals

These evals check how a model behaves with Control's tools, without a real Jira:

- **Assistant** — a real LLM with the production instructions (`src/main/resources/llm/rules.md`) and all production tool schemas (`src/main/resources/llm/*-tools.json`).
- **Jira** — simulated. Each scenario holds a deterministic Jira state; a domain simulator answers tool calls, including previews, `planId`, `start_operation` and `get_operation`.
- **User** — deterministic replies in most scenarios; a separate LLM in a few short ones.
- **Grader** — checks the tool trace structurally: preview before apply, a visible plan, a separate "yes", one managed apply per approved plan, no extra writes, no repeated applies, the final answer after the terminal result. Exact wording is not compared.

A simulated PASS does not prove the behavior of a real Jira; that is what [evals/live](../live/README.md) is for.

## Free suite (no LLM, no Jira)

```
npm run test:offline
```

Runs every `*.test.mjs` in this folder. Tests that start the runner import `offline-network.mjs`, which blocks outgoing sockets except loopback. The tests drive the real runner with a scripted assistant over a loopback HTTP transport, including intentionally broken traces that the grader must reject. Requires Node.js 20.6+ and `npm install` (for `ajv`).

## Paid runs (real model, simulated Jira)

```
node evals/offline/run.mjs <scenario>
```

The scenario defaults to `assign_workflow_happy_path` (also `EVAL_SCENARIO`). By default the assistant runs through the `omp` CLI; `EVAL_OMP_MODEL` and `EVAL_OMP_THINKING` choose the model. An OpenAI-compatible Chat Completions endpoint can be used instead:

| Variable | Meaning |
| --- | --- |
| `EVAL_API_KEY`, `EVAL_BASE_URL` | Endpoint and key (`OPENAI_API_KEY` / `OPENAI_BASE_URL` as fallback) |
| `EVAL_ASSISTANT_MODEL`, `EVAL_USER_MODEL`, `EVAL_JUDGE_MODEL` | Models of the assistant, the simulated user and the judge |
| `EVAL_MAX_TOKENS_PER_RUN`, `EVAL_TOKEN_LIMIT_MODE` | Token budget per run (`soft` may overrun by one request) |
| `EVAL_SCENARIO_TIMEOUT_MS`, `EVAL_REQUEST_TIMEOUT_MS` | Time limits |
| `EVAL_SEED` | Seed of generated scenarios |

All variable names are listed in [`evals/.env.example`](../.env.example). The runner does not read `.env` by itself: pass values through the process environment or `node --env-file`.

`node evals/offline/run-paid.mjs --scenario <id> [--check]` runs a scenario with a fixed, pre-approved model and token limit; `--check` only validates the configuration and starts no model.

Exit code: `0` PASS, `1` FAIL, `2` the eval could not start.

## Scenarios

| Family | Scenarios | What is checked |
| --- | --- | --- |
| Basic flow | `assign_workflow_happy_path`, `project_document_happy_path` | Short workflow assignment; a large change document: read first, ask all missing decisions in one message, then a dependency-ordered plan |
| Workflow resolution | `workflow_resolution_obvious`, `_ambiguous`, `_missing`, `_unresolved` | Resolution chosen from the actual catalog, asked when ambiguous, never invented |
| Workflow with screens | `workflow_new_screen`, `workflow_obvious_screen` | New screen and field before the workflow, each with its own confirmation |
| Workflow reads and drafts | `workflow_read_no_counts_assign`, `_clone`, `workflow_draft_publish` | No full issue scan by default; a draft is reported, not published |
| Workflow rules | `workflow_validators_*`, `workflow_validators_comment_*` | Read before preview, one table and one question, separate "yes", no hidden structural edits |
| Manual transcripts | `workflow_manual_qa_loops`, `workflow_manual_qa_loops_original`, `workflow_midnight_cactus`, `workflow_purple_octopus` | Real admin requests replayed against the simulator |
| Custom fields and screens | `customfields_*`, `screen_layout_*` | Field placement, screen pagination, global fields, field names on new screens, partial results |
| Dialogue protocol | `dialogue_protocol_*` | Proposed bounded-plan protocol (harness only, not production policy) |

The full list is the scenario switch in `run.mjs`; each family has its own `*.test.mjs` with the grader cases.

## Artifacts

Each run writes `evals/offline/runs/<timestamp>-<scenario>/` (not committed):

- `conversation.md` — visible User/Assistant messages only;
- `tool-trace.json` — every tool call with arguments, full result and status;
- `verdict.json` — checks, failures, termination reason, models, durations and request counters;
- `summary.txt` — a short summary.

API keys and model reasoning are never written.
