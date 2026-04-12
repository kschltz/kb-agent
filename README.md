# kb — Kanban Board for Coding Agents

Process discipline for AI coding agents: WIP limits, quality gates, git worktree isolation, and a live web UI.

## Install

### Prerequisites

- [Babashka](https://babashka.org/) (bb) v1.12+

### Install kb

```bash
# Install babashka (if not already installed)
curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install | bash

# Clone kb-agent
git clone https://github.com/your-org/kb-agent.git ~/.kb-agent

# Put kb on PATH
sudo ln -s ~/.kb-agent/bin/kb /usr/local/bin/kb
# or add an alias: echo 'alias kb="bb --config ~/.kb-agent/bb.edn kb"' >> ~/.bashrc
```

### Agent auto-install

Add this to your project's `CLAUDE.md` so agents can install kb on first use:

````markdown
## kb — Kanban Board

Install kb if not already available:
```bash
which kb || (curl -sL https://raw.githubusercontent.com/babashka/babashka/master/install | bash \
  && git clone https://github.com/your-org/kb-agent.git ~/.kb-agent \
  && sudo ln -s ~/.kb-agent/bin/kb /usr/local/bin/kb)
```

**Before running `kb init`, ask the user** how they want the board configured. Do not use defaults blindly.
Ask about: project name, base branch, lane names, quality gates, and agent_command template.
Only after the user confirms the config should you run `kb init` and edit `.kanban/board.yaml`.
````

## Setup — Ask the User First

Before running `kb init` in a project, **ask the user** how they want the board configured. The default config is a starting point — the board config determines the entire workflow, so don't skip this step.

Ask the user:

1. **Project name** — What should the board call this project?
2. **Base branch** — Which branch do cards merge into? (default: `main`)
3. **Lanes** — What workflow stages? (e.g. `backlog → in-progress → review → done`)
4. **Gates** — What checks must pass before a card moves lanes? (e.g. `npm test`, `npm run lint`)
5. **agent_command** — How should sub-agents be launched? (e.g. `claude --system-prompt "$(kb context {card_id})" --cwd {worktree}`)
6. **max_wip / max_parallelism** — Any limits on concurrent cards per lane?

Only after the user confirms the config, run `kb init` and edit `.kanban/board.yaml`.

See `examples/board.yaml` for a full annotated config.

## Quick Start

```bash
kb init                              # create .kanban/ in a git repo (after configuring board.yaml)
kb add "Fix auth bug" --desc fix.md  # add a card to backlog
kb pull --lane in-progress --agent claude  # claim card, move to in-progress, create worktree
kb note 001 "Fixed the null pointer"       # log progress
kb move 001 review                   # advance card (runs gates)
kb serve                             # open web UI
```

## CLI Commands

| Command | Description |
|---|---|
| `kb init` | Create `.kanban/`, verify git repo, record base branch |
| `kb add <title> [--desc FILE]` | Create a card in backlog |
| `kb pull [--lane] [--agent]` | Claim next card, create worktree + branch; `--lane` auto-moves card |
| `kb move <card> <lane>` | Move card; run gates; merge on entering `done` |
| `kb advance <card>` | Move card to next lane (shortcut for move) |
| `kb done <card>` | Move card to final lane (runs all gates) |
| `kb reject <card> [--reason]` | Push card back to previous lane |
| `kb block <card> [--reason]` | Block a card |
| `kb unblock <card>` | Remove block |
| `kb ask <card> <question>` | Ask the human a question (blocks card until answered) |
| `kb answer <card> <answer>` | Answer a pending question (unblocks card) |
| `kb approve <card>` | Approve a card pending approval |
| `kb note <card> <message>` | Add a note to card history |
| `kb log <card> [--since TS]` | Show structured conversation history |
| `kb diff <card> [--stat]` | Show git diff of card's branch vs base |
| `kb show <card>` | Full card details: meta + history + diff stats |
| `kb gates <card>` | Show gates for the next lane transition |
| `kb edit <card> [--title ...] [--priority N] [--desc ...]` | Edit card fields |
| `kb heartbeat <card>` | Record an agent heartbeat (signal you're alive) |
| `kb status` | Print the board |
| `kb context <card>` | Output card context for agent system prompt |
| `kb spawn <card>` | Start a sub-agent scoped to this card |
| `kb cleanup <card>` | Remove worktree, optionally delete branch |
| `kb recover [--clean]` | Detect and clean orphaned worktrees |
| `kb serve [--port 8741]` | Start web UI |

All commands accept `--json`. Mutating commands accept `--agent <id>`.

## Board Configuration

The board is configured via `.kanban/board.yaml`:

```yaml
project: my-project
base_branch: main
merge_strategy: squash    # squash | merge | rebase

agent_command: "claude --system-prompt \"$(kb context {card_id})\" --cwd {worktree}"

lanes:
  - name: backlog
    instructions: "Waiting to be picked up. Do not start work yet."

  - name: in-progress
    max_wip: 5
    max_parallelism: 2
    instructions: "Implement the solution. Write code, make changes, iterate."

  - name: review
    max_wip: 3
    gate_from_in-progress:
      - "cd $KB_WORKTREE && npm test"
    instructions: "Review changes for correctness and style."

  - name: done
    gate_from_review:
      - "cd $KB_WORKTREE && npm run lint"
    on_enter: merge
```

### Lane Instructions

Each lane can include an `instructions` field that tells agents what work is expected in that lane. `kb context` includes a `## Lane:` section with the applicable instructions, so agents know what to do before advancing.

Common lane names (`backlog`, `discovery`, `plan`, `in-progress`, `unit-tests`, `review`, `testing`, `done`) have sensible defaults. Add `instructions` to a lane in `board.yaml` to override:

```yaml
lanes:
  - name: discovery
    instructions: "Research the problem. Do NOT implement. Log findings with kb note."
```

### Gate Environment Variables

| Variable | Description |
|---|---|
| `KB_CARD_ID` | Card ID (e.g., "001") |
| `KB_CARD_TITLE` | Card title |
| `KB_CARD_LANE` | Current lane (before move) |
| `KB_CARD_DIR` | Path to card metadata directory |
| `KB_WORKTREE` | Path to the card's git worktree |
| `KB_BRANCH` | Git branch name for this card |
| `KB_BASE_BRANCH` | The base branch |

## Web UI

```bash
kb serve [--port 8741]
```

Auto-opens the browser. Serves a live board view at `http://localhost:8741` with:

- Drag-and-drop card transitions (runs gates server-side)
- Card detail panel with conversation history and diff viewer
- Real-time updates via WebSocket
- Add cards, notes, block/unblock from the browser
- Lane management: add, rename, reorder (drag), and delete lanes
- Lane instructions displayed under each lane header

## How It Works

**File isolation**: Each card gets its own git worktree at `.kanban/worktrees/<id>/`. Agents work in isolated copies — two agents can work on two cards simultaneously without conflict.

**Quality gates**: Shell commands defined in `board.yaml` run on lane transitions. Gates check exit code (0 = pass) and receive card context via environment variables.

**Conversation history**: Each card has a `history.jsonl` with structured entries (role, action, content, timestamp). Agents log progress with `kb note`, humans add notes via the web UI.

**Sub-agent spawning**: `kb spawn <card>` starts an agent with the card's full context (description, history, gates to pass) injected as a system prompt.

## File Layout

```
.kanban/
  board.yaml              # Lane definitions, gates, WIP limits
  cards/
    001-fix-auth-bug/
      meta.yaml           # Card state: lane, priority, blocked, agent, dependencies
      history.jsonl        # Structured conversation log
      description.md      # Card description / acceptance criteria (optional)
  worktrees/
    001/                   # Isolated git worktree for card 001
```

## Examples

- `examples/board.yaml` — annotated board config for a Node.js project
- `examples/CLAUDE.md` — project instructions template for agents