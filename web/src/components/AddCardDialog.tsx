import { useState, useRef, useEffect } from 'react';
import type { UICommand } from '../types';

interface AddCardDialogProps {
  open: boolean;
  onClose: () => void;
  send: (cmd: UICommand) => void;
}

export function AddCardDialog({ open, onClose, send }: AddCardDialogProps) {
  const [title, setTitle] = useState('');
  const [desc, setDesc] = useState('');
  const titleRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) setTimeout(() => titleRef.current?.focus(), 50);
  }, [open]);

  if (!open) return null;

  const submit = () => {
    if (!title.trim()) return;
    send({ action: 'add', title: title.trim(), description: desc.trim() || undefined });
    setTitle('');
    setDesc('');
    onClose();
  };

  return (
    <div onClick={e => { if (e.target === e.currentTarget) onClose(); }} style={{
      position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.6)', zIndex: 200,
      display: 'flex', alignItems: 'center', justifyContent: 'center',
    }}>
      <div style={{
        background: 'var(--bg-1)', border: '1px solid var(--border)',
        borderRadius: 8, padding: 24, width: 420, maxWidth: '90vw',
      }}>
        <h3 style={{ fontSize: 15, fontWeight: 600, marginBottom: 16 }}>Add card</h3>

        <label style={labelStyle}>Title</label>
        <input ref={titleRef} value={title} onChange={e => setTitle(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') submit(); }}
          placeholder="What needs to be done?" style={inputStyle} />

        <label style={labelStyle}>Description</label>
        <textarea value={desc} onChange={e => setDesc(e.target.value)}
          placeholder="Optional details, acceptance criteria..."
          style={{ ...inputStyle, minHeight: 80, resize: 'vertical' }} />

        <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 8 }}>
          <button onClick={onClose} style={btnStyle}>Cancel</button>
          <button onClick={submit} style={{ ...btnStyle, borderColor: 'var(--accent-dim)', color: 'var(--accent)' }}>Create</button>
        </div>
      </div>
    </div>
  );
}

const labelStyle: React.CSSProperties = {
  display: 'block', fontFamily: 'var(--mono)', fontSize: 11, color: 'var(--text-2)',
  marginBottom: 4, textTransform: 'uppercase', letterSpacing: '0.5px',
};

const inputStyle: React.CSSProperties = {
  width: '100%', fontFamily: 'var(--mono)', fontSize: 13, padding: '8px 10px',
  background: 'var(--bg-0)', border: '1px solid var(--border)',
  borderRadius: 'var(--radius)', color: 'var(--text-0)', outline: 'none', marginBottom: 12,
};

const btnStyle: React.CSSProperties = {
  fontFamily: 'var(--mono)', fontSize: 11, padding: '5px 12px',
  borderRadius: 'var(--radius)', cursor: 'pointer',
  border: '1px solid var(--border)', background: 'var(--bg-3)', color: 'var(--text-1)',
};
