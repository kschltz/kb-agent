import { useDroppable } from '@dnd-kit/core';
import { Card } from './Card';
import type { Lane as LaneType, CardData } from '../types';

interface LaneProps {
  lane: LaneType;
  onCardClick: (card: CardData) => void;
}

export function Lane({ lane, onCardClick }: LaneProps) {
  const { setNodeRef, isOver } = useDroppable({
    id: `lane-${lane.name}`,
    data: { lane: lane.name },
  });

  const maxWip = lane.max_wip ?? '∞';
  const count = lane.cards.length;

  return (
    <div style={{
      flex: 1, minWidth: 260, maxWidth: 400,
      display: 'flex', flexDirection: 'column',
      background: 'var(--bg-1)', border: '1px solid var(--border)',
      borderRadius: 'var(--radius)', overflow: 'hidden',
    }}>
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '10px 14px', borderBottom: '1px solid var(--border)',
        background: 'var(--bg-2)', flexShrink: 0,
      }}>
        <span style={{
          fontFamily: 'var(--mono)', fontWeight: 600, fontSize: 11,
          textTransform: 'uppercase', letterSpacing: '1.2px', color: 'var(--text-1)',
        }}>{lane.name}</span>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--text-2)' }}>
          <span style={{ padding: '1px 6px', background: 'var(--bg-3)', borderRadius: 3 }}>{count}/{maxWip}</span>
          {lane.max_parallelism && <span title="Max parallelism">⊞{lane.max_parallelism}</span>}
        </div>
      </div>

      <div ref={setNodeRef} style={{
        flex: 1, overflowY: 'auto', padding: 8,
        display: 'flex', flexDirection: 'column', gap: 6,
        ...(isOver ? {
          background: 'var(--accent-glow)',
          border: '1px dashed var(--accent-dim)',
          borderRadius: 4, margin: 4, padding: 4,
        } : {}),
      }}>
        {lane.cards.length === 0 ? (
          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            flex: 1, fontFamily: 'var(--mono)', fontSize: 11,
            color: 'var(--text-2)', opacity: 0.5,
          }}>no cards</div>
        ) : (
          lane.cards.map(card => (
            <Card key={card.id} card={card} onClick={() => onCardClick(card)} />
          ))
        )}
      </div>
    </div>
  );
}
