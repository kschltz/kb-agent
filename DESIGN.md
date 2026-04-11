# kb — Kanban Board for Coding Agents

## Design & Decision Document

**Status:** Draft v2
**Last updated:** 2026-04-08

---

## 1. Problem Statement

Coding agents (Claude Code, OpenCode, etc.) work in an unstructured stream — they receive a task, produce changes, and move on. There is no mechanism to enforce process discipline: no WIP limits, no quality gates, no way for a human to observe progress in real-time or intervene mid-flight. Worse, agents have unrestricted access to the working tree, so concurrent tasks bleed into each other.

**kb** is a kanban board that sits between the human and the agent. It provides:

- Structured workflow with configurable lanes, WIP limits, and parallelism constraints
- Quality gates as arbitrary shell commands that run on lane transitions
- File isolation: each card gets its own git worktree — agents cannot touch the main tree
- A persistent record of work: each card carries its own git history and structured conversation log
- Sub-agent orchestration: each card spawns a scoped agent with the card's context
- A live web UI so the human can observe, reprioritize, block, or annotate tasks while agents work


## 2. Decisions

### 2.1 Agent interface: CLI tool

Agents interact with the board via a `kb` CLI. Any agent that can run shell commands can use the board — no SDK, no protocol adapter. Claude Code and OpenCode both support shell execution. The CLI also serves as the human's terminal interface.

The CLI outputs JSON (`--json` flag) for machine consumption and human-readable text by default.

### 2.2 Quality gate failure: stay in current lane

When a card fails a quality gate during `kb move`, it is not moved forward. The gate failure and its output are recorded in the card's history. The agent sees the failure and can fix the issue before retrying.

### 2.3 State storage: local filesystem (git-friendly)

All board state lives in a `.kanban/` directory at the project root. Board config is YAML. Card metadata is YAML. History is structured JSONL. The board state can be committed to git. File-watching enables the web UI without a coordination protocol.

### 2.4 Quality gates: arbitrary shell commands

Gates are shell commands defined in `board.yaml`. The CLI runs them, checks exit code (0 = pass), and captures stdout/stderr. Gate commands receive card context via environment variables.

### 2.5 File isolation: git worktrees

Each card gets its own git worktree. The agent operates exclusively within that worktree. Changes only reach the main branch when the card passes all gates and moves to done.

**How it works:**

1. `kb pull` creates a branch `kb/<card-id>-<slug>` and a worktree at `.kanban/worktrees/<card-id>/`
2. The sub-agent's working directory is set to this worktree
3. The agent can freely edit files within the worktree — it's an isolated copy
4. `kb move <card> review` runs gates against the worktree
5. `kb move <card> done` merges the branch into the target branch (default: current branch at init)
6. `kb cleanup <card>` removes the worktree and optionally deletes the branch

**Why worktrees over branches alone:** A branch is just a pointer — the agent would still be editing the shared working tree and would need to stash/switch between tasks. Worktrees give physical isolation: each card has its own directory with its own file state. Two agents can work on two cards simultaneously without conflict.

**Git requirements:** The project must be a git repository. `kb init` verifies this.

### 2.6 Sub-agent model with structured conversation history

Each card gets a spawned sub-agent with scoped context. The conversation history is structured, not freeform notes.

**History format (history.jsonl):**

Each line is a JSON object with a required schema:

```json
{"ts": 1712345678.0, "role": "system", "action": "created", "content": "Card created in backlog"}
{"ts": 1712345700.0, "role": "human", "action": "note", "content": "Use jose library not jsonwebtoken"}
{"ts": 1712345800.0, "role": "agent", "action": "thinking", "content": "Looking at the current auth flow...", "agent_id": "claude-abc123"}
{"ts": 1712345900.0, "role": "agent", "action": "tool_use", "content": "Ran npm test — 3 failures in auth.test.ts", "agent_id": "claude-abc123"}
{"ts": 1712346000.0, "role": "system", "action": "gate_fail", "content": "Gate 'npm test' failed (exit 1): ...", "gate": "npm test"}
{"ts": 1712346100.0, "role": "agent", "action": "thinking", "content": "Tests failed because...", "agent_id": "claude-abc123"}
{"ts": 1712346200.0, "role": "system", "action": "moved", "content": "Moved from in-progress to review"}
```

**Roles:** `system` (board events), `human` (user interventions from UI or CLI), `agent` (sub-agent actions).

**Actions:** `created`, `moved`, `note`, `thinking`, `tool_use`, `gate_pass`, `gate_fail`, `blocked`, `unblocked`, `pulled`, `rejected`, `ask`, `answer`, `edited`, `heartbeat`, `approved`, `diff_snapshot`.

**Sub-agent spawning:**

`kb spawn <card-id>` starts a sub-agent process with:

- Working directory set to the card's worktree
- System prompt injected with: card title, description, full conversation history, board constraints
- Instructions to use `kb note`, `kb snapshot`, and `kb move` to report progress
- The agent's conversation is captured into the card's history.jsonl

The spawning command is configurable in board.yaml:

```yaml
agent_command: "claude --system-prompt \"$(kb context {card_id})\" --cwd {worktree}"
```

`kb context <card-id>` outputs the full card context (description + history + board rules) formatted as a system prompt.

### 2.7 Web UI: self-contained HTML/JS SPA

The web UI is a single-page app written in plain HTML, CSS, and JavaScript — no build step or Node.js required for serving. `kb serve` serves the static HTML plus a WebSocket endpoint for live updates.

**Stack:**

- Vanilla HTML/CSS/JS (no framework)
- WebSocket client for live board updates
- Drag-and-drop via HTML5 API
- Syntax-highlighted diff viewer
- Tailwind CSS + shadcn/ui components available in separate React dev UI (`web/`)

**Served by:** Babashka HTTP server that also handles the WebSocket endpoint. The self-contained UI is embedded in `src/kb/ui.html`; a React-based dev UI lives in `web/` for iteration.


## 3. Architecture

### 3.1 Filesystem layout

```
project-root/
  .kanban/
    board.yaml                    # Lane definitions, gates, WIP limits
    cards/
      001-fix-auth-bug/
        meta.yaml                 # Card state: lane, priority, blocked, agent, timestamps
        history.jsonl             # Structured conversation log (see 2.6)
        description.md            # Card description / acceptance criteria (optional)
      002-add-user-tests/
        meta.yaml
        history.jsonl
    worktrees/
      001/  -> git worktree       # Isolated working copy for card 001
      002/  -> git worktree       # Isolated working copy for card 002
```

Diffs are not stored as separate files. Since each card has a git branch, the diff history is the git log itself. `kb diff <card>` reads from `git log`/`git diff` on the card's branch.

### 3.2 board.yaml schema

```yaml
project: my-project
base_branch: main                  # Branch worktrees are created from
merge_strategy: squash             # How cards merge to base: squash | merge | rebase

agent_command: "claude --system-prompt \"$(kb context {card_id})\" --cwd {worktree}"

lanes:
  - name: backlog

  - name: in-progress
    max_wip: 5
    max_parallelism: 2

  - name: review
    max_wip: 3
    gate_from_in-progress:
      - "cd $KB_WORKTREE && npm test"
      - "test $(git diff --stat $KB_BASE_BRANCH..HEAD -- | wc -l) -lt 100"

  - name: done
    gate_from_review:
      - "cd $KB_WORKTREE && npm run lint"
    on_enter: merge
```

**Gate environment variables:**

| Variable | Description |
|---|---|
| `KB_CARD_ID` | Card ID (e.g., "001") |
| `KB_CARD_TITLE` | Card title |
| `KB_CARD_LANE` | Current lane (before move) |
| `KB_CARD_DIR` | Absolute path to card metadata directory |
| `KB_WORKTREE` | Absolute path to the card's git worktree |
| `KB_BRANCH` | Git branch name for this card |
| `KB_BASE_BRANCH` | The base branch (from board.yaml) |

### 3.3 CLI commands

| Command | Description |
|---|---|
| `kb init` | Create `.kanban/`, verify git repo, record base branch |
| `kb add <title> [--desc FILE]` | Create a card in backlog with optional description |
| `kb pull [--lane] [--agent] [--spawn]` | Claim next available card (excludes done lane), create worktree + branch, optionally spawn sub-agent |
| `kb move <card> <lane>` | Move card; run gates; merge on entering `done` if configured |
| `kb advance <card>` | Move card to next lane (shortcut) |
| `kb done <card>` | Move card to final lane, running all gates |
| `kb reject <card> [--reason]` | Push card back to previous lane |
| `kb block <card> [--reason]` | Block card (agents skip it, UI shows it) |
| `kb unblock <card>` | Remove block |
| `kb ask <card> <question>` | Ask the human a question (blocks card until answered) |
| `kb answer <card> <answer>` | Answer a pending question (unblocks card) |
| `kb approve <card>` | Approve a card pending approval |
| `kb note <card> <message>` | Append structured note to history |
| `kb log <card> [--since TS]` | Show structured conversation history |
| `kb diff <card> [--stat]` | Show git diff of card's branch vs base |
| `kb show <card>` | Full card details: meta + history + diff stats |
| `kb gates <card>` | Show gates for the next lane transition |
| `kb edit <card> [--title ...] [--priority N] [--desc ...]` | Edit card fields |
| `kb heartbeat <card>` | Record an agent heartbeat (signal you're alive) |
| `kb status` | Print the board |
| `kb context <card>` | Output card context formatted for agent system prompt |
| `kb spawn <card>` | Start a sub-agent scoped to this card |
| `kb cleanup <card>` | Remove worktree, optionally delete branch |
| `kb serve [--host 0.0.0.0] [--port 8741]` | Start web UI |

All commands accept `--json`. Mutating commands accept `--agent <id>`.

### 3.4 Web UI

```
  Sub-agent 1 (CLI)          Sub-agent 2 (CLI)          Human (Browser)
       |                          |                          |
  worktree/001             worktree/002               drag, note, block
       |                          |                          |
       v                          v                          v
  +-----------------------------------------------------------------+
  |                    .kanban/ (filesystem)                         |
  +-----------------------------------------------------------------+
                                 ^
                                 |
                            file watcher
                                 |
                           WebSocket push
                                 |
                            +---------+
                            |  Web UI |  (React + shadcn)
                            +---------+
```

**UI views:**

- **Board view:** Lanes as columns, cards as draggable items showing title, status badges, assigned agent, blocked state. Live-updating via WebSocket.
- **Card detail panel:** Slide-out sheet with description (markdown rendered), structured conversation timeline (color-coded by role), git diff viewer with syntax highlighting, gate result history.
- **Activity feed:** Global real-time stream of all card events across the board.
- **Interventions:** Drag cards between lanes (triggers gates server-side), add notes (injected into agent's context on next read), block/unblock, edit priority, edit description.


## 4. Lifecycle: A Card From Start to Finish

```
1. Human creates a task
   $ kb add "Fix JWT token expiration bug" --desc fix-jwt.md

2. Agent pulls the card -- worktree + branch are created (cards in the done lane are skipped)
   $ kb pull --agent claude-01 --spawn
   -> creates branch kb/001-fix-jwt-token-expiration
   -> creates worktree at .kanban/worktrees/001/
   -> spawns sub-agent with cwd=worktree, context=card history

3. Sub-agent works inside its worktree
   - reads code, runs tests, makes changes
   - logs thinking via: kb note 001 "Found the bug in token.ts line 42"
   - commits to its branch normally

4. Human intervenes via web UI
   - sees agent is going down the wrong path
   - adds note: "Use jose library not jsonwebtoken"
   - agent picks this up on next kb context 001 read

5. Agent attempts to advance the card
   $ kb move 001 review
   -> gate "npm test" runs inside the worktree
   -> tests fail -> card stays in in-progress, failure logged
   -> agent sees failure output, fixes, retries

6. Gates pass, card moves to review
   $ kb move 001 review
   -> all gates pass -> card is now in review

7. Human reviews diff in web UI
   - sees syntax-highlighted diff of branch vs main
   - reads conversation history to understand decisions
   - moves card to done via drag-and-drop

8. Card completes -- branch merges
   -> gate on "done" lane runs lint
   -> on_enter: merge squashes branch into main
   -> worktree is cleaned up
```


## 5. Open Questions

### 5.1 Multi-agent coordination

Two agents writing to different worktrees is safe (separate directories). The risk is concurrent writes to `.kanban/cards/<id>/meta.yaml` or `history.jsonl`.

**Plan:** Atomic writes (temp file + rename) for meta.yaml. `flock`-based append for history.jsonl. Sufficient for v1.

### 5.2 Worktree lifecycle edge cases

- **Agent crash:** Worktree and branch persist. `kb cleanup` handles manual removal. Future: `kb recover` detects orphaned worktrees.
- **Merge conflicts:** Gate could include a conflict check. If base has diverged, agent may need to rebase. The `on_enter: merge` step should fail loudly if there are conflicts, leaving the card in review for human intervention.
- **Disk space:** Worktrees share the git object store so they're cheap. `max_parallelism` naturally limits active worktrees.

### 5.3 Conversation capture depth

How much of the agent's internal conversation gets captured to history.jsonl?

- **Minimal:** Agent explicitly calls `kb note` for key decisions. Most internal thinking is lost.
- **Full transcript:** A wrapper captures every agent message. Complete but noisy.
- **Summarized:** Agent periodically summarizes progress. Middle ground.

Leaning toward minimal for v1 with a convention that agents log at decision points. Full transcript capture could be a v2 feature.

### 5.4 Human note delivery to agent

When a human adds a note via the web UI, how does the running sub-agent see it?

- **Poll-based:** Agent periodically calls `kb log <card> --since <ts>` to check for new entries.
- **File watch:** Drop a `.kb-message` file in the worktree the agent is told to check.
- **Signal-based:** Write to agent's stdin. Requires deeper integration.

Leaning toward poll-based. The agent's system prompt includes instructions to check for new human notes before each major step.


## 6. Non-goals (v1)

- No built-in CI/CD integration (gates handle this via shell commands)
- No multi-board / multi-project support
- No user authentication on the web UI (localhost only)
- No persistent server / daemon mode
- No mobile UI
- No integration with GitHub Issues, Jira, Linear, etc.
- No MCP server (future: could wrap CLI as MCP for richer integration)


## 7. Implementation Plan

### Phase 1: Core CLI + git integration

- Board model: filesystem read/write with atomic operations
- Git operations: branch creation, worktree management, merge/squash/rebase
- All CLI commands except spawn and serve
- Gate execution with environment variable injection
- `--json` output for all commands
- Unit tests

### Phase 2: Web UI

- Babashka HTTP + WebSocket server (with file watcher for live state)
- Self-contained HTML/JS SPA (no Node required to serve)
- React + shadcn dev UI (Vite build, for iteration)
- Board view with drag-and-drop (@dnd-kit)
- Card detail sheet with conversation timeline and diff viewer
- Live updates via filesystem watcher + WebSocket
- Block/unblock/note interventions from UI

### Phase 3: Sub-agent orchestration

- `kb context` command for system prompt generation
- `kb spawn` with configurable agent command template
- Conversation capture into structured history.jsonl
- Example CLAUDE.md / agent configurations
- Documentation and examples

### Phase 4: Polish + distribution

- `kb recover` for orphaned worktrees
- Merge conflict detection and handling
- `kb log --since` for incremental history reads
- pip package with bundled web UI assets
- README, usage guide, example projects