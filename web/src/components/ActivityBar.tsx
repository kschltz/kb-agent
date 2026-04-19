import { useState } from 'react';
import type { BoardState } from '../types';
import { fmtTime } from '../lib/utils';

interface ActivityEvent {
  ts: number;
  card_id: string;
  card_title: string;
  role: string;
  action: string;
  content: string;
  lane: string;
}

interface ActivityBarProps {
  board: BoardState | null;
  onCardClick?: (cardId: string) => void;
}

function roleColor(role: string): string {
  if (role === 'human') return 'var(--accent)';
  if (role === 'agent') return 'var(--blue)';
  return 'var(--text-2)';
}

function actionColor(action: string): string {
  if (action === 'gate_fail') return 'var(--danger)';
  if (action === 'gate_pass') return 'var(--accent)';
  if (action === 'ask') return '#eab308';
  if (action === 'answer') return 'var(--accent)';
  if (action === 'created') return 'var(--accent)';
  if (action === 'merged') return 'var(--accent)';
  return 'var(--text-2)';
}

export function ActivityBar({ board, onCardClick }: ActivityBarProps) {
  const [expanded, setExpanded] = useState(false);
  const [filter, setFilter] = useState<'all' | 'human' | 'agent'>('all');

  if (!board?.lanes) return null;

  const allEvents: ActivityEvent[] = [];
  for (const lane of board.lanes) {
    for (const card of lane.cards) {
      if (card.history) {
        for (const h of card.history) {
          allEvents.push({
            ts: h.ts,
            card_id: card.id,
            card_title: card.title,
            role: h.role,
            action: h.action,
            content: h.content,
            lane: lane.name,
          });
        }
      }
    }
  }
  allEvents.sort((a, b) => b.ts - a.ts);

  const filtered = filter === 'all' ? allEvents : allEvents.filter(e => e.role === filter);
  const displayed = expanded ? filtered.slice(0, 50) : filtered.slice(0, 1);
  const lastEvent = allEvents[0];

  if (!lastEvent) {
    return (
      <div style={{
        display: 'flex', alignItems: 'center', gap: 12,
        padding: '6px 20px', background: 'var(--bg-1)',
        borderTop: '1px solid var(--border)',
        fontFamily: 'var(--mono)', fontSize: '0.769rem', color: 'var(--text-2)',
        flexShrink: 0, overflow: 'hidden',
      }}>
        <span>activity:</span>
        <span>waiting for updates...</span>
      </div>
    );
  }

  if (!expanded) {
    return (
      <div onClick={() => setExpanded(true)} style={{
        display: 'flex', alignItems: 'center', gap: 12,
        padding: '6px 20px', background: 'var(--bg-1)',
        borderTop: '1px solid var(--border)',
        fontFamily: 'var(--mono)', fontSize: '0.769rem', color: 'var(--text-2)',
        flexShrink: 0, overflow: 'hidden', cursor: 'pointer',
      }}>
        <span style={{ color: 'var(--text-2)' }}>activity:</span>
        <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', flex: 1 }}>
          <span style={{ color: roleColor(lastEvent.role) }}>{lastEvent.role}/{lastEvent.action}</span>{' '}
          [{lastEvent.card_id}] {(lastEvent.content || '').substring(0, 100)}
        </span>
        <span style={{ color: 'var(--text-2)', opacity: 0.5, flexShrink: 0 }}>{fmtTime(lastEvent.ts)}</span>
        <span style={{ color: 'var(--text-2)', opacity: 0.5, flexShrink: 0, fontSize: '0.615rem' }}>&#9650; expand</span>
      </div>
    );
  }

  return (
    <div style={{
      background: 'var(--bg-1)', borderTop: '1px solid var(--border)',
      flexShrink: 0, display: 'flex', flexDirection: 'column',
      maxHeight: 200,
    }}>
      {/* Header */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8,
        padding: '6px 12px', borderBottom: '1px solid var(--border)',
        fontFamily: 'var(--mono)', fontSize: '0.769rem',
      }}>
        <span style={{ color: 'var(--text-2)', fontWeight: 600 }}>ACTIVITY</span>
        <div style={{ display: 'flex', gap: 4, marginLeft: 8 }}>
          {(['all', 'human', 'agent'] as const).map(f => (
            <button key={f} onClick={() => setFilter(f)} style={{
              fontFamily: 'var(--mono)', fontSize: '0.692rem', padding: '2px 6px',
              borderRadius: 3, cursor: 'pointer',
              border: filter === f ? '1px solid var(--accent-dim)' : '1px solid var(--border)',
              background: filter === f ? 'var(--accent-glow)' : 'var(--bg-0)',
              color: filter === f ? 'var(--accent)' : 'var(--text-2)',
            }}>{f}</button>
          ))}
        </div>
        <span style={{ flex: 1 }} />
        <button onClick={() => setExpanded(false)} style={{
          fontFamily: 'var(--mono)', fontSize: '0.692rem', padding: '2px 6px',
          borderRadius: 3, cursor: 'pointer',
          border: '1px solid var(--border)', background: 'var(--bg-0)', color: 'var(--text-2)',
        }}>&#9660; collapse</button>
      </div>

      {/* Events */}
      <div style={{ overflowY: 'auto', padding: '4px 12px' }}>
        {displayed.length === 0 && (
          <div style={{ padding: '8px 0', fontSize: '0.769rem', color: 'var(--text-2)' }}>No events</div>
        )}
        {displayed.map((e, i) => (
          <div key={i} onClick={() => onCardClick?.(e.card_id)} style={{
            display: 'flex', gap: 8, padding: '3px 6px', borderRadius: 3,
            fontSize: '0.846rem', cursor: onCardClick ? 'pointer' : 'default',
          }}>
            <span style={{ fontFamily: 'var(--mono)', fontSize: '0.692rem', color: 'var(--text-2)', whiteSpace: 'nowrap', minWidth: 44, paddingTop: 1 }}>
              {fmtTime(e.ts)}
            </span>
            <span style={{
              fontFamily: 'var(--mono)', fontSize: '0.692rem', fontWeight: 600, color: 'var(--text-2)', minWidth: 28,
            }}>#{e.card_id}</span>
            <span style={{ fontFamily: 'var(--mono)', fontSize: '0.692rem', color: actionColor(e.action) }}>{e.action}</span>
            <span style={{ color: 'var(--text-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', flex: 1 }}>
              {e.content.substring(0, 120)}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
