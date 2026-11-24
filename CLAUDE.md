# CLAUDE.md

YAMCS Pixxel fork. Java/Maven. Main branch: `master`.

Graph-tool usage rules live in `AGENTS.md`. This file holds everything else.

## Contents

- [Mission reference docs](#mission-reference-docs)
- [Tooling](#tooling)
- [Where Claude puts its working files](#where-claude-puts-its-working-files)
- [Workflow rules](#workflow-rules)
- [Design decisions](#design-decisions)
- [Terminology](#terminology)

## Mission reference docs

- `/Users/nikhil/GSS Workspace/gss-jtyu-prd` holds the raw ICDs, requirements, and PRDs for the mission.
- These docs are updated frequently. Re-read the relevant file before relying on it; do not trust earlier summaries or cached notes.

## Tooling

- **code-review-graph is required.** Query the graph MCP tools (`semantic_search_nodes_tool`, `query_graph_tool`, `get_impact_radius_tool`, `detect_changes_tool`, ...) before Grep/Glob/Read for any code exploration, impact, or review. They are deferred: load them via ToolSearch first. Never propose disabling the graph.
- The CLI is at `.venv/bin/code-review-graph` and is **not on PATH**. Anything guarding on `command -v code-review-graph` silently does nothing. Hooks in `.claude/settings.json` resolve PATH first, then `.venv/bin/`.
- If the startup hook says the graph was built on another branch, rebuild: `.venv/bin/code-review-graph build`.
- Refresh embeddings after large changes:
  `.venv/bin/code-review-graph update --base HEAD --embedding-provider local --embedding-model all-MiniLM-L6-v2`
- Embeddings are **local only**. This is a proprietary fork; never send code to a cloud embedding provider.

## Where Claude puts its working files

- Working docs, manual-test procedures, helper scripts, Python venvs, and run logs go in `.claude/<topic>/` (e.g. `.claude/testing/`, `.claude/plans/`), not the `/private/tmp` scratchpad. macOS purges the scratchpad between sessions.
- Each such folder gets a `.gitignore` for `venv/` and `logs/`.
- Product docs (example READMEs, in-repo test scripts) still go in the repo proper.

## Workflow rules

### Plan docs need a Contents section

Every `.claude/plans/*.md` has a `## Contents` section right after the title, listing H2 and nested H3 headings as GitHub anchor links (`[label](#slug)`), plus a companion-plan link where one exists.

- Any edit that adds, renames, removes, or reorders a heading updates `## Contents` in the same change.
- Labels may be shortened; the anchor must be the exact GitHub slug of the full heading (lowercase, strip backticks/punctuation, spaces → hyphens).
- After editing, check every anchor resolves to a real heading.

### Merging master into a feature branch

A clean `git merge` is not a verified merge.

1. For each master commit that changes a wire layout or config key, grep the branch for code that reads that layout (decoders, parsers that exist only on the branch) and update it.
2. Run the affected `examples/*/tests` e2e scripts, not only `mvn test`.

Example of what this catches: the d0e766354 merge added `spareBytes` to the TC[11,4] encoder, but branch-only `Pus11WrapperParser` was not updated and the tracker read N = 0. Unit tests passed; the e2e would have caught it.

## Design decisions

### PUS-11 time-tag queue is ground-native

- The time-tag queue (`.claude/plans/time-tag-queue-plan.md`) is a native, ground-only Yamcs implementation designed from ECSS-E-ST-70-41C and mission needs.
- The simulator (`simulator/.../pus/Pus11Service.java`) is a test tool. When it conflicts with or lacks what the ground design needs, change the simulator (and example MDBs), not yamcs-core.

### PUS-11 Gap #5 / Gap #8 scope (`pus_analysis/pus11.md`)

- Gap #5 part 1 (insert visibility, verifier match on wrapper TC[11,4]): fixed. `PusCommandPostprocessor` publishes `ccsds-apid`; scheduled commands keep the inner ID under `pus11-inner-apid` / `pus11-inner-seqcount`; `pus.xml` verifiers read `/yamcs/cmdHist/ccsds-apid`.
- Gap #5 part 2 (release visibility / reconciliation) is **not** a standalone fix. It belongs to the time-tag-queue feature: the tracker subscribes to `/PUS/pus-tc-ack-*` (plan §6 step 5). TM[11,13] reconcile is authoritative; ACKs only corroborate.
- Open: plan §8.4 `pus11_schedule` keys its PK on `releaseTime`. Preferred direction is to key on the time-tag command's identity (`gentime, origin, seqNum`) with `releaseTime` as a mutable column. Confirm with the user before building this table.

### Link-management redesign targets a new mission

- `.claude/plans/link-management-*.md` are for a **new mission** in a new repo on `pixxel/master`, free to redesign transports, link layout, controller, and config.
- The current FF/FFLY deployment (ff-mcs `ff/prod/base` / `ffly/prod/base`, wds-backend `main` / `main-us`, gss-fos-backend as MPS) is **reference only**.
- Never frame proposals as migrating, patching, or shadow-running the current deployment (optional read-only comparison is fine). Phrase current-system faults as requirements for the new mission. Do not assume WDS is reused; where the pass controller runs is an open choice.

## Terminology

- **RTM** = realtime TM (live downlink). **STM** = stored TM (recorded onboard, dumped later in a pass). These appear only in commit messages, not code.
- STM packets carry old generation times, so events and alarms must be stamped with packet gentime, not mission time. Check any timestamping logic against STM dumps.
