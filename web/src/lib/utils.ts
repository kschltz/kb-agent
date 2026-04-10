import { clsx, type ClassValue } from "clsx"
import { twMerge } from "tailwind-merge"

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs))
}

export function fmtTime(ts: number): string {
  if (!ts) return '';
  return new Date(ts * 1000).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function fmtFull(ts: number): string {
  if (!ts) return '';
  return new Date(ts * 1000).toLocaleString();
}

export function colorDiff(text: string): string {
  return text.split('\n').map(line => {
    const e = escapeHtml(line);
    if (line.startsWith('+++') || line.startsWith('---')) return `<span style="color:var(--blue);font-weight:600">${e}</span>`;
    if (line.startsWith('@@')) return `<span style="color:var(--purple)">${e}</span>`;
    if (line.startsWith('+')) return `<span style="color:var(--accent)">${e}</span>`;
    if (line.startsWith('-')) return `<span style="color:var(--danger)">${e}</span>`;
    return e;
  }).join('\n');
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
