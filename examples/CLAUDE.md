# Project Instructions

This project uses `kb` for task management with kanban-style workflow.

## Setup — Ask the User First

Before running `kb init`, **ask the user** how they want the board configured:
1. Project name
2. Base branch (default: main)
3. Lane names and workflow stages
4. Quality gates (shell commands that must pass before lane transitions)
5. agent_command template (how sub-agents are launched)
6. max_wip and max_parallelism limits

Only after the user confirms, run `kb init` and edit `.kanban/board.yaml`.

## Working with the Board

1. `kb status` — see what's available
2. `kb pull --lane in-progress --agent claude` — claim the next card (auto-moves to that lane)
3. Work in the card's worktree (shown in pull output)
4. `kb note <id> "description of what you did"` — log progress
5. `kb ask <id> "question for the human"` — ask a question (card will be blocked until answered)
6. `kb log <id>` — check for new human notes or answers before each major step
7. `kb heartbeat <id>` — signal you are still working (call every 2 minutes)
8. `kb advance <id>` — move card to next lane when done (runs gates)
9. `kb done <id>` — move card directly to the final lane (runs all gates)
10. If gates fail, read the output, fix the issue, and retry

## Rules

- Always work inside the card's worktree, not the main tree
- Use `kb pull --lane <lane>` to both claim and move a card in one step
- Use `kb note` to record key decisions and progress
- Use `kb ask` when you need clarification — the card will be blocked until the human answers
- Do not skip quality gates — fix failures before retrying
- One card at a time unless explicitly told to work on multiple