@AGENTS.md

## Claude Code-specific guidance

- Use plan mode for nontrivial work (multi-file, architectural); persist the outcome as a plan
  directory under `.ai/plans/` per AGENTS.md.
- Before completing a task, run the project commands from AGENTS.md `## Project specifics` and
  `aictl validate --ci`.
