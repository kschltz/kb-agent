import { useState, useEffect, useCallback } from 'react';
import type { CommandResult } from '../types';

interface ToastProps {
  lastResult: CommandResult | null;
}

interface ToastItem {
  id: number;
  message: string;
  type: 'success' | 'error' | '';
}

let nextId = 0;

export function ToastContainer({ lastResult }: ToastProps) {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const addToast = useCallback((message: string, type: 'success' | 'error' | '' = '') => {
    const id = nextId++;
    setToasts(prev => [...prev, { id, message, type }]);
    setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 4000);
  }, []);

  useEffect(() => {
    if (!lastResult) return;
    if (!lastResult.success && lastResult.message) {
      addToast(lastResult.message, 'error');
    }
  }, [lastResult, addToast]);

  // expose addToast globally for other components
  useEffect(() => {
    (window as unknown as Record<string, unknown>).__kbToast = addToast;
  }, [addToast]);

  return (
    <div style={{
      position: 'fixed', bottom: 20, right: 20, zIndex: 300,
      display: 'flex', flexDirection: 'column', gap: 6,
    }}>
      {toasts.map(t => (
        <div key={t.id} style={{
          fontFamily: 'var(--mono)', fontSize: '0.923rem', padding: '8px 14px',
          borderRadius: 'var(--radius)', border: '1px solid var(--border)',
          background: 'var(--bg-2)', color: 'var(--text-0)', maxWidth: 360,
          ...(t.type === 'error' ? { borderColor: 'var(--danger-dim)', color: 'var(--danger)' } : {}),
          ...(t.type === 'success' ? { borderColor: 'var(--accent-dim)', color: 'var(--accent)' } : {}),
        }}>{t.message}</div>
      ))}
    </div>
  );
}
