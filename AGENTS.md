# herald — Agent Instructions

<!-- Rendered by `aictl project bootstrap` from ai-foundation templates/project/AGENTS.md.tmpl.
     Only the bounded workflow block is refreshed by `aictl project sync`. -->

<!-- ai-foundation:workflow:begin -->

## AI Foundation workflow

- Read `AGENTS.md`, `.ai/INDEX.md`, and the matching skill before planning, implementing,
  reviewing, debugging, maintaining memory, validating context, or landing changes.
- Git and current repository sources are canonical. Maintain reusable facts directly in
  `.ai/memory/active/` or `.ai/memory/archive/` with current Git evidence.
- Use a separate agent worktree under `/home/nic/worktrees/<repo>/<task>` for nontrivial work;
  never share a worktree between agents.
- Put scratch files under `${TMPDIR:-/home/nic/.cache/tmp}` and never commit derived state,
  credentials, tokens, or secret values.
- Plan bookkeeping is part of the deliverable: commit `tasks.yaml` status/evidence and
  `handoff.md` entries with the change they record, and land them on the default branch. Never
  end a session with uncommitted or unpushed `.ai/` changes in a worktree.
- Run `aictl validate --ci` and the repository tests before completion.
- Never push a deploy-coupled repository without explicit authorization.

<!-- ai-foundation:workflow:end -->

## Project specifics

- Install on a box with `tools/install-adb.sh <host>` (needs `adb` on the PATH), or through `tools/adb.sh` when ADB runs in the build container; see docs/SETUP.md.
- Test with `tools/build.sh` (JVM tests and APK packaging checks) and `tests/ha/run.sh` (offline Home Assistant tests).
- Validate with `aictl validate --ci`.
- Run locally by installing on a box, then checking `curl http://<box>:7979/v1/info`.
- Pushing to main runs CI. Deployment is manual. Releases follow docs/RELEASING.md (`tools/release.sh build|publish|verify`).
- This repository is public: never commit house-specific details (addresses, host names, people, local paths), tokens, pairing codes or signing material. House notes belong in the private deployment repositories.
- Start at `app/src/io/github/iwinties/herald/HeraldService.java` for the Android service, `Api.java` for HTTP, `CardBoard.java` for card lifecycle and `custom_components/herald/__init__.py` for the Home Assistant integration.
