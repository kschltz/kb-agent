import { useState } from 'react';
import { DndContext, type DragEndEvent } from '@dnd-kit/core';
import { SortableContext, arrayMove, horizontalListSortingStrategy } from '@dnd-kit/sortable';
import { Lane } from './Lane';
import type { BoardState, CardData, UICommand } from '../types';

interface BoardProps {
  board: BoardState;
  onCardClick: (card: CardData) => void;
  send: (cmd: UICommand) => void;
}

export function Board({ board, onCardClick, send }: BoardProps) {
  const laneNames = board.lanes.map(l => l.name);
  const [addingLane, setAddingLane] = useState(false);
  const [newLaneName, setNewLaneName] = useState('');

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over) return;

    const activeType = active.data.current?.type as string | undefined;

    if (activeType === 'lane') {
      const fromName = active.data.current?.laneName as string | undefined;
      const overId = String(over.id);
      const toName = overId.startsWith('lane-') ? overId.slice(5) : overId;
      if (!fromName || !toName || fromName === toName) return;
      const oldIndex = laneNames.indexOf(fromName);
      const newIndex = laneNames.indexOf(toName);
      if (oldIndex === -1 || newIndex === -1) return;
      send({ action: 'reorder_lanes', order: arrayMove(laneNames, oldIndex, newIndex) });
    } else {
      const card = active.data.current?.card as CardData | undefined;
      const targetLane = over.data.current?.lane as string | undefined;
      if (card && targetLane && card.lane !== targetLane) {
        send({ action: 'move', card_id: card.id, lane: targetLane });
      }
    }
  };

  const handleAddLane = () => {
    const name = newLaneName.trim().toLowerCase().replace(/\s+/g, '-');
    if (!name || laneNames.includes(name)) return;
    send({ action: 'add_lane', lane: name });
    setNewLaneName('');
    setAddingLane(false);
  };

  return (
    <DndContext onDragEnd={handleDragEnd}>
      <SortableContext items={laneNames} strategy={horizontalListSortingStrategy}>
        <div style={{
          display: 'flex', gap: 'var(--lane-gap)', padding: 'var(--lane-gap)',
          flex: 1, overflowX: 'auto', overflowY: 'hidden',
        }}>
          {board.lanes.map(lane => (
            <Lane key={lane.name} lane={lane} onCardClick={onCardClick} />
          ))}
          {/* Add lane column */}
          <div style={{
            flex: '0 0 260px', display: 'flex', flexDirection: 'column',
            alignItems: 'center', justifyContent: 'center',
            background: 'var(--bg-1)', border: '1px dashed var(--border)',
            borderRadius: 'var(--radius)', minHeight: 120,
            cursor: 'pointer',
          }} onClick={() => setAddingLane(true)}>
            {addingLane ? (
              <div style={{ display: 'flex', gap: 4, alignItems: 'center' }} onClick={e => e.stopPropagation()}>
                <input
                  autoFocus
                  value={newLaneName}
                  onChange={e => setNewLaneName(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') handleAddLane(); if (e.key === 'Escape') { setAddingLane(false); setNewLaneName(''); } }}
                  placeholder="lane-name"
                  style={{
                    fontFamily: 'var(--mono)', fontSize: '0.846rem',
                    background: 'var(--bg-0)', border: '1px solid var(--border)',
                    borderRadius: 'var(--radius)', color: 'var(--text-0)',
                    padding: '4px 8px', outline: 'none', width: 140,
                  }}
                />
                <button onClick={handleAddLane} style={{
                  fontFamily: 'var(--mono)', fontSize: '0.846rem',
                  background: 'var(--accent-glow)', border: '1px solid var(--accent-dim)',
                  borderRadius: 'var(--radius)', color: 'var(--accent)',
                  padding: '4px 10px', cursor: 'pointer',
                }}>Add</button>
              </div>
            ) : (
              <span style={{ fontFamily: 'var(--mono)', fontSize: '1.231rem', color: 'var(--text-2)', opacity: 0.4 }}>+</span>
            )}
          </div>
        </div>
      </SortableContext>
    </DndContext>
  );
}
