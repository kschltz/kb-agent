import { DndContext, type DragEndEvent } from '@dnd-kit/core';
import { Lane } from './Lane';
import type { BoardState, CardData, UICommand } from '../types';

interface BoardProps {
  board: BoardState;
  onCardClick: (card: CardData) => void;
  send: (cmd: UICommand) => void;
}

export function Board({ board, onCardClick, send }: BoardProps) {
  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event;
    if (!over) return;

    const card = active.data.current?.card as CardData | undefined;
    const targetLane = over.data.current?.lane as string | undefined;

    if (card && targetLane && card.lane !== targetLane) {
      send({ action: 'move', card_id: card.id, lane: targetLane });
    }
  };

  return (
    <DndContext onDragEnd={handleDragEnd}>
      <div style={{
        display: 'flex', gap: 'var(--lane-gap)', padding: 'var(--lane-gap)',
        flex: 1, overflowX: 'auto', overflowY: 'hidden',
      }}>
        {board.lanes.map(lane => (
          <Lane key={lane.name} lane={lane} onCardClick={onCardClick} />
        ))}
      </div>
    </DndContext>
  );
}
