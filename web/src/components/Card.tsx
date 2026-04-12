import { useDraggable } from '@dnd-kit/core';
import type { CardData } from '../types';

interface CardProps {
  card: CardData;
  onClick: () => void;
}

function heartbeatStaleness(card: CardData): 'fresh' | 'stale' | 'dead' | null {
  if (!card.assigned_agent || !card.last_heartbeat) return null;
  const ageSec = (Date.now() / 1000) - card.last_heartbeat;
  if (ageSec < 120) return 'fresh';
  if (ageSec < 300) return 'stale';
  return 'dead';
}

export function Card({ card, onClick }: CardProps) {
  const { attributes, listeners, setNodeRef, setActivatorNodeRef, transform, isDragging } = useDraggable({
    id: `card-${card.id}`,
    data: { card },
  });

  const hb = heartbeatStaleness(card);

  const style: React.CSSProperties = {
    background: 'var(--bg-2)',
    border: '1px solid var(--border)',
    borderRadius: 'var(--radius)',
    padding: '10px 12px',
    cursor: 'pointer',
    position: 'relative',
    opacity: isDragging ? 0.4 : 1,
    transform: transform ? `translate(${transform.x}px, ${transform.y}px)${isDragging ? ' scale(0.97)' : ''}` : undefined,
    borderLeft: card.blocked ? '3px solid var(--danger)' : card.assigned_agent ? '3px solid var(--accent)' : undefined,
    zIndex: isDragging ? 50 : undefined,
  };

  return (
    <div ref={setNodeRef} style={style} {...attributes} onClick={onClick}>
      {/* Drag handle — only this element initiates drag */}
      <span
        ref={setActivatorNodeRef}
        {...listeners}
        onClick={e => e.stopPropagation()}
        style={{
          position: 'absolute', top: 6, right: 6,
          cursor: 'grab', color: 'var(--text-2)', fontSize: 10, opacity: 0.4,
          padding: '2px 3px', lineHeight: 1, userSelect: 'none',
        }}
        title="Drag to move"
      >⠿</span>
      <div style={{ fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--text-2)', marginBottom: 3 }}>#{card.id}</div>
      <div style={{ fontWeight: 500, fontSize: 13, color: 'var(--text-0)', marginBottom: 6, wordBreak: 'break-word' }}>{card.title}</div>

      {card.tags?.length > 0 && (
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4, marginBottom: 6 }}>
          {card.tags.map(t => (
            <span key={t} style={{
              fontFamily: 'var(--mono)', fontSize: 9, padding: '1px 6px',
              background: 'var(--bg-0)', border: '1px solid var(--border)',
              borderRadius: 3, color: 'var(--text-2)',
            }}>#{t}</span>
          ))}
        </div>
      )}

      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
        {card.assigned_agent && <Badge color="accent">● {card.assigned_agent}</Badge>}
        {card.blocked && <Badge color="danger">⊘ blocked</Badge>}
        {card.pending_approval && <Badge color="amber">⏳ approval</Badge>}
        {card.pending_question && <Badge color="amber">❓ question</Badge>}
        {card.branch && <Badge color="blue" truncate>{card.branch.replace('kb/', '')}</Badge>}
        {card.diff_stat?.trim() && <Badge color="purple">Δ</Badge>}
        {card.confidence != null && <Badge color={card.confidence >= 80 ? 'accent' : card.confidence >= 50 ? 'amber' : 'danger'}>C:{card.confidence}%</Badge>}
        {hb === 'fresh' && <Badge color="accent">💚 alive</Badge>}
        {hb === 'stale' && <Badge color="amber">⚠️ stale</Badge>}
        {hb === 'dead' && <Badge color="danger">💤 no signal</Badge>}
        {card.last_heartbeat_doing && (
          <Badge color="blue" truncate>
            {card.last_heartbeat_doing}
            {card.last_heartbeat_progress != null && ` ${Math.round(card.last_heartbeat_progress * 100)}%`}
          </Badge>
        )}
      </div>
    </div>
  );
}

function Badge({ children, color, truncate }: { children: React.ReactNode; color: string; truncate?: boolean }) {
  const colors: Record<string, { bg: string; border: string; fg: string }> = {
    accent: { bg: 'var(--accent-glow)', border: 'var(--accent-dim)', fg: 'var(--accent)' },
    danger: { bg: 'rgba(239,90,90,0.1)', border: 'var(--danger-dim)', fg: 'var(--danger)' },
    blue: { bg: 'rgba(90,158,240,0.08)', border: 'var(--blue-dim)', fg: 'var(--blue)' },
    purple: { bg: 'rgba(160,122,239,0.08)', border: 'rgba(160,122,239,0.3)', fg: 'var(--purple)' },
    amber: { bg: 'rgba(234,179,8,0.1)', border: 'rgba(234,179,8,0.3)', fg: '#eab308' },
  };
  const c = colors[color] || colors.accent;
  return (
    <span style={{
      fontFamily: 'var(--mono)', fontSize: 9, padding: '1px 6px', borderRadius: 3,
      display: 'inline-flex', alignItems: 'center', gap: 4,
      background: c.bg, border: `1px solid ${c.border}`, color: c.fg,
      ...(truncate ? { maxWidth: 180, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' as const } : {}),
    }}>{children}</span>
  );
}
