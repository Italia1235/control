# Security Policy

## Supported versions

Only the latest released version of Control receives security fixes. Older versions are not patched; upgrade first.

## Reporting a vulnerability

Do not open a public issue for a vulnerability.

Report it privately through GitHub: [Report a vulnerability](https://github.com/Italia1235/control/security/advisories/new) (Security → Advisories → Report a vulnerability).

Include:

- Control version and Jira version;
- affected endpoint or MCP tool;
- steps to reproduce and the observed impact;
- whether the issue requires a Jira administrator account.

Never include real Personal Access Tokens, passwords or data from a production Jira instance. Use a local test instance (`atlas-run`) for reproduction.

This is a solo project: expect an acknowledgement within 7 days. Fixes ship in a new release; the advisory is published after the release.

## Scope

In scope:

- the MCP endpoint `/rest/control/1.0/mcp` and the REST endpoints under `/rest/control/1.0/changes`: authentication (`Authorization: Bearer <PAT>` only), `Origin` checks, authorization of non-administrators;
- the preview → confirmation → apply protocol: applying a change that differs from the shown preview, reusing another user's `planId`, bypassing `PREVIEW_STALE`;
- the Audit page (`/plugins/servlet/control/applies`): access control, XSS, data leaks;
- secrets (tokens, credentials) written to logs, the operation journal or tool results.

Out of scope:

- actions an authenticated Jira administrator is allowed to perform through the documented tools;
- an LLM client that applies a change without asking the user: human confirmation is the client's responsibility (see [README](README.md#how-changes-work));
- vulnerabilities in Jira itself or in third-party libraries without a Control-specific exploit; report them upstream.
