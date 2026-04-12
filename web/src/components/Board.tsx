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

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over) return;

    const activeType = active.data.current?.type as string | undefined;

    if (activeType === 'lane') {
      // Lane reordering
      const fromName = active.data.current?.laneName as string;
      const toName = over.data.current?.laneName as string;
      if (!fromName || !toName || fromName === toName) return;
      const oldIndex = laneNames.indexOf(fromName);
      const newIndex = laneNames.indexOf(toName);
      if (oldIndex === -1 || newIndex === -1) return;
      const newOrder = arrayMove(laneNames, oldIndex, newIndex);
      send({ action: 'reorder_lanes', order: newOrder });
    } else {
      // Card move
      const card = active.data.current?.card as CardData | undefined;
      const targetLane = over.data.current?.lane as string | undefined;
      if (card && targetLane && card.lane !== targetLane) {
        send({ action: 'move', card_id: card.id, lane: targetLane });
      }
    }
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
        </div>
      </SortableContext>
    </DndContext>
  );
}
