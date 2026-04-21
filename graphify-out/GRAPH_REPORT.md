# Graph Report - ./  (2026-04-13)

## Corpus Check
- Corpus is ~11,684 words - fits in a single context window. You may not need a graph.

## Summary
- 108 nodes · 123 edges · 24 communities detected
- Extraction: 85% EXTRACTED · 15% INFERRED · 0% AMBIGUOUS · INFERRED: 19 edges (avg confidence: 0.79)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- [[_COMMUNITY_App Core & Activity Feed|App Core & Activity Feed]]
- [[_COMMUNITY_Card Status & Signals|Card Status & Signals]]
- [[_COMMUNITY_Board Design & Architecture|Board Design & Architecture]]
- [[_COMMUNITY_Icon System|Icon System]]
- [[_COMMUNITY_Utility Functions|Utility Functions]]
- [[_COMMUNITY_Board & Lane UI|Board & Lane UI]]
- [[_COMMUNITY_Brand & Visual Assets|Brand & Visual Assets]]
- [[_COMMUNITY_App Navigation Handlers|App Navigation Handlers]]
- [[_COMMUNITY_Card Detail Actions|Card Detail Actions]]
- [[_COMMUNITY_Toast & Command Types|Toast & Command Types]]
- [[_COMMUNITY_Activity Bar Colors|Activity Bar Colors]]
- [[_COMMUNITY_Context Delivery Design|Context Delivery Design]]
- [[_COMMUNITY_CLI Interface & Examples|CLI Interface & Examples]]
- [[_COMMUNITY_Board State Hook|Board State Hook]]
- [[_COMMUNITY_Add Card Dialog|Add Card Dialog]]
- [[_COMMUNITY_Toast Container|Toast Container]]
- [[_COMMUNITY_Drag & Drop Handler|Drag & Drop Handler]]
- [[_COMMUNITY_Activity Feed Colors|Activity Feed Colors]]
- [[_COMMUNITY_Card Heartbeat|Card Heartbeat]]
- [[_COMMUNITY_ESLint Config|ESLint Config]]
- [[_COMMUNITY_Vite Config|Vite Config]]
- [[_COMMUNITY_TypeScript Types|TypeScript Types]]
- [[_COMMUNITY_Header Component|Header Component]]
- [[_COMMUNITY_Lane Component|Lane Component]]

## God Nodes (most connected - your core abstractions)
1. `App Root Component` - 12 edges
2. `CardData Interface` - 8 edges
3. `kb-agent Icons Sprite SVG` - 7 edges
4. `BoardState Interface` - 6 edges
5. `CommandResult Interface` - 6 edges
6. `useBoard Hook` - 6 edges
7. `Board Component` - 6 edges
8. `CardDetail Slide-Out Panel` - 6 edges
9. `ActivityFeed Component` - 6 edges
10. `kb-agent Favicon SVG` - 5 edges

## Surprising Connections (you probably didn't know these)
- `Design: Card Dependencies (depends_on)` --references--> `CardData Interface`  [INFERRED]
  DESIGN.md → web/src/types.ts
- `Design: Agent Confidence Signals` --references--> `CardData Interface`  [INFERRED]
  DESIGN.md → web/src/types.ts
- `Decision: Self-Contained HTML/JS SPA Web UI` --references--> `Board Component`  [INFERRED]
  DESIGN.md → web/src/components/Board.tsx
- `Design: Stuck Agent Detection (heartbeat_timeout)` --conceptually_related_to--> `heartbeatStaleness Helper`  [INFERRED]
  DESIGN.md → web/src/components/Card.tsx
- `Decision: CLI as Agent Interface` --references--> `kb — Kanban Board for Coding Agents`  [INFERRED]
  DESIGN.md → README.md

## Hyperedges (group relationships)
- **WebSocket State Synchronization Pattern** — hooks_useBoard_websocket, types_WSMessage, types_BoardState, app_App [EXTRACTED 0.95]
- **Drag-and-Drop Card/Lane Move Flow** — board_dnd, lane_droppable, lane_sortable, components_Board, types_UICommand [EXTRACTED 0.90]
- **Card Status Badge Rendering System** — components_Card, card_Badge, card_heartbeatStaleness, types_CardData [EXTRACTED 0.92]
- **Social Platform Icons (Bluesky, Discord, X)** — icon_bluesky, icon_discord, icon_x [INFERRED 0.90]
- **Developer Resource Icons (GitHub, Documentation)** — icon_github, icon_documentation [INFERRED 0.85]
- **Filled Dark Style Icon Group** — icon_bluesky, icon_discord, icon_github, icon_x [EXTRACTED 1.00]
- **Outlined Purple Style Icon Group** — icon_documentation, icon_social [EXTRACTED 1.00]
- **Purple Brand Color System** — favicon_purple_brand_color, icon_style_outlined_purple, favicon_svg, icons_svg [INFERRED 0.85]

## Communities

### Community 0 - "App Core & Activity Feed"
Cohesion: 0.15
Nodes (21): ActivityFeed /api/activity Endpoint, App Root Component, activityTick Board-Change Trigger, findCardById Helper, Zoom State (localStorage persistence), CardDetail /api/cards/{id}/diff Endpoint, Card Diff Viewer (colorDiff), ActivityBar Component (+13 more)

### Community 1 - "Card Status & Signals"
Cohesion: 0.22
Nodes (10): Badge Sub-Component, heartbeatStaleness Helper, Card Component, Design: Card Dependencies (depends_on), Design: Agent Confidence Signals, Design: Notification Hooks, Design: Stuck Agent Detection (heartbeat_timeout), CardData Interface (+2 more)

### Community 2 - "Board Design & Architecture"
Cohesion: 0.28
Nodes (9): board.yaml Schema, Card Lifecycle: Start to Finish, Filesystem Layout (.kanban/), Decision: Local Filesystem State Storage, Decision: Gate Failure Stays in Current Lane, Problem: Unstructured Agent Workflow, Decision: Quality Gates as Shell Commands, Decision: Structured Conversation History (history.jsonl) (+1 more)

### Community 3 - "Icon System"
Cohesion: 0.42
Nodes (9): Bluesky Social Icon, Discord Icon, Documentation / Code File Icon, GitHub Icon, Social / User Profile Icon, Filled Dark Style (black #08060d fill), Outlined Purple Style (stroke #aa3bff, no fill), X (Twitter) Icon (+1 more)

### Community 4 - "Utility Functions"
Cohesion: 0.33
Nodes (0): 

### Community 5 - "Board & Lane UI"
Cohesion: 0.47
Nodes (6): DnD Drag-and-Drop (dnd-kit), Board Component, Lane Component, Decision: Self-Contained HTML/JS SPA Web UI, Lane Droppable Target, Lane Sortable Handle

### Community 6 - "Brand & Visual Assets"
Cohesion: 0.47
Nodes (6): Blue Accent Color (#47bfff), Lightning Bolt / Power Symbol Shape, Purple Brand Color (#863bff), kb-agent Favicon SVG, kb-agent Web UI, Web Public Static Assets

### Community 7 - "App Navigation Handlers"
Cohesion: 0.5
Nodes (2): findCardById(), handleActivityCardClick()

### Community 8 - "Card Detail Actions"
Cohesion: 0.4
Nodes (0): 

### Community 9 - "Toast & Command Types"
Cohesion: 0.4
Nodes (5): ToastContainer Component, Global __kbToast Window Exposure, CommandResult Interface, GateInfo Interface, GateResult Interface

### Community 10 - "Activity Bar Colors"
Cohesion: 0.67
Nodes (0): 

### Community 11 - "Context Delivery Design"
Cohesion: 0.67
Nodes (3): Design: Context Compaction Strategy, Open Question: Human Note Delivery to Agent, Design: Progressive Context Disclosure

### Community 12 - "CLI Interface & Examples"
Cohesion: 0.67
Nodes (3): Decision: CLI as Agent Interface, Example CLAUDE.md Agent Instructions, kb — Kanban Board for Coding Agents

### Community 13 - "Board State Hook"
Cohesion: 1.0
Nodes (0): 

### Community 14 - "Add Card Dialog"
Cohesion: 1.0
Nodes (0): 

### Community 15 - "Toast Container"
Cohesion: 1.0
Nodes (0): 

### Community 16 - "Drag & Drop Handler"
Cohesion: 1.0
Nodes (0): 

### Community 17 - "Activity Feed Colors"
Cohesion: 1.0
Nodes (0): 

### Community 18 - "Card Heartbeat"
Cohesion: 1.0
Nodes (0): 

### Community 19 - "ESLint Config"
Cohesion: 1.0
Nodes (0): 

### Community 20 - "Vite Config"
Cohesion: 1.0
Nodes (0): 

### Community 21 - "TypeScript Types"
Cohesion: 1.0
Nodes (0): 

### Community 22 - "Header Component"
Cohesion: 1.0
Nodes (0): 

### Community 23 - "Lane Component"
Cohesion: 1.0
Nodes (0): 

## Knowledge Gaps
- **22 isolated node(s):** `HistoryEntry Interface`, `GateResult Interface`, `GateInfo Interface`, `ActivityEntry Interface`, `findCardById Helper` (+17 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **Thin community `Board State Hook`** (2 nodes): `useBoard()`, `useBoard.ts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Add Card Dialog`** (2 nodes): `submit()`, `AddCardDialog.tsx`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Toast Container`** (2 nodes): `ToastContainer()`, `Toast.tsx`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Drag & Drop Handler`** (2 nodes): `handleDragEnd()`, `Board.tsx`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Activity Feed Colors`** (2 nodes): `actionColor()`, `ActivityFeed.tsx`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Card Heartbeat`** (2 nodes): `heartbeatStaleness()`, `Card.tsx`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `ESLint Config`** (1 nodes): `eslint.config.js`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Vite Config`** (1 nodes): `vite.config.ts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `TypeScript Types`** (1 nodes): `types.ts`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Header Component`** (1 nodes): `Header.tsx`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.
- **Thin community `Lane Component`** (1 nodes): `Lane.tsx`
  Too small to be a meaningful cluster - may be noise or needs more connections extracted.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `App Root Component` connect `App Core & Activity Feed` to `Toast & Command Types`, `Board & Lane UI`?**
  _High betweenness centrality (0.059) - this node is a cross-community bridge._
- **Why does `CardData Interface` connect `Card Status & Signals` to `App Core & Activity Feed`, `Toast & Command Types`?**
  _High betweenness centrality (0.049) - this node is a cross-community bridge._
- **Why does `CardDetail Slide-Out Panel` connect `App Core & Activity Feed` to `Card Status & Signals`?**
  _High betweenness centrality (0.035) - this node is a cross-community bridge._
- **Are the 2 inferred relationships involving `CardData Interface` (e.g. with `Design: Card Dependencies (depends_on)` and `Design: Agent Confidence Signals`) actually correct?**
  _`CardData Interface` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `HistoryEntry Interface`, `GateResult Interface`, `GateInfo Interface` to the rest of the system?**
  _22 weakly-connected nodes found - possible documentation gaps or missing edges._