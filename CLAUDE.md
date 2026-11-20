<!-- code-review-graph MCP tools -->
## MCP Tools: code-review-graph

**IMPORTANT: This project has a knowledge graph. ALWAYS use the
code-review-graph MCP tools BEFORE using Grep/Glob/Read to explore
the codebase.** The graph is faster, cheaper (fewer tokens), and gives
you structural context (callers, dependents, test coverage) that file
scanning cannot.

### When to use graph tools FIRST

- **Exploring code**: `semantic_search_nodes_tool` or `query_graph_tool` instead of Grep
- **Understanding impact**: `get_impact_radius_tool` instead of manually tracing imports
- **Code review**: `detect_changes_tool` + `get_review_context_tool` instead of reading entire files
- **Finding relationships**: `query_graph_tool` with callers_of/callees_of/imports_of/tests_for
- **Architecture questions**: `get_architecture_overview_tool` + `list_communities_tool`

Fall back to Grep/Glob/Read **only** when the graph doesn't cover what you need.

## Documentation updates

**Every time a feature is built or changed, update the docs in the same change:**

- Write a short summary of what was built or changed (what it does, config keys, new APIs/commands, and any limits).
- Update the relevant docs wherever they apply. PUS work goes in `docs/server-manual/links/command-postprocessor/pus.rst` and/or `docs/server-manual/links/packet-preprocessor/pus.rst`. Other features go in the matching page under `docs/` (for example `docs/server-manual/` or `docs/http-api/`).
- If no existing page fits, add a new page under `docs/` and link it from the section index.
- Skip docs only for pure refactors, test-only, or internal changes with no user-visible effect, and say so in the final message.

## Decisions and assumptions log

Log every decision or assumption made while building a feature in `.claude/plans/decisions-and-assumptions.md`.

- Each feature has its own `## <Feature>` section in that file. Add a new section for a new feature, above the marker at the bottom.
- Each entry has: ID (`<feature-prefix>-<n>`), assumption, why, what it affects, verify-when, status (`Assumed` / `Confirmed <date>` / `Deferred` / `Wrong, fixed <date>`), and owner.
- When a real answer arrives, update the status and fix the code or config listed under "Affects".
