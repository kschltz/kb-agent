# Project Knowledge Base & Context

This repository contains an extensive knowledge base for agent development and usage, located in `.claude/context/`. 

As a Pi coding agent working in this repository, you should consult these files when you need context about project standards, concepts, workflows, or troubleshooting.

## Essential Entry Points

Whenever you need to understand the architecture or look up how to perform a task, use the `read` or `bash` tools to consult the following entry points:

1. **`.claude/context/openagents-repo/quick-start.md`**
   - Read this for a 2-minute orientation of the repository structure, core concepts, essential paths, and common commands.

2. **`.claude/context/openagents-repo/navigation.md`**
   - Read this as your primary index. It maps out where to find specific files for Standards, Concepts, Examples, Guides, Lookup tables, and Error handling.

## Loading Strategy

If you are asked to perform a specific task, always look up the relevant guide from the context first. For example:
- **Adding/Modifying Agents:** Read `standards/agent-frontmatter.md` and `standards/subagent-structure.md` via the navigation index.
- **Testing:** Reference the evals and testing guides in `core-concepts/evals.md`.
- **Debugging:** Check the `errors/` directory or `guides/debugging.md` mapped in the navigation.

*Note: The context files in `.claude/context/` adhere to the MVI (Minimum Viable Information) principle and are optimized for agent consumption. Do not guess conventions; load them from the knowledge base.*

## KB (Kanban Board) — Lessons Learned

These are hard-won lessons from working with the `kb` CLI in this repo. Read them before working on cards.

### Critical Bugs & Workarounds

1. **`flock-append!` was corrupting JSONL** — The old implementation passed JSON lines as shell arguments via `pr-str` + `printf`. This caused:
   - `$(...)` in content was **executed as a shell subshell** (command injection!)
   - Multi-line content was mangled by shell word splitting
   - **Fix**: Now uses stdin piping (`cat >> file`) instead of embedding JSON in shell commands.
   - **If you see JSON parse errors on `kb context`/`kb show`**: check the card's `history.jsonl` for broken lines. Delete corrupted lines manually.

2. **`auto_spawn: true` is dangerous** — When a card moves to a lane with `auto_spawn: true`, the full `kb context` system prompt (multi-line, thousands of chars) was written to `history.jsonl` as the spawn event's `:content`. This created gigantic, fragile JSON lines. **Always set `auto_spawn: false`** in board.yaml until a proper fix lands (the spawn content is now truncated to 200 chars).

3. **`agent_command` template uses `--cwd`** — The default `agent_command` in board.yaml passes `--cwd {worktree}` to Claude, which doesn't support that flag. Auto-spawned agents fail immediately with `error: unknown option '--cwd'`.

### KB Workflow Pitfalls

4. **`kb advance` requires an agent note** — You cannot advance without at least one `role: "agent"` note in the current lane. Use `kb note <id> "msg" --agent pi` (the `--agent` flag is what sets the role; without it, notes are saved as `role: "human"`).

5. **`kb pull --lane X` pulls from backlog** — It does NOT pick up an existing card already in lane X. It creates a worktree for a backlog card and moves it into lane X. Use `kb move <id> <lane>` to reposition an existing card.

6. **Cards can land in lanes without real work** — Always `kb diff <id>` to verify actual commits exist. A previous agent may have advanced a card without implementing anything.

7. **`history.jsonl` is fragile** — Each line must be single-line valid JSON. If `kb context` or `kb show` fails with a parse error, look at the last few lines of the card's `history.jsonl` for corruption and remove them.

### Board Config

8. **Lane order**: `backlog → plan → in-progress → simplify → unit-tests → ui-test → code-quality → doc-update → done` — The extra gates catch issues early. `simplify` before tests is especially useful.

9. **Base branch is `pi-agent-work`** — not `master` or `main`. Cards merge into this branch on `done`.
