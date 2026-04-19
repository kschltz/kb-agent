import { useEffect, useMemo, useState, type ReactNode } from 'react';
import type { BoardState, CardData } from '../types';

interface AttentionDashboardProps {
  board: BoardState;
  onCardClick: (card: CardData) => void;
}

interface AttentionItem {
  card: CardData;
  detail: ReactNode;
}

interface AttentionGroup {
  label: string;
  items: AttentionItem[];
}

function computeGroups(board: BoardState, now: number): AttentionGroup[] {
  const staleThresholdMs = board.stale_heartbeat_mins * 60 * 1000;
  const cards = board.lanes.flatMap(l => l.cards);

  const blocked: AttentionItem[] = cards
    .filter(c => c.blocked && !c.pending_question)
    .map(c => ({ card: c, detail: <>reason: {c.blocked_reason}</> }));

  const pendingQuestion: AttentionItem[] = cards
    .filter(c => !!c.pending_question)
    .map(c => ({ card: c, detail: <>"{c.pending_question}"</> }));

  const pendingApproval: AttentionItem[] = cards
    .filter(c => c.pending_approval)
    .map(c => ({ card: c, detail: <>in {c.lane}</> }));

  const staleHeartbeat: AttentionItem[] = cards
    .filter(c => c.assigned_agent && !c.blocked && !c.pending_approval && c.lane !== 'done')
    .map(c => {
      const lastHbMs = (c.last_heartbeat ?? c.updated_at ?? c.created_at) * 1000;
      return { card: c, ageMs: now - lastHbMs };
    })
    .filter(x => x.ageMs > staleThresholdMs)
    .map(({ card, ageMs }) => ({
      card,
      detail: <>last heartbeat {Math.floor(ageMs / 60000)}m ago</>,
    }));

  return [
    { label: `🛑 Blocked`, items: blocked },
    { label: `❓ Pending question`, items: pendingQuestion },
    { label: `✋ Pending approval`, items: pendingApproval },
    { label: `💤 Stale heartbeat (>${board.stale_heartbeat_mins}m)`, items: staleHeartbeat },
  ];
}

export function AttentionDashboard({ board, onCardClick }: AttentionDashboardProps) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(id);
  }, []);
  const groups = useMemo(() => computeGroups(board, now), [board, now]);
  const total = groups.reduce((n, g) => n + g.items.length, 0);

  if (total === 0) return null;

  return (
    <div className="attention-dashboard">
      <h3 className="attention-header">🚨 Needs Attention ({total})</h3>
      <div className="attention-grid">
        {groups.map(g => g.items.length > 0 && (
          <div key={g.label} className="attention-group">
            <h4>{g.label} ({g.items.length})</h4>
            <ul>
              {g.items.map(({ card, detail }) => (
                <li key={card.id} onClick={() => onCardClick(card)}>
                  <strong>[{card.id}]</strong> {card.title}
                  <div className="attention-reason">{detail}</div>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>
    </div>
  );
}
