import type { BoardState } from '../types';

interface HeaderProps {
  board: BoardState | null;
  connected: boolean;
  onAddCard: () => void;
}

export function Header({ board, connected, onAddCard }: HeaderProps) {
  return (
    <div style={{
      display: 'flex', alignItems: 'center', justifyContent: 'space-between',
      padding: '10px 20px', background: 'var(--bg-1)',
      borderBottom: '1px solid var(--border)', flexShrink: 0,
    }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 14 }}>
        <span style={{ fontFamily: 'var(--mono)', fontWeight: 700, fontSize: 16, letterSpacing: -0.5, color: 'var(--accent)' }}>kb</span>
        <span style={{
          fontFamily: 'var(--mono)', fontSize: 12, color: 'var(--text-2)',
          padding: '2px 8px', background: 'var(--bg-2)', borderRadius: 3,
          border: '1px solid var(--border)',
        }}>{board?.project || '—'}</span>
        <span style={{
          fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--blue)',
          padding: '2px 8px', background: 'rgba(90,158,240,0.08)',
          border: '1px solid var(--blue-dim)', borderRadius: 3,
        }}>⎇ {board?.base_branch || 'main'}</span>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <button onClick={onAddCard} style={{
          fontFamily: 'var(--mono)', fontSize: 12, padding: '5px 14px',
          background: 'var(--bg-2)', border: '1px solid var(--border)',
          color: 'var(--accent)', borderRadius: 'var(--radius)', cursor: 'pointer',
        }}>+ add card</button>
        <div style={{
          width: 8, height: 8, borderRadius: '50%',
          background: connected ? 'var(--accent)' : 'var(--danger)',
          boxShadow: `0 0 6px ${connected ? 'var(--accent)' : 'var(--danger)'}`,
        }} />
        <span style={{ fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--text-2)' }}>
          {connected ? 'connected' : 'disconnected'}
        </span>
      </div>
    </div>
  );
}
