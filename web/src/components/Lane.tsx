import { useDroppable } from '@dnd-kit/core';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import { Card } from './Card';
import type { Lane as LaneType, CardData } from '../types';

interface LaneProps {
  lane: LaneType;
  onCardClick: (card: CardData) => void;
}

export function Lane({ lane, onCardClick }: LaneProps) {
  // Droppable target for card-move drops (the card area inside the lane)
  const { setNodeRef: setDropRef, isOver } = useDroppable({
    id: `lane-${lane.name}`,
    data: { lane: lane.name },
  });

  // Sortable for lane reordering (the lane column itself)
  const {
    attributes,
    listeners,
    setNodeRef: setSortRef,
    setActivatorNodeRef,
    transform,
    transition,
    isDragging,
  } = useSortable({
    id: lane.name,
    data: { type: 'lane', laneName: lane.name },
  });

  const maxWip = lane.max_wip ?? '∞';
  const count = lane.cards.length;

  const style: React.CSSProperties = {
    flex: 1, minWidth: 260, maxWidth: 400,
    display: 'flex', flexDirection: 'column',
    background: 'var(--bg-1)', border: '1px solid var(--border)',
    borderRadius: 'var(--radius)', overflow: 'hidden',
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <div ref={setSortRef} style={style}>
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '10px 14px', borderBottom: '1px solid var(--border)',
        background: 'var(--bg-2)', flexShrink: 0,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {/* Drag handle — only this element activates lane sorting */}
          <span
            ref={setActivatorNodeRef}
            {...listeners}
            {...attributes}
            title="Drag to reorder lane"
            style={{
              cursor: 'grab', color: 'var(--text-2)', fontSize: 12,
              lineHeight: 1, userSelect: 'none', opacity: 0.5,
            }}>⠿</span>
          <span style={{
            fontFamily: 'var(--mono)', fontWeight: 600, fontSize: 11,
            textTransform: 'uppercase', letterSpacing: '1.2px', color: 'var(--text-1)',
          }}>{lane.name}</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontFamily: 'var(--mono)', fontSize: 10, color: 'var(--text-2)' }}>
          <span style={{ padding: '1px 6px', background: 'var(--bg-3)', borderRadius: 3 }}>{count}/{maxWip}</span>
          {lane.max_parallelism && <span title="Max parallelism">⊞{lane.max_parallelism}</span>}
        </div>
      </div>

      <div ref={setDropRef} style={{
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
