import { useState, useEffect } from 'react';
import { useBoard } from './hooks/useBoard';
import { Header } from './components/Header';
import { Board } from './components/Board';
import { CardDetail } from './components/CardDetail';
import { AddCardDialog } from './components/AddCardDialog';
import { ActivityBar } from './components/ActivityBar';
import { ToastContainer } from './components/Toast';
import type { CardData } from './types';
import './index.css';

function findCardById(board: { lanes: { cards: CardData[] }[] } | null, cardId: string): CardData | null {
  if (!board) return null;
  for (const lane of board.lanes) {
    for (const card of lane.cards) {
      if (card.id === cardId) return card;
    }
  }
  return null;
}

export default function App() {
  const { board, connected, send, lastResult } = useBoard();
  const [selectedCard, setSelectedCard] = useState<CardData | null>(null);
  const [addDialogOpen, setAddDialogOpen] = useState(false);
  const [zoom, setZoom] = useState<number>(() => {
    const stored = localStorage.getItem('kb-zoom');
    const parsed = stored ? parseFloat(stored) : 1;
    return isNaN(parsed) ? 1 : parsed;
  });

  const handleZoomChange = (next: number) => {
    setZoom(next);
    localStorage.setItem('kb-zoom', String(next));
  };

  // Keep selected card data fresh when board updates
  useEffect(() => {
    if (!selectedCard || !board) return;
    const fresh = findCardById(board, selectedCard.id);
    if (fresh) setSelectedCard(fresh);
  }, [board, selectedCard]);

  const laneNames = board?.lanes.map(l => l.name) ?? [];

  const handleActivityCardClick = (cardId: string) => {
    const card = findCardById(board, cardId);
    if (card) setSelectedCard(card);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', zoom }}>
      <Header board={board} connected={connected} onAddCard={() => setAddDialogOpen(true)} zoom={zoom} onZoomChange={handleZoomChange} />

      {board && !board.error ? (
        <Board board={board} onCardClick={setSelectedCard} send={send} />
      ) : (
        <div style={{
          flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--text-2)',
        }}>
          {board?.error || 'connecting...'}
        </div>
      )}

      <ActivityBar board={board} onCardClick={handleActivityCardClick} />

      <CardDetail card={selectedCard} laneNames={laneNames} onClose={() => setSelectedCard(null)} send={send} />
      <AddCardDialog open={addDialogOpen} onClose={() => setAddDialogOpen(false)} send={send} />
      <ToastContainer lastResult={lastResult} />
    </div>
  );
}
