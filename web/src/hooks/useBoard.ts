import { useState, useEffect, useRef, useCallback } from 'react';
import type { BoardState, UICommand, WSMessage, CommandResult } from '../types';

interface UseBoardReturn {
  board: BoardState | null;
  connected: boolean;
  send: (cmd: UICommand) => void;
  lastResult: CommandResult | null;
}

export function useBoard(): UseBoardReturn {
  const [board, setBoard] = useState<BoardState | null>(null);
  const [connected, setConnected] = useState(false);
  const [lastResult, setLastResult] = useState<CommandResult | null>(null);
  const wsRef = useRef<WebSocket | null>(null);
  const reconnectRef = useRef<ReturnType<typeof setTimeout>>(undefined);

  const connect = useCallback(() => {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    const ws = new WebSocket(`${proto}//${location.host}/`);
    wsRef.current = ws;

    ws.onopen = () => setConnected(true);

    ws.onclose = () => {
      setConnected(false);
      reconnectRef.current = setTimeout(connect, 2000);
    };

    ws.onerror = () => {};

    ws.onmessage = (ev) => {
      const msg: WSMessage = JSON.parse(ev.data);
      if (msg.type === 'state') {
        setBoard(msg.data);
      } else if (msg.type === 'result') {
        setLastResult(msg.data);
      } else if (msg.type === 'error') {
        setLastResult({ success: false, message: msg.data });
      }
    };
  }, []);

  useEffect(() => {
    connect();
    return () => {
      if (reconnectRef.current) clearTimeout(reconnectRef.current);
      wsRef.current?.close();
    };
  }, [connect]);

  const send = useCallback((cmd: UICommand) => {
    if (wsRef.current?.readyState === 1) {
      wsRef.current.send(JSON.stringify(cmd));
    }
  }, []);

  return { board, connected, send, lastResult };
}
