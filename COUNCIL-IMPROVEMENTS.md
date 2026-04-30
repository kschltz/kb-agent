# Council of High Intelligence: How to Improve kb

**Deliberation Date:** 2026-04-30  
**Members:** Donella Meadows (Systems), Linus Torvalds (Pragmatism), Alan Watts (Perspective)  
**Topic:** How can we improve how this project works?

---

## Context

This deliberation analyzed the `kb` project — a Kanban board CLI for AI coding agents, built in Clojure/Babashka. It manages workflow via lanes, git worktrees per card, and JSONL history.

---

## Round 1: Independent Analysis

### Donella Meadows (Systems Thinking)

**System Map:**
- **Stocks:** Cards, Git worktrees, JSONL history files, Agent context
- **Flows:** Cards advancing through lanes, notes appended, commands executed
- **Feedback Loops:** Lane gates, `auto_spawn` triggers, `kb context` instructions

**Archetype:** "Shifting the Burden" + "Accidental Complexity"

The KB lessons document fix-after-fix: JSONL corruption patched with stdin piping, `auto_spawn` truncates to 200 chars, `--cwd` flag removed. Each patch solves a symptom while underlying fragility persists.

**Leverage Point:** Redesign the data model. JSONL creates corruption surface, no atomicity, no query capability. Replacing with SQLite/Datahike eliminates entire bug categories.

**Unintended Consequences:**
- More lanes = more blocking gates
- More validation = migration cost
- Stronger gates = slower iteration

---

### Linus Torvalds (Pragmatic Engineering)

**Critical Bug:** `kb spawn <card>` and `auto_spawn: true` are broken. The default `agent_command` template passes `--cwd` to Claude, which doesn't support that flag. This breaks the core spawn-based workflow.

**Second Bug:** `kb advance` gate requires agent note with `role: "agent"`, but `kb note` defaults to `role: "human"`. Creates a confusing trap.

**Maintainability:** JSONL history is fragile. Single malformed line corrupts entire card history.

**Ship Verdict:** Fix the `agent_command` template today. Spawning is fundamental.

---

### Alan Watts (Perspective Shifting)

**Frame Audit:** The question assumes the project is broken. But 12 KB lessons = evidence the tool *works enough* to generate learning.

**Double-Bind:** We're using a Kanban board to manage work on improving the Kanban board. The board is navel-gazing.

**Reframe:** What if the real issue isn't `kb` but our relationship to it? We treat it as authority. What if we sat with it differently — not as slave to workflow, but as playful companion in a game.

---

## Round 2: Cross-Examination

| Member | Disagrees With | Agrees With |
|--------|---------------|-------------|
| Meadows | Alan — cosmic detachment romanticizes dysfunction; offers no actionable guidance | Linus — `--cwd` fix validates structural intervention |
| Torvalds | Alan — cosmic perspective is "beautiful and completely useless for shipping" | Meadows — confirms `auto_spawn` + `agent_command` feedback loop |
| Watts | Linus — fixing spawning feeds the spawning economy that created fragility | Meadows — "Shifting the Burden" deepens his position |

---

## Round 3: Final Crystallization

| Member | Final Position |
|--------|----------------|
| **Meadows** | "The structure produces the behavior. Change the structure." |
| **Torvalds** | "Fix `agent_command` template. Ship the critical path fix." |
| **Watts** | "Stop seeking optimization — participate in the tool. The frame is the problem." |

---

## Actionable Recommendations

### 🔴 Priority 1: Fix the spawn template

- **Who:** Torvalds consensus (Meadows + Watts agree it's broken)
- **What:** Remove or fix the `--cwd` flag in `agent_command` template
- **Why:** One-line fix unblocks the entire spawn-based workflow
- **Status:** Broken — Fix today

### 🟡 Priority 2: Decouple `auto_spawn` from template

- **Who:** Meadows + Torvalds alignment
- **What:** Make `auto_spawn` rules explicit and testable, separate from CLI flags
- **Why:** Current feedback loop produces silent failures
- **Status:** Fragile — Redesign the coupling

### 🟡 Priority 3: Upgrade data model

- **Who:** Meadows
- **What:** Replace JSONL with SQLite/Datahike
- **Why:** Eliminates corruption surface, adds atomicity and query capability
- **Status:** Technical debt — Plan migration

### 🟢 Cultural: Appreciate the friction

- **Who:** Watts
- **What:** Stop treating imperfection as failure
- **Why:** The tool already works enough (12 KB lessons = evidence)
- **Status:** Ongoing — Mindset shift

---

## Summary

**The council agrees:** The spawn template is the immediate blocker. Fix it.

**The deeper insight (Meadows):** Real leverage is in system redesign — change information flows and authority structures, not individual bugs.

**The counterpoint (Watts):** The seeking is the point. Don't let optimization become the obstacle.

**Recommendation:** Fix the spawn template now. Schedule a systems audit for the data model upgrade. Don't let perfect be the enemy of shipped — but do ship the *right* fix, not another symptom patch.