import { useState, useEffect, useCallback } from 'react';
import type { ActivityEntry } from '../types';
import { fmtFull } from '../lib/utils';

const TIME_RANGES = [
  { label: '1h',  seconds: 3600 },
  { label: '6h',  seconds: 6 * 3600 },
  { label: '24h', seconds: 24 * 3600 },
  { label: 'All', seconds: null },
];

const ACTION_COLORS: Record<string, string> = {
  created:          'var(--accent)',
  moved:            'var(--blue)',
  pulled:           'var(--blue)',
  blocked:          'var(--danger)',
  unblocked:        'var(--accent)',
  approved:         'var(--accent)',
  rejected:         'var(--danger)',
  gate_pass:        'var(--accent)',
  gate_fail:        'var(--danger)',
  merged:           '#a07aef',
  note:             'var(--text-2)',
  approval_required:'#eab308',
  asked:            '#eab308',
  answered:         'var(--accent)',
  heartbeat:        'var(--text-2)',
};

function actionColor(action: string): string {
  return ACTION_COLORS[action] ?? 'var(--text-2)';
}

interface Props {
  refreshTick: number;
}

export function ActivityFeed({ refreshTick }: Props) {
  const [entries, setEntries] = useState<ActivityEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [timeRange, setTimeRange] = useState<number | null>(3600);
  const [actionFilter, setActionFilter] = useState('');
  const [actions, setActions] = useState<string[]>([]);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (timeRange) params.set('since', String(Date.now() / 1000 - timeRange));
      if (actionFilter) params.set('action', actionFilter);
      params.set('limit', '500');
      const res = await fetch(`/api/activity?${params}`);
      const data = await res.json();
      const list: ActivityEntry[] = data.entries ?? [];
      setEntries(list);
      // Collect unique actions for filter dropdown
      const seen = new Set<string>(list.map(e => e.action));
      setActions(Array.from(seen).sort());
    } catch {
      // silently ignore fetch errors
    } finally {
      setLoading(false);
    }
  }, [timeRange, actionFilter]);

  useEffect(() => { load(); }, [load, refreshTick]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', gap: 0 }}>
      {/* Filter bar */}
      <div style={{
        display: 'flex', alignItems: 'center', gap: 8, padding: '10px 16px',
        borderBottom: '1px solid var(--border)', flexShrink: 0,
        background: 'var(--bg-1)',
      }}>
        <span style={{ fontSize: 11, color: 'var(--text-2)', fontFamily: 'var(--mono)' }}>
          {loading ? 'loading…' : `${entries.length} events`}
        </span>
        <div style={{ flex: 1 }} />
        {/* Time range pills */}
        {TIME_RANGES.map(tr => (
          <button
            key={tr.label}
            onClick={() => setTimeRange(tr.seconds)}
            style={{
              fontFamily: 'var(--mono)', fontSize: 10, padding: '2px 8px',
              border: '1px solid var(--border)', borderRadius: 3, cursor: 'pointer',
              background: timeRange === tr.seconds ? 'var(--accent)' : 'var(--bg-2)',
              color: timeRange === tr.seconds ? '#fff' : 'var(--text-1)',
            }}
          >{tr.label}</button>
        ))}
        {/* Action filter */}
        <select
          value={actionFilter}
          onChange={e => setActionFilter(e.target.value)}
          style={{
            fontFamily: 'var(--mono)', fontSize: 10, padding: '2px 6px',
            border: '1px solid var(--border)', borderRadius: 3,
            background: 'var(--bg-2)', color: 'var(--text-1)',
          }}
        >
          <option value=''>all actions</option>
          {actions.map(a => <option key={a} value={a}>{a}</option>)}
        </select>
      </div>

      {/* Feed list */}
      <div style={{ flex: 1, overflowY: 'auto', overflowX: 'hidden', padding: '8px 0' }}>
        {entries.length === 0 && !loading && (
          <div style={{ textAlign: 'center', color: 'var(--text-2)', fontSize: 12, marginTop: 40 }}>
            No activity in this time range.
          </div>
        )}
        {entries.map((e, i) => (
          <div key={i} style={{
            display: 'flex', gap: 8, alignItems: 'flex-start', padding: '5px 16px',
            overflow: 'hidden',
            borderBottom: '1px solid var(--bg-0)',
            fontSize: 11, fontFamily: 'var(--mono)',
          }}>
            <span style={{ flexShrink: 0, width: 80, fontSize: 10, color: 'var(--text-2)', whiteSpace: 'nowrap' }}>{fmtFull(e.ts)}</span>
            <span style={{ flexShrink: 0, width: 90, color: actionColor(e.action), fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {e.action}
            </span>
            <span style={{ flexShrink: 0, width: 160, color: 'var(--accent)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              #{e.card_id} {e.card_title}
            </span>
            <span title={e.content} style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--text-1)' }}>
              {e.content}
              {e.agent_id && <span style={{ color: 'var(--text-2)', marginLeft: 6 }}>— {e.agent_id}</span>}
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}
