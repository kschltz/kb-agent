import { parseDiffStat } from '../lib/diff';
import { colorDiff } from '../lib/utils';

interface DiffViewProps {
  diffText: string | null;
  diffStat: string | null;
  loading: boolean;
  empty: boolean;
}

export function DiffView({ diffText, diffStat, loading, empty }: DiffViewProps) {
  const files = parseDiffStat(diffStat || '');
  const totalAdd = files.reduce((s, f) => s + f.insertions, 0);
  const totalDel = files.reduce((s, f) => s + f.deletions, 0);

  if (loading) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-2)', fontFamily: 'var(--mono)', fontSize: '0.923rem' }}>
        Loading diff...
      </div>
    );
  }

  if (empty) {
    return (
      <div style={{ padding: 24, textAlign: 'center', color: 'var(--text-2)', fontFamily: 'var(--mono)', fontSize: '0.923rem' }}>
        No changes yet.
      </div>
    );
  }

  return (
    <div>
      {/* File summary */}
      {files.length > 0 && (
        <div style={{
          background: 'var(--bg-0)', border: '1px solid var(--border)',
          borderRadius: 'var(--radius)', padding: 10, marginBottom: 10,
          fontFamily: 'var(--mono)', fontSize: '0.769rem',
        }}>
          <div style={{ display: 'flex', gap: 16, marginBottom: 6, color: 'var(--text-2)' }}>
            <span>{files.length} file{files.length !== 1 ? 's' : ''}</span>
            <span style={{ color: 'var(--accent)' }}>+{totalAdd}</span>
            <span style={{ color: 'var(--danger)' }}>-{totalDel}</span>
          </div>
          {files.map((f, i) => (
            <div key={i} style={{ display: 'flex', justifyContent: 'space-between', padding: '2px 0' }}>
              <span style={{ color: 'var(--text-1)' }}>{f.file}</span>
              <span>
                {f.insertions > 0 && <span style={{ color: 'var(--accent)' }}>+{f.insertions} </span>}
                {f.deletions > 0 && <span style={{ color: 'var(--danger)' }}>-{f.deletions}</span>}
              </span>
            </div>
          ))}
        </div>
      )}

      {/* Full diff */}
      {diffText && (
        <div style={{
          background: 'var(--bg-0)', border: '1px solid var(--border)',
          borderRadius: 'var(--radius)', padding: 12,
          fontFamily: 'var(--mono)', fontSize: '0.846rem', lineHeight: 1.6,
          whiteSpace: 'pre', overflowX: 'auto', color: 'var(--text-1)',
          maxHeight: 500, overflowY: 'auto',
        }} dangerouslySetInnerHTML={{ __html: colorDiff(diffText) }} />
      )}
    </div>
  );
}