import { useState, useRef, useEffect, useCallback } from 'react';
import type { CardData, UICommand } from '../types';
import { fmtTime, fmtFull, colorDiff } from '../lib/utils';

interface CardDetailProps {
  card: CardData | null;
  laneNames: string[];
  onClose: () => void;
  send: (cmd: UICommand) => void;
}

export function CardDetail({ card, laneNames, onClose, send }: CardDetailProps) {
  const [diffHtml, setDiffHtml] = useState<string | null>(null);
  const [askText, setAskText] = useState('');
  const [answerText, setAnswerText] = useState('');
  const noteRef = useRef<HTMLInputElement>(null);

  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (e.key === 'Escape') onClose();
  }, [onClose]);

  useEffect(() => {
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [handleKeyDown]);

  useEffect(() => {
    setDiffHtml(null);
  }, [card?.id]);

  if (!card) return null;

  const idx = laneNames.indexOf(card.lane);
  const nextLane = idx < laneNames.length - 1 ? laneNames[idx + 1] : null;
  const prevLane = idx > 0 ? laneNames[idx - 1] : null;

  const sendNote = () => {
    const msg = noteRef.current?.value.trim();
    if (msg) {
      send({ action: 'note', card_id: card.id, message: msg });
      noteRef.current!.value = '';
    }
  };

  const viewFullDiff = async () => {
    setDiffHtml('<span style="color:var(--text-2)">Loading diff...</span>');
    try {
      const resp = await fetch(`/api/cards/${card.id}/diff`);
      const data = await resp.json() as { diff?: string; error?: string };
      if (data.diff !== undefined) {
        setDiffHtml(data.diff.trim() ? colorDiff(data.diff) : '<span style="color:var(--text-2)">No changes yet.</span>');
      } else {
        setDiffHtml(`<span style="color:var(--danger)">${data.error ?? 'Failed to load diff'}</span>`);
      }
    } catch {
      setDiffHtml('<span style="color:var(--danger)">Network error loading diff.</span>');
    }
  };

  const sendAsk = () => {
    if (askText.trim()) {
      send({ action: 'ask', card_id: card.id, question: askText.trim() });
      setAskText('');
    }
  };

  const sendAnswer = () => {
    if (answerText.trim()) {
      send({ action: 'answer', card_id: card.id, answer: answerText.trim() });
      setAnswerText('');
    }
  };

  return (
    <>
      {/* Overlay */}
      <div onClick={onClose} style={{
        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.5)', zIndex: 100,
      }} />

      {/* Panel */}
      <div style={{
        position: 'fixed', top: 0, right: 0, width: 560, height: '100vh',
        background: 'var(--bg-1)', borderLeft: '1px solid var(--border)',
        zIndex: 101, display: 'flex', flexDirection: 'column', overflow: 'hidden',
      }}>
        {/* Header */}
        <div style={{
          display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between',
          padding: '16px 20px', borderBottom: '1px solid var(--border)',
          background: 'var(--bg-2)', flexShrink: 0,
        }}>
          <div>
            <div style={{ fontFamily: 'var(--mono)', fontSize: '0.846rem', color: 'var(--text-2)' }}>#{card.id}</div>
            <h2 style={{ fontSize: '1.154rem', fontWeight: 600, color: 'var(--text-0)', marginTop: 4 }}>{card.title}</h2>
          </div>
          <button onClick={onClose} style={{
            background: 'none', border: '1px solid var(--border)', color: 'var(--text-2)',
            width: 28, height: 28, borderRadius: 4, cursor: 'pointer', fontSize: '1.077rem',
            display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
          }}>✕</button>
        </div>

        {/* Body */}
        <div style={{ flex: 1, overflowY: 'auto', padding: '16px 20px' }}>
          {/* Pending question banner */}
          {card.pending_question && (
            <div style={{
              background: 'rgba(234,179,8,0.1)', border: '1px solid rgba(234,179,8,0.3)',
              borderRadius: 'var(--radius)', padding: '12px 14px', marginBottom: 16,
            }}>
              <div style={{ fontFamily: 'var(--mono)', fontSize: '0.769rem', fontWeight: 600, color: '#eab308', marginBottom: 6, textTransform: 'uppercase', letterSpacing: '1px' }}>
                ❓ Agent is asking
              </div>
              <div style={{ fontSize: '1rem', color: 'var(--text-0)', marginBottom: 10 }}>{card.pending_question}</div>
              <div style={{ display: 'flex', gap: 6 }}>
                <input value={answerText} onChange={e => setAnswerText(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') sendAnswer(); }}
                  placeholder="Type your answer..."
                  style={{
                    flex: 1, fontFamily: 'var(--mono)', fontSize: '0.923rem', padding: '6px 10px',
                    background: 'var(--bg-0)', border: '1px solid var(--border)',
                    borderRadius: 'var(--radius)', color: 'var(--text-0)', outline: 'none',
                  }} />
                <button onClick={sendAnswer} style={{ ...btnStyle, borderColor: 'var(--accent-dim)', color: 'var(--accent)' }}>Answer</button>
              </div>
            </div>
          )}

          {/* Pending approval banner */}
          {card.pending_approval && (
            <div style={{
              background: 'rgba(234,179,8,0.1)', border: '1px solid rgba(234,179,8,0.3)',
              borderRadius: 'var(--radius)', padding: '12px 14px', marginBottom: 16,
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            }}>
              <span style={{ fontSize: '0.923rem', color: '#eab308' }}>⏳ Pending approval</span>
              <button onClick={() => send({ action: 'approve', card_id: card.id })}
                style={{ ...btnStyle, borderColor: 'var(--accent-dim)', color: 'var(--accent)' }}>Approve</button>
            </div>
          )}

          {/* Meta */}
          <Section title="Details">
            <dl style={{ display: 'grid', gridTemplateColumns: '90px 1fr', gap: '4px 12px', fontSize: '0.923rem' }}>
              <Dt>Lane</Dt><Dd>{card.lane}</Dd>
              <Dt>Priority</Dt>
              <Dd>
                <select
                  value={card.priority ?? 2}
                  onChange={e => send({ action: 'edit', card_id: card.id, priority: Number(e.target.value) })}
                  style={{
                    fontFamily: 'var(--mono)', fontSize: '0.923rem',
                    background: 'var(--bg-0)', border: '1px solid var(--border)',
                    color: 'var(--text-0)', borderRadius: 'var(--radius)',
                    padding: '2px 6px', outline: 'none', cursor: 'pointer',
                  }}>
                  <option value={0}>P0 (critical)</option>
                  <option value={1}>P1 (high)</option>
                  <option value={2}>P2 (normal)</option>
                  <option value={3}>P3 (low)</option>
                </select>
              </Dd>
              <Dt>Blocked</Dt><Dd>{card.blocked ? `Yes — ${card.blocked_reason}` : 'No'}</Dd>
              <Dt>Agent</Dt><Dd>{card.assigned_agent || '—'}</Dd>
              <Dt>Branch</Dt><Dd>{card.branch || '—'}</Dd>
              <Dt>Created</Dt><Dd>{fmtFull(card.created_at)}</Dd>
              <Dt>Updated</Dt><Dd>{fmtFull(card.updated_at)}</Dd>
              {card.last_heartbeat ? (<><Dt>Heartbeat</Dt><Dd>{fmtFull(card.last_heartbeat)}</Dd></>) : null}
            </dl>
          </Section>

          {/* Diff stat */}
          {card.diff_stat?.trim() && (
            <Section title="Changes">
              <div style={{
                background: 'var(--bg-0)', border: '1px solid var(--border)',
                borderRadius: 'var(--radius)', padding: 12,
                fontFamily: 'var(--mono)', fontSize: '0.846rem', lineHeight: 1.6,
                whiteSpace: 'pre', overflowX: 'auto', color: 'var(--text-1)',
                maxHeight: diffHtml ? 600 : 400, overflowY: 'auto',
              }} dangerouslySetInnerHTML={{ __html: diffHtml || escapeHtml(card.diff_stat) }} />
              {!diffHtml && (
                <button onClick={viewFullDiff} style={{
                  ...btnStyle, borderColor: 'var(--accent-dim)', color: 'var(--accent)', marginTop: 8,
                }}>View full diff</button>
              )}
            </Section>
          )}

          {/* History */}
          {card.history?.length > 0 && (
            <Section title={`History (${card.history.length})`}>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                {card.history.slice(-50).map((e, i) => (
                  <div key={i} style={{
                    display: 'flex', gap: 10, padding: '5px 8px',
                    borderRadius: 4, fontSize: '0.923rem', lineHeight: 1.4,
                  }}>
                    <span style={{ fontFamily: 'var(--mono)', fontSize: '0.769rem', color: 'var(--text-2)', whiteSpace: 'nowrap', minWidth: 60, paddingTop: 1 }}>
                      {fmtTime(e.ts)}
                    </span>
                    <span style={{
                      width: 6, height: 6, borderRadius: '50%', marginTop: 5, flexShrink: 0,
                      background: e.role === 'human' ? 'var(--accent)' : e.role === 'agent' ? 'var(--blue)' : 'var(--text-2)',
                    }} />
                    <div style={{ flex: 1, color: 'var(--text-1)', fontSize: '0.923rem' }}>
                      <span style={{
                        fontFamily: 'var(--mono)', fontSize: '0.692rem', fontWeight: 600,
                        textTransform: 'uppercase', letterSpacing: '0.5px', marginRight: 6,
                        color: e.role === 'human' ? 'var(--accent)' : e.role === 'agent' ? 'var(--blue)' : 'var(--text-2)',
                      }}>{e.role}</span>
                      <span style={{
                        fontFamily: 'var(--mono)', fontSize: '0.692rem', marginRight: 6,
                        color: e.action === 'gate_fail' ? 'var(--danger)' : e.action === 'gate_pass' ? 'var(--accent)' : e.action === 'ask' ? '#eab308' : e.action === 'answer' ? 'var(--accent)' : 'var(--text-2)',
                      }}>{e.action}</span>
                      {e.content}
                    </div>
                  </div>
                ))}
              </div>
            </Section>
          )}

          {/* Note input */}
          <Section title="Add note">
            <div style={{ display: 'flex', gap: 6 }}>
              <input ref={noteRef} placeholder="Type a note for the agent..." onKeyDown={e => { if (e.key === 'Enter') sendNote(); }}
                style={{
                  flex: 1, fontFamily: 'var(--mono)', fontSize: '0.923rem', padding: '6px 10px',
                  background: 'var(--bg-0)', border: '1px solid var(--border)',
                  borderRadius: 'var(--radius)', color: 'var(--text-0)', outline: 'none',
                }} />
              <button onClick={sendNote} style={{ ...btnStyle, borderColor: 'var(--accent-dim)', color: 'var(--accent)' }}>Send</button>
            </div>
          </Section>

          {/* Ask question */}
          {!card.pending_question && (
            <Section title="Ask agent a question">
              <div style={{ display: 'flex', gap: 6 }}>
                <input value={askText} onChange={e => setAskText(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') sendAsk(); }}
                  placeholder="Ask the agent..."
                  style={{
                    flex: 1, fontFamily: 'var(--mono)', fontSize: '0.923rem', padding: '6px 10px',
                    background: 'var(--bg-0)', border: '1px solid var(--border)',
                    borderRadius: 'var(--radius)', color: 'var(--text-0)', outline: 'none',
                  }} />
                <button onClick={sendAsk} style={{ ...btnStyle, borderColor: 'rgba(234,179,8,0.3)', color: '#eab308' }}>Ask</button>
              </div>
            </Section>
          )}
        </div>

        {/* Actions */}
        <div style={{
          padding: '12px 20px', borderTop: '1px solid var(--border)',
          background: 'var(--bg-2)', display: 'flex', gap: 8, flexShrink: 0, flexWrap: 'wrap',
        }}>
          {nextLane && (
            <button onClick={() => send({ action: 'move', card_id: card.id, lane: nextLane })}
              style={{ ...btnStyle, borderColor: 'var(--accent-dim)', color: 'var(--accent)' }}>→ {nextLane}</button>
          )}
          {prevLane && (
            <button onClick={() => send({ action: 'reject', card_id: card.id, reason: 'Rejected via UI' })}
              style={{ ...btnStyle, borderColor: 'var(--warn-dim)', color: 'var(--warn)' }}>← {prevLane}</button>
          )}
          {card.blocked ? (
            <button onClick={() => send({ action: 'unblock', card_id: card.id })}
              style={{ ...btnStyle, borderColor: 'var(--accent-dim)', color: 'var(--accent)' }}>Unblock</button>
          ) : (
            <button onClick={() => {
              const reason = prompt('Block reason:');
              if (reason !== null) send({ action: 'block', card_id: card.id, reason });
            }} style={{ ...btnStyle, borderColor: 'var(--danger-dim)', color: 'var(--danger)' }}>Block</button>
          )}
        </div>
      </div>
    </>
  );
}

const btnStyle: React.CSSProperties = {
  fontFamily: 'var(--mono)', fontSize: '0.846rem', padding: '5px 12px',
  borderRadius: 'var(--radius)', cursor: 'pointer',
  border: '1px solid var(--border)', background: 'var(--bg-3)', color: 'var(--text-1)',
};

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div style={{ marginBottom: 20 }}>
      <div style={{
        fontFamily: 'var(--mono)', fontSize: '0.769rem', fontWeight: 600,
        textTransform: 'uppercase', letterSpacing: '1.5px', color: 'var(--text-2)',
        marginBottom: 8, paddingBottom: 4, borderBottom: '1px solid var(--border)',
      }}>{title}</div>
      {children}
    </div>
  );
}

function Dt({ children }: { children: React.ReactNode }) {
  return <dt style={{ fontFamily: 'var(--mono)', fontSize: '0.846rem', color: 'var(--text-2)' }}>{children}</dt>;
}

function Dd({ children }: { children: React.ReactNode }) {
  return <dd style={{ color: 'var(--text-0)', fontFamily: 'var(--mono)', fontSize: '0.846rem', wordBreak: 'break-all' }}>{children}</dd>;
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}
