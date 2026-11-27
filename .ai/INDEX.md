# herald — AI Context Index

Entry point for agents working in this repo (`herald`). Read this file before touching
code; load further context only as the task requires.

## Start here

1. `AGENTS.md` — the binding repo agent contract.
2. `.ai/context.yaml` — machine-readable project/foundation wiring.
3. `.ai/memory/INDEX.md` — durable, evidence-backed facts about this repo.
4. `.ai/plans/` — the active plan directory, if any (read the active plan only).

## Required commands

- Install/test/lint/build: use the exact commands in this repo's `AGENTS.md`; do not infer
  project-specific commands from this generic template.
- Validate AI context: `aictl validate --ci`
- Check generated memory index: `aictl memory index --check`

## Context loading

- Load only what the task needs: this index, `context.yaml`, the relevant doc(s), the active
  plan, and memory entries scoped to the touched component. Do not bulk-load `.ai/`.
- For cross-repo or fleet questions, query the project-memory MCP (`search_context`,
  `get_memory_entry`) before grepping clones; if the MCP is unavailable, read `.ai/` from git
  directly.

## Documentation map

- `README.md` — project overview when present.
- `docs/` — canonical project documentation and decisions when present.

## Memory

- Index: `.ai/memory/INDEX.md` (regenerate with aictl; do not hand-edit).
- Active entries: `.ai/memory/active/` — trusted while verifiable.
- Archived entries: `.ai/memory/archive/` — historical facts retained with an archive reason.
- Search before writing. Agents use `aictl memory set`/`archive` locally or
  `apply_memory_changes` through MCP and maintain entries themselves.
