import { useState, useEffect, useRef } from 'react';
import { useBoard } from './hooks/useBoard';
import { Header } from './components/Header';
import { Board } from './components/Board';
import { CardDetail } from './components/CardDetail';
import { AddCardDialog } from './components/AddCardDialog';
import { ActivityBar } from './components/ActivityBar';
import { ActivityFeed } from './components/ActivityFeed';
import { ToastContainer } from './components/Toast';
import { AttentionDashboard } from './components/AttentionDashboard';
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

type TabId = 'board' | 'activity';

export default function App() {
  const { board, connected, send, lastResult } = useBoard();
  const [selectedCard, setSelectedCard] = useState<CardData | null>(null);
  const [addDialogOpen, setAddDialogOpen] = useState(false);
  const [activeTab, setActiveTab] = useState<TabId>('board');
  const [activityTick, setActivityTick] = useState(0);
  const prevBoardRef = useRef<typeof board>(null);
  const [zoom, setZoom] = useState<number>(() => {
    const stored = localStorage.getItem('kb-zoom');
    const parsed = stored ? parseFloat(stored) : 1;
    return isNaN(parsed) ? 1 : parsed;
  });

  useEffect(() => {
    document.documentElement.style.setProperty('--font-scale', String(zoom));
  }, [zoom]);

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

  // Bump activity tick on board change to trigger feed refresh
  useEffect(() => {
    if (board && board !== prevBoardRef.current) {
      prevBoardRef.current = board;
      setActivityTick(t => t + 1);
    }
  }, [board]);

  const laneNames = board?.lanes.map(l => l.name) ?? [];

  const handleActivityCardClick = (cardId: string) => {
    const card = findCardById(board, cardId);
    if (card) setSelectedCard(card);
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh' }}>
      <Header board={board} connected={connected} onAddCard={() => setAddDialogOpen(true)} zoom={zoom} onZoomChange={handleZoomChange} />

      {/* Tab bar */}
      <div style={{
        display: 'flex', gap: 0, background: 'var(--bg-1)',
        borderBottom: '1px solid var(--border)', flexShrink: 0,
      }}>
        {(['board', 'activity'] as TabId[]).map(tab => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            style={{
              fontFamily: 'var(--mono)', fontSize: '0.769rem', padding: '6px 16px',
              border: 'none', cursor: 'pointer',
              background: activeTab === tab ? 'var(--bg-0)' : 'transparent',
              color: activeTab === tab ? 'var(--accent)' : 'var(--text-2)',
              borderBottom: activeTab === tab ? '2px solid var(--accent)' : '2px solid transparent',
              textTransform: 'uppercase', letterSpacing: 1,
            }}
          >{tab}</button>
        ))}
      </div>

      {/* Main content area */}
      <div style={{ flex: 1, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
        {activeTab === 'board' && (
          board && !board.error ? (
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden' }}>
              <AttentionDashboard board={board} onCardClick={setSelectedCard} />
              <Board board={board} onCardClick={setSelectedCard} send={send} />
            </div>
          ) : (
            <div style={{
              flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontFamily: 'var(--mono)', fontSize: '0.923rem', color: 'var(--text-2)',
            }}>
              {board?.error || 'connecting...'}
            </div>
          )
        )}
        {activeTab === 'activity' && (
          <ActivityFeed refreshTick={activityTick} />
        )}
      </div>

      {activeTab === 'board' && <ActivityBar board={board} onCardClick={handleActivityCardClick} />}

      <CardDetail card={selectedCard} laneNames={laneNames} onClose={() => setSelectedCard(null)} send={send} />
      <AddCardDialog open={addDialogOpen} onClose={() => setAddDialogOpen(false)} send={send} />
      <ToastContainer lastResult={lastResult} />
    </div>
  );
}
