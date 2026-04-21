import { useState, useRef, useEffect } from 'react';

interface ApprovalBarProps {
  cardId: string;
  onApprove: () => void;
  onReject: (reason: string) => void;
  position: 'top' | 'bottom';
}

export function ApprovalBar({ cardId, onApprove, onReject, position }: ApprovalBarProps) {
  const [rejecting, setRejecting] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const rejectRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      if (rejecting) return;
      const target = e.target as HTMLElement;
      if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.tagName === 'SELECT') return;
      if (e.key === 'a') {
        e.preventDefault();
        onApprove();
      }
      if (e.key === 'r') {
        e.preventDefault();
        setRejecting(true);
        setTimeout(() => rejectRef.current?.focus(), 50);
      }
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [rejecting, onApprove]);

  const submitReject = () => {
    if (rejectReason.trim()) {
      onReject(rejectReason.trim());
      setRejectReason('');
      setRejecting(false);
    }
  };

  return (
    <div style={{
      position: position === 'top' ? 'sticky' : 'relative',
      top: position === 'top' ? 0 : undefined,
      zIndex: 10,
      background: 'var(--bg-2)',
      borderBottom: position === 'top' ? '1px solid var(--border)' : undefined,
      borderTop: position === 'bottom' ? '1px solid var(--border)' : undefined,
      padding: '8px 20px',
      display: 'flex', alignItems: 'center', gap: 10, flexShrink: 0,
    }}>
      <span style={{ fontFamily: 'var(--mono)', fontSize: '0.769rem', fontWeight: 600, color: '#eab308', textTransform: 'uppercase', letterSpacing: '1px' }}>
        ⏳ Pending approval
      </span>
      <span style={{ fontFamily: 'var(--mono)', fontSize: '0.769rem', color: 'var(--text-2)' }}>#{cardId}</span>
      <div style={{ flex: 1 }} />
      {!rejecting ? (
        <>
          <button onClick={onApprove} style={{
            ...btnStyle, borderColor: 'var(--accent-dim)', color: 'var(--accent)', background: 'rgba(34,197,94,0.1)',
          }}>Approve <kbd style={{ fontSize: '0.692rem', opacity: 0.6 }}>a</kbd></button>
          <button onClick={() => { setRejecting(true); setTimeout(() => rejectRef.current?.focus(), 50); }} style={{
            ...btnStyle, borderColor: 'var(--danger-dim)', color: 'var(--danger)',
          }}>Reject <kbd style={{ fontSize: '0.692rem', opacity: 0.6 }}>r</kbd></button>
        </>
      ) : (
        <div style={{ display: 'flex', gap: 6, flex: 1 }}>
          <input ref={rejectRef} value={rejectReason} onChange={e => setRejectReason(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') submitReject(); if (e.key === 'Escape') setRejecting(false); }}
            placeholder="Reason for rejection..."
            style={{
              flex: 1, fontFamily: 'var(--mono)', fontSize: '0.923rem', padding: '6px 10px',
              background: 'var(--bg-0)', border: '1px solid var(--danger-dim)',
              borderRadius: 'var(--radius)', color: 'var(--text-0)', outline: 'none',
            }} />
          <button onClick={submitReject} style={{ ...btnStyle, borderColor: 'var(--danger)', color: 'var(--danger)' }}>Reject</button>
          <button onClick={() => setRejecting(false)} style={{ ...btnStyle }}>Cancel</button>
        </div>
      )}
    </div>
  );
}

const btnStyle: React.CSSProperties = {
  fontFamily: 'var(--mono)', fontSize: '0.846rem', padding: '5px 12px',
  borderRadius: 'var(--radius)', cursor: 'pointer',
  border: '1px solid var(--border)', background: 'var(--bg-3)', color: 'var(--text-1)',
};