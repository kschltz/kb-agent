export interface BoardState {
  project: string;
  base_branch: string;
  stale_heartbeat_mins: number;
  lanes: Lane[];
  timestamp: number;
  error?: string;
}

export interface Lane {
  name: string;
  max_wip?: number;
  max_parallelism?: number;
  instructions?: string;
  cards: CardData[];
  [key: string]: unknown;
}

export interface CardData {
  id: string;
  title: string;
  lane: string;
  priority: number;
  blocked: boolean;
  blocked_reason: string;
  assigned_agent: string;
  branch: string;
  worktree: string;
  created_at: number;
  updated_at: number;
  tags: string[];
  history: HistoryEntry[];
  diff_stat: string;
  pending_approval: boolean;
  approved_by: string;
  pending_question: string;
  last_heartbeat: number | null;
  last_heartbeat_doing?: string;
  last_heartbeat_progress?: number;
  confidence?: number;
  reviewer?: string;
  parent_id?: string;
}

export interface HistoryEntry {
  ts: number;
  role: 'system' | 'human' | 'agent';
  action: string;
  content: string;
  agent_id?: string;
  gate?: string;
}

export type UICommand =
  | { action: 'move'; card_id: string; lane: string }
  | { action: 'reject'; card_id: string; reason?: string }
  | { action: 'block'; card_id: string; reason?: string }
  | { action: 'unblock'; card_id: string }
  | { action: 'approve'; card_id: string }
  | { action: 'ask'; card_id: string; question: string }
  | { action: 'answer'; card_id: string; answer: string }
  | { action: 'note'; card_id: string; message: string }
  | { action: 'add'; title: string; description?: string }
  | { action: 'edit'; card_id: string; title?: string; description?: string; priority?: number }
  | { action: 'heartbeat'; card_id: string }
  | { action: 'diff'; card_id: string }
  | { action: 'context'; card_id: string }
  | { action: 'gates'; card_id: string }
  | { action: 'reorder_lanes'; order: string[] }
  | { action: 'add_lane'; lane: string };

export type WSMessage =
  | { type: 'state'; data: BoardState }
  | { type: 'result'; data: CommandResult }
  | { type: 'error'; data: string };

export interface CommandResult {
  success: boolean;
  message?: string;
  gate_results?: GateResult[];
  card?: CardData;
  diff?: string;
  context?: string;
  gates?: GateInfo[];
}

export interface GateResult {
  gate: string;
  passed: boolean;
  output: string;
  timestamp: number;
}

export interface GateInfo {
  gate: string;
  target_lane: string;
  description: string | null;
}

export interface ActivityEntry {
  ts: number;
  role: 'system' | 'human' | 'agent';
  action: string;
  content: string;
  agent_id?: string;
  gate?: string;
  card_id: string;
  card_title: string;
  card_lane: string;
}
