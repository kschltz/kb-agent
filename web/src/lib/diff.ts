export interface FileStat {
  file: string;
  insertions: number;
  deletions: number;
}

export function parseDiffStat(statText: string): FileStat[] {
  if (!statText?.trim()) return [];
  return statText.split('\n').map(line => {
    const m = line.match(/^(.+?)\s*\|\s*(\d+)\s*([+-]+)/);
    if (!m) return null;
    const file = m[1].trim();
    const plus = (m[3] || '').replace(/-/g, '').length;
    const minus = (m[3] || '').replace(/\+/g, '').length;
    return { file, insertions: plus, deletions: minus };
  }).filter((s): s is FileStat => s !== null);
}